package com.hereliesaz.guillotine.platform

import android.content.Context
import com.hereliesaz.guillotine.ai.AiSettings
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
 *
 * A second bug lived here after that fix: [settings] — the path the user actually picked via a model
 * picker's "✓ Use", or typed into its model-path field — was never consulted. Every call site fetched
 * [AiSettings] and then ignored it, so with more than one model of a category installed (e.g. both
 * Gemma 3n E2B and E4B), whichever happened to come first in the catalog always won, no matter what the
 * user selected — and a hand-typed custom path was silently inert. [settings] is now checked first;
 * disk auto-detection is only the fallback for a blank/deleted-out-from-under-it field.
 */
object ModelResolver {

    fun resolve(context: Context, settings: AiSettings, property: String): String {
        val chosen = pathFromSettings(settings, property)
        if (!chosen.isNullOrBlank() && File(chosen).exists()) return chosen

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

    /** The explicit path the user chose for [property], straight off [AiSettings] — no existence check. */
    private fun pathFromSettings(settings: AiSettings, property: String): String? = when (property) {
        "agentModelPath" -> settings.agentModelPath
        "idEmbedModelPath" -> settings.idEmbedModelPath
        "faceEmbedModelPath" -> settings.faceEmbedModelPath
        "audioEventModelPath" -> settings.audioEventModelPath
        "asrModelPath" -> settings.asrModelPath
        "ttsModelPath" -> settings.ttsModelPath
        "vlmModelPath" -> settings.vlmModelPath
        "diarizeSegModelPath" -> settings.diarizeSegModelPath
        "diarizeEmbedModelPath" -> settings.diarizeEmbedModelPath
        "stemModelPath" -> settings.stemModelPath
        "denoiseModelPath" -> settings.denoiseModelPath
        "effect_depth" -> settings.effectModelPaths["depth"]
        "effect_superres" -> settings.effectModelPaths["superres"]
        "effect_lowlight" -> settings.effectModelPaths["lowlight"]
        "effect_style" -> settings.effectModelPaths["style"]
        else -> null
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
