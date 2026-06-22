package com.hereliesaz.guillotine.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a clip's time-varying VOLUME keyframes to 16-bit PCM during export. The gain at each
 * frame is `staticMultiplier × valueAt(VOLUME, t)`, where `staticMultiplier` folds in track volume
 * and peak-normalization (0 when muted) and `t` is the clip-local time. This export item's first
 * sample corresponds to [clipLocalStartMs] from the clip's start (for a kept range starting at
 * source `rangeStart`, that's `rangeStart - clip.trimStartMs`).
 *
 * Only used when the clip actually has VOLUME keyframes; otherwise the static channel-mixing gain
 * path handles it.
 */
@UnstableApi
class KeyframeVolumeProcessor(
    private val clip: TimelineClip,
    private val clipLocalStartMs: Long,
    private val staticMultiplier: Float,
) : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channelCount = 0
    private var framesProcessed = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val limit = inputBuffer.limit()
        val bytes = limit - inputBuffer.position()
        if (bytes <= 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = replaceOutputBuffer(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerFrame = 2 * channelCount.coerceAtLeast(1)
        val frames = bytes / bytesPerFrame
        for (f in 0 until frames) {
            val tMs = clipLocalStartMs + (framesProcessed * 1000L / sampleRate.coerceAtLeast(1))
            val gain = staticMultiplier *
                TimelineMath.valueAt(clip, KeyframeProperty.VOLUME, tMs, clip.filters.volume)
            for (c in 0 until channelCount) {
                val scaled = (inputBuffer.short * gain).toInt().coerceIn(-32768, 32767)
                out.putShort(scaled.toShort())
            }
            framesProcessed++
        }
        inputBuffer.position(limit)
        out.flip()
    }

    override fun onReset() {
        framesProcessed = 0L
    }
}
