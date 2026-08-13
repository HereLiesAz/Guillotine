package com.hereliesaz.guillotine.media

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Applies a `.cube` 3D LUT to a still [Bitmap] via the same trilinear-interpolation algorithm
 * `DesktopColorMatrix.applyLut` uses on desktop — CPU, no GL context, so it works on a plain
 * [Bitmap] outside a video pipeline. Used only for the store's live effect-preview thumbnails; the
 * real per-clip LUT render path stays Media3's GL-based `SingleColorLut` (see [LutCube]), which
 * needs a GL texture per frame at video framerate, not a one-off still.
 */
object AndroidLutBitmap {

    fun apply(bitmap: Bitmap, lut: CubeLut.Lut3d): Bitmap {
        val size = lut.size
        if (size < 2 || lut.entries.isEmpty()) return bitmap
        val entries = lut.entries
        val maxIdx = size - 1
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            val rf = ((argb ushr 16) and 0xFF) / 255f
            val gf = ((argb ushr 8) and 0xFF) / 255f
            val bf = (argb and 0xFF) / 255f

            // Position within the lattice (grid coords), and the low corner + fractional offset.
            val x = rf * maxIdx
            val y = gf * maxIdx
            val z = bf * maxIdx
            val x0 = x.toInt().coerceIn(0, maxIdx); val x1 = (x0 + 1).coerceAtMost(maxIdx)
            val y0 = y.toInt().coerceIn(0, maxIdx); val y1 = (y0 + 1).coerceAtMost(maxIdx)
            val z0 = z.toInt().coerceIn(0, maxIdx); val z1 = (z0 + 1).coerceAtMost(maxIdx)
            val fx = x - x0; val fy = y - y0; val fz = z - z0

            val i000 = lut.indexOf(x0, y0, z0); val i100 = lut.indexOf(x1, y0, z0)
            val i010 = lut.indexOf(x0, y1, z0); val i110 = lut.indexOf(x1, y1, z0)
            val i001 = lut.indexOf(x0, y0, z1); val i101 = lut.indexOf(x1, y0, z1)
            val i011 = lut.indexOf(x0, y1, z1); val i111 = lut.indexOf(x1, y1, z1)
            val gx = 1f - fx; val gy = 1f - fy; val gz = 1f - fz
            val w000 = gx * gy * gz; val w100 = fx * gy * gz
            val w010 = gx * fy * gz; val w110 = fx * fy * gz
            val w001 = gx * gy * fz; val w101 = fx * gy * fz
            val w011 = gx * fy * fz; val w111 = fx * fy * fz
            val nr = entries[i000] * w000 + entries[i100] * w100 + entries[i010] * w010 + entries[i110] * w110 +
                entries[i001] * w001 + entries[i101] * w101 + entries[i011] * w011 + entries[i111] * w111
            val ng = entries[i000 + 1] * w000 + entries[i100 + 1] * w100 + entries[i010 + 1] * w010 + entries[i110 + 1] * w110 +
                entries[i001 + 1] * w001 + entries[i101 + 1] * w101 + entries[i011 + 1] * w011 + entries[i111 + 1] * w111
            val nb = entries[i000 + 2] * w000 + entries[i100 + 2] * w100 + entries[i010 + 2] * w010 + entries[i110 + 2] * w110 +
                entries[i001 + 2] * w001 + entries[i101 + 2] * w101 + entries[i011 + 2] * w011 + entries[i111 + 2] * w111

            val ir = (nr * 255f).roundToInt().coerceIn(0, 255)
            val ig = (ng * 255f).roundToInt().coerceIn(0, 255)
            val ib = (nb * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (ir shl 16) or (ig shl 8) or ib
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
