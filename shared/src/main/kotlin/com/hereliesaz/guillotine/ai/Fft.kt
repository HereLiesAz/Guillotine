package com.hereliesaz.guillotine.ai

/**
 * In-place iterative radix-2 Cooley–Tukey FFT over parallel real/imag arrays. Size must be a power of 2.
 * The inverse transform is NOT scaled by 1/N — the caller divides. Used by the Spleeter STFT/iSTFT.
 */
object Fft {

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        if (n <= 1) return
        // Bit-reversal permutation.
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
            val ang = 2.0 * Math.PI / len * (if (inverse) 1.0 else -1.0)
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0; var curI = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ik = i + k
                    val jk = i + k + half
                    val bR = re[jk] * curR - im[jk] * curI
                    val bI = re[jk] * curI + im[jk] * curR
                    re[jk] = re[ik] - bR; im[jk] = im[ik] - bI
                    re[ik] += bR; im[ik] += bI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
