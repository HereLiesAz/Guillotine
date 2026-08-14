package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    /**
     * Zip-bomb guards for [unzip]: a compressed `.azp` claiming to inflate to gigabytes must not be
     * allowed to actually do it just because someone opened it (a store preview scroll, an install, a
     * `verify()` call — all of them unzip). [MAX_ENTRY_BYTES] caps any single decompressed entry,
     * [MAX_TOTAL_BYTES] caps the sum across the whole archive, and [MAX_ENTRIES] caps entry *count* —
     * a highly-compressible archive of a million empty-ish entries can exhaust memory/CPU on bookkeeping
     * alone even if no single entry is large. All three fail the install/verify cleanly (an
     * [AzpException], never an OOM or a hang), well above any legitimate package: `AzphaltRegistry`
     * itself caps a downloaded `.azp` at 128 MiB, so even a fully-legitimate max-size package inflates
     * to a small multiple of that at most.
     */
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    /**
     * Highest `azphalt` manifest **format** major version this build understands ([AzpManifest.azphalt]
     * — distinct from [AzpCompat.HOST_API_VERSION], which is the *host-API* version a package's `compat`
     * field is checked against). Every package this codebase builds or tests against declares `"0.1"`
     * (major `0`) — see `AzpManifestSpecTest`/`AzpPackageTest`/etc. A manifest declaring a strictly
     * newer major is a package format this build was never taught to parse safely (field meanings and
     * even the envelope shape can change across majors), so it's refused outright rather than parsed and
     * silently mishandled — deliberately simple (major-only, no minor/patch negotiation): the same
     * "small, exact grammar" call [AzpCompat] documents for `compat`, made here for the manifest's own
     * version field, which until now was read and never checked at all.
     */
    private const val SUPPORTED_AZPHALT_MAJOR = 0

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
        /**
         * True when no verdict was reached — this platform could not run the check. Distinct from
         * [valid] being false, which asserts the signature is genuinely bad. Callers MUST NOT treat
         * this as a failed signature: doing so refused every correctly-signed package on a platform
         * whose provider resolves `Ed25519` by name but throws when asked to use it.
         */
        val unverifiable: Boolean = false,
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
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        throw AzpException("azp: package has more than $MAX_ENTRIES entries — refusing to extract")
                    }
                    // Normalize Windows-style separators so a backslash can't smuggle a `..` past the
                    // forward-slash path check (spec mandates forward-slash names anyway).
                    val name = entry.name.replace('\\', '/')
                    // Reject duplicate entries: a second entry silently overwriting the first is a
                    // ZIP-confusion vector (verify one copy, a different parser runs the other).
                    if (out.containsKey(name)) throw AzpException("azp: duplicate entry $name")
                    val data = readEntryBounded(zin, name)
                    totalBytes += data.size
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw AzpException(
                            "azp: package decompresses to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB — refusing to extract",
                        )
                    }
                    out[name] = data
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return out
    }

    /** Reads one zip entry's bytes, refusing to inflate past [MAX_ENTRY_BYTES] — a per-entry zip-bomb guard. */
    private fun readEntryBounded(input: InputStream, name: String): ByteArray {
        val buf = ByteArray(64 * 1024)
        val bos = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) {
                throw AzpException("azp: entry $name exceeds the ${MAX_ENTRY_BYTES / (1024 * 1024)} MB per-entry limit — refusing to extract")
            }
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
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
        return verify(entries)
    }

    /** [verify] over already-unzipped entries — lets callers ([load], [verifyTrust]) unzip only once. */
    private fun verify(entries: Map<String, ByteArray>): List<String> {
        val raw = entries["manifest.json"] ?: return listOf("azp: manifest.json is missing")
        val manifest = try {
            parseManifest(raw)
        } catch (e: Exception) {
            return listOf(e.message ?: "azp: invalid manifest.json")
        }
        val payload = entries.filterKeys { it != "manifest.json" }
        val errors = mutableListOf<String>()

        // The manifest's own format-version field, checked for the first time: a manifest declaring a
        // major version newer than this build understands is refused with a clear message rather than
        // parsed and silently mishandled (or crashing on a field shape this build doesn't expect). An
        // unparseable value is treated the same as "too new" — this build has no basis for assuming a
        // string it can't read as MAJOR[.MINOR[.PATCH]] is one it can safely handle.
        val specMajor = manifest.azphalt.trim().substringBefore('.').toIntOrNull()
        if (specMajor == null || specMajor > SUPPORTED_AZPHALT_MAJOR) {
            errors.add(
                "azp: unsupported package format — manifest declares azphalt \"${manifest.azphalt}\", this " +
                    "build supports major version $SUPPORTED_AZPHALT_MAJOR",
            )
        }

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
        val entries = try {
            unzip(bytes)
        } catch (e: Exception) {
            throw AzpException("Invalid .azp: ${e.message ?: "read failed"}")
        }
        val errors = verify(entries)
        if (errors.isNotEmpty()) throw AzpException("Invalid .azp: ${errors.joinToString("; ")}")
        val raw = entries["manifest.json"] ?: throw AzpException("azp: manifest.json is missing")
        return Loaded(parseManifest(raw), entries.filterKeys { it != "manifest.json" })
    }

    private fun parseSignature(entries: Map<String, ByteArray>): AzpSignature? {
        val raw = entries["signature.json"] ?: return null
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
        return signatureStatus(entries)
    }

    /** [signatureStatus] over already-unzipped entries — reused by [verifyTrust]. */
    private fun signatureStatus(entries: Map<String, ByteArray>): SignatureStatus {
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
            return SignatureStatus(
                signed = true, valid = false, signerPublicKey = sig.publicKey,
                error = "Ed25519 verification is unavailable on this platform", unverifiable = true,
            )
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
        // Three-valued on purpose: UNAVAILABLE is "this device couldn't check", which must never be
        // reported as a failed signature — that reading refused every correctly-signed package outright.
        return when (AzpCrypto.verifyEd25519(spki, manifestRaw, signature)) {
            AzpCrypto.Verification.VALID ->
                SignatureStatus(signed = true, valid = true, signerPublicKey = sig.publicKey)
            AzpCrypto.Verification.INVALID ->
                SignatureStatus(signed = true, valid = false, signerPublicKey = sig.publicKey, error = "signature verification failed")
            AzpCrypto.Verification.UNAVAILABLE ->
                SignatureStatus(
                    signed = true, valid = false, signerPublicKey = sig.publicKey,
                    error = "Ed25519 verification is unavailable on this platform",
                    unverifiable = true,
                )
        }
    }

    /**
     * Verify a `.azp` against a set of trusted base64 SPKI public keys ([trustedKeys]): integrity,
     * then whether the signer's key is trusted — directly, or through a registry counter-signature
     * chain by a key in the store. Mirrors `@azphalt/azp` `verifyTrust`.
     */
    fun verifyTrust(bytes: ByteArray, trustedKeys: Set<String>): TrustResult {
        val entries = try {
            unzip(bytes)
        } catch (e: Exception) {
            return TrustResult(ok = false, signed = false, trusted = false, reason = "invalid package: ${e.message ?: "read failed"}")
        }
        val integrity = verify(entries)
        if (integrity.isNotEmpty()) {
            return TrustResult(ok = false, signed = false, trusted = false, reason = "invalid package: ${integrity.joinToString("; ")}")
        }
        val status = signatureStatus(entries)
        if (!status.signed) {
            return TrustResult(ok = true, signed = false, trusted = false, reason = "unsigned: no signer to trust")
        }
        if (!AzpCrypto.ed25519Available || status.unverifiable) {
            return TrustResult(ok = true, signed = true, trusted = false, reason = "signature present but Ed25519 is unavailable on this platform — provenance unverified", signerPublicKey = status.signerPublicKey)
        }
        if (!status.valid) {
            return TrustResult(ok = false, signed = true, trusted = false, reason = status.error ?: "signature verification failed", signerPublicKey = status.signerPublicKey)
        }
        val sig = try {
            parseSignature(entries)
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
            val hopResult = try {
                AzpCrypto.verifyEd25519(
                    Base64.getDecoder().decode(cs.publicKey),
                    Base64.getDecoder().decode(vouchedKey),
                    Base64.getDecoder().decode(cs.signature),
                )
            } catch (e: Exception) {
                return TrustResult(ok = true, signed = true, trusted = false, reason = "counter-signature error at hop $hop: ${e.message}", signerPublicKey = signer)
            }
            if (hopResult == AzpCrypto.Verification.UNAVAILABLE) {
                // Can't walk the chain here; that costs trust, never validity.
                return TrustResult(ok = true, signed = true, trusted = false, reason = "Ed25519 verification is unavailable on this platform", signerPublicKey = signer)
            }
            if (hopResult != AzpCrypto.Verification.VALID) {
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
