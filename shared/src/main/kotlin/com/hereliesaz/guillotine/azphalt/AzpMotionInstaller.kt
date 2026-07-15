package com.hereliesaz.guillotine.azphalt

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
                
                // Extract params
                val paramsString = asset.params?.toString() ?: "{}"
                val format = if (paramsString.contains("\"format\":\"")) {
                    paramsString.substringAfter("\"format\":\"").substringBefore("\"")
                } else "az-motion"
                
                val staggerMode = if (paramsString.contains("\"staggerMode\":\"")) {
                    paramsString.substringAfter("\"staggerMode\":\"").substringBefore("\"")
                } else "character"
                
                val stagger = if (paramsString.contains("\"stagger\":")) {
                    paramsString.substringAfter("\"stagger\":").substringBefore(",").substringBefore("}").toFloatOrNull() ?: 0f
                } else 0f
                
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
