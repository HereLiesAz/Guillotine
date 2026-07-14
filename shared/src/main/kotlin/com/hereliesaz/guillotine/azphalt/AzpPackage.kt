package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Loads and verifies an azphalt `.azp` package — conformance **job #1** ("load and verify") from
 * azphalt `docs/ADOPTION.md`, done on-device in pure JVM (no WASM runtime, no host wiring yet).
 *
 * A `.azp` is a ZIP holding `manifest.json`, a `LICENSE`, an `assets/` and/or `code/` payload, and —
 * when signed — a detached `signature.json`. The verification mirrors `@azphalt/azp` so a package
 * built by the reference tools loads here unchanged:
 *
 * - [verify] — **integrity**: reject unsafe paths, confirm every `manifest.files` SHA-256 digest,
 *   and reject any payload file that isn't digested (unlisted ⇒ unverifiable). `signature.json` is
 *   exempt, since it's the detached signature, not a signed payload file. A host MUST reject any
 *   integrity failure.
 * - [signatureStatus] — **authenticity**: if a `signature.json` is present, verify its Ed25519
 *   signature over the canonical `manifest.json` bytes.
 * - [verifyTrust] — **identity**: whether the signer is trusted against a host trust store, directly
 *   or via a registry counter-signature chain (web of trust).
 *
 * Signing is optional; an unsigned but integrity-valid package has integrity, not established
 * provenance (spec/package-format.md § Signing) — surface that to the user rather than blocking.
 */
object AzpPackage {

    /** Unknown keys are ignored so a newer manifest field doesn't break an older host. */
    private val json = Json { ignoreUnknownKeys = true }

    /** Ceiling on counter-signature chain length — a DoS guard against attacker-crafted deep chains. */
    private const val MAX_CHAIN_DEPTH = 10

    /** A parsed package: the manifest plus every payload entry (all entries except `manifest.json`). */
    data class Loaded(val manifest: AzpManifest, val payload: Map<String, ByteArray>)

    /** Thrown by [load] when a package is malformed or fails integrity verification. */
    class AzpException(message: String) : Exception(message)

    /** Result of checking a package's `signature.json` (authenticity, not identity). */
    data class SignatureStatus(
        /** The package carries a `signature.json`. */
        val signed: Boolean,
        /** The signature is present and cryptographically valid over `manifest.json`. */
        val valid: Boolean,
        /** The signer's base64 SPKI public key, when signed. */
        val signerPublicKey: String? = null,
        /** Why the signature is not valid (malformed, failed, or unverifiable on this platform). */
        val error: String? = null,
    )

    /** Result of checking a package against a trust store (identity). Mirrors `@azphalt/azp` `TrustResult`. */
    data class TrustResult(
        /** Integrity holds and, if signed, the signature is cryptographically valid. */
        val ok: Boolean,
        /** The package carries a `signature.json`. */
        val signed: Boolean,
        /** The signer is trusted per the store (directly, or via a trusted registry counter-signature). */
        val trusted: Boolean,
        /** How trust was (or wasn't) established. */
        val reason: String,
        /** The signer's base64 SPKI public key, when signed. */
        val signerPublicKey: String? = null,
        /** When trust came transitively, the registry key that vouched for the signer. */
        val viaRegistryPublicKey: String? = null,
    )

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
                if (!entry.isDirectory) {
                    // Normalize Windows-style separators so a backslash can't smuggle a `..` past the
                    // forward-slash path check (spec mandates forward-slash names anyway).
                    val name = entry.name.replace('\\', '/')
                    // Reject duplicate entries: a second entry silently overwriting the first is a
                    // ZIP-confusion vector (verify one copy, a different parser runs the other).
                    if (out.containsKey(name)) throw AzpException("azp: duplicate entry $name")
                    out[name] = zin.readBytes()
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return out
    }

    private fun parseManifest(raw: ByteArray): AzpManifest = try {
        json.decodeFromString(AzpManifest.serializer(), raw.decodeToString())
    } catch (e: Exception) {
        throw AzpException("azp: invalid manifest.json — ${e.message}")
    }

    /** Open a `.azp` and parse its manifest. Does NOT verify integrity — call [verify] or [load]. */
    fun read(bytes: ByteArray): Loaded {
        val entries = unzip(bytes)
        val raw = entries["manifest.json"] ?: throw AzpException("azp: manifest.json is missing")
        return Loaded(parseManifest(raw), entries.filterKeys { it != "manifest.json" })
    }

    /**
     * Verify a `.azp`'s **integrity** and return the list of problems (empty ⇒ valid). Mirrors
     * `@azphalt/azp`: reject unsafe paths, confirm each `manifest.files` digest, and reject unlisted
     * payload (`signature.json` exempt). Signature/trust are separate — see [signatureStatus] /
     * [verifyTrust].
     */
    fun verify(bytes: ByteArray): List<String> {
        val entries = try {
            unzip(bytes)
        } catch (e: Exception) {
            return listOf(e.message ?: "azp: read failed")
        }
        val raw = entries["manifest.json"] ?: return listOf("azp: manifest.json is missing")
        val manifest = try {
            parseManifest(raw)
        } catch (e: Exception) {
            return listOf(e.message ?: "azp: invalid manifest.json")
        }
        val payload = entries.filterKeys { it != "manifest.json" }
        val errors = mutableListOf<String>()

        for (path in payload.keys) {
            // Reject absolute paths, `..` traversal, and colons (Windows drive letters like `C:evil`
            // and NTFS alternate-data-stream names like `file:stream` bypass a plain `..` check).
            if (path.startsWith("/") || path.split("/").contains("..") || path.contains(":")) {
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
        // Completeness: every payload file must be digested — except `signature.json`, which is the
        // detached signature (added after the manifest is finalized), not a signed payload file.
        for (path in payload.keys) {
            if (path != "signature.json" && !manifest.files.containsKey(path)) {
                errors.add("unlisted payload (no digest in manifest.files): $path")
            }
        }
        return errors
    }

    /** True if the package passes integrity [verify]. */
    fun isValid(bytes: ByteArray): Boolean = verify(bytes).isEmpty()

    /** Load + integrity-verify; throws [AzpException] listing all problems if it doesn't verify. */
    fun load(bytes: ByteArray): Loaded {
        val errors = verify(bytes)
        if (errors.isNotEmpty()) throw AzpException("Invalid .azp: ${errors.joinToString("; ")}")
        return read(bytes)
    }

    private fun parseSignature(bytes: ByteArray): AzpSignature? {
        val raw = unzip(bytes)["signature.json"] ?: return null
        return json.decodeFromString(AzpSignature.serializer(), raw.decodeToString())
    }

    /**
     * Check a package's `signature.json` (authenticity — is the Ed25519 signature over `manifest.json`
     * internally consistent?). Does NOT decide identity/trust — see [verifyTrust].
     */
    fun signatureStatus(bytes: ByteArray): SignatureStatus {
        val entries = try {
            unzip(bytes)
        } catch (e: Exception) {
            return SignatureStatus(signed = false, valid = false, error = e.message)
        }
        val manifestRaw = entries["manifest.json"]
            ?: return SignatureStatus(signed = false, valid = false, error = "azp: manifest.json is missing")
        val sigRaw = entries["signature.json"] ?: return SignatureStatus(signed = false, valid = false)

        val sig = try {
            json.decodeFromString(AzpSignature.serializer(), sigRaw.decodeToString())
        } catch (e: Exception) {
            return SignatureStatus(signed = true, valid = false, error = "signature.json is malformed")
        }
        if (sig.alg != "ed25519" || sig.publicKey.isBlank() || sig.signature.isBlank()) {
            return SignatureStatus(signed = true, valid = false, signerPublicKey = sig.publicKey, error = "signature.json is malformed")
        }
        if (!AzpCrypto.ed25519Available) {
            return SignatureStatus(signed = true, valid = false, signerPublicKey = sig.publicKey, error = "Ed25519 verification is unavailable on this platform")
        }
        val spki = try {
            Base64.getDecoder().decode(sig.publicKey)
        } catch (e: Exception) {
            return SignatureStatus(signed = true, valid = false, signerPublicKey = sig.publicKey, error = "signature public key is not valid base64")
        }
        val signature = try {
            Base64.getDecoder().decode(sig.signature)
        } catch (e: Exception) {
            return SignatureStatus(signed = true, valid = false, signerPublicKey = sig.publicKey, error = "signature is not valid base64")
        }
        val valid = AzpCrypto.verifyEd25519(spki, manifestRaw, signature)
        return SignatureStatus(
            signed = true,
            valid = valid,
            signerPublicKey = sig.publicKey,
            error = if (valid) null else "signature verification failed",
        )
    }

    /**
     * Verify a `.azp` against a set of trusted base64 SPKI public keys ([trustedKeys]): integrity,
     * then whether the signer's key is trusted — directly, or through a registry counter-signature
     * chain by a key in the store. Mirrors `@azphalt/azp` `verifyTrust`.
     */
    fun verifyTrust(bytes: ByteArray, trustedKeys: Set<String>): TrustResult {
        val integrity = verify(bytes)
        if (integrity.isNotEmpty()) {
            return TrustResult(ok = false, signed = false, trusted = false, reason = "invalid package: ${integrity.joinToString("; ")}")
        }
        val status = signatureStatus(bytes)
        if (!status.signed) {
            return TrustResult(ok = true, signed = false, trusted = false, reason = "unsigned: no signer to trust")
        }
        if (!AzpCrypto.ed25519Available) {
            return TrustResult(ok = true, signed = true, trusted = false, reason = "signature present but Ed25519 is unavailable on this platform — provenance unverified", signerPublicKey = status.signerPublicKey)
        }
        if (!status.valid) {
            return TrustResult(ok = false, signed = true, trusted = false, reason = status.error ?: "signature verification failed", signerPublicKey = status.signerPublicKey)
        }
        val sig = try {
            parseSignature(bytes)
        } catch (e: Exception) {
            return TrustResult(ok = true, signed = true, trusted = false, reason = "signature unreadable: ${e.message}", signerPublicKey = status.signerPublicKey)
        } ?: return TrustResult(ok = true, signed = false, trusted = false, reason = "unsigned: no signer to trust")

        val signer = sig.publicKey
        // (a) Direct trust: the signer key is in the store.
        if (signer.isNotBlank() && signer in trustedKeys) {
            return TrustResult(ok = true, signed = true, trusted = true, reason = "signer key is directly trusted", signerPublicKey = signer)
        }
        // (b) Transitive trust: walk the counter-signature chain from the author up. Each link's key
        // vouches (signs) for the key below it; trusted as soon as a link's key is in the store,
        // provided every hop's signature down to it verifies (a broken lower link severs the chain).
        var vouchedKey = signer
        var cs = sig.countersignature
        var hop = 0
        while (cs != null) {
            hop++
            if (hop > MAX_CHAIN_DEPTH) {
                return TrustResult(ok = true, signed = true, trusted = false, reason = "counter-signature chain exceeds the $MAX_CHAIN_DEPTH-hop limit", signerPublicKey = signer)
            }
            if (cs.publicKey.isBlank() || cs.signature.isBlank()) {
                return TrustResult(ok = true, signed = true, trusted = false, reason = "counter-signature is malformed at hop $hop", signerPublicKey = signer)
            }
            val valid = try {
                AzpCrypto.verifyEd25519(
                    Base64.getDecoder().decode(cs.publicKey),
                    Base64.getDecoder().decode(vouchedKey),
                    Base64.getDecoder().decode(cs.signature),
                )
            } catch (e: Exception) {
                return TrustResult(ok = true, signed = true, trusted = false, reason = "counter-signature error at hop $hop: ${e.message}", signerPublicKey = signer)
            }
            if (!valid) {
                return TrustResult(ok = true, signed = true, trusted = false, reason = "counter-signature invalid at hop $hop", signerPublicKey = signer)
            }
            if (cs.publicKey in trustedKeys) {
                return TrustResult(
                    ok = true, signed = true, trusted = true,
                    reason = if (hop == 1) "signer counter-signed by a trusted registry" else "signer trusted via a $hop-hop counter-signature chain",
                    signerPublicKey = signer, viaRegistryPublicKey = cs.publicKey,
                )
            }
            vouchedKey = cs.publicKey
            cs = cs.countersignature
        }
        return TrustResult(
            ok = true, signed = true, trusted = false,
            reason = if (sig.countersignature != null) "counter-signature chain reaches no trusted key" else "signer key is not in the trust store",
            signerPublicKey = signer,
        )
    }
}
