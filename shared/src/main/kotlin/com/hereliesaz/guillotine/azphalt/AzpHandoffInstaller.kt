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
        /**
         * The `.azp` verified and was written. [signed]/[signatureValid] surface provenance for the UI,
         * and [surfaces] says where in Guillotine the package actually turns up, so the caller can tell
         * the user rather than guessing (see [AzpInstallSurfaces]).
         */
        data class Success(
            val id: String,
            val name: String,
            val signed: Boolean,
            val signatureValid: Boolean,
            // No default, for the same reason hostAppId has none: an empty list is indistinguishable
            // from "nothing was derived", and would render a notice dialog with no destination in it —
            // precisely the failure this field exists to prevent.
            val surfaces: List<AzpInstallSurfaces.Surface>,
        ) : InstallResult()
        data class Failure(val message: String) : InstallResult()
        /** Integrity-sound, but not from a trusted signer (or unsigned) and not yet approved by the user. */
        data class Untrusted(val reason: String) : InstallResult()
        /** [packageId] was previously installed from a different publisher key than this update carries. */
        data class PublisherChanged(val packageId: String, val pinnedKey: String, val newSignerKey: String?) : InstallResult()

        /**
         * The package declares a non-empty `targetApps` that doesn't include this host — it was built for
         * a different editor. Refused outright: azphalt `spec/web-handoff.md` § Host obligations (5) makes
         * this a MUST, and there is deliberately no "install anyway" override.
         *
         * Note this is a real behaviour change, not just a tightening of something already inert. Asset
         * packages were already invisible when scoped elsewhere ([AzpInstalledUi.list] filters on the same
         * field), but **motion packages were not**: [com.hereliesaz.guillotine.ui.KineticTypographyPicker]
         * enumerates every `.azp` on disk with no host filter, so a wrong-host caption animation used to
         * install and work. It no longer installs. That's what the spec asks for — a package that names
         * other hosts is not offering itself to this one — but it does take away something that worked.
         */
        data class WrongHost(val packageId: String, val name: String, val targetApps: List<String>) : InstallResult()

        /**
         * The package needs a host azphalt-API version this build doesn't provide (`spec/web-handoff.md`
         * § Host obligations (5), `spec/extension-manifest.md` § `compat`). Refused rather than installed
         * and left to fail obscurely later. Only a comparator that parsed *and* went unsatisfied lands
         * here — an unparseable one is not evidence of anything (see [AzpCompat.satisfies]).
         */
        data class Incompatible(
            val packageId: String,
            val name: String,
            val required: String,
            val hostVersion: String,
        ) : InstallResult()
    }

    /**
     * Verify [bytes] and, if they check out, write them into [extensionsDirPath] — the directory the
     * editor reads installed extensions from. The package id/name/version come from the manifest
     * inside [bytes], never from a caller-supplied label — trust decisions are keyed to what's actually
     * in the package. Blocks on disk IO — call off the main thread.
     *
     * [hostAppId] is this host's own id, checked against the package's `targetApps` (see
     * [InstallResult.WrongHost]). It has **no default**: the check it drives is a spec MUST, and a
     * defaulted parameter is a check a future caller skips by saying nothing. Passing blank still means
     * "no host identity, don't scope", but that has to be chosen out loud.
     */
    fun install(
        bytes: ByteArray,
        extensionsDirPath: String,
        trustedKeys: Set<String> = emptySet(),
        pins: AzpPublisherPins? = null,
        allowUntrusted: Boolean = false,
        allowPublisherChange: Boolean = false,
        hostAppId: String,
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
        // Host scoping, before any trust prompt: a package scoped to other hosts is refused outright, so
        // the user is never asked to vouch for a publisher on something that was never going to run here.
        // This mattered less when the store app was the only route — it filters on the `app` browse extra
        // — but a deep link names a package with nothing in between, so the host has to check for itself.
        if (hostAppId.isNotBlank() && !manifest.targetsApp(hostAppId)) {
            return InstallResult.WrongHost(manifest.id, manifest.name, manifest.targetApps)
        }
        // The other half of obligation 5: a package that needs a newer host API than this build provides
        // cannot work, so refusing now beats installing it and letting it fail as something inscrutable.
        if (AzpCompat.satisfies(manifest.compat) == false) {
            return InstallResult.Incompatible(
                manifest.id, manifest.name, manifest.compat.trim(), AzpCompat.HOST_API_VERSION,
            )
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
        return InstallResult.Success(
            manifest.id,
            manifest.name,
            signed = trust.signed,
            signatureValid = signatureValid,
            surfaces = AzpInstallSurfaces.of(manifest),
        )
    }
}
