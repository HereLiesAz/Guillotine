package com.hereliesaz.guillotine.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import com.hereliesaz.guillotine.model.MediaKind
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** One sampled video frame: its timeline-relative time and JPEG bytes as base64. */
data class SampledFrame(val timeMs: Long, val jpegBase64: String)

/**
 * Extracts evenly-spaced frames from video (or the single image for an image
 * clip) so vision models that can't ingest video natively (OpenAI/Anthropic) can
 * still analyze it. Audio yields no frames.
 */
object FrameSampler {

    private const val MAX_DIM = 768
    private const val DEFAULT_MAX_FRAMES = 8

    fun sample(
        context: Context,
        uri: Uri,
        kind: MediaKind,
        durationMs: Long,
        maxFrames: Int = DEFAULT_MAX_FRAMES,
    ): List<SampledFrame> = when (kind) {
        MediaKind.IMAGE -> listOfNotNull(sampleImage(context, uri))
        MediaKind.VIDEO -> sampleVideo(context, uri, durationMs, maxFrames)
        MediaKind.AUDIO -> emptyList()
    }

    private fun sampleVideo(context: Context, uri: Uri, durationMs: Long, maxFrames: Int): List<SampledFrame> {
        val retriever = MediaMetadataRetriever()
        val out = mutableListOf<SampledFrame>()
        try {
            retriever.setDataSource(context, uri)
            val dur = if (durationMs > 0) durationMs
            else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // ~1 frame per 2s, clamped to [1, maxFrames].
            val count = if (dur <= 0) 1 else min(maxFrames, max(1, (dur / 2000L).toInt()))
            for (i in 0 until count) {
                val t = if (count == 1) dur / 2 else dur * i / (count - 1)
                val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                out += SampledFrame(t, encode(bmp))
            }
        } catch (_: Exception) {
            // best effort
        } finally {
            runCatching { retriever.release() }
        }
        return out
    }

    private fun sampleImage(context: Context, uri: Uri): SampledFrame? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)?.let { SampledFrame(0, encode(it)) }
        }
    } catch (_: Exception) {
        null
    }

    private fun encode(src: Bitmap): String {
        val scaled = scaleDown(src)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val maxSide = max(b.width, b.height)
        if (maxSide <= MAX_DIM) return b
        val r = MAX_DIM.toFloat() / maxSide
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt(), (b.height * r).toInt(), true)
    }
}
