package com.hereliesaz.guillotine.ai

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dependency-free "remove vocals" (karaoke) via classic stereo **center-channel cancellation**: lead
 * vocals are usually panned dead-center, so `L − R` cancels them and leaves the stereo-spread
 * instruments. Works only on genuinely stereo audio (a mono track has no center to cancel — the caller
 * must supply distinct L/R channels). Not a true ML stem split (that needs a Spleeter model) — a
 * lightweight, on-device instrumental extractor.
 *
 * Pure JVM DSP with no platform dependency: each platform decodes the clip's stereo PCM its own way
 * (Android via `MediaCodec`, desktop via JavaCV) and passes the [-1,1] samples in; the mix + WAV write
 * happen here. On-device only — audio never leaves the machine.
 */
object VocalIsolator {

    /**
     * Cancel the stereo center channel of already-decoded PCM ([left]/[right], normalized [-1,1] at
     * [sampleRate] Hz) and write a mono 16-bit WAV of the instrumental to [outWavPath]. Returns the
     * output duration in ms, or null if there are no samples.
     */
    fun removeVocals(left: FloatArray, right: FloatArray, sampleRate: Int, outWavPath: String): Long? {
        val frames = minOf(left.size, right.size)
        if (frames == 0 || sampleRate <= 0) return null
        val outFile = File(outWavPath)
        outFile.parentFile?.mkdirs()
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(0)
            val dataSize = frames * 2 // mono 16-bit
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII)); header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)            // PCM fmt chunk size
            header.putShort(1)           // audio format = PCM
            header.putShort(1)           // channels = mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // byte rate (mono * 16-bit)
            header.putShort(2)           // block align
            header.putShort(16)          // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(dataSize)
            raf.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                // (L - R) / 2 cancels center-panned vocals; keep it in range.
                val v = ((left[i] - right[i]) / 2f).coerceIn(-1f, 1f)
                body.putShort((v * 32767f).toInt().toShort())
            }
            raf.write(body.array())
        }
        return frames * 1000L / sampleRate
    }
}
