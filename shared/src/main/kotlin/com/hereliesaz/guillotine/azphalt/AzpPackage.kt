package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Loads and verifies an azphalt `.azp` package — conformance **job #1** ("load and verify") from
 * azphalt `docs/ADOPTION.md`, done on-device in pure JVM (no WASM runtime, no host wiring yet).
 *
 * A `.azp` is a ZIP holding `manifest.json`, a `LICENSE`, and an `assets/` and/or `code/` payload.
 * [verify] mirrors `@azphalt/azp`'s `verifyAzp` exactly, so a package built by the reference tools
 * loads here unchanged: reject unsafe paths, confirm every `manifest.files` SHA-256 digest, and
 * reject any payload file that isn't digested in the manifest (unlisted ⇒ unverifiable ⇒ refused).
 *
 * Signing (`signature.json`, Ed25519 over the manifest) is not yet enforced — the spec marks the
 * trust model undecided ("treat a signature as tamper-evidence, not identity"), so this layer
 * guarantees integrity today and gains authenticity when signing lands.
 */
object AzpPackage {

    /** Unknown keys are ignored so a newer manifest field doesn't break an older host. */
    private val json = Json { ignoreUnknownKeys = true }

    /** A parsed package: the manifest plus every payload entry (all entries except `manifest.json`). */
    data class Loaded(val manifest: AzpManifest, val payload: Map<String, ByteArray>)

    /** Thrown by [load] when a package is malformed or fails verification. */
    class AzpException(message: String) : Exception(message)

    /** SHA-256 of [bytes] as `sha256-<lowercase-hex>` — the digest form used in `manifest.files`. */
    fun digest(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) } // mask: Byte is signed
        return "sha256-$hex"
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zin.readBytes()
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return out
    }

    /** Open a `.azp` and parse its manifest. Does NOT verify integrity — call [verify] or [load]. */
    fun read(bytes: ByteArray): Loaded {
        val entries = unzip(bytes)
        val manifestRaw = entries["manifest.json"]
            ?: throw AzpException("azp: manifest.json is missing")
        val manifest = try {
            json.decodeFromString(AzpManifest.serializer(), manifestRaw.decodeToString())
        } catch (e: Exception) {
            throw AzpException("azp: invalid manifest.json — ${e.message}")
        }
        val payload = entries.filterKeys { it != "manifest.json" }
        return Loaded(manifest, payload)
    }

    /**
     * Verify a `.azp` and return the list of problems (empty ⇒ valid). Mirrors `@azphalt/azp`:
     * reject unsafe paths, confirm each `manifest.files` digest, and reject unlisted payload.
     */
    fun verify(bytes: ByteArray): List<String> {
        val loaded = try {
            read(bytes)
        } catch (e: Exception) {
            return listOf(e.message ?: "azp: read failed")
        }
        val errors = mutableListOf<String>()
        val (manifest, payload) = loaded

        for (path in payload.keys) {
            if (path.startsWith("/") || path.split("/").contains("..")) {
                errors.add("unsafe path: $path")
            }
        }
        // Integrity: every declared file must be present and match its digest.
        for ((path, want) in manifest.files) {
            val data = payload[path]
            if (data == null) {
                errors.add("missing payload for $path")
                continue
            }
            if (digest(data) != want) errors.add("digest mismatch: $path")
        }
        // Completeness: every payload file must be covered by a digest (no unlisted, unsigned files).
        for (path in payload.keys) {
            if (!manifest.files.containsKey(path)) {
                errors.add("unlisted payload (no digest in manifest.files): $path")
            }
        }
        return errors
    }

    /** True if the package passes [verify]. */
    fun isValid(bytes: ByteArray): Boolean = verify(bytes).isEmpty()

    /** Load + verify; throws [AzpException] listing all problems if the package doesn't verify. */
    fun load(bytes: ByteArray): Loaded {
        val errors = verify(bytes)
        if (errors.isNotEmpty()) throw AzpException("Invalid .azp: ${errors.joinToString("; ")}")
        return read(bytes)
    }
}
