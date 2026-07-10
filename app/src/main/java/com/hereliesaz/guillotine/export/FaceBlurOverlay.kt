@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.graphics.Bitmap
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings

/**
 * Renders precomputed **face-blur patches** (transparent everywhere except a blurred, feathered copy
 * over each detected face) on top of the base video, anonymizing faces in the export. The patches are
 * computed off the render thread (see [Exporter.precomputeFaceBlur]) into [patches], keyed by timeline
 * bucket (`timelineMs / CACHE_MS`), so the encoder isn't stalled running ML Kit face detection per
 * frame. Mirrors [MatteOverlay]'s precompute-and-lookup approach.
 *
 * One instance is attached per base export item; [timelineStartMs] is that item's start on the
 * original timeline, so `timelineMs = timelineStartMs + presentationTimeUs/1000` stays correct even
 * after AI 'remove' ranges are physically cut.
 */
class FaceBlurOverlay(
    private val patches: Map<Long, Bitmap>,
    private val timelineStartMs: Long,
) : BitmapOverlay() {

    private val settings = StaticOverlaySettings.Builder().build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timelineMs = timelineStartMs + presentationTimeUs / 1000
        return patches[timelineMs / CACHE_MS] ?: SHARED_BLANK
    }

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings = settings

    companion object {
        const val CACHE_MS = 100L
        private val SHARED_BLANK: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
}
