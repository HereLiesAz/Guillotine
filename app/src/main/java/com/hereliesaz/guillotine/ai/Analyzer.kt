package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
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
 * with no key and no network: ML Kit vision for video/images, the Local silence heuristic for
 * audio (and for the explicit on-device "Local" choice).
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
        // Audio has no frames to look at — silence detection is the on-device answer.
        kind == MediaKind.AUDIO ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
        // Explicit on-device "Local": audio-based silence cut (kept whole for images).
        settings.provider == AiProviderType.LOCAL ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
        // A silence/quiet request on a video is an audio task — ML Kit is vision-only and can't
        // hear it, so route to the silence heuristic (it decodes the clip's audio track).
        isSilenceIntent(prompt) ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
        // Everything else (video/image): free on-device ML Kit face/label vision.
        else ->
            MlKitProvider().analyze(context, mediaUri, kind, prompt, durationMs, onProgress, checkpoint)
    }

    /** Heuristic: does the prompt ask about audio (silence/quiet/pauses) rather than what's on screen? */
    private fun isSilenceIntent(prompt: String): Boolean {
        val p = prompt.lowercase()
        return listOf("silen", "quiet", "pause", "dead air", "dead-air", "mute", "no sound", "no audio")
            .any { it in p }
    }
}
