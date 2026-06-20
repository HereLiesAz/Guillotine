@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.graphics.Bitmap
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings

/**
 * Renders the background-removed foreground (matte) over the base video. The mattes are
 * **precomputed off the render thread** (see [Exporter.precomputeMattes]) into [mattes], keyed by
 * timeline bucket (`timelineMs / CACHE_MS`), so the encoder isn't stalled running ML segmentation
 * per frame.
 *
 * One instance is attached per base export item; [timelineStartMs] is that item's start on the
 * original timeline, so `timelineMs = timelineStartMs + presentationTimeUs/1000` stays correct even
 * after AI 'remove' ranges are physically cut.
 */
class MatteOverlay(
    private val mattes: Map<Long, Bitmap>,
    private val timelineStartMs: Long,
) : BitmapOverlay() {

    private val blank: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val settings = StaticOverlaySettings.Builder().build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timelineMs = timelineStartMs + presentationTimeUs / 1000
        return mattes[timelineMs / CACHE_MS] ?: blank
    }

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings = settings

    companion object {
        const val CACHE_MS = 100L
    }
}
