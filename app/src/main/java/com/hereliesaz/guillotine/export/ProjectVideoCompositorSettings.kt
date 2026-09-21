@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.StaticOverlaySettings
import com.hereliesaz.guillotine.model.ProjectCanvasSize

/**
 * Makes the project frame an output canvas rather than a transform applied to every input layer.
 *
 * Inputs stay centered at identity compositor scale; clip scale/pan/rotation are already applied by
 * the clip's own effects. Changing project aspect therefore changes only [getOutputSize].
 */
class ProjectVideoCompositorSettings(
    private val canvas: ProjectCanvasSize,
) : VideoCompositorSettings {
    private val identity = StaticOverlaySettings.Builder().build()

    override fun getOutputSize(inputSizes: MutableList<Size>): Size =
        Size(canvas.width, canvas.height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
        identity
}
