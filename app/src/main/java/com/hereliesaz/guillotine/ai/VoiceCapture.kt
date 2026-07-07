package com.hereliesaz.guillotine.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Minimal mic capture for voice-command editing: records 16 kHz mono float PCM (what the offline ASR
 * expects) from tap-to-start until [stop]. One-shot — create a new instance per recording. The caller
 * MUST hold RECORD_AUDIO permission before [start]. On-device only — audio never leaves the device.
 */
class VoiceCapture {

    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private val samples = ArrayList<Float>(SAMPLE_RATE * 4)
    private var worker: Thread? = null

    val isRecording: Boolean get() = recording

    /** Begin recording from the mic. No-op if already recording. */
    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE) // at least ~1s of headroom
        val ar = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf,
            )
        }.getOrNull() ?: return false
        if (ar.state != AudioRecord.STATE_INITIALIZED) { runCatching { ar.release() }; return false }
        record = ar
        synchronized(samples) { samples.clear() }
        recording = true
        runCatching { ar.startRecording() }
        worker = Thread {
            val buf = ShortArray(minBuf)
            while (recording) {
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) synchronized(samples) { for (i in 0 until n) samples.add(buf[i] / 32768f) }
            }
        }.also { it.start() }
        return true
    }

    /** Stop recording and return the captured 16 kHz mono float PCM. */
    fun stop(): FloatArray {
        recording = false
        runCatching { worker?.join(500) }
        worker = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        return synchronized(samples) { samples.toFloatArray() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
