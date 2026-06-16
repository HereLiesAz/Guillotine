@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.hereliesaz.guillotine.media.SubjectSegmenter
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.TimelineClip

/**
 * A Media3 [BitmapOverlay] that renders the **background-removed foreground** of the
 * upper-layer clips over the base (lower-layer) video, per frame. For each requested
 * presentation time it finds the active foreground clip, grabs that source frame, runs
 * on-device segmentation, and returns the matted bitmap (transparent where the background
 * was) so the layer below shows through.
 *
 * Note: this matters per frame on-device, so export is slow; results are bucketed/cached at
 * ~100 ms. Built for the canonical single-background composite — verify on device.
 */
class MatteOverlay(
    private val context: Context,
    private val foreground: List<TimelineClip>,
    private val mediaOf: (TimelineClip) -> MediaItem?,
    private val baseStartMs: Long,
) : BitmapOverlay() {

    private val blank: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val settings = OverlaySettings.Builder().build()
    private var cacheBucket = Long.MIN_VALUE
    private var cached: Bitmap = blank

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timelineMs = baseStartMs + presentationTimeUs / 1000
        val bucket = timelineMs / CACHE_MS
        if (bucket == cacheBucket) return cached
        cacheBucket = bucket

        val clip = foreground.lastOrNull { timelineMs >= it.startTimeMs && timelineMs < it.endTimeMs }
        cached = if (clip == null) {
            blank
        } else {
            val media = mediaOf(clip)
            if (media == null) {
                blank
            } else {
                val src = clip.trimStartMs + (timelineMs - clip.startTimeMs)
                SubjectSegmenter.cutoutBlocking(context, media.uri, media.kind, src) ?: blank
            }
        }
        return cached
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private companion object {
        const val CACHE_MS = 100L
    }
}
