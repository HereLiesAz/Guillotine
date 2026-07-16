package com.hereliesaz.guillotine.platform

import android.content.Context
import com.hereliesaz.guillotine.ai.agent.ModelCategory
import com.hereliesaz.guillotine.ai.agent.ModelDownloadManager
import com.hereliesaz.guillotine.ai.agent.recommendedModelsFor
import java.io.File

/**
 * Resolves a logical on-device model slot (e.g. `agentModelPath`, `asrModelPath`, `effect_depth`) to a
 * real, **existing** file/dir path on disk, or `""` when nothing is installed.
 *
 * The old implementation returned a fixed `filesDir/azp-models/<pkg>/assets/...` path with no existence
 * check — but every install mechanism writes elsewhere: one-tap downloads and the bundled starter land
 * in `getExternalFilesDir/<category>-models/` (see [ModelDownloadManager]), and a `.azp` install lands
 * flat in `filesDir/azp-models/`. So resolution never matched an actual file, and because the path was
 * always non-blank, every "no model set → offer to download" guard was dead. This resolves against the
 * real catalog with existence checks so those guards work and installed models actually load.
 */
object ModelResolver {

    fun resolve(context: Context, property: String): String {
        val category = categoryFor(property) ?: return ""
        val azpDir = File(context.filesDir, "azp-models")
        for (model in recommendedModelsFor(category)) {
            // Downloaded (or bundled-then-extracted) into the category dir — existence + size verified.
            ModelDownloadManager.installedPath(context, model)?.let { return it }
            // Or delivered flat by a `.azp` install, keyed by the model's known filename.
            File(azpDir, model.fileName).takeIf { it.exists() }?.let { return it.absolutePath }
        }
        return ""
    }

    /**
     * Map a logical slot to the [ModelCategory] whose catalog can satisfy it. Slots backed by a bundled
     * MediaPipe/ML Kit asset rather than a downloadable file (`faceDetectModelPath`, `segModelPath`,
     * `labelModelPath`, `speechModelPath`/Vosk) return null → `""`, so the caller falls back to its
     * bundled default instead of a path that was never going to exist.
     */
    private fun categoryFor(property: String): ModelCategory? = when (property) {
        "agentModelPath" -> ModelCategory.ASSISTANT_LLM
        "idEmbedModelPath" -> ModelCategory.RECOGNITION
        "faceEmbedModelPath" -> ModelCategory.FACE
        "audioEventModelPath" -> ModelCategory.AUDIO_EVENT
        "asrModelPath" -> ModelCategory.ASR
        "ttsModelPath" -> ModelCategory.TTS
        "vlmModelPath" -> ModelCategory.VLM
        "diarizeSegModelPath" -> ModelCategory.DIARIZE_SEG
        "diarizeEmbedModelPath" -> ModelCategory.DIARIZE_EMBED
        "stemModelPath" -> ModelCategory.STEM
        "denoiseModelPath" -> ModelCategory.DENOISE
        // Image→image effect models, requested as effect_<name> by apply_image_effect / apply_bokeh.
        "effect_depth" -> ModelCategory.DEPTH
        "effect_superres" -> ModelCategory.SUPERRES
        "effect_lowlight" -> ModelCategory.LOWLIGHT
        "effect_style" -> ModelCategory.STYLE
        else -> null
    }
}
