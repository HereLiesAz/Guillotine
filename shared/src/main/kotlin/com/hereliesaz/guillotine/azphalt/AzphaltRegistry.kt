package com.hereliesaz.guillotine.azphalt

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the bytes an [AzpInstallLink] names — the **download half only** of azphalt's Repository API
 * (`spec/repository-api.md`).
 *
 * There is deliberately no catalog browsing here. Guillotine removed `AzphaltRepository`'s
 * `fetchCatalog`/`download` on 2026-07-28 (see `docs/TODO.md`) when the app-to-app store handoff replaced
 * its home-grown storefront UI, and browsing stays delegated to whichever Azphalt Store app is installed.
 * A deep link doesn't need a catalog: it already names exactly one package.
 *
 * The bytes come back **unverified**. Trust is [AzpHandoffInstaller]'s job — integrity, signature, and
 * trust-on-first-use publisher pinning — and a link that named the package earns no standing at all.
 *
 * Pure JVM ([HttpURLConnection] + `org.json`, mirroring
 * [com.hereliesaz.guillotine.ai.ModelCatalog]) so `:shared` stays platform-agnostic. Every call blocks;
 * invoke off the main thread.
 */
object AzphaltRegistry {

    /**
     * The flagship registry root. v1 downloads from **this host only** — a caller-supplied repository URL
     * is deliberately not accepted, because a deep link originates in a web page and a web page must not
     * be able to point Guillotine at an arbitrary download host. This is a deliberate v1 limit, removable
     * once `spec/repository-api.md` says how a host decides that some *other* repository is trustworthy
     * (the `/.well-known/azphalt-repository.json` trust bootstrap is the obvious hook, but its
     * `signingKeys` field isn't populated yet — see [AzphaltTrust.FLAGSHIP_SIGNING_KEY]).
     *
     * Not `…/api` — that's the storefront's internal Next.js route namespace, where the download path
     * falls through an SPA catch-all and returns `index.html` with HTTP 200. The Repository API root has
     * no `/api` prefix. Do not re-add one.
     */
    const val BASE_URL = "https://www.azphalt.store"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Cap a package-detail body so a broken or hostile registry can't exhaust memory. */
    private const val MAX_DETAIL_BYTES = 8L * 1024 * 1024

    /**
     * Cap a downloaded `.azp` at 128 MiB and fail loudly rather than OOM. Large model weights ship
     * out-of-band (a manifest's `remoteUrl` + checksum), so a package itself has no business being this
     * big in the first place.
     */
    private const val MAX_AZP_BYTES = 128L * 1024 * 1024

    /** A transport, HTTP-status, or response-shape failure talking to the registry. */
    class RegistryException(message: String) : Exception(message)

    /**
     * Resolves and downloads the `.azp` [link] names, returning its **unverified** bytes.
     *
     * When [AzpInstallLink.version] is absent the version comes from `GET /packages/{id}`'s top-level
     * `latest`; otherwise the named version is used as-is. Both are path-validated at parse time.
     */
    fun download(link: AzpInstallLink): ByteArray {
        val version = link.version ?: latestVersion(link.id)
        val bytes = getBytes("$BASE_URL/packages/${link.id}/versions/$version/download", MAX_AZP_BYTES)
        // Every ZIP — a .azp is a zip container — starts with a "PK" signature. Anything else (an HTML
        // error page, a JSON envelope) would otherwise reach AzpPackage and surface as a baffling
        // "manifest.json is missing" instead of "that wasn't a package".
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw RegistryException("The download from azphalt.store wasn't a package (got ${sniff(bytes)}).")
        }
        return bytes
    }

    /** The `latest` version string from `GET /packages/{id}`. */
    fun latestVersion(id: String): String {
        val body = String(getBytes("$BASE_URL/packages/$id", MAX_DETAIL_BYTES), Charsets.UTF_8)
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw RegistryException("azphalt.store returned something that isn't package details for “$id”.")
        }
        val latest = json.optString("latest").takeIf { it.isNotBlank() }
            ?: throw RegistryException("azphalt.store didn't say which version of “$id” is latest.")
        // The link's own version is validated at parse time; this one comes from the network, so it gets
        // the same treatment before being interpolated into a URL path.
        if (latest.contains("..") || !AzpInstallLink.VERSION_PATTERN.matches(latest)) {
            throw RegistryException("azphalt.store reported an unusable version for “$id”.")
        }
        return latest
    }

    /** A short, safe-to-show description of bytes that weren't a package, for [download]'s message. */
    private fun sniff(bytes: ByteArray): String {
        val text = bytes.take(64).toByteArray().toString(Charsets.UTF_8).trimStart()
        return when {
            bytes.isEmpty() -> "an empty response"
            text.startsWith("<!DOCTYPE", ignoreCase = true) || text.startsWith("<html", ignoreCase = true) -> "a web page"
            text.startsWith("{") || text.startsWith("[") -> "a JSON response"
            else -> "an unrecognized response"
        }
    }

    private fun getBytes(url: String, maxBytes: Long): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true // azphalt.store → www.azphalt.store
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw RegistryException("Couldn't reach azphalt.store — ${e.message ?: "no connection"}.")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.errorStream?.use { runCatching { it.readBounded(maxBytes) } }
                throw RegistryException(describe(code))
            }
            return conn.inputStream.use { it.readBounded(maxBytes) }
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int): String = when (code) {
        // The registry gates paid downloads behind an entitlement token. Guillotine doesn't implement
        // entitlements — that's a separate piece of the spec and out of scope here — so say so plainly
        // rather than pretending the download merely failed.
        HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_PAYMENT_REQUIRED ->
            "This package is paid — acquire it in the Azphalt Store app. Guillotine can't buy it for you: " +
                "it doesn't implement azphalt's paid-download entitlement tokens yet."
        HttpURLConnection.HTTP_NOT_FOUND -> "azphalt.store doesn't have that package or version."
        else -> "azphalt.store returned HTTP $code."
    }

    /** Reads at most [maxBytes]; exceeding it is a hard error, never a silent truncation. */
    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw RegistryException("That download is larger than the ${maxBytes / (1024 * 1024)} MB limit.")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
