package com.hereliesaz.guillotine.azphalt

import com.hereliesaz.guillotine.ai.AiSettings

/**
 * Plans the on-device installation of AI models delivered as azphalt `.azp` packages, and owns every
 * security-critical decision: the package is integrity-verified and trust-checked ([AzpPackage]), each
 * model asset is routed to the right [AiSettings] model-path slot by its `role`, and a downloaded
 * remote model is checked against its declared SHA-256 before it's ever wired in.
 *
 * This is the platform-agnostic core: it decides **what** to install and **where** it goes. The thin
 * platform layer does the I/O — fetch [PlannedModel.remoteUrl] (or read [bundledBytes]), verify with
 * [checksumMatches], write the file, then apply [ModelSlot.apply] to persist the path — so no HTTP or
 * filesystem lives here, and the whole plan is unit-testable.
 *
 * The on-device invariant is untouched: a model install pulls model *weights* from a source the user
 * chose (ideally a trusted registry), never the user's footage.
 */
object AzpModelInstaller {

    /** Asset `type`s that are AI models (spec/extension-manifest.md § AI Models). */
    val MODEL_TYPES = setOf("onnx", "tflite", "litert", "sherpa-bundle")

    /** An [AiSettings] model-path field a delivered model can be routed to. */
    enum class ModelSlot(val apply: (AiSettings, String) -> AiSettings) {
        IMAGE_LABELING({ s, p -> s.copy(labelModelPath = p) }),
        FACE_DETECTION({ s, p -> s.copy(faceDetectModelPath = p) }),
        FACE_EMBEDDING({ s, p -> s.copy(faceEmbedModelPath = p) }),
        SUBJECT_SEGMENTATION({ s, p -> s.copy(segModelPath = p) }),
        IMAGE_EMBEDDING({ s, p -> s.copy(idEmbedModelPath = p) }),
        SPEECH_TO_TEXT({ s, p -> s.copy(speechModelPath = p) }),
        AUDIO_EVENT({ s, p -> s.copy(audioEventModelPath = p) }),
    }

    /** Map an asset's semantic `role` to the [ModelSlot] it drives (null ⇒ unknown; host may prompt). */
    fun slotForRole(role: String?): ModelSlot? = when (role?.trim()?.lowercase()) {
        "image-labeling", "labeling", "classification", "tagging" -> ModelSlot.IMAGE_LABELING
        "face-detection", "face-detect", "face" -> ModelSlot.FACE_DETECTION
        "face-embedding", "face-id", "face-recognition" -> ModelSlot.FACE_EMBEDDING
        "subject-segmentation", "segmentation", "matting", "background-removal" -> ModelSlot.SUBJECT_SEGMENTATION
        "image-embedding", "embedding", "concept" -> ModelSlot.IMAGE_EMBEDDING
        "speech-to-text", "asr", "transcription", "stt" -> ModelSlot.SPEECH_TO_TEXT
        "audio-event", "highlight-detection", "highlight", "audio-tagging" -> ModelSlot.AUDIO_EVENT
        else -> null
    }

    /** One model asset from a `.azp`, resolved to where and how it installs. */
    data class PlannedModel(
        /** The package id (for provenance / display). */
        val packageId: String,
        /** Model format: `onnx` | `tflite` | `litert` | `sherpa-bundle`. */
        val type: String,
        /** Declared role, verbatim. */
        val role: String?,
        /** The settings slot this model drives, or null if the role is unrecognized. */
        val slot: ModelSlot?,
        /** Suggested on-disk filename. */
        val filename: String,
        /** True ⇒ bytes are bundled in the `.azp` at [assetPath]; false ⇒ fetch [remoteUrl]. */
        val bundled: Boolean,
        /** Payload key for the bundled bytes (empty when remote). */
        val assetPath: String,
        val remoteUrl: String?,
        val checksum: String?,
        val byteSize: Long?,
    )

    /** The result of planning an install: trust status + the model assets to install. */
    data class InstallPlan(
        val trust: AzpPackage.TrustResult,
        val loaded: AzpPackage.Loaded,
        val models: List<PlannedModel>,
    )

    /**
     * Integrity-verify + trust-check the package, then resolve its model assets into an [InstallPlan].
     * Throws [AzpPackage.AzpException] if integrity fails or a present signature is invalid — those must
     * block. An unsigned or untrusted-but-valid package still plans (`trust.trusted == false`): the host
     * should surface that and let the user decide, per the spec.
     */
    fun plan(azpBytes: ByteArray, trustedKeys: Set<String>): InstallPlan {
        val trust = AzpPackage.verifyTrust(azpBytes, trustedKeys)
        if (!trust.ok) throw AzpPackage.AzpException("cannot install: ${trust.reason}")
        val loaded = AzpPackage.load(azpBytes) // integrity already confirmed by verifyTrust
        val models = loaded.manifest.assets
            .filter { it.type.trim().lowercase() in MODEL_TYPES }
            .map { asset ->
                val bundled = asset.path.isNotBlank()
                PlannedModel(
                    packageId = loaded.manifest.id,
                    type = asset.type,
                    role = asset.role,
                    slot = slotForRole(asset.role),
                    filename = filenameFor(loaded.manifest.id, asset),
                    bundled = bundled,
                    assetPath = if (bundled) asset.path else "",
                    remoteUrl = asset.remoteUrl,
                    checksum = asset.checksum,
                    byteSize = asset.byteSize,
                )
            }
        return InstallPlan(trust, loaded, models)
    }

    /** The bundled bytes for a bundled [model] from its [plan]'s payload, or null if remote/missing. */
    fun bundledBytes(plan: InstallPlan, model: PlannedModel): ByteArray? =
        if (model.bundled) plan.loaded.payload[model.assetPath] else null

    /**
     * Verify a downloaded remote model file against its declared [checksum]. Accepts `sha256-<hex>` or
     * bare hex (case-insensitive). Returns false when the checksum is absent — a remote model without an
     * integrity value MUST NOT be trusted.
     */
    fun checksumMatches(bytes: ByteArray, checksum: String?): Boolean {
        if (checksum.isNullOrBlank()) return false
        val want = checksum.trim().removePrefix("sha256-").lowercase()
        val got = AzpPackage.digest(bytes).removePrefix("sha256-")
        return want == got
    }

    private fun filenameFor(id: String, asset: AzpAsset): String {
        asset.path.substringAfterLast('/').takeIf { it.isNotBlank() }?.let { return it }
        asset.remoteUrl?.substringBefore('?')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { return it }
        return "$id.${extensionFor(asset.type)}"
    }

    private fun extensionFor(type: String): String = when (type.trim().lowercase()) {
        "onnx" -> "onnx"
        "tflite", "litert" -> "tflite"
        "sherpa-bundle" -> "zip"
        else -> "bin"
    }
}
