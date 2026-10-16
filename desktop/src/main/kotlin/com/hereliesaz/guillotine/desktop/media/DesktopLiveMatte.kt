package com.hereliesaz.guillotine.desktop.media

import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background removal and bokeh during PLAYBACK. Segmentation is far too slow to run on every frame,
 * so each clip keeps its most recent mask and applies it to the frames that follow, which costs only
 * a per-pixel alpha copy. Meanwhile one background thread re-segments the latest frame whenever the
 * cached mask is more than [REFRESH_MS] of source time old, so the matte keeps up with a moving
 * subject after a short lag. Until a clip's first mask arrives, its frames play un-matted.
 *
 * Paused and scrubbed frames still segment synchronously (exact matte). Export always segments every
 * frame. This is a preview approximation only.
 */
object DesktopLiveMatte {

    private const val REFRESH_MS = 120L

    /** Most clips whose masks are kept; older ones are dropped (a mask is a full-frame float plane). */
    private const val MAX_CLIPS = 8

    private class Cached(val sourceMs: Long, val modelPath: String, val mask: DesktopSegmenter.Mask)

    // Access-ordered LRU, bounded by MAX_CLIPS, so masks of deleted/no-longer-playing clips don't pile up.
    private val masks = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > MAX_CLIPS
        },
    )
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "live-matte").apply { isDaemon = true } }

    /**
     * [img] with the clip's latest mask applied (matte, or bokeh when [bokeh]); [img] unchanged while no
     * mask exists yet. May schedule a background refresh from a copy of [img].
     */
    fun apply(clipId: String, img: BufferedImage, sourceMs: Long, modelPath: String, bokeh: Boolean): BufferedImage {
        // A mask made by a different model (the setting changed) is discarded, not reused.
        val cached = masks[clipId]?.takeIf { it.modelPath == modelPath }
        val stale = cached == null || kotlin.math.abs(sourceMs - cached.sourceMs) > REFRESH_MS
        if (stale && busy.compareAndSet(false, true)) {
            val copy = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also {
                it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
            }
            worker.execute {
                try {
                    runCatching { DesktopSegmenter.mask(copy, modelPath) }.getOrNull()
                        ?.let { masks[clipId] = Cached(sourceMs, modelPath, it) }
                } finally {
                    busy.set(false)
                }
            }
        }
        val mask = cached?.mask ?: return img
        return if (bokeh) DesktopSegmenter.portraitBlurWith(img, mask) else DesktopSegmenter.applyMask(img, mask)
    }

    /** Drop a clip's cached mask (e.g. after a seek far away), so stale shapes don't linger. */
    fun forget(clipId: String) {
        masks.remove(clipId)
    }

    /** Drop masks for every clip not in [clipIds] (called when the document changes). */
    fun retainOnly(clipIds: Set<String>) {
        synchronized(masks) { masks.keys.retainAll(clipIds) }
    }
}
