package com.hereliesaz.guillotine.azphalt

import com.hereliesaz.guillotine.ai.AiSettings
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Executes the on-device installation planned by [AzpModelInstaller]: it obtains each model's bytes
 * (bundled in the `.azp`, or streamed from its `remoteUrl` with a resumable retry), verifies a remote
 * download against its declared SHA-256 **before** it touches a model slot, writes it under a
 * host-chosen models directory (extracting a `sherpa-bundle` zip into its own folder), and returns the
 * [AiSettings] slot each model routes to so the caller can persist the paths.
 *
 * This is deliberately platform-agnostic — it uses only `java.io` / `java.net`, both available on
 * Android and desktop JVM — so a single implementation drives both apps. The thin platform layer only
 * supplies the `.azp` bytes, the trust store, and the target directory, then folds [Result.applyTo]
 * into its own settings store.
 *
 * The on-device invariant holds: a model install fetches model *weights* from a source the user chose
 * (ideally a trusted registry), never the user's footage, and a remote file that fails its checksum is
 * discarded, not wired in.
 */
object AzpModelInstall {

    /** The plan is integrity-sound, but its signer is not in the trust store (or it's unsigned) and the
     *  caller did not opt into installing anyway. UI catches this to surface a trust warning and re-invoke
     *  with `allowUntrusted = true` on the user's confirmation. */
    class UntrustedException(val trust: AzpPackage.TrustResult) :
        Exception("azp: package is not from a trusted signer — ${trust.reason}")

    enum class Phase { DOWNLOADING, VERIFYING, WRITING }

    /** Progress for a single model. [bytesTotal] is null when the size wasn't declared. */
    data class Progress(
        val model: AzpModelInstaller.PlannedModel,
        val phase: Phase,
        val bytesDone: Long,
        val bytesTotal: Long?,
    )

    /** One installed model: where it landed on disk and which [AiSettings] slot it drives (null ⇒ unrouted). */
    data class Installed(
        val model: AzpModelInstaller.PlannedModel,
        val slot: AzpModelInstaller.ModelSlot?,
        val path: String,
    )

    data class Result(
        val trust: AzpPackage.TrustResult,
        val packageId: String,
        val installed: List<Installed>,
    ) {
        /** Fold every routed model's path into [settings]; returns the updated settings to persist. */
        fun applyTo(settings: AiSettings): AiSettings =
            installed.fold(settings) { s, i -> i.slot?.apply(s, i.path) ?: s }
    }

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val DOWNLOAD_RETRIES = 4
    private const val BUFFER = 1 shl 16
    private const val PROGRESS_INTERVAL = 1 shl 20 // ~1 MB between progress callbacks

    /**
     * Plan (integrity + trust) then install every model asset. Throws [AzpPackage.AzpException] if the
     * package fails integrity or carries an invalid signature — those must block. If the package is valid
     * but untrusted and [allowUntrusted] is false, throws [UntrustedException] so the host can prompt.
     *
     * Not `suspend` — it does blocking I/O and is meant to be called off the main thread (e.g. from a
     * `Dispatchers.IO` coroutine on the host).
     */
    fun install(
        azpBytes: ByteArray,
        trustedKeys: Set<String>,
        modelsDir: File,
        allowUntrusted: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val plan = AzpModelInstaller.plan(azpBytes, trustedKeys) // throws on integrity/signature failure
        if (!plan.trust.trusted && !allowUntrusted) throw UntrustedException(plan.trust)
        if (!modelsDir.isDirectory && !modelsDir.mkdirs()) {
            throw AzpPackage.AzpException("azp: cannot create models directory ${modelsDir.absolutePath}")
        }
        val installed = plan.models.map { model -> installOne(plan, model, modelsDir, onProgress) }
        return Result(plan.trust, plan.loaded.manifest.id, installed)
    }

    private fun installOne(
        plan: AzpModelInstaller.InstallPlan,
        model: AzpModelInstaller.PlannedModel,
        modelsDir: File,
        onProgress: (Progress) -> Unit,
    ): Installed {
        // Land the bytes in a `.part` file first; only a fully-verified download is promoted to its final
        // name, so a crash mid-download never leaves a truncated model wired in.
        val part = File(modelsDir, sanitizeFilename(model.filename) + ".part")
        part.parentFile?.mkdirs()
        try {
            if (model.bundled) {
                val bytes = AzpModelInstaller.bundledBytes(plan, model)
                    ?: throw AzpPackage.AzpException("azp: bundled bytes missing for ${model.assetPath}")
                // Integrity of bundled bytes was already confirmed against manifest.files by plan().
                part.delete()
                onProgress(Progress(model, Phase.WRITING, 0, bytes.size.toLong()))
                part.writeBytes(bytes)
                onProgress(Progress(model, Phase.WRITING, bytes.size.toLong(), bytes.size.toLong()))
            } else {
                val url = model.remoteUrl
                    ?: throw AzpPackage.AzpException("azp: model ${model.filename} has neither bundled bytes nor a remoteUrl")
                val digest = downloadTo(url, part, model, onProgress)
                onProgress(Progress(model, Phase.VERIFYING, part.length(), model.byteSize))
                if (!AzpModelInstaller.checksumMatchesDigest(digest, model.checksum)) {
                    throw AzpPackage.AzpException(
                        "azp: checksum mismatch for ${model.filename} — refusing to install a model that " +
                            "doesn't match its declared SHA-256",
                    )
                }
            }
            return Installed(model, model.slot, finalize(modelsDir, model, part))
        } finally {
            part.delete()
        }
    }

    /**
     * Stream [url] into [dest] while hashing, returning the `sha256-<hex>` of what was written. Retries
     * transient failures, resuming from the bytes already on disk (via a `Range` request) when the server
     * honours it; a definite 4xx doesn't retry.
     */
    private fun downloadTo(
        url: String,
        dest: File,
        model: AzpModelInstaller.PlannedModel,
        onProgress: (Progress) -> Unit,
    ): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return attemptDownload(url, dest, model, onProgress)
            } catch (e: InterruptedException) {
                // Cancellation (e.g. the host coroutine was cancelled): restore the flag and propagate.
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                // A definite 4xx won't fix itself by retrying the same URL. Only parse when the message
                // actually carries an "HTTP <code>" so an unrelated AzpException isn't misclassified.
                val message = (e as? AzpPackage.AzpException)?.message
                val httpCode = message?.takeIf { "HTTP " in it }?.substringAfterLast("HTTP ")?.take(3)?.toIntOrNull()
                val fatal = httpCode != null && httpCode in 400..499
                if (fatal) throw e
                if (attempt >= DOWNLOAD_RETRIES) {
                    if (e is AzpPackage.AzpException) throw e
                    throw AzpPackage.AzpException("azp: download of ${model.filename} failed after $attempt attempts — ${e.message}")
                }
                Thread.sleep(500L * attempt) // brief backoff; keep the partial so the next attempt resumes
            }
        }
    }

    private fun attemptDownload(
        url: String,
        dest: File,
        model: AzpModelInstaller.PlannedModel,
        onProgress: (Progress) -> Unit,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val resumeFrom = if (dest.exists()) dest.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw AzpPackage.AzpException("azp: download of ${model.filename} failed — HTTP $code")
            }
            val resuming = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (!resuming) dest.delete()
            val base = if (resuming) resumeFrom else 0L
            val total = model.byteSize
                ?: conn.contentLengthLong.takeIf { it > 0 }?.let { it + base }
            // When resuming, re-hash the bytes already on disk so the digest covers the whole file
            // (reported as VERIFYING so the UI doesn't show the bar racing during a local read).
            if (resuming) dest.inputStream().use { copyHashing(it, md, null, 0, total, model, Phase.VERIFYING, onProgress) }
            conn.inputStream.use { input ->
                FileOutputStream(dest, resuming).use { out ->
                    copyHashing(input, md, out, base, total, model, Phase.DOWNLOADING, onProgress)
                }
            }
            return "sha256-" + md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Copy [input] to [out] (null ⇒ hash only), feeding [md]; returns the running total after [startDone].
     * Progress is throttled to [PROGRESS_INTERVAL] so a hundred-MB model doesn't fire thousands of
     * callbacks at the host UI, but the final byte count is always reported.
     */
    private fun copyHashing(
        input: InputStream,
        md: MessageDigest,
        out: OutputStream?,
        startDone: Long,
        total: Long?,
        model: AzpModelInstaller.PlannedModel,
        phase: Phase,
        onProgress: (Progress) -> Unit,
    ): Long {
        val buf = ByteArray(BUFFER)
        var done = startDone
        var lastReported = startDone
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            out?.write(buf, 0, n)
            done += n
            if (done - lastReported >= PROGRESS_INTERVAL) {
                onProgress(Progress(model, phase, done, total))
                lastReported = done
            }
        }
        if (done != lastReported) onProgress(Progress(model, phase, done, total))
        return done
    }

    /** Promote a verified `.part` to its final on-disk form; a `sherpa-bundle` zip is extracted to a dir. */
    private fun finalize(modelsDir: File, model: AzpModelInstaller.PlannedModel, part: File): String {
        if (model.type.trim().lowercase() == "sherpa-bundle") {
            val outDir = File(modelsDir, sanitizeFilename(model.filename).removeSuffix(".zip"))
            if (outDir.exists()) outDir.deleteRecursively()
            outDir.mkdirs()
            part.inputStream().use { extractZip(it, outDir) }
            return outDir.absolutePath
        }
        val target = File(modelsDir, sanitizeFilename(model.filename))
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true) // renameTo can fail across filesystems
        }
        return target.absolutePath
    }

    /** Extract a zip [input] under [destDir], rejecting any entry that would escape it (Zip-Slip). */
    private fun extractZip(input: InputStream, destDir: File) {
        val root = destDir.canonicalFile
        ZipInputStream(input).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                val out = File(destDir, name).canonicalFile
                if (out.path != root.path && !out.path.startsWith(root.path + File.separator)) {
                    throw AzpPackage.AzpException("azp: sherpa-bundle entry escapes target dir: $name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { fos -> zin.copyTo(fos, BUFFER) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    /** Strip any path component from a suggested filename so it can only land directly in the models dir. */
    private fun sanitizeFilename(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = base.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "model.bin"
    }

    /** Test seam: extract a zip [bytes] into [destDir] with the same Zip-Slip guard as install. */
    internal fun extractZipBytes(bytes: ByteArray, destDir: File) =
        ByteArrayInputStream(bytes).use { extractZip(it, destDir) }
}
