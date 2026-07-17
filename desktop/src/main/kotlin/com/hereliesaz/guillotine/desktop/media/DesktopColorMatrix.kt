package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.media.CubeLut
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-JVM port of the color matrix math from the Android VideoEffects.
 * Builds a 4x4 column-major RGBA matrix from filter settings and applies it to BufferedImage pixels.
 */
object DesktopColorMatrix {

    private const val LR = 0.213f
    private const val LG = 0.715f
    private const val LB = 0.072f

    /**
     * Build a 4x4 column-major RGBA matrix from filter settings.
     * Element layout: `matrix[inputChannel * 4 + outputChannel]`.
     * An additional 3-element offset array is returned as indices 16..18 (R, G, B offsets in 0..1 space).
     */
    fun buildMatrix(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        hueRotate: Float,
        sepia: Float,
        grayscale: Float = 0f,
        invert: Float = 0f,
    ): FloatArray {
        // Work in row-major 5x4 (android.graphics.ColorMatrix layout):
        // row r: [r0 r1 r2 r3 offset] where output = r0*R + r1*G + r2*B + r3*A + offset
        val cm = identityRowMajor()

        // Saturation
        if (saturation != 1f) {
            val s = saturation.coerceAtLeast(0f)
            val is1 = 1f - s
            postConcat(
                cm,
                floatArrayOf(
                    LR * is1 + s, LG * is1, LB * is1, 0f, 0f,
                    LR * is1, LG * is1 + s, LB * is1, 0f, 0f,
                    LR * is1, LG * is1, LB * is1 + s, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }

        // Hue rotation
        if (hueRotate != 0f) {
            postConcat(cm, hueRowMajor(hueRotate))
        }

        // Brightness
        if (brightness != 1f) {
            postConcat(
                cm,
                floatArrayOf(
                    brightness, 0f, 0f, 0f, 0f,
                    0f, brightness, 0f, 0f, 0f,
                    0f, 0f, brightness, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }

        // Contrast
        if (contrast != 1f) {
            val o = 128f * (1f - contrast)
            postConcat(
                cm,
                floatArrayOf(
                    contrast, 0f, 0f, 0f, o,
                    0f, contrast, 0f, 0f, o,
                    0f, 0f, contrast, 0f, o,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }

        // Sepia
        val s = (sepia / 100f).coerceIn(0f, 1f)
        if (s > 0f) {
            postConcat(cm, sepiaRowMajor(s))
        }

        // Grayscale (0..100%): interpolate identity → luminance projection.
        val gray = (grayscale / 100f).coerceIn(0f, 1f)
        if (gray > 0f) {
            fun lg(id: Float, lum: Float) = (1f - gray) * id + gray * lum
            postConcat(
                cm,
                floatArrayOf(
                    lg(1f, LR), lg(0f, LG), lg(0f, LB), 0f, 0f,
                    lg(0f, LR), lg(1f, LG), lg(0f, LB), 0f, 0f,
                    lg(0f, LR), lg(0f, LG), lg(1f, LB), 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }

        // Invert (0..100%): out = (1-2i)·in + 255·i, so i=1 gives 255−in.
        val inv = (invert / 100f).coerceIn(0f, 1f)
        if (inv > 0f) {
            val d = 1f - 2f * inv
            val o = 255f * inv
            postConcat(
                cm,
                floatArrayOf(
                    d, 0f, 0f, 0f, o,
                    0f, d, 0f, 0f, o,
                    0f, 0f, d, 0f, o,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }

        // Convert row-major 5x4 to column-major 4x4 + offsets (19 elements total)
        val out = FloatArray(19)
        for (outc in 0 until 4) {
            for (inc in 0 until 4) {
                out[inc * 4 + outc] = cm[outc * 5 + inc]
            }
        }
        // Offsets (in 0..255 space in row-major) → 0..1 space, placed on alpha-input column
        for (outc in 0 until 3) {
            out[3 * 4 + outc] += cm[outc * 5 + 4] / 255f
        }
        // Store raw offsets for BufferedImage application (0..255 space)
        out[16] = cm[0 * 5 + 4]
        out[17] = cm[1 * 5 + 4]
        out[18] = cm[2 * 5 + 4]
        return out
    }

    /** Apply the color matrix to a BufferedImage in-place. */
    fun applyToImage(image: BufferedImage, matrix: FloatArray) {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)

        // Extract the 4x4 portion (column-major) back to row-major for per-pixel math
        val m00 = matrix[0]; val m01 = matrix[4]; val m02 = matrix[8]
        val m10 = matrix[1]; val m11 = matrix[5]; val m12 = matrix[9]
        val m20 = matrix[2]; val m21 = matrix[6]; val m22 = matrix[10]
        val offR = matrix[16]; val offG = matrix[17]; val offB = matrix[18]

        for (i in pixels.indices) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            val nr = (m00 * r + m01 * g + m02 * b + offR).roundToInt().coerceIn(0, 255)
            val ng = (m10 * r + m11 * g + m12 * b + offG).roundToInt().coerceIn(0, 255)
            val nb = (m20 * r + m21 * g + m22 * b + offB).roundToInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        image.setRGB(0, 0, w, h, pixels, 0, w)
    }

    /**
     * Apply a `.cube` 3D LUT to a BufferedImage in-place using **trilinear interpolation** over the
     * LUT lattice. Each pixel's normalized RGB is placed inside the `size`³ cube; the 8 surrounding
     * grid entries are blended by the fractional position. Mirrors the Android LUT grade (Media3's
     * `SingleColorLut`). Alpha is preserved. Applied AFTER the color matrix (matching Android's order).
     */
    fun applyLut(image: BufferedImage, lut: CubeLut.Lut3d) {
        val size = lut.size
        if (size < 2 || lut.entries.isEmpty()) return
        val entries = lut.entries
        val maxIdx = size - 1
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)

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

            // Blend the 8 cube corners (trilinear), unrolled to a flat, branchless expression — this
            // runs per pixel per frame, so the loop/branches are removed to let the JIT keep it fast.
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

        image.setRGB(0, 0, w, h, pixels, 0, w)
    }

    /**
     * Gaussian-ish blur of [image] in place by [radiusPx] pixels (the clip's `filters.blur`), applied as
     * a separable box kernel via two [ConvolveOp]s (horizontal then vertical) — well-tested Java2D math,
     * so it's correct without a hand-rolled convolution. Desktop previously ignored blur entirely, so a
     * blurred clip rendered sharp on both preview and export. No-op for a zero/negative radius. The
     * radius is capped so a runaway value can't stall a per-frame render.
     */
    fun blur(image: BufferedImage, radiusPx: Float) {
        val r = radiusPx.roundToInt().coerceIn(0, 40)
        if (r <= 0 || image.width < 2 || image.height < 2) return
        val size = 2 * r + 1
        val line = FloatArray(size) { 1f / size } // normalized 1D box kernel
        val opH = ConvolveOp(Kernel(size, 1, line), ConvolveOp.EDGE_NO_OP, null)
        val opV = ConvolveOp(Kernel(1, size, line), ConvolveOp.EDGE_NO_OP, null)
        // ConvolveOp requires distinct src/dst. Let it create a type-compatible scratch (passing null
        // dest), then blur vertically back into the original — avoids constructing a BufferedImage from a
        // possibly-TYPE_CUSTOM decoded-frame type.
        val tmp = opH.filter(image, null)
        opV.filter(tmp, image)
    }

    /** Check if the matrix is effectively identity (no visible change). */
    fun isIdentity(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        hueRotate: Float,
        sepia: Float,
        grayscale: Float = 0f,
        invert: Float = 0f,
    ): Boolean = brightness == 1f && contrast == 1f && saturation == 1f && hueRotate == 0f &&
        sepia == 0f && grayscale == 0f && invert == 0f

    // --- Row-major 5x4 matrix helpers (same layout as android.graphics.ColorMatrix) ---

    private fun identityRowMajor() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun postConcat(dst: FloatArray, rhs: FloatArray) {
        val tmp = dst.copyOf()
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += rhs[row * 5 + k] * tmp[k * 5 + col]
                }
                if (col == 4) sum += rhs[row * 5 + 4]
                dst[row * 5 + col] = sum
            }
        }
    }

    private fun hueRowMajor(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return floatArrayOf(
            LR + c * (1 - LR) + s * (-LR), LG + c * (-LG) + s * (-LG), LB + c * (-LB) + s * (1 - LB), 0f, 0f,
            LR + c * (-LR) + s * 0.143f, LG + c * (1 - LG) + s * 0.140f, LB + c * (-LB) + s * (-0.283f), 0f, 0f,
            LR + c * (-LR) + s * (-(1 - LR)), LG + c * (-LG) + s * LG, LB + c * (1 - LB) + s * LB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun sepiaRowMajor(amt: Float): FloatArray {
        fun l(id: Float, sep: Float) = (1f - amt) * id + amt * sep
        return floatArrayOf(
            l(1f, 0.393f), l(0f, 0.769f), l(0f, 0.189f), 0f, 0f,
            l(0f, 0.349f), l(1f, 0.686f), l(0f, 0.168f), 0f, 0f,
            l(0f, 0.272f), l(0f, 0.534f), l(1f, 0.131f), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}
