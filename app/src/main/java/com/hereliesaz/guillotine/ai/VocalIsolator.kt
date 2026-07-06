package com.hereliesaz.guillotine.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dependency-free "remove vocals" (karaoke) via classic stereo **center-channel cancellation**: lead
 * vocals are usually panned dead-center, so `L − R` cancels them and leaves the stereo-spread
 * instruments. Works only on genuinely stereo audio (a mono track has no center to cancel). Not a true
 * ML stem split (that needs a Spleeter model) — a lightweight, on-device instrumental extractor.
 *
 * Samples are streamed straight to the output WAV (O(1) memory: a full song is ~15M samples, so buffering
 * them would risk OOM). We write a placeholder header, stream the PCM, then patch the header with the
 * real sizes. On-device only — audio never leaves the device.
 */
object VocalIsolator {

    /**
     * Decode [uri]'s first audio track, cancel the center channel, and write a mono 16-bit WAV of the
     * instrumental to [outWavPath]. Returns the output duration in ms, or null if there's no audio or
     * the track isn't stereo.
     */
    fun removeVocals(context: Context, uri: Uri, outWavPath: String): Long? {
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
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        if (channels < 2) { extractor.release(); return null } // needs stereo to cancel the center
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        extractor.selectTrack(trackIndex)

        val outFile = File(outWavPath)
        val bos = try {
            BufferedOutputStream(FileOutputStream(outFile))
        } catch (_: Exception) {
            extractor.release(); return null
        }

        var total = 0L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var chan = 0
        var left = 0
        var right = 0
        var codec: MediaCodec? = null
        try {
            bos.write(ByteArray(44)) // placeholder header, patched after we know the size
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
                            val s = shorts.get().toInt()
                            when (chan) {
                                0 -> left = s
                                1 -> right = s
                            }
                            chan++
                            if (chan >= channels) {
                                // (L - R) / 2 cancels center-panned vocals; extra channels are ignored.
                                val v = ((left - right) / 2).coerceIn(-32768, 32767)
                                bos.write(v and 0xFF)
                                bos.write((v shr 8) and 0xFF)
                                total++
                                chan = 0
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
            runCatching { bos.close() }
        }
        if (total == 0L) { runCatching { outFile.delete() }; return null }
        patchWavHeader(outFile, total, sampleRate)
        return total * 1000L / sampleRate
    }

    /** Overwrite the 44-byte placeholder with a real mono 16-bit PCM WAV header for [samples] frames. */
    private fun patchWavHeader(file: File, samples: Long, sampleRate: Int) {
        val dataSize = (samples * 2).toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)            // PCM fmt chunk size
        header.putShort(1)           // audio format = PCM
        header.putShort(1)           // channels = mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate (mono * 16-bit)
        header.putShort(2)           // block align
        header.putShort(16)          // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }
}
