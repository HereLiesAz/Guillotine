package com.hereliesaz.guillotine.ai

import android.graphics.Bitmap

/**
 * Depth-of-field ("bokeh") compositing: blur a frame where a depth map marks pixels as FAR, keeping the
 * near subject sharp — a portrait/cinematic look. Pairs with the on-device MiDaS depth model (whose
 * normalized output is brighter = nearer). Pure Kotlin (separable box blur); on-device only.
 */
object DepthBokeh {

    /**
     * Composite [frame] with its [depth] map: far pixels get a box blur of [radius] px, near pixels stay
     * sharp, with a smooth transition. Returns a new bitmap the size of [frame].
     */
    fun apply(frame: Bitmap, depth: Bitmap, radius: Int): Bitmap {
        val w = frame.width
        val h = frame.height
        val r = radius.coerceIn(2, 48)
        val sharp = IntArray(w * h)
        frame.getPixels(sharp, 0, w, 0, 0, w, h)
        val blurred = boxBlur(sharp, w, h, r)
        // Depth → nearness per pixel (MiDaS: brighter = nearer). Scale the depth map to the frame size.
        val dScaled = Bitmap.createScaledBitmap(depth, w, h, true)
        val dpx = IntArray(w * h)
        dScaled.getPixels(dpx, 0, w, 0, 0, w, h)
        if (dScaled !== depth) dScaled.recycle()
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val d = dpx[i]
            val near = ((d ushr 16 and 0xFF) + (d ushr 8 and 0xFF) + (d and 0xFF)) / 765f // 0..1
            out[i] = mix(sharp[i], blurred[i], near.coerceIn(0f, 1f))
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Per-channel blend: [near]=1 → all [sharp], [near]=0 → all [blur]. */
    private fun mix(sharp: Int, blur: Int, near: Float): Int {
        val f = near
        val g = 1f - near
        val r = ((sharp ushr 16 and 0xFF) * f + (blur ushr 16 and 0xFF) * g).toInt()
        val gr = ((sharp ushr 8 and 0xFF) * f + (blur ushr 8 and 0xFF) * g).toInt()
        val b = ((sharp and 0xFF) * f + (blur and 0xFF) * g).toInt()
        return (0xFF shl 24) or (r shl 16) or (gr shl 8) or b
    }

    /** Separable box blur (running-sum, O(w·h)) over packed ARGB, edges clamped. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val win = 2 * r + 1
        val a = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val p = src[row + k.coerceIn(0, w - 1)]; sr += p ushr 16 and 0xFF; sg += p ushr 8 and 0xFF; sb += p and 0xFF }
            for (x in 0 until w) {
                a[row + x] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val po = src[row + (x - r).coerceIn(0, w - 1)]
                val pi = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += (pi ushr 16 and 0xFF) - (po ushr 16 and 0xFF)
                sg += (pi ushr 8 and 0xFF) - (po ushr 8 and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
        val b = IntArray(w * h)
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val p = a[k.coerceIn(0, h - 1) * w + x]; sr += p ushr 16 and 0xFF; sg += p ushr 8 and 0xFF; sb += p and 0xFF }
            for (y in 0 until h) {
                b[y * w + x] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val po = a[(y - r).coerceIn(0, h - 1) * w + x]
                val pi = a[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += (pi ushr 16 and 0xFF) - (po ushr 16 and 0xFF)
                sg += (pi ushr 8 and 0xFF) - (po ushr 8 and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
        return b
    }
}
