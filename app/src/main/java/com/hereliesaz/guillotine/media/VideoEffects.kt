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
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath

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

    /**
     * Keyframe-aware video effects. [startMs] maps the renderer's presentationTime to clip-relative
     * time: export passes `clipLocalStartMs` (`rangeStart - clip.trimStartMs`); preview passes
     * `-clip.trimStartMs` (the picture player is seeked to source time). Each returns the animated
     * effect only when that property group is keyframed, else the static equivalent — so non-keyframed
     * clips render exactly as before.
     */
    fun colorEffects(clip: TimelineClip, startMs: Long): List<Effect> =
        if (clip.keyframes.any { it.property in KeyframeProperty.COLOR }) {
            listOf(KeyframeColorMatrix(clip, startMs)) + nonColorStatic(clip.filters)
        } else {
            build(clip.filters)
        }

    /** Per-frame crop/placement transform when keyframed (SCALE/ROTATION/OFFSET_X/OFFSET_Y), else static. */
    fun transformEffects(clip: TimelineClip, startMs: Long): List<Effect> =
        if (clip.keyframes.any { it.property in KeyframeProperty.TRANSFORM }) {
            listOf(KeyframeTransform(clip, startMs))
        } else {
            transform(clip.scale, clip.rotation, clip.offsetX, clip.offsetY)
        }

    /** Per-frame alpha when opacity is keyframed (track/clip-level opacity is applied separately). */
    fun opacityEffects(clip: TimelineClip, startMs: Long): List<Effect> =
        if (clip.keyframes.any { it.property == KeyframeProperty.OPACITY }) {
            listOf(KeyframeAlpha(clip, startMs))
        } else {
            emptyList()
        }

    /** Filters that can't be keyframed (fixed Media3 config): binary grayscale/invert + Gaussian blur. */
    private fun nonColorStatic(filters: ClipFilters): List<Effect> {
        val out = mutableListOf<Effect>()
        if (filters.grayscale >= 50f) out += RgbFilter.createGrayscaleFilter()
        if (filters.invert >= 50f) out += RgbFilter.createInvertedFilter()
        if (filters.blur > 0f) out += GaussianBlur(filters.blur)
        return out
    }

    /**
     * A linear alpha fade-in from 0→1 across the timeline window [[fromMs], [toMs]] (used by the
     * exporter's crossfade: the incoming clip fades in over the held outgoing clip). [startMs] is the
     * timeline time of the item's first frame (presentationTimeUs 0), so this works across 'remove'
     * cuts the same way [KeyframeAlpha] does. Before the window alpha is 0, after it 1.
     */
    fun fadeIn(startMs: Long, fromMs: Long, toMs: Long): Effect = FadeInAlpha(startMs, fromMs, toMs)

    private class FadeInAlpha(
        private val startMs: Long,
        private val fromMs: Long,
        private val toMs: Long,
    ) : RgbMatrix {
        private val m = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        private val span = (toMs - fromMs).coerceAtLeast(1L)
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val t = startMs + presentationTimeUs / 1000
            m[15] = ((t - fromMs).toFloat() / span).coerceIn(0f, 1f)
            return m
        }
    }

    /** Animates the frame's alpha from the clip's OPACITY keyframes (1 = opaque). */
    private class KeyframeAlpha(private val clip: TimelineClip, private val startMs: Long) : RgbMatrix {
        // Column-major identity; element[15] is the alpha→alpha factor, updated per frame.
        private val m = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            m[15] = TimelineMath.valueAt(clip, KeyframeProperty.OPACITY, startMs + presentationTimeUs / 1000, 1f)
                .coerceIn(0f, 1f)
            return m
        }
    }

    /**
     * Per-frame crop/placement transform from SCALE/ROTATION/OFFSET_X/OFFSET_Y keyframes (absolute —
     * the clip's static value is the default). Rotation is applied in NDC: exact on square frames,
     * slightly skewed on wide frames (preview's graphicsLayer rotation is exact); an aspect-correct
     * GL fix for export rotation is a follow-up.
     */
    private class KeyframeTransform(private val clip: TimelineClip, private val startMs: Long) : MatrixTransformation {
        private val m = android.graphics.Matrix()
        // Pre-filtered/sorted once so getMatrix (per frame) stays allocation-free.
        private val scaleKfs = clip.keyframes.filter { it.property == KeyframeProperty.SCALE }.sortedBy { it.timeMs }
        private val rotationKfs = clip.keyframes.filter { it.property == KeyframeProperty.ROTATION }.sortedBy { it.timeMs }
        private val offsetXKfs = clip.keyframes.filter { it.property == KeyframeProperty.OFFSET_X }.sortedBy { it.timeMs }
        private val offsetYKfs = clip.keyframes.filter { it.property == KeyframeProperty.OFFSET_Y }.sortedBy { it.timeMs }
        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
            val t = startMs + presentationTimeUs / 1000
            val s = TimelineMath.interpolateSorted(scaleKfs, t, clip.scale).coerceAtLeast(0f)
            val rot = TimelineMath.interpolateSorted(rotationKfs, t, clip.rotation)
            val ox = TimelineMath.interpolateSorted(offsetXKfs, t, clip.offsetX)
            val oy = TimelineMath.interpolateSorted(offsetYKfs, t, clip.offsetY)
            m.reset()
            m.postScale(s, s)   // NDC centre (0,0)
            m.postRotate(-rot)  // Compose CW vs Media3 CCW
            m.postTranslate(ox * 2f, -oy * 2f)
            return m
        }
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
     * Per-frame color matrix animating brightness/contrast/saturation/hue/sepia from the clip's color
     * keyframes (absolute; the clip's static filter value is the default). Composed via android's
     * tested [android.graphics.ColorMatrix] then converted to Media3's column-major 4×4 RGBA matrix.
     */
    private class KeyframeColorMatrix(private val clip: TimelineClip, private val startMs: Long) : RgbMatrix {
        private val out = FloatArray(16)
        // Pre-filtered/sorted once so getMatrix (per frame) stays allocation-free.
        private val brightnessKfs = clip.keyframes.filter { it.property == KeyframeProperty.BRIGHTNESS }.sortedBy { it.timeMs }
        private val contrastKfs = clip.keyframes.filter { it.property == KeyframeProperty.CONTRAST }.sortedBy { it.timeMs }
        private val saturationKfs = clip.keyframes.filter { it.property == KeyframeProperty.SATURATION }.sortedBy { it.timeMs }
        private val hueKfs = clip.keyframes.filter { it.property == KeyframeProperty.HUE }.sortedBy { it.timeMs }
        private val sepiaKfs = clip.keyframes.filter { it.property == KeyframeProperty.SEPIA }.sortedBy { it.timeMs }
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val t = startMs + presentationTimeUs / 1000
            val b = TimelineMath.interpolateSorted(brightnessKfs, t, clip.filters.brightness)
            val c = TimelineMath.interpolateSorted(contrastKfs, t, clip.filters.contrast)
            val s = TimelineMath.interpolateSorted(saturationKfs, t, clip.filters.saturation)
            val hue = TimelineMath.interpolateSorted(hueKfs, t, clip.filters.hueRotate)
            val sepia = (TimelineMath.interpolateSorted(sepiaKfs, t, clip.filters.sepia) / 100f)
                .coerceIn(0f, 1f)
            return colorMatrixGl(b, c, s, hue, sepia, out)
        }
    }

    /**
     * Compose brightness/contrast/saturation/hue/sepia into Media3's column-major 4×4 RGBA matrix
     * (`element[in*4 + out]`), reusing [android.graphics.ColorMatrix]'s tested math. Contrast's affine
     * offset rides on the alpha-input column (valid because the picture is opaque before this runs) and
     * is rescaled from ColorMatrix's 0..255 space to Media3's 0..1. [out] is reused to avoid GC churn.
     */
    private fun colorMatrixGl(
        brightness: Float, contrast: Float, saturation: Float, hueDeg: Float, sepia: Float, out: FloatArray,
    ): FloatArray {
        val cm = android.graphics.ColorMatrix()
        cm.setSaturation(saturation.coerceAtLeast(0f))
        if (hueDeg != 0f) cm.postConcat(hueMatrix(hueDeg))
        if (brightness != 1f) {
            cm.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        brightness, 0f, 0f, 0f, 0f,
                        0f, brightness, 0f, 0f, 0f,
                        0f, 0f, brightness, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        if (contrast != 1f) {
            val o = 128f * (1f - contrast)
            cm.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, o,
                        0f, contrast, 0f, 0f, o,
                        0f, 0f, contrast, 0f, o,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        if (sepia > 0f) cm.postConcat(sepiaColorMatrix(sepia))

        val a = cm.array // float[20], row-major: out row r (R,G,B,A) × in col (R,G,B,A) + offset
        for (outc in 0 until 4) for (inc in 0 until 4) out[inc * 4 + outc] = a[outc * 5 + inc]
        for (outc in 0 until 3) out[3 * 4 + outc] += a[outc * 5 + 4] / 255f // offsets on the alpha-input column
        return out
    }

    /** Standard hue-rotation matrix about the luma axis. */
    private fun hueMatrix(deg: Float): android.graphics.ColorMatrix {
        val r = Math.toRadians(deg.toDouble())
        val cos = Math.cos(r).toFloat()
        val sin = Math.sin(r).toFloat()
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        return android.graphics.ColorMatrix(
            floatArrayOf(
                lr + cos * (1 - lr) + sin * (-lr), lg + cos * (-lg) + sin * (-lg), lb + cos * (-lb) + sin * (1 - lb), 0f, 0f,
                lr + cos * (-lr) + sin * (0.143f), lg + cos * (1 - lg) + sin * (0.140f), lb + cos * (-lb) + sin * (-0.283f), 0f, 0f,
                lr + cos * (-lr) + sin * (-(1 - lr)), lg + cos * (-lg) + sin * (lg), lb + cos * (1 - lb) + sin * (lb), 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    /** Blend identity → classic sepia weights by [amt] (0..1), as an [android.graphics.ColorMatrix]. */
    private fun sepiaColorMatrix(amt: Float): android.graphics.ColorMatrix {
        fun l(id: Float, sep: Float) = (1f - amt) * id + amt * sep
        return android.graphics.ColorMatrix(
            floatArrayOf(
                l(1f, 0.393f), l(0f, 0.769f), l(0f, 0.189f), 0f, 0f,
                l(0f, 0.349f), l(1f, 0.686f), l(0f, 0.168f), 0f, 0f,
                l(0f, 0.272f), l(0f, 0.534f), l(1f, 0.131f), 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
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
