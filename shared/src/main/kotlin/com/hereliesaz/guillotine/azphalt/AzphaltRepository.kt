package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for an azphalt **Repository API** (azphalt `spec/repository-api.md`) — the hosted storefront
 * that serves the package catalog, defaulting to the flagship registry at azphalt.store. This is the
 * *discovery* half of the store: browsing/searching packages and pulling a chosen `.azp`'s bytes.
 *
 * It deliberately does **not** decide trust or install anything — a downloaded `.azp` is untrusted
 * until the caller runs it through [AzpPackage] (`load` for integrity, `verifyTrust` for provenance)
 * and the on-device `Azp*Installer` host runtime. Keeping discovery remote and install on-device is
 * the whole point: the catalog lives at the registry, not embedded in the app.
 *
 * Pure-JVM (`HttpURLConnection`, mirroring [AzpModelInstall]) so both Android and desktop share it.
 * All calls block, so invoke them off the main thread.
 */
class AzphaltRepository(
    /** Repository root; every endpoint is appended to this. Defaults to the flagship registry. */
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        /**
         * The flagship registry's API root. A user/host may point at any conforming repository.
         *
         * Deliberately **not** `https://www.azphalt.store/api` — that's the Next.js storefront's own
         * internal route namespace, where only `/api/packages` happens to exist (the storefront's own
         * catalog fetch). It's a trap: browsing looks like it works, since that one path resolves, but
         * `/api/packages/{id}/versions/{version}/download` and `/api/.well-known/...` fall through the
         * SPA catch-all and return the storefront's `index.html` (HTTP 200, so it isn't even caught as
         * an HTTP error) — which `AzpPackage` then correctly, but confusingly, rejects as "manifest.json
         * is missing" rather than the real problem being "wrong base URL entirely". The real Repository
         * API root has no `/api` prefix: verified live — `GET /packages` there returns the spec's
         * `{ "packages": [...] }` envelope, and `/packages/{id}/versions/{version}/download` returns a
         * real `.azp`. Do not re-add `/api` here.
         */
        const val DEFAULT_BASE_URL = "https://www.azphalt.store"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Cap a catalog body so a broken/hostile registry can't exhaust memory. */
        private const val MAX_CATALOG_BYTES = 8L * 1024 * 1024

        /** Cap a downloaded `.azp`. Large model weights ship out-of-band (manifest `remoteUrl`), so the
         *  package itself stays small — this guards against a registry streaming an unbounded body. */
        private const val MAX_AZP_BYTES = 64L * 1024 * 1024
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A package summary as served by `GET /packages`. Every field past [id] is optional/defaulted so
     * the client tolerates a registry that omits fields or adds new ones (unknown keys are ignored).
     */
    @Serializable
    data class RepoPackage(
        val id: String,
        val name: String = id,
        val description: String = "",
        val author: String = "",
        val version: String = "",
        /** `asset` | `code` | `mixed` | `app` | `mcp`. */
        val kind: String = "asset",
        val capabilities: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        /** Reverse-DNS host ids this package is scoped to; empty = global. See [targetsApp]. */
        val targetApps: List<String> = emptyList(),
        val mediaDomains: List<String> = emptyList(),
        val downloads: Long = 0,
        val rating: Double? = null,
        val ratingCount: Long = 0,
        val updatedAt: String? = null,
        /** Absent/`null` ⇒ free (open lane); present ⇒ a paid package. */
        val price: RepoPrice? = null,
        /** Store-card preview (`image`/`clip`), each an `https:` URL or in-package path. */
        val preview: AzpPreview? = null,
    ) {
        val isFree: Boolean get() = price == null || price.amountCents <= 0

        /** Whether this package is offered to host [hostId] (global, or [hostId] in [targetApps]). */
        fun targetsApp(hostId: String): Boolean = targetApps.isEmpty() || targetApps.contains(hostId)
    }

    @Serializable
    data class RepoPrice(val amountCents: Int = 0, val currency: String = "USD")

    /** A transport, HTTP-status, or parse failure talking to the registry. */
    class RepoException(message: String) : Exception(message)

    /**
     * Fetch the catalog from `GET /packages`. [query] and [hostAppId] are sent as the spec's `q`/`app`
     * filters; [hostAppId] is **also** applied client-side (via [RepoPackage.targetsApp]) so host
     * scoping holds even against a registry that ignores the param. Blocks; throws [RepoException].
     */
    fun fetchCatalog(query: String = "", hostAppId: String = ""): List<RepoPackage> {
        val params = buildList {
            if (query.isNotBlank()) add("q=" + enc(query))
            if (hostAppId.isNotBlank()) add("app=" + enc(hostAppId))
        }
        val url = endpoint("/packages") + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return scopeToHost(parseCatalog(getText(url, MAX_CATALOG_BYTES)), hostAppId)
    }

    /** Client-side host scoping — kept even against a registry that ignores the `app` param. */
    internal fun scopeToHost(packages: List<RepoPackage>, hostAppId: String): List<RepoPackage> =
        if (hostAppId.isBlank()) packages else packages.filter { it.targetsApp(hostAppId) }

    /**
     * Download a package's `.azp` bytes from `GET /packages/{id}/versions/{version}/download`. The
     * bytes are returned **unverified** — the caller MUST still pass them through [AzpPackage] before
     * installing (integrity/signature/trust are its job, not this one). This only rejects the shape
     * a wrong base URL produces: an HTTP-200 body that isn't a ZIP at all (e.g. an SPA's `index.html`
     * served from a catch-all route) — that used to reach [AzpPackage] and fail as a confusing
     * "manifest.json is missing" with no hint the real problem was upstream of the package entirely.
     */
    fun download(id: String, version: String): ByteArray {
        val bytes = getBytes(endpoint("/packages/${enc(id)}/versions/${enc(version)}/download"), MAX_AZP_BYTES)
        // Every ZIP - empty or not - starts with a "PK" local-file-header or end-of-central-directory
        // signature; anything else (HTML, JSON, plain text) could not possibly be a valid .azp.
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw RepoException(
                "registry: download did not return a package (got ${sniff(bytes)} instead) — " +
                    "check the registry base URL",
            )
        }
        return bytes
    }

    /** A short, safe-to-log description of non-package bytes, for [download]'s error message. */
    private fun sniff(bytes: ByteArray): String {
        val text = bytes.take(64).toByteArray().toString(Charsets.UTF_8)
        return when {
            text.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) || text.trimStart().startsWith("<html", ignoreCase = true) -> "an HTML page"
            text.trimStart().startsWith("{") || text.trimStart().startsWith("[") -> "a JSON response"
            bytes.isEmpty() -> "an empty response"
            else -> "an unrecognized response"
        }
    }

    // ---- parsing ----

    /** Accept the live bare-array shape `[...]` or the spec's `{ "packages": [...] }` envelope. */
    internal fun parseCatalog(body: String): List<RepoPackage> {
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw RepoException("registry: response is not JSON (is the base URL a Repository API root?)")
        }
        val arrayElement = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is JsonObject -> root["packages"] as? kotlinx.serialization.json.JsonArray
                ?: throw RepoException("registry: object response has no \"packages\" array")
            else -> throw RepoException("registry: unexpected response shape")
        }
        return try {
            json.decodeFromJsonElement(ListSerializer(RepoPackage.serializer()), arrayElement)
        } catch (e: Exception) {
            throw RepoException("registry: could not parse catalog — ${e.message}")
        }
    }

    /** Best-effort read of the normative error envelope `{ "error": { "code", "message" } }`. */
    private fun describeError(code: Int, body: String?): String {
        if (body != null) {
            try {
                val err = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                val ec = err?.get("code")?.jsonPrimitive?.content
                val msg = err?.get("message")?.jsonPrimitive?.content
                if (ec != null || msg != null) return "registry: HTTP $code (${ec ?: "error"})${msg?.let { " — $it" } ?: ""}"
            } catch (_: Exception) {
                // fall through to the bare status
            }
        }
        return "registry: HTTP $code"
    }

    // ---- transport ----

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true // apex → www, http → https
            setRequestProperty("Accept", "application/json")
        }

    private fun getText(url: String, maxBytes: Long): String =
        String(getBytes(url, maxBytes), Charsets.UTF_8)

    private fun getBytes(url: String, maxBytes: Long): ByteArray {
        val conn = open(url)
        try {
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw RepoException("registry: cannot reach ${hostOf(url)} — ${e.message}")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.use { runCatching { it.readBounded(maxBytes) }.getOrNull() }
                    ?.toString(Charsets.UTF_8)
                throw RepoException(describeError(code, err))
            }
            return conn.inputStream.use { it.readBounded(maxBytes) }
        } finally {
            conn.disconnect()
        }
    }

    private fun hostOf(url: String): String = try { URL(url).host } catch (_: Exception) { url }

    /** Read at most [maxBytes]; a body that would exceed it is a hard error, not a silent truncation. */
    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw RepoException("registry: response exceeds ${maxBytes / (1024 * 1024)} MB cap")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
