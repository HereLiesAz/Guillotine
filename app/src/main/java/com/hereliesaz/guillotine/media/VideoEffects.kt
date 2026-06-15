@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.media

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import com.hereliesaz.guillotine.model.ClipFilters

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
}
