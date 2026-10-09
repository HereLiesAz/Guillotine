package com.hereliesaz.guillotine.ai.gen

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.newId

/**
 * Runs a generation end-to-end and returns a ready-to-import [MediaItem]. Resolves the provider
 * (an explicit pick, else the user's default for the category), builds the [GenRequest], drives the
 * [AsyncJobPoller], saves the result locally via [AndroidGenSink], then probes the media so the clip
 * gets a real duration and `hasAudio`. Generated media therefore flows through the exact same
 * [com.hereliesaz.guillotine.editor.EditorViewModel.addMedia] path as user imports.
 */
object GenController {

    suspend fun generate(
        context: Context,
        settings: AiSettings,
        kind: GenKind,
        prompt: String,
        providerOverride: GenProviderType? = null,
        modelOverride: String? = null,
        durationSec: Int = 8,
        widthPx: Int = 1280,
        heightPx: Int = 720,
        extra: Map<String, String> = emptyMap(),
        onProgress: (Float?) -> Unit = {},
        checkpoint: suspend () -> Unit = {},
    ): MediaItem {
        require(prompt.isNotBlank()) { "Enter a prompt to generate." }
        val provider = providerOverride?.takeIf { settings.genProviderAvailable(it) }
            ?: settings.defaultGenProvider(kind)
            ?: throw GenException(
                "No ${kind.name.lowercase()} generator is set up. Add a key for one in Settings → Generation.",
            )

        val model = modelOverride?.takeIf { it.isNotBlank() } ?: settings.genModelFor(provider)
        val req = GenRequest(
            kind = kind,
            provider = provider,
            apiKey = settings.genKeyFor(provider),
            model = model,
            prompt = prompt.trim(),
            widthPx = widthPx,
            heightPx = heightPx,
            durationSec = durationSec.coerceAtLeast(1),
            extra = extra + mapOf("base_url" to settings.genExtraFor(provider)),
        )

        val sink = AndroidGenSink(context)
        val job = GenBackends.jobFor(req, sink)
        val cfg = PollConfig(
            maxAttempts = 300,
            intervalMs = 2_000,
            timeoutMessage = "${provider.meta.label} timed out generating.",
        )
        val result = AsyncJobPoller.run(job, cfg, onProgress, checkpoint)

        // Result is either an already-local uri (byte-returning backends) or a remote url to download.
        val localUri = if (result.startsWith("file://") || result.startsWith("content://")) result
        else sink.saveUrl(result, GenBackends.extFor(kind))

        val mediaKind = when (kind) {
            GenKind.VIDEO -> MediaKind.VIDEO
            GenKind.MUSIC -> MediaKind.AUDIO
        }
        // ContentSafety layer 3: nothing reaches the project unchecked; a flagged result is deleted.
        com.hereliesaz.guillotine.ai.safety.AndroidContentSafety.requireSafe(context, localUri, mediaKind)
        val (durationMs, hasAudio) = probe(context, localUri, kind, durationSec)
        val label = "${provider.meta.label}: ${prompt.trim().take(24)}"
        val provenance = com.hereliesaz.guillotine.model.AiProvenance(
            provider.meta.label, model, prompt.trim(), System.currentTimeMillis(),
        )
        return MediaItem(newId(), localUri, label, mediaKind, durationMs, hasAudio, aiProvenance = provenance)
    }

    /** Probe a generated video/audio for real length + audio track. */
    private fun probe(context: Context, uri: String, kind: GenKind, durationSec: Int): Pair<Long, Boolean> {
        val fallback = (durationSec.coerceAtLeast(1)) * 1000L
        return runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(uri))
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                (dur?.takeIf { d -> d > 0 } ?: fallback) to (kind == GenKind.VIDEO && hasAudio)
            } finally {
                r.release()
            }
        }.getOrDefault(fallback to false)
    }
}
