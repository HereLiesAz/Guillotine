package com.hereliesaz.guillotine.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A downloadable file attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /**
     * Lowercase hex SHA-256 the release publishes for this asset (GitHub's `digest`, minus its
     * `sha256:` prefix), or null when the release doesn't publish one. Null is "nothing to check
     * against", never "checked and fine" — see [UpdateChecker.verify].
     */
    val sha256: String? = null,
)

/**
 * The GitHub release we might update to. [assets] is the list of attached installers/APKs; each
 * platform picks the one it cares about (by filename) and compares that asset's embedded version
 * against its own — Android against the `.apk`, desktop against the host `.deb`/`.dmg`/`.msi`. We
 * deliberately do NOT compare the release *name*: the Android versionName (major 0) and the desktop
 * package version (major floored to 1) live in different numbering spaces, so only like-for-like
 * asset comparison is meaningful.
 */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
) {
    /** The first asset whose filename satisfies [predicate], or null. */
    fun asset(predicate: (String) -> Boolean): ReleaseAsset? = assets.firstOrNull { predicate(it.name) }
}

/**
 * Checks GitHub Releases for a newer build and streams the chosen installer/APK to disk. Pure JVM
 * (okhttp + org.json), so `:app` (github flavor) and `:desktop` share it — each supplies its own
 * current version and asset matcher, then hands the downloaded file to its platform installer.
 *
 * Every network path is failure-tolerant (returns null / false), because a failed update check must
 * never disrupt the editor.
 */
object UpdateChecker {
    const val DEFAULT_OWNER = "HereLiesAz"
    const val DEFAULT_REPO = "Guillotine"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetch the latest published (non-draft, non-prerelease) release for [owner]/[repo]. Returns null
     * on any network/HTTP/parse failure.
     */
    fun fetchLatest(owner: String = DEFAULT_OWNER, repo: String = DEFAULT_REPO): ReleaseInfo? = runCatching {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Guillotine-Updater")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            parseRelease(resp.body.string())
        }
    }.getOrNull()

    /** Parse a GitHub `releases/latest` JSON payload into [ReleaseInfo]. Pure — unit-testable. */
    fun parseRelease(json: String): ReleaseInfo {
        val o = JSONObject(json)
        val assets = ArrayList<ReleaseAsset>()
        o.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                assets.add(
                    ReleaseAsset(
                        name = a.optString("name"),
                        downloadUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                        // GitHub publishes this as "sha256:<hex>". Anything else (a different algorithm,
                        // a malformed value) is treated as absent rather than guessed at.
                        sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }
                            ?.removePrefix("sha256:")?.lowercase()?.takeIf { h ->
                                h.length == 64 && h.all { it in '0'..'9' || it in 'a'..'f' }
                            },
                    ),
                )
            }
        }
        return ReleaseInfo(
            tag = o.optString("tag_name"),
            name = o.optString("name"),
            notes = o.optString("body"),
            htmlUrl = o.optString("html_url"),
            assets = assets,
        )
    }

    /**
     * The first dotted-numeric version found in [text], preferring the longest form (4-part, then 3,
     * then 2). Used to pull a comparable version out of an asset filename or the release name, e.g.
     * `Guillotine-0.9.463.205921353-release.apk` → `0.9.463.205921353`, `guillotine_1.9.463-1_amd64.deb`
     * → `1.9.463`. Returns null if none is present.
     */
    fun versionIn(text: String): String? {
        Regex("""\d+(?:\.\d+){3}""").find(text)?.let { return it.value }
        Regex("""\d+(?:\.\d+){2}""").find(text)?.let { return it.value }
        Regex("""\d+(?:\.\d+){1}""").find(text)?.let { return it.value }
        return null
    }

    /** True when [latest] is a strictly higher version than [current] (dotted numeric, missing parts = 0). */
    fun isNewer(current: String, latest: String): Boolean = compareVersions(latest, current) > 0

    /** Compare two dotted versions component-by-component; shorter is zero-padded. */
    fun compareVersions(a: String, b: String): Int {
        val pa = versionParts(a)
        val pb = versionParts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val cmp = pa.getOrElse(i) { 0L }.compareTo(pb.getOrElse(i) { 0L })
            if (cmp != 0) return cmp
        }
        return 0
    }

    // Long, not Int: the build component is a monotonic timestamp-ish number that can exceed
    // Int.MAX_VALUE, which toIntOrNull would silently turn into 0 and break the comparison.
    private fun versionParts(v: String): List<Long> =
        Regex("""\d+""").findAll(v).map { it.value.toLongOrNull() ?: 0L }.toList()

    /**
     * Stream [url] to [dest], reporting (bytesRead, totalBytes) as it goes (totalBytes is -1 if the
     * server omits Content-Length). Returns true on success; on any failure the partial file is
     * deleted and false returned.
     */
    /**
     * Check [file] against what [asset] advertises, before it is handed to an installer.
     *
     * Size is always checked — it is free and catches the common real failure, a partial download.
     * The SHA-256 is checked when the release publishes one. Note honestly what this is worth: the
     * digest arrives over the same API connection as the download URL, so it proves the bytes are the
     * ones GitHub meant to serve, not that GitHub itself is trustworthy. Provenance needs a signing key
     * this project doesn't have yet (see the signing/notarization item in docs/TODO.md).
     */
    fun verify(file: File, asset: ReleaseAsset): UpdateVerification {
        if (!file.isFile) return UpdateVerification.Failed("the downloaded file is missing")
        if (asset.sizeBytes > 0 && file.length() != asset.sizeBytes) {
            return UpdateVerification.Failed(
                "size mismatch — expected ${asset.sizeBytes} bytes, got ${file.length()}",
            )
        }
        val expected = asset.sha256 ?: return UpdateVerification.SizeOnly
        val actual = sha256Of(file) ?: return UpdateVerification.Failed("could not hash the downloaded file")
        return if (actual == expected) {
            UpdateVerification.Verified
        } else {
            UpdateVerification.Failed("SHA-256 mismatch — expected $expected, got $actual")
        }
    }

    /** Streaming SHA-256 as lowercase hex, or null if the file can't be read. */
    fun sha256Of(file: File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "Guillotine-Updater").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
            }
            true
        }.getOrElse {
            runCatching { dest.delete() }
            false
        }
    }
}

/**
 * What was established about a downloaded update file before anything was run.
 *
 * Both updaters downloaded an installer/APK and handed it straight to the OS — no size check, no
 * digest, nothing. HTTPS to GitHub covers the wire, but it does not catch a truncated write, a full
 * disk, or a stale file left in the temp dir, and those all end with the OS being asked to install
 * whatever bytes happen to be there.
 */
sealed interface UpdateVerification {
    /** The published SHA-256 matched. The strongest statement available without a signing key. */
    object Verified : UpdateVerification

    /**
     * Size matched, but the release published no digest to check against. Not proof of anything beyond
     * "not truncated" — callers may proceed, but must not describe this as verified.
     */
    object SizeOnly : UpdateVerification

    /** Refuse. [reason] says which check failed. */
    data class Failed(val reason: String) : UpdateVerification
}
