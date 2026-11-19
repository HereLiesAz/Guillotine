package com.hereliesaz.guillotine.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Minimal mic capture for voice-command editing: records 16 kHz mono float PCM (what the offline ASR
 * expects) from tap-to-start until [stop]. One-shot — create a new instance per recording. The caller
 * MUST hold RECORD_AUDIO permission before [start]. Capture itself is local; what happens to the PCM
 * is up to the caller's transcriber — on-device ASR keeps it on the device, but if a cloud
 * transcription provider (e.g. Whisper API) is configured the audio is uploaded to it.
 *
 * Recording auto-stops after [MAX_DURATION_SEC] (the buffer stops growing and [onLimitReached] is
 * invoked on the capture thread); [stop] still returns everything captured up to the cap.
 */
class VoiceCapture(private val onLimitReached: () -> Unit = {}) {

    @Volatile private var recording = false
    private var record: AudioRecord? = null
    // Growable PCM store: fixed-size FloatArray chunks (no per-sample boxing like ArrayList<Float>,
    // and no big copy-on-grow). Guarded by synchronized(chunks).
    private val chunks = ArrayList<FloatArray>()
    private var filledInLast = CHUNK_SAMPLES // forces a new chunk on first write
    private var totalSamples = 0
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
        synchronized(chunks) { chunks.clear(); filledInLast = CHUNK_SAMPLES; totalSamples = 0 }
        recording = true
        runCatching { ar.startRecording() }
        worker = Thread {
            val buf = ShortArray(minBuf)
            while (recording) {
                val n = ar.read(buf, 0, buf.size)
                if (n > 0 && !append(buf, n)) {
                    recording = false // hit MAX_DURATION_SEC: auto-stop
                    runCatching { onLimitReached() }
                }
            }
        }.also { it.start() }
        return true
    }

    /** Stop recording and return the captured 16 kHz mono float PCM. */
    fun stop(): FloatArray {
        recording = false
        runCatching { record?.stop() }
        runCatching { worker?.join(500) }
        worker = null
        runCatching { record?.release() }
        record = null
        return synchronized(chunks) {
            val out = FloatArray(totalSamples)
            var pos = 0
            for (c in chunks) {
                val len = minOf(c.size, totalSamples - pos)
                System.arraycopy(c, 0, out, pos, len)
                pos += len
            }
            out
        }
    }

    /** Append [n] samples from [buf]; false once the duration cap is reached (excess dropped). */
    private fun append(buf: ShortArray, n: Int): Boolean = synchronized(chunks) {
        for (i in 0 until n) {
            if (totalSamples >= MAX_SAMPLES) return false
            if (filledInLast == CHUNK_SAMPLES) { chunks.add(FloatArray(CHUNK_SAMPLES)); filledInLast = 0 }
            chunks[chunks.size - 1][filledInLast++] = buf[i] / 32768f
            totalSamples++
        }
        totalSamples < MAX_SAMPLES
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** Hard cap on one recording (10 min ≈ 38 MB of float PCM). */
        const val MAX_DURATION_SEC = 600
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SEC
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 4 // 4 s per chunk
    }
}
