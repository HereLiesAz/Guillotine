package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.ai.agent.PromptCoach
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.MediaKind

/** A source of keep/remove suggestions for one clip's media. */
interface ClipAnalyzer {
    suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
        onProgress: (AnalysisProgress) -> Unit = {},
        /** Called periodically during the scan; may block to pause or throw to cancel the operation. */
        checkpoint: () -> Unit = {},
    ): List<EditSegment>
}

/**
 * Runs an analysis request **entirely on-device** — the video never leaves the device.
 *
 * This is deliberate: cloud AIs (Gemini/OpenAI/Anthropic/…) are *controllers* that drive the
 * editor through the MCP server and only ever exchange text; they never receive clips or frames.
 * So no matter which AI the user selects, the actual keep/remove analysis happens here, locally,
 * with no key and no network.
 *
 * Routing:
 *  1. Silence/quiet prompts → [LocalHeuristicProvider] (RMS audio level detection)
 *  2. Speech-content prompts ("says", "mentions", "talking about") → [SpeechContentAnalyzer]
 *     (transcribes audio via Vosk/Whisper, matches transcript against search terms)
 *  3. Audio clips without a specific intent → [LocalHeuristicProvider]
 *  4. Explicit "Local" provider → [LocalHeuristicProvider]
 *  5. Everything else (video/image) → [MlKitProvider] (on-device vision)
 */
object Analysis {
    suspend fun run(
        context: Context,
        settings: AiSettings,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
        onProgress: (AnalysisProgress) -> Unit = {},
        checkpoint: () -> Unit = {},
    ): List<EditSegment> = when {
        // Silence/quiet and explicit "cut the boring parts" pacing requests route to the cheap audio
        // heuristic. "Boring" is not a visual label; sending it to frame classification is both slow
        // and meaningless, while dead-air removal is the concrete behavior Prompt Coach advertises.
        isSilenceIntent(prompt) ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
        // Speech-content request — transcribe audio and match transcript against the prompt.
        SpeechContentAnalyzer.isSpeechContentIntent(prompt) ->
            SpeechContentAnalyzer(settings).analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
        // Audio with no specific intent — silence detection is the default.
        kind == MediaKind.AUDIO ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
        // Explicit on-device "Local": audio-based silence cut (kept whole for images).
        settings.provider == AiProviderType.LOCAL ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
        // Everything else (video/image): free on-device ML Kit face/label vision.
        else ->
            MlKitProvider().analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
    }

    /** Heuristic: does the prompt ask for silence/dead-air or the explicit boring-parts pacing edit? */
    private fun isSilenceIntent(prompt: String): Boolean {
        if (PromptCoach.isBoringCutRequest(prompt)) return true
        val p = prompt.lowercase()
        return listOf("silen", "quiet", "pause", "dead air", "dead-air", "mute", "no sound", "no audio")
            .any { it in p }
    }
}
