package com.hereliesaz.guillotine.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Decoded stereo PCM at the source sample rate: L/R normalized [-1,1] plus the track's channel count. */
data class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Int, val channels: Int)

/**
 * Android decode glue for the shared on-device stereo tools ([Spleeter], [VocalIsolator]). Decodes a
 * clip's first audio track to **stereo** float PCM at its native sample rate (a mono track is duplicated
 * to both channels), then the shared DSP resamples/mixes as it needs. Factored out of the old in-`:app`
 * Spleeter so the ORT + DSP could move to `:shared`. On-device only — the audio never leaves the device.
 */
object StereoPcmDecoder {

    /** Decode [uri]'s first audio track to stereo float PCM at its native rate. Null if there's no audio. */
    fun decode(context: Context, uri: Uri): StereoPcm? {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(context, uri, null) } catch (_: Exception) {
            runCatching { extractor.release() }; return null
        }
        var track = -1; var format: MediaFormat? = null
        for (t in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(t)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = t; format = f; break }
        }
        if (track < 0 || format == null) { extractor.release(); return null }
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        extractor.selectTrack(track)
        val left = ArrayList<Float>(1 shl 20); val right = ArrayList<Float>(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false; var ch = 0; var l = 0f; var r = 0f
        var codec: MediaCodec? = null
        try {
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(format, null, null, 0); dec.start()
            while (!outputDone) {
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) { dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = dec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        val sh = ob.asShortBuffer()
                        while (sh.hasRemaining()) {
                            val s = sh.get() / 32768f
                            if (channels == 1) { left.add(s); right.add(s) }
                            else { when (ch) { 0 -> l = s; 1 -> r = s }; ch++; if (ch >= channels) { left.add(l); right.add(r); ch = 0 } }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
        }
        if (left.isEmpty()) return null
        return StereoPcm(left.toFloatArray(), right.toFloatArray(), srcRate, channels)
    }
}
