package com.hereliesaz.guillotine.azphalt

import com.hereliesaz.guillotine.ai.AiSettings
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /**
     * This package's id was previously installed from a *different* publisher key (or was signed before
     * and is now unsigned). Trust-on-first-use pins the first signer; a mismatched signer on a later
     * install of the same id is a publisher change — most likely a hijack, occasionally a legitimate key
     * rotation. UI catches this to surface a distinct "different publisher" warning and re-invoke with
     * `allowPublisherChange = true` only on the user's explicit confirmation.
     */
    class PublisherChangedException(
        val packageId: String,
        val pinnedKey: String,
        val newSignerKey: String?,
    ) : Exception(
        "azp: '$packageId' was first installed from a different publisher; this update is signed by " +
            (if (newSignerKey == null) "no key" else "a different key") +
            " — refusing without explicit approval of the publisher change",
    )

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
        /** The base64 SPKI key that signed this package, or null if it was unsigned. Now pinned for [packageId]. */
        val signerPublicKey: String? = null,
    ) {
        /** (Deprecated) Legacy routing hook; models now load straight from the registry. */
        fun applyTo(settings: AiSettings): AiSettings = settings
    }

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val DOWNLOAD_RETRIES = 4
    private const val BUFFER = 1 shl 16
    private const val PROGRESS_INTERVAL = 1 shl 20 // ~1 MB between progress callbacks

    /** Overall wall-clock ceiling for a single model download, across every retry — see [downloadTo]. */
    private const val MAX_DOWNLOAD_MS = 30 * 60_000L

    /** Fallback byte ceiling for a download whose manifest didn't declare [AzpModelInstaller.PlannedModel.byteSize]. */
    private const val DEFAULT_MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024 * 1024

    /** How far over a declared `byteSize` a download may run before it's treated as runaway. */
    private const val DOWNLOAD_SIZE_MARGIN = 1.10

    /**
     * Zip-bomb guards for [extractZip] — same rationale and same limits as [AzpPackage]'s own `unzip`
     * guards: a `sherpa-bundle` is a zip too, and extracting it is exactly as unzip-a-hostile-archive
     * exposed as [AzpPackage.verify]/[load]/etc are.
     */
    private const val MAX_ZIP_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 10_000

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
        pins: AzpPublisherPins? = null,
        allowPublisherChange: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val plan = AzpModelInstaller.plan(azpBytes, trustedKeys) // throws on integrity/signature failure
        val packageId = plan.loaded.manifest.id
        val signer = plan.trust.signerPublicKey

        // Publisher continuity (trust-on-first-use): if this id was installed before, the signer must
        // match the pinned key. Checked before the trust-store prompt so a hijacked update surfaces as a
        // publisher change, not a generic "untrusted signer". Dormant until packages are signed.
        val pinned = pins?.keyFor(packageId)
        if (pinned != null && signer != pinned && !allowPublisherChange) {
            throw PublisherChangedException(packageId, pinned, signer)
        }

        if (!plan.trust.trusted && !allowUntrusted) throw UntrustedException(plan.trust)
        if (!modelsDir.isDirectory && !modelsDir.mkdirs()) {
            throw AzpPackage.AzpException("azp: cannot create models directory ${modelsDir.absolutePath}")
        }
        val installed = plan.models.map { model -> installOne(plan, model, modelsDir, onProgress) }

        // Pin the publisher on first install, or when the caller approved a rotation. Only signed
        // packages pin — an unsigned package leaves no key to enforce against.
        if (signer != null && pins != null && (pinned == null || allowPublisherChange)) {
            pins.pin(packageId, signer)
        }
        return Result(plan.trust, packageId, installed, signer)
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
     *
     * Refuses a non-`https` [url] outright, before the first attempt and without retrying — model
     * weights are the one thing this pipeline pulls from a network address a manifest names, and
     * plaintext `http` has no protection against a network-position attacker substituting a malicious
     * file (nothing downstream re-verifies content *type*, only the declared checksum, which the same
     * attacker could rewrite in the manifest too if the transport itself isn't authenticated).
     *
     * The whole download — every retry combined — is also bounded by a single wall-clock deadline
     * ([MAX_DOWNLOAD_MS]) computed once here, so a slow-drip server can't hang an install indefinitely
     * by trickling bytes just fast enough to dodge the per-read [READ_TIMEOUT_MS].
     */
    private fun downloadTo(
        url: String,
        dest: File,
        model: AzpModelInstaller.PlannedModel,
        onProgress: (Progress) -> Unit,
    ): String {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw AzpPackage.AzpException(
                "azp: refusing to download ${model.filename} over a non-https URL — model weights must be fetched over https",
            )
        }
        val deadlineAt = System.currentTimeMillis() + MAX_DOWNLOAD_MS
        var attempt = 0
        while (true) {
            attempt++
            try {
                return attemptDownload(url, dest, model, deadlineAt, onProgress)
            } catch (e: InterruptedException) {
                // Cancellation (e.g. the host coroutine was cancelled): restore the flag and propagate.
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                // A definite 4xx won't fix itself by retrying the same URL, and neither will a download
                // that already blew through its wall-clock deadline or byte ceiling — those are absolute
                // stop conditions, not transient hiccups, so retrying them would just repeat the same
                // failure (or, for the byte ceiling, keep re-downloading past it) up to DOWNLOAD_RETRIES
                // times for no benefit. Only parse the HTTP code when the message actually carries an
                // "HTTP <code>" so an unrelated AzpException isn't misclassified.
                val message = (e as? AzpPackage.AzpException)?.message
                val httpCode = message?.takeIf { "HTTP " in it }?.substringAfterLast("HTTP ")?.take(3)?.toIntOrNull()
                val fatal = (httpCode != null && httpCode in 400..499) || message?.contains("exceeded") == true
                if (fatal) throw e
                if (attempt >= DOWNLOAD_RETRIES || System.currentTimeMillis() >= deadlineAt) {
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
        deadlineAt: Long,
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
            // A hard byte ceiling independent of the server's own claims: the declared byteSize plus a
            // margin for legitimate slack, or a fixed fallback cap when no size was declared at all —
            // either way a run-away/lying server gets cut off rather than filling the disk.
            val maxBytes = model.byteSize?.let { (it * DOWNLOAD_SIZE_MARGIN).toLong() } ?: DEFAULT_MAX_DOWNLOAD_BYTES
            // When resuming, re-hash the bytes already on disk so the digest covers the whole file
            // (reported as VERIFYING so the UI doesn't show the bar racing during a local read).
            if (resuming) {
                dest.inputStream().use { copyHashing(it, md, null, 0, total, model, Phase.VERIFYING, deadlineAt, maxBytes, onProgress) }
            }
            conn.inputStream.use { input ->
                FileOutputStream(dest, resuming).use { out ->
                    copyHashing(input, md, out, base, total, model, Phase.DOWNLOADING, deadlineAt, maxBytes, onProgress)
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
     *
     * Enforces both runaway-download guards per read: [deadlineAt] (wall-clock, shared across every
     * retry of the same download) and [maxBytes] (a hard byte ceiling — see [attemptDownload]).
     */
    private fun copyHashing(
        input: InputStream,
        md: MessageDigest,
        out: OutputStream?,
        startDone: Long,
        total: Long?,
        model: AzpModelInstaller.PlannedModel,
        phase: Phase,
        deadlineAt: Long,
        maxBytes: Long,
        onProgress: (Progress) -> Unit,
    ): Long {
        val buf = ByteArray(BUFFER)
        var done = startDone
        var lastReported = startDone
        while (true) {
            if (System.currentTimeMillis() >= deadlineAt) {
                throw AzpPackage.AzpException(
                    "azp: download of ${model.filename} exceeded the ${MAX_DOWNLOAD_MS / 60_000} minute time limit",
                )
            }
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            out?.write(buf, 0, n)
            done += n
            if (done > maxBytes) {
                throw AzpPackage.AzpException(
                    "azp: download of ${model.filename} exceeded ${maxBytes / (1024 * 1024)} MB — refusing to continue",
                )
            }
            if (done - lastReported >= PROGRESS_INTERVAL) {
                onProgress(Progress(model, phase, done, total))
                lastReported = done
            }
        }
        if (done != lastReported) onProgress(Progress(model, phase, done, total))
        return done
    }

    /**
     * Promote a verified `.part` to its final on-disk form; a `sherpa-bundle` zip is extracted to a dir.
     *
     * [ModelResolver] (`app`/`desktop`) trusts *any* file sitting at a catalog-known filename in the
     * models dir — it has no manifest to check a signature against once a model has landed on disk, only
     * a path. Every file that lands here already passed [AzpModelInstaller.plan]'s integrity + trust
     * gate (directly trusted, or an explicit user override), so that half of "eligible to satisfy a
     * resolver slot" already held before this method exists. What did **not** hold: a *different*
     * package — trusted or user-approved on its own terms — declaring an asset whose filename happens to
     * match one already installed by another package would silently swap out that other package's model
     * with no signal at all, because nothing recorded *which* package put which file there. [checkSlotCollision]
     * closes that: it refuses (rather than silently overwriting) when the target name is already owned
     * by a different package id, and [recordProvenance] keeps that ownership record current for next time.
     */
    private fun finalize(modelsDir: File, model: AzpModelInstaller.PlannedModel, part: File): String {
        if (model.type.trim().lowercase() == "sherpa-bundle") {
            val key = sanitizeFilename(model.filename).removeSuffix(".zip")
            checkSlotCollision(modelsDir, key, model.packageId)
            val outDir = File(modelsDir, key)
            if (outDir.exists()) outDir.deleteRecursively()
            outDir.mkdirs()
            part.inputStream().use { extractZip(it, outDir) }
            recordProvenance(modelsDir, key, model.packageId)
            return outDir.absolutePath
        }
        val key = sanitizeFilename(model.filename)
        checkSlotCollision(modelsDir, key, model.packageId)
        val target = File(modelsDir, key)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true) // renameTo can fail across filesystems
        }
        recordProvenance(modelsDir, key, model.packageId)
        return target.absolutePath
    }

    // ---- slot-collision guard: which package id currently owns each on-disk filename/dir ----

    private val provenanceLock = Any()

    private fun provenanceFile(modelsDir: File) = File(modelsDir, ".azp-model-provenance.json")

    private fun readProvenance(modelsDir: File): Map<String, String> {
        val f = provenanceFile(modelsDir)
        if (!f.isFile || f.length() == 0L) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            val m = LinkedHashMap<String, String>()
            for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it }
            m
        } catch (e: Exception) {
            // Best-effort: a corrupt provenance file must not block every future install. Losing the
            // ownership record just means the next write to a colliding name won't be caught — no worse
            // than the guard not existing at all, and strictly better than refusing every install because
            // one bookkeeping file got corrupted.
            emptyMap()
        }
    }

    /**
     * Refuses to let [packageId] claim [key] (a filename or `sherpa-bundle` directory name under the
     * models dir) when a *different* package id already owns it — the actual fix for the filename-
     * collision model-swap: without this, any trusted-or-approved install could silently replace a
     * different package's model just by declaring an asset that resolves to the same on-disk name. An
     * update from the *same* package id is the ordinary, expected case and passes through untouched.
     */
    private fun checkSlotCollision(modelsDir: File, key: String, packageId: String) = synchronized(provenanceLock) {
        val existingOwner = readProvenance(modelsDir)[key]
        if (existingOwner != null && existingOwner != packageId) {
            throw AzpPackage.AzpException(
                "azp: refusing to install '$key' — it was installed by a different package " +
                    "('$existingOwner'), and '$packageId' would silently replace it. Uninstall the " +
                    "existing package first if this is intentional.",
            )
        }
    }

    /** Records [packageId] as the current owner of on-disk name [key], written atomically. */
    private fun recordProvenance(modelsDir: File, key: String, packageId: String) = synchronized(provenanceLock) {
        val m = readProvenance(modelsDir)
        if (m[key] == packageId) return@synchronized
        val updated = LinkedHashMap(m).apply { put(key, packageId) }
        val o = JSONObject()
        for ((k, v) in updated) o.put(k, v)
        val file = provenanceFile(modelsDir)
        val tmp = File(modelsDir, file.name + ".tmp")
        tmp.writeText(o.toString(2))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                file.writeText(o.toString(2))
                tmp.delete()
            }
        }
    }

    /**
     * Extract a zip [input] under [destDir], rejecting any entry that would escape it (Zip-Slip), any
     * single entry over [MAX_ZIP_ENTRY_BYTES], a cumulative extract over [MAX_ZIP_TOTAL_BYTES], and more
     * than [MAX_ZIP_ENTRIES] entries — the same zip-bomb ceilings [AzpPackage]'s own unzip enforces,
     * since a `sherpa-bundle` is exactly as much a hostile-archive risk as the outer `.azp` is.
     */
    private fun extractZip(input: InputStream, destDir: File) {
        val root = destDir.canonicalFile
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(input).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw AzpPackage.AzpException("azp: sherpa-bundle has more than $MAX_ZIP_ENTRIES entries — refusing to extract")
                }
                val name = entry.name.replace('\\', '/')
                val out = File(destDir, name).canonicalFile
                if (out.path != root.path && !out.path.startsWith(root.path + File.separator)) {
                    throw AzpPackage.AzpException("azp: sherpa-bundle entry escapes target dir: $name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    val written = out.outputStream().use { fos -> copyBounded(zin, fos, name) }
                    totalBytes += written
                    if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                        throw AzpPackage.AzpException(
                            "azp: sherpa-bundle decompresses to more than ${MAX_ZIP_TOTAL_BYTES / (1024 * 1024)} MB — refusing to extract",
                        )
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    /** Copies [input] to [out], refusing to write more than [MAX_ZIP_ENTRY_BYTES] for entry [name]. */
    private fun copyBounded(input: InputStream, out: OutputStream, name: String): Long {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ZIP_ENTRY_BYTES) {
                throw AzpPackage.AzpException(
                    "azp: sherpa-bundle entry $name exceeds the ${MAX_ZIP_ENTRY_BYTES / (1024 * 1024)} MB per-entry limit",
                )
            }
            out.write(buf, 0, n)
        }
        return total
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
