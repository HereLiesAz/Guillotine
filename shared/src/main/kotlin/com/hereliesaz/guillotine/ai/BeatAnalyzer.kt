package com.hereliesaz.guillotine.ai

import com.hereliesaz.guillotine.model.BeatMap
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device beat / tempo / onset detection. Fully local (the audio never leaves the device), no
 * native dependency: given already-decoded **mono PCM** it builds a **spectral-flux onset envelope**,
 * estimates tempo by **autocorrelation**, then phase-aligns a beat grid. Good enough to drive "edit to
 * the beat"; can later be swapped for a TFLite BeatNet/TempoCNN for tougher material.
 *
 * This is pure JVM DSP with no platform dependency — each platform decodes PCM its own way (Android via
 * `PcmDecoder`, desktop via `DesktopMediaDecoder`) and passes the samples in. All timestamps in the
 * returned [BeatMap] are source-media milliseconds.
 */
object BeatAnalyzer {

    private const val FFT = 1024          // window size (power of 2)
    private const val HOP = 256           // ~23 ms at 11025 Hz
    private const val MIN_BPM = 70f
    private const val MAX_BPM = 180f

    /** Analyze already-decoded **mono** [samples] (normalized [-1,1]) at [sampleRate] Hz. */
    fun analyze(samples: FloatArray, sampleRate: Int): BeatMap {
        val sr = sampleRate
        if (samples.size < FFT * 2 || sr <= 0) return BeatMap(0f, emptyList(), emptyList(), emptyList())

        val flux = onsetEnvelope(samples)
        if (flux.isEmpty()) return BeatMap(0f, emptyList(), emptyList(), emptyList())
        val frameMs = HOP * 1000.0 / sr

        val bpm = estimateTempo(flux, frameMs)
        val beatsFrames = beatGrid(flux, frameMs, bpm)
        val beatsMs = beatsFrames.map { (it * frameMs).roundToInt().toLong() }
        // Downbeats: every 4th beat (assume 4/4), anchored on the strongest of the first four.
        val downbeats = downbeatsFrom(beatsMs, flux, frameMs)
        val onsetsMs = pickOnsets(flux, frameMs)

        return BeatMap(bpm = bpm, beatsMs = beatsMs, downbeatsMs = downbeats, onsetsMs = onsetsMs)
    }

    // ---- onset envelope (spectral flux) ------------------------------------

    private fun onsetEnvelope(samples: FloatArray): FloatArray {
        val window = FloatArray(FFT) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / (FFT - 1)).toFloat() } // Hann
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        var prevMag = FloatArray(FFT / 2)
        val frames = ((samples.size - FFT) / HOP).coerceAtLeast(0)
        val flux = FloatArray(frames)
        for (f in 0 until frames) {
            val base = f * HOP
            for (i in 0 until FFT) { re[i] = (samples[base + i] * window[i]).toDouble(); im[i] = 0.0 }
            fft(re, im)
            var sum = 0f
            val mag = FloatArray(FFT / 2)
            for (k in 0 until FFT / 2) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
                mag[k] = m
                val d = m - prevMag[k]
                if (d > 0) sum += d // half-wave rectified flux
            }
            flux[f] = sum
            prevMag = mag
        }
        return normalize(smooth(flux))
    }

    private fun smooth(x: FloatArray): FloatArray {
        if (x.size < 3) return x
        val out = FloatArray(x.size)
        for (i in x.indices) {
            var s = 0f; var n = 0
            for (j in -1..1) { val k = i + j; if (k in x.indices) { s += x[k]; n++ } }
            out[i] = s / n
        }
        return out
    }

    private fun normalize(x: FloatArray): FloatArray {
        val max = x.maxOrNull() ?: 0f
        if (max <= 0f) return x
        return FloatArray(x.size) { x[it] / max }
    }

    // ---- tempo via autocorrelation -----------------------------------------

    private fun estimateTempo(flux: FloatArray, frameMs: Double): Float {
        val minLag = ((60_000.0 / MAX_BPM) / frameMs).roundToInt().coerceAtLeast(1)
        val maxLag = ((60_000.0 / MIN_BPM) / frameMs).roundToInt().coerceAtMost(flux.size - 1)
        if (maxLag <= minLag) return 120f
        var bestLag = minLag
        var best = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var acc = 0.0
            var n = 0
            var i = lag
            while (i < flux.size) { acc += flux[i] * flux[i - lag]; n++; i++ }
            val score = if (n > 0) acc / n else 0.0
            if (score > best) { best = score; bestLag = lag }
        }
        val bpm = (60_000.0 / (bestLag * frameMs)).toFloat()
        return bpm.coerceIn(MIN_BPM, MAX_BPM)
    }

    // ---- beat grid: best phase for the tempo period ------------------------

    private fun beatGrid(flux: FloatArray, frameMs: Double, bpm: Float): List<Double> {
        if (bpm <= 0f) return emptyList()
        val periodFrames = (60_000.0 / bpm) / frameMs
        if (periodFrames < 1.0) return emptyList()
        val periodI = periodFrames.roundToInt().coerceAtLeast(1)
        // Choose the phase (0..periodI) whose beat samples sum the most onset energy.
        var bestPhase = 0
        var best = Double.NEGATIVE_INFINITY
        for (phase in 0 until periodI) {
            var acc = 0.0
            var pos = phase.toDouble()
            while (pos < flux.size) { acc += flux[pos.toInt()]; pos += periodFrames }
            if (acc > best) { best = acc; bestPhase = phase }
        }
        val beats = ArrayList<Double>()
        var pos = bestPhase.toDouble()
        while (pos < flux.size) { beats.add(pos); pos += periodFrames }
        return beats
    }

    private fun downbeatsFrom(beatsMs: List<Long>, flux: FloatArray, frameMs: Double): List<Long> {
        if (beatsMs.size < 4) return beatsMs
        // Anchor the bar on whichever of the first four beats carries the most onset energy.
        fun energyAt(ms: Long): Float {
            val f = (ms / frameMs).roundToInt().coerceIn(0, flux.size - 1)
            return flux[f]
        }
        val anchor = (0..3).maxByOrNull { energyAt(beatsMs[it]) } ?: 0
        return beatsMs.filterIndexed { i, _ -> (i - anchor) % 4 == 0 && i >= anchor }
    }

    // ---- discrete onsets (peaks above adaptive threshold) ------------------

    private fun pickOnsets(flux: FloatArray, frameMs: Double): List<Long> {
        val mean = flux.average().toFloat()
        val thr = mean + 0.15f
        val out = ArrayList<Long>()
        var last = -1000L
        for (i in 1 until flux.size - 1) {
            if (flux[i] > thr && flux[i] >= flux[i - 1] && flux[i] > flux[i + 1]) {
                val ms = (i * frameMs).roundToInt().toLong()
                if (ms - last >= 60L) { out.add(ms); last = ms } // debounce 60 ms
            }
        }
        return out
    }

    // ---- iterative radix-2 Cooley–Tukey FFT (in place) ---------------------

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenR = cos(ang); val wlenI = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0; var wI = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]; val uI = im[i + k]
                    val vR = re[i + k + len / 2] * wR - im[i + k + len / 2] * wI
                    val vI = re[i + k + len / 2] * wI + im[i + k + len / 2] * wR
                    re[i + k] = uR + vR; im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR; im[i + k + len / 2] = uI - vI
                    val nwR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR; wR = nwR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
