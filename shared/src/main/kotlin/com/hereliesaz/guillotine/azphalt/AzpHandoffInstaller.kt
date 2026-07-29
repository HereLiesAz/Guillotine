package com.hereliesaz.guillotine.azphalt

import java.io.File

/**
 * Verifies and lands an already-fetched `.azp`'s bytes on disk. Browsing and downloading are the
 * Azphalt Store app's job now, delegated over the acquisition handoff azphalt `spec/store-app.md`
 * specifies — this is the host side of that contract, the part a store app cannot do on Guillotine's
 * behalf. The spec is explicit that a store app's own check is not evidence ("a lying store app gains
 * nothing"), so every check here — integrity, signature, publisher continuity — runs again from
 * scratch on the bytes actually received, exactly as if Guillotine had downloaded them itself.
 *
 * Mirrors [AzpModelInstall]'s trust gate (integrity + signature + trust-on-first-use publisher
 * pinning) — a package id is trusted the same way whether it arrived as an AI model or a general
 * extension handed over by a store app.
 */
object AzpHandoffInstaller {

    sealed class InstallResult {
        /** The `.azp` verified and was written. [signed]/[signatureValid] surface provenance for the UI. */
        data class Success(val id: String, val name: String, val signed: Boolean, val signatureValid: Boolean) : InstallResult()
        data class Failure(val message: String) : InstallResult()
        /** Integrity-sound, but not from a trusted signer (or unsigned) and not yet approved by the user. */
        data class Untrusted(val reason: String) : InstallResult()
        /** [packageId] was previously installed from a different publisher key than this update carries. */
        data class PublisherChanged(val packageId: String, val pinnedKey: String, val newSignerKey: String?) : InstallResult()
    }

    /**
     * Verify [bytes] and, if they check out, write them into [extensionsDirPath] — the directory the
     * editor reads installed extensions from. The package id/name/version come from the manifest
     * inside [bytes], never from a caller-supplied label — trust decisions are keyed to what's actually
     * in the package. Blocks on disk IO — call off the main thread.
     */
    fun install(
        bytes: ByteArray,
        extensionsDirPath: String,
        trustedKeys: Set<String> = emptySet(),
        pins: AzpPublisherPins? = null,
        allowUntrusted: Boolean = false,
        allowPublisherChange: Boolean = false,
    ): InstallResult {
        val trust = AzpPackage.verifyTrust(bytes, trustedKeys)
        if (!trust.ok) {
            return InstallResult.Failure("Package failed verification and was not installed: ${trust.reason}")
        }
        val manifest = try {
            AzpPackage.read(bytes).manifest
        } catch (e: Exception) {
            return InstallResult.Failure("Install failed: ${e.message}")
        }
        // Publisher continuity (trust-on-first-use): if this id was installed before, the signer must
        // match the pinned key, or the caller must have already confirmed the change. Checked before
        // the trust prompt so a hijacked update surfaces as a publisher change, not a generic warning.
        val pinnedKey = pins?.keyFor(manifest.id)
        if (pinnedKey != null && trust.signerPublicKey != pinnedKey && !allowPublisherChange) {
            return InstallResult.PublisherChanged(manifest.id, pinnedKey, trust.signerPublicKey)
        }
        // Only a *signed* package with an unrecognized signer needs a confirmation — an unsigned
        // package has no provenance claim to be suspicious of.
        if (trust.signed && !trust.trusted && !allowUntrusted) {
            return InstallResult.Untrusted(trust.reason)
        }
        val dir = File(extensionsDirPath).apply { mkdirs() }
        // A package id is reverse-DNS, but sanitize anyway so it can never escape the dir, and cap its
        // length so a pathological id can't blow the filesystem's 255-char name limit.
        val sanitized = manifest.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeName = sanitized.take(120) + ".azp"
        File(dir, safeName).writeBytes(bytes)
        // Pin the publisher on first install, or when the caller approved a rotation. Only signed
        // packages pin — an unsigned package leaves no key to enforce against.
        if (trust.signerPublicKey != null && pins != null && (pinnedKey == null || allowPublisherChange)) {
            pins.pin(manifest.id, trust.signerPublicKey)
        }
        // trust.signed only means "carries a signature.json", not "it was cryptographically verified"
        // — verifyTrust's Ed25519-unavailable path returns signed=true with the signature genuinely
        // unverified. signatureStatus().valid is the field that actually means "verified", so re-derive
        // it here rather than reporting trust.signed as if it were that.
        val signatureValid = AzpPackage.signatureStatus(bytes).valid
        return InstallResult.Success(manifest.id, manifest.name, signed = trust.signed, signatureValid = signatureValid)
    }
}
