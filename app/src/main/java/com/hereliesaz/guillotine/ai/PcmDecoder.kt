package com.hereliesaz.guillotine.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Decoded mono PCM: normalized [-1,1] samples plus the (possibly decimated) sample rate. */
data class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate > 0) samples.size * 1000L / sampleRate else 0L
}

/**
 * Decodes the first audio track of a media file to **mono** normalized PCM, decimated to a modest
 * analysis rate. Factored out of [SpeechContentAnalyzer]'s decode loop so both the RMS analyzers and
 * [BeatAnalyzer] can share one decode path. On-device only — the audio never leaves the device.
 */
object PcmDecoder {

    /** Decode [uri] to mono PCM at roughly [targetRate] Hz (stride-decimated). Null if no audio track. */
    fun decode(context: Context, uri: Uri, targetRate: Int = 11_025): PcmAudio? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (_: Exception) {
            runCatching { extractor.release() }
            return null
        }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (t in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(t)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = t; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) { extractor.release(); return null }
        extractor.selectTrack(trackIndex)

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
        // Integer decimation factor toward the target rate.
        val stride = (sampleRate / targetRate).coerceAtLeast(1)
        val outRate = sampleRate / stride

        val out = ArrayList<Float>(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        // Running state so decimation spans buffer boundaries.
        var frameAcc = 0.0
        var chanCount = 0
        var monoIndex = 0L

        // Codec creation/configure/start can throw — keep them inside the try so the extractor
        // (and a partially-created codec) are always released.
        var codec: MediaCodec? = null
        try {
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                .also { codec = it }
            dec.configure(format, null, null, 0)
            dec.start()
            while (!outputDone) {
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = dec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val shorts = outBuf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            frameAcc += shorts.get() / 32768.0
                            chanCount++
                            if (chanCount >= channels) {
                                val mono = (frameAcc / channels)
                                if (monoIndex % stride == 0L) out.add(mono.toFloat())
                                monoIndex++
                                frameAcc = 0.0; chanCount = 0
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
        return PcmAudio(out.toFloatArray(), outRate)
    }
}
