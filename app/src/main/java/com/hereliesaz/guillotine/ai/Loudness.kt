package com.hereliesaz.guillotine.ai

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ITU-R BS.1770 "K-weighted" loudness measurement (LUFS/LKFS) for normalizing audio to a platform
 * target (e.g. −14 LUFS). Applies the two K-weighting biquads (a high-shelf + a high-pass), computed
 * for the actual sample rate via the bilinear transform, then integrates mean-square energy. This is an
 * **ungated** integrated loudness (no relative gating) — close enough for level-matching a clip to a
 * target, not a certified broadcast meter. On-device only.
 */
object Loudness {

    /** Integrated K-weighted loudness of mono [samples] at [sampleRate], in LUFS. */
    fun measureLufs(samples: FloatArray, sampleRate: Int): Double {
        if (samples.isEmpty() || sampleRate <= 0) return -70.0
        val stage1 = highShelf(sampleRate)
        val stage2 = highPass(sampleRate)
        var s1x1 = 0.0; var s1x2 = 0.0; var s1y1 = 0.0; var s1y2 = 0.0
        var s2x1 = 0.0; var s2x2 = 0.0; var s2y1 = 0.0; var s2y2 = 0.0
        var sumSq = 0.0
        for (s in samples) {
            val x = s.toDouble()
            val y1 = stage1.b0 * x + stage1.b1 * s1x1 + stage1.b2 * s1x2 - stage1.a1 * s1y1 - stage1.a2 * s1y2
            s1x2 = s1x1; s1x1 = x; s1y2 = s1y1; s1y1 = y1
            val y2 = stage2.b0 * y1 + stage2.b1 * s2x1 + stage2.b2 * s2x2 - stage2.a1 * s2y1 - stage2.a2 * s2y2
            s2x2 = s2x1; s2x1 = y1; s2y2 = s2y1; s2y1 = y2
            sumSq += y2 * y2
        }
        val meanSq = sumSq / samples.size
        if (meanSq <= 1e-12) return -70.0
        return -0.691 + 10.0 * log10(meanSq)
    }

    /** Linear gain to bring [lufs] to [targetLufs], clamped to a sane range. */
    fun gainToTarget(lufs: Double, targetLufs: Double): Float {
        val db = targetLufs - lufs
        return Math.pow(10.0, db / 20.0).toFloat().coerceIn(0.05f, 8f)
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    // BS.1770 stage 1: high-shelf (+4 dB) around ~1681 Hz, Q≈0.707. Coeffs derived at [sr] via bilinear.
    private fun highShelf(sr: Int): Biquad {
        val f0 = 1681.974450955533
        val g = 3.999843853973347 // dB
        val q = 0.7071752369554196
        val a = Math.pow(10.0, g / 40.0)
        val w0 = 2.0 * PI * f0 / sr
        val cw = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * q)
        val sqrtA = sqrt(a)
        val b0 = a * ((a + 1) + (a - 1) * cw + 2 * sqrtA * alpha)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cw)
        val b2 = a * ((a + 1) + (a - 1) * cw - 2 * sqrtA * alpha)
        val a0 = (a + 1) - (a - 1) * cw + 2 * sqrtA * alpha
        val a1 = 2 * ((a - 1) - (a + 1) * cw)
        val a2 = (a + 1) - (a - 1) * cw - 2 * sqrtA * alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    // BS.1770 stage 2: high-pass around ~38 Hz, Q≈0.5 (RLB weighting).
    private fun highPass(sr: Int): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val w0 = 2.0 * PI * f0 / sr
        val cw = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * q)
        val b0 = (1 + cw) / 2
        val b1 = -(1 + cw)
        val b2 = (1 + cw) / 2
        val a0 = 1 + alpha
        val a1 = -2 * cw
        val a2 = 1 - alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
