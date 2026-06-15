package com.hereliesaz.guillotine.model

import kotlin.math.abs

/**
 * Pure timeline math shared by the live preview and the exporter so both behave
 * identically. No Android dependencies — unit-testable.
 */
object TimelineMath {

    /** X component of the cubic bezier at parameter t (endpoints fixed at 0 and 1). */
    private fun bezierAxis(p1: Float, p2: Float, t: Float): Float {
        val mt = 1f - t
        // 0*(mt^3) + p1*3*mt^2*t + p2*3*mt*t^2 + 1*t^3
        return 3f * mt * mt * t * p1 + 3f * mt * t * t * p2 + t * t * t
    }

    /**
     * CSS-style cubic-bezier timing function: given input progress [x] in [0,1],
     * solve for the curve parameter t (via bisection) and return the eased y.
     */
    fun CubicBezier.ease(x: Float): Float {
        val clampedX = x.coerceIn(0f, 1f)
        var lo = 0f
        var hi = 1f
        var t = clampedX
        repeat(24) {
            val xAtT = bezierAxis(x1, x2, t)
            if (abs(xAtT - clampedX) < 1e-4f) return bezierAxis(y1, y2, t)
            if (xAtT < clampedX) lo = t else hi = t
            t = (lo + hi) * 0.5f
        }
        return bezierAxis(y1, y2, t)
    }

    /**
     * Interpolated value of [property] at [clipTimeMs] (ms from clip start),
     * honoring per-segment bezier easing. Returns [default] when there are no
     * keyframes for the property.
     */
    fun valueAt(
        clip: TimelineClip,
        property: KeyframeProperty,
        clipTimeMs: Long,
        default: Float,
    ): Float {
        val kfs = clip.keyframes.filter { it.property == property }.sortedBy { it.timeMs }
        if (kfs.isEmpty()) return default
        if (clipTimeMs <= kfs.first().timeMs) return kfs.first().value
        if (clipTimeMs >= kfs.last().timeMs) return kfs.last().value

        for (i in 0 until kfs.size - 1) {
            val a = kfs[i]
            val b = kfs[i + 1]
            if (clipTimeMs in a.timeMs..b.timeMs) {
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                val progress = (clipTimeMs - a.timeMs).toFloat() / span
                val eased = a.easing.ease(progress)
                return a.value + (b.value - a.value) * eased
            }
        }
        return kfs.last().value
    }

    /** Convert a timeline time to the corresponding source-media time for [clip]. */
    fun sourceTimeMs(clip: TimelineClip, timelineMs: Long): Long =
        clip.trimStartMs + (timelineMs - clip.startTimeMs)

    /**
     * The clip of [type] that should be shown/heard at [timelineMs]. When clips
     * overlap, the last one (topmost / most recently added) wins, which is
     * deterministic unlike a plain first-match.
     */
    fun activeClip(clips: List<TimelineClip>, type: ClipType, timelineMs: Long): TimelineClip? =
        clips.lastOrNull { it.type == type && timelineMs >= it.startTimeMs && timelineMs < it.endTimeMs }

    /**
     * True if the given source-media time falls inside a 'remove' segment of the
     * clip. Used to skip removed ranges during preview/export.
     */
    fun isRemoved(clip: TimelineClip, sourceMs: Long): Boolean =
        clip.edits.any { it.action == EditAction.REMOVE && sourceMs >= it.startMs && sourceMs < it.endMs }

    /**
     * The 'keep' ranges of a clip in source-media ms. If the clip has no edits,
     * the whole trimmed range is kept. Used to build export cut lists.
     */
    fun keptRanges(clip: TimelineClip): List<LongRange> {
        val clipStart = clip.trimStartMs
        val clipEnd = clip.trimStartMs + clip.durationMs
        val removes = clip.edits
            .filter { it.action == EditAction.REMOVE }
            .map { it.startMs.coerceAtLeast(clipStart)..it.endMs.coerceAtMost(clipEnd) }
            .filter { it.first < it.last }
            .sortedBy { it.first }
        if (removes.isEmpty()) return listOf(clipStart until clipEnd)

        val kept = mutableListOf<LongRange>()
        var cursor = clipStart
        for (r in removes) {
            if (r.first > cursor) kept.add(cursor until r.first)
            cursor = maxOf(cursor, r.last)
        }
        if (cursor < clipEnd) kept.add(cursor until clipEnd)
        return kept
    }
}
