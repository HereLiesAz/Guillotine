package com.hereliesaz.guillotine.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The pure-JVM parts of Whisper: the 80-bin log-mel frontend and token-text decoding. Mirrors OpenAI
 * Whisper's `log_mel_spectrogram` (16 kHz, 400-point Hann STFT, hop 160, Slaney mel filters,
 * `log10` clamped to 8 below the peak, then `(x + 4) / 4`) on one 30-second window, which is the
 * input sherpa-onnx's Whisper encoders expect. The model runtime lives on the platform side.
 */
object WhisperFrontend {

    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MELS = 80
    const val CHUNK_SAMPLES = SAMPLE_RATE * 30
    const val N_FRAMES = CHUNK_SAMPLES / HOP // 3000

    private const val BINS = N_FFT / 2 + 1

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    private val cosTable = FloatArray(BINS * N_FFT) { cos(2 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() }
    private val sinTable = FloatArray(BINS * N_FFT) { sin(2 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() }
    private val melFilters: Array<FloatArray> = slaneyMelFilters()

    /**
     * Log-mel features of one window: [samples] (16 kHz mono) zero-padded or cut to 30 s. Returns
     * `N_MELS × N_FRAMES` values, mel-major (the `[1, 80, 3000]` encoder input, flattened).
     */
    fun logMel(samples: FloatArray): FloatArray {
        val x = FloatArray(CHUNK_SAMPLES).also { samples.copyInto(it, 0, 0, min(samples.size, CHUNK_SAMPLES)) }
        // Centre the frames with reflect padding, as torch.stft(center=True) does.
        val pad = N_FFT / 2
        val padded = FloatArray(x.size + 2 * pad)
        for (i in padded.indices) {
            var j = i - pad
            if (j < 0) j = -j
            if (j >= x.size) j = 2 * (x.size - 1) - j
            padded[i] = x[j]
        }
        val power = FloatArray(BINS)
        val frame = FloatArray(N_FFT)
        val mel = FloatArray(N_MELS * N_FRAMES)
        var peak = Float.NEGATIVE_INFINITY
        // Whisper keeps N_FRAMES frames (it drops the STFT's last one).
        for (t in 0 until N_FRAMES) {
            val off = t * HOP
            for (n in 0 until N_FFT) frame[n] = padded[off + n] * window[n]
            for (k in 0 until BINS) {
                var re = 0f
                var im = 0f
                val row = k * N_FFT
                for (n in 0 until N_FFT) {
                    re += frame[n] * cosTable[row + n]
                    im -= frame[n] * sinTable[row + n]
                }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                val filt = melFilters[m]
                var e = 0f
                for (k in 0 until BINS) e += filt[k] * power[k]
                val v = log10(max(e, 1e-10f))
                mel[m * N_FRAMES + t] = v
                if (v > peak) peak = v
            }
        }
        val floor = peak - 8f
        for (i in mel.indices) mel[i] = (max(mel[i], floor) + 4f) / 4f
        return mel
    }

    /**
     * Text for [tokens], given the vocabulary lines of a sherpa-onnx `tokens.txt` (`<base64> <id>`).
     * Special tokens (id >= [firstSpecial]) are skipped; bytes are joined before UTF-8 decoding, since a
     * token can hold part of a multi-byte character.
     */
    fun decodeTokens(tokens: List<Int>, vocab: Map<Int, ByteArray>, firstSpecial: Int): String {
        val out = java.io.ByteArrayOutputStream()
        for (t in tokens) if (t < firstSpecial) vocab[t]?.let { out.write(it) }
        return out.toByteArray().decodeToString().trim()
    }

    /** Parse a sherpa-onnx `tokens.txt`: one `<base64 bytes> <id>` per line. */
    fun parseVocab(lines: Sequence<String>): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        for (line in lines) {
            val cut = line.lastIndexOf(' ')
            if (cut <= 0) continue
            val id = line.substring(cut + 1).trim().toIntOrNull() ?: continue
            out[id] = runCatching { java.util.Base64.getDecoder().decode(line.substring(0, cut)) }.getOrNull() ?: continue
        }
        return out
    }

    // Slaney-style mel scale and area-normalised triangles (librosa's defaults, as Whisper uses).
    private fun hzToMel(f: Double): Double =
        if (f < 1000.0) 3.0 * f / 200.0 else 15.0 + ln(f / 1000.0) / (ln(6.4) / 27.0)

    private fun melToHz(m: Double): Double =
        if (m < 15.0) 200.0 * m / 3.0 else 1000.0 * exp((ln(6.4) / 27.0) * (m - 15.0))

    private fun slaneyMelFilters(): Array<FloatArray> {
        val fftFreqs = DoubleArray(BINS) { it * (SAMPLE_RATE / 2.0) / (BINS - 1) }
        val lo = hzToMel(0.0)
        val hi = hzToMel(SAMPLE_RATE / 2.0)
        val pts = DoubleArray(N_MELS + 2) { melToHz(lo + (hi - lo) * it / (N_MELS + 1)) }
        return Array(N_MELS) { i ->
            val enorm = 2.0 / (pts[i + 2] - pts[i])
            FloatArray(BINS) { k ->
                val lower = (fftFreqs[k] - pts[i]) / (pts[i + 1] - pts[i])
                val upper = (pts[i + 2] - fftFreqs[k]) / (pts[i + 2] - pts[i + 1])
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }
}
