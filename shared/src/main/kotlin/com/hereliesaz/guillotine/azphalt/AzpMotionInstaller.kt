package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Plans the on-device installation of kinetic typography / motion presets delivered as azphalt `.azp`
 * packages. The package is integrity-verified and trust-checked ([AzpPackage]), and motion assets
 * are resolved so the host UI can present them as available kinetic typography presets.
 */
object AzpMotionInstaller {

    val MOTION_TYPES = setOf("motion")

    /** One motion preset from a `.azp`, resolved to where and how it installs. */
    data class PlannedMotion(
        /** The package id (for provenance / display). */
        val packageId: String,
        /** Asset type, should be `motion`. */
        val type: String,
        /** True ⇒ bytes are bundled in the `.azp` at [assetPath]; false ⇒ fetch [remoteUrl]. */
        val bundled: Boolean,
        /** Payload key for the bundled bytes (empty when remote). */
        val assetPath: String,
        val remoteUrl: String?,
        val checksum: String?,
        val byteSize: Long?,
        val format: String,
        val stagger: Float,
        val staggerMode: String,
    )

    /** The result of planning an install: trust status + the motion assets to install. */
    data class InstallPlan(
        val trust: AzpPackage.TrustResult,
        val loaded: AzpPackage.Loaded,
        val motions: List<PlannedMotion>,
    )

    /**
     * Integrity-verify + trust-check the package, then resolve its motion assets into an [InstallPlan].
     * Throws [AzpPackage.AzpException] if integrity fails or a present signature is invalid.
     */
    fun plan(azpBytes: ByteArray, trustedKeys: Set<String>): InstallPlan {
        val trust = AzpPackage.verifyTrust(azpBytes, trustedKeys)
        if (!trust.ok) throw AzpPackage.AzpException("cannot install: ${trust.reason}")
        
        val loaded = AzpPackage.read(azpBytes)
        val motions = loaded.manifest.assets
            .filter { it.type.trim().lowercase() in MOTION_TYPES }
            .map { asset ->
                val bundled = asset.path.isNotBlank()

                // Decode params as JSON rather than slicing params.toString() — the old string-matching
                // broke on ordinary whitespace (e.g. `"stagger": 0.5`, which yielded 0), silently
                // disabling the stagger effect and defaulting format/staggerMode incorrectly.
                val params = (asset.params as? JsonObject)
                val format = (params?.get("format") as? JsonPrimitive)?.contentOrNull ?: "az-motion"
                val staggerMode = (params?.get("staggerMode") as? JsonPrimitive)?.contentOrNull ?: "character"
                val stagger = (params?.get("stagger") as? JsonPrimitive)?.floatOrNull ?: 0f

                PlannedMotion(
                    packageId = loaded.manifest.id,
                    type = asset.type,
                    bundled = bundled,
                    assetPath = if (bundled) asset.path else "",
                    remoteUrl = asset.remoteUrl,
                    checksum = asset.checksum,
                    byteSize = asset.byteSize,
                    format = format,
                    stagger = stagger,
                    staggerMode = staggerMode,
                )
            }
        return InstallPlan(trust, loaded, motions)
    }

    /** The bundled bytes for a bundled [motion] from its [plan]'s payload, or null if remote/missing. */
    fun bundledBytes(plan: InstallPlan, motion: PlannedMotion): ByteArray? =
        if (motion.bundled) plan.loaded.payload[motion.assetPath] else null
}
