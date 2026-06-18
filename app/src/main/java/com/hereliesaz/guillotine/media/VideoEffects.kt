@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.media

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.GlobalSettings

/**
 * Translates a clip's [ClipFilters] into a list of Media3 [Effect]s. The SAME
 * builder feeds both the live preview (`ExoPlayer.setVideoEffects`) and the
 * exporter (`Transformer` via `EditedMediaItem`), so what you see matches what
 * you get.
 *
 * Note: time-varying (keyframed) opacity/scale are applied at the view layer in
 * preview and as static transforms in export v1; the per-clip Crop-tool transform
 * (scale/rotation/offset) is applied via [transform].
 */
object VideoEffects {

    fun build(filters: ClipFilters): List<Effect> {
        val effects = mutableListOf<Effect>()

        // Brightness: scale all channels uniformly (1.0 = unchanged).
        if (filters.brightness != 1f) {
            effects += RgbAdjustment.Builder()
                .setRedScale(filters.brightness)
                .setGreenScale(filters.brightness)
                .setBlueScale(filters.brightness)
                .build()
        }

        // Contrast: Media3 expects [-1, 1] with 0 = unchanged; our scale is 0..2.
        if (filters.contrast != 1f) {
            effects += Contrast((filters.contrast - 1f).coerceIn(-1f, 1f))
        }

        // Saturation + hue rotation via HSL.
        if (filters.saturation != 1f || filters.hueRotate != 0f) {
            effects += HslAdjustment.Builder()
                .adjustSaturation((filters.saturation - 1f) * 100f)
                .adjustHue(filters.hueRotate)
                .build()
        }

        // Sepia: blend identity toward the classic sepia color matrix by intensity (0..100% → 0..1).
        if (filters.sepia > 0f) {
            effects += SepiaMatrix((filters.sepia / 100f).coerceIn(0f, 1f))
        }

        // Grayscale / invert are binary presets in Media3; apply past the midpoint.
        if (filters.grayscale >= 50f) {
            effects += RgbFilter.createGrayscaleFilter()
        }
        if (filters.invert >= 50f) {
            effects += RgbFilter.createInvertedFilter()
        }

        // Gaussian blur: our control is a pixel radius (0..20); Media3's sigma is the half-width
        // of one standard deviation in pixels, so the radius maps directly.
        if (filters.blur > 0f) {
            effects += GaussianBlur(filters.blur)
        }

        return effects
    }

    /**
     * Per-clip Crop-tool transform: uniform [scale] about center, [rotationDeg] clockwise, and a
     * normalized [offsetX]/[offsetY] (fraction of the frame, from center) — matching the preview's
     * `graphicsLayer`. Compose's `rotationZ` is clockwise while Media3 rotates counter-clockwise, so
     * the rotation is negated; NDC spans [-1, 1] (full edge = 2) and screen-Y is down while NDC-Y is
     * up, so the Y offset is inverted.
     */
    fun transform(scale: Float, rotationDeg: Float, offsetX: Float, offsetY: Float): List<Effect> {
        val effects = mutableListOf<Effect>()
        val s = scale.coerceAtLeast(0f)
        if (s != 1f || rotationDeg != 0f) {
            effects += ScaleAndRotateTransformation.Builder()
                .setScale(s, s)
                .setRotationDegrees(-rotationDeg)
                .build()
        }
        if (offsetX != 0f || offsetY != 0f) {
            // The translation is static for the clip, so build the Matrix once and reuse it —
            // the lambda is called per frame, so allocating there would churn the GC.
            val matrix = android.graphics.Matrix().apply { setTranslate(offsetX * 2f, -offsetY * 2f) }
            effects += MatrixTransformation { _ -> matrix }
        }
        return effects
    }

    /** A constant 4x4 RGB matrix that blends the identity toward sepia by [amount] (0..1). */
    private class SepiaMatrix(private val amount: Float) : RgbMatrix {
        // Column-major (GLSL): element[inputChannel * 4 + outputChannel]. Sepia weights:
        //   R' = .393R + .769G + .189B,  G' = .349R + .686G + .168B,  B' = .272R + .534G + .131B.
        private val matrix: FloatArray = run {
            val sepia = floatArrayOf(
                0.393f, 0.349f, 0.272f, 0f, // input R → (R,G,B,A)
                0.769f, 0.686f, 0.534f, 0f, // input G
                0.189f, 0.168f, 0.131f, 0f, // input B
                0f, 0f, 0f, 1f,             // input A
            )
            val identity = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
            FloatArray(16) { (1f - amount) * identity[it] + amount * sepia[it] }
        }

        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
    }

    /**
     * Project-level geometry effects (crop + aspect ratio) derived from
     * [GlobalSettings]. Applied to every video clip in the export so the output
     * frame matches the project, not just the preview.
     */
    fun geometry(settings: GlobalSettings): List<Effect> {
        val effects = mutableListOf<Effect>()

        // Crop. Our crop is x/y/w/h in percent; Media3 Crop takes NDC [-1, 1].
        val c = settings.crop
        val isFullFrame = c.x == 0f && c.y == 0f && c.w == 100f && c.h == 100f
        if (!isFullFrame && c.w > 0f && c.h > 0f) {
            val left = (c.x / 50f) - 1f
            val right = ((c.x + c.w) / 50f) - 1f
            val top = 1f - (c.y / 50f)
            val bottom = 1f - ((c.y + c.h) / 50f)
            if (right > left && top > bottom) {
                effects += Crop(
                    left.coerceIn(-1f, 1f),
                    right.coerceIn(-1f, 1f),
                    bottom.coerceIn(-1f, 1f),
                    top.coerceIn(-1f, 1f),
                )
            }
        }

        // Aspect ratio (scale-to-fit, letterboxed). ORIGINAL = no change.
        val ratio = when (settings.aspectRatio) {
            AspectRatio.RATIO_16_9 -> 16f / 9f
            AspectRatio.RATIO_9_16 -> 9f / 16f
            AspectRatio.RATIO_1_1 -> 1f
            AspectRatio.ORIGINAL -> null
        }
        if (ratio != null) {
            effects += Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT)
        }
        return effects
    }
}
