@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A live 16-bit-PCM gain + stereo-pan processor for the preview ExoPlayers. Unlike `ExoPlayer.volume`
 * (capped at 1.0, no pan), this can **boost** (gain > 1, needed for peak-normalize) and apply a
 * left/right balance. [gain] and [pan] are read per buffer so the UI can update them as the active
 * clip changes. Pan applies to stereo content; a mono clip can't be panned in preview (no second
 * channel) — that's an export-only nicety.
 */
class LiveAudioProcessor : BaseAudioProcessor() {

    @Volatile var gain: Float = 1f
    @Volatile var pan: Float = 0f

    private var channelCount = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val limit = inputBuffer.limit()
        val bytes = limit - inputBuffer.position()
        if (bytes <= 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = replaceOutputBuffer(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val g = gain
        val p = pan
        val leftFactor = (if (p <= 0f) 1f else 1f - p) * g
        val rightFactor = (if (p >= 0f) 1f else 1f + p) * g
        val stereo = channelCount >= 2
        // Frame-based loop so a trailing partial frame can't underflow getShort().
        val bytesPerFrame = 2 * channelCount.coerceAtLeast(1)
        val frames = bytes / bytesPerFrame
        for (f in 0 until frames) {
            for (c in 0 until channelCount) {
                val factor = if (stereo) (if (c == 0) leftFactor else rightFactor) else g
                val scaled = (inputBuffer.short * factor).toInt().coerceIn(-32768, 32767)
                out.putShort(scaled.toShort())
            }
        }
        inputBuffer.position(limit)
        out.flip()
    }
}

/** An ExoPlayer renderers factory that routes audio through [processor] (16-bit, no float output). */
fun previewRenderersFactory(context: Context, processor: AudioProcessor): RenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(processor))
            .build()
    }
