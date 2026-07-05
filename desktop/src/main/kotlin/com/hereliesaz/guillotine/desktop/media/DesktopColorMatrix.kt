package com.hereliesaz.guillotine.desktop.media

import java.awt.image.BufferedImage
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

    /** Check if the matrix is effectively identity (no visible change). */
    fun isIdentity(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        hueRotate: Float,
        sepia: Float,
    ): Boolean = brightness == 1f && contrast == 1f && saturation == 1f && hueRotate == 0f && sepia == 0f

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
