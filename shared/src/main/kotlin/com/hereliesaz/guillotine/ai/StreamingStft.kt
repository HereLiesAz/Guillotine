package com.hereliesaz.guillotine.ai

/**
 * Frame-by-frame STFT → per-frame processing → overlap-add, for streaming spectral models such as the
 * GTCRN speech denoiser (512-point FFT, hop 256, sqrt-Hann analysis and synthesis windows).
 *
 * With a periodic sqrt-Hann window at 50% overlap the squared windows sum to exactly 1, so an identity
 * [process] reconstructs the input (to float rounding) with no extra normalisation. The signal is padded
 * by one hop in front and one frame behind so the first and last samples are covered by two frames;
 * the output has the input's length.
 *
 * Pure JVM, no model: the caller's [process] receives each frame's one-sided spectrum (`nFft/2 + 1`
 * bins, real and imaginary parts) and returns the spectrum to synthesise.
 */
object StreamingStft {

    fun interface FrameProcessor {
        /** Transform one frame's spectrum. [re]/[im] have `nFft/2 + 1` bins; return arrays of the same size. */
        fun process(re: FloatArray, im: FloatArray): Pair<FloatArray, FloatArray>
    }

    fun sqrtHann(n: Int): FloatArray =
        FloatArray(n) { i -> kotlin.math.sqrt(0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / n)).toFloat() }

    fun run(signal: FloatArray, nFft: Int = 512, hop: Int = 256, process: FrameProcessor): FloatArray {
        require(nFft > 0 && nFft and (nFft - 1) == 0) { "nFft must be a power of two" }
        require(hop * 2 == nFft) { "only 50% overlap is supported" }
        val window = sqrtHann(nFft)
        val bins = nFft / 2 + 1
        val padded = FloatArray(hop + signal.size + nFft).also { signal.copyInto(it, hop) }
        val out = FloatArray(padded.size)
        val frames = (padded.size - nFft) / hop + 1
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        for (t in 0 until frames) {
            val off = t * hop
            for (i in 0 until nFft) {
                re[i] = (padded[off + i] * window[i]).toDouble()
                im[i] = 0.0
            }
            Fft.transform(re, im, inverse = false)
            val fr = FloatArray(bins) { re[it].toFloat() }
            val fi = FloatArray(bins) { im[it].toFloat() }
            val (pr, pi) = process.process(fr, fi)
            // Rebuild the full Hermitian spectrum, then inverse (unscaled, so divide by nFft).
            for (k in 0 until bins) {
                re[k] = pr[k].toDouble()
                im[k] = pi[k].toDouble()
            }
            im[0] = 0.0
            im[bins - 1] = 0.0
            for (k in 1 until bins - 1) {
                re[nFft - k] = re[k]
                im[nFft - k] = -im[k]
            }
            Fft.transform(re, im, inverse = true)
            for (i in 0 until nFft) out[off + i] += (re[i] / nFft).toFloat() * window[i]
        }
        return out.copyOfRange(hop, hop + signal.size)
    }
}
