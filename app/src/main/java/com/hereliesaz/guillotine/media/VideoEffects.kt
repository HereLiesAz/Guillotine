@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.media

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
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
 * preview and as static transforms in export v1; blur/sepia are approximated and
 * tracked as follow-ups.
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

        // Grayscale / invert are binary presets in Media3; apply past the midpoint.
        if (filters.grayscale >= 50f) {
            effects += RgbFilter.createGrayscaleFilter()
        }
        if (filters.invert >= 50f) {
            effects += RgbFilter.createInvertedFilter()
        }

        return effects
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
