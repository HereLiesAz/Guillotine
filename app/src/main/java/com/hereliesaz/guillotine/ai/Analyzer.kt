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
    ): List<EditSegment> = when {
        // Audio has no frames to look at — silence detection is the on-device answer.
        kind == MediaKind.AUDIO ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress)
        // Explicit on-device "Local": audio-based silence cut (kept whole for images).
        settings.provider == AiProviderType.LOCAL ->
            LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs, onProgress)
        // Everything else (video/image): free on-device ML Kit face/label vision.
        else ->
            MlKitProvider().analyze(context, mediaUri, kind, prompt, durationMs, onProgress)
    }
}
