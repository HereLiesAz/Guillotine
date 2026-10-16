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
    ): Float = interpolateSorted(
        clip.keyframes.filter { it.property == property }.sortedBy { it.timeMs },
        clipTimeMs,
        default,
    )

    /**
     * Interpolated value over a **pre-filtered, time-sorted** keyframe list [kfs] (all for one
     * property). Allocation-free — call this from hot per-frame/per-sample paths where the caller
     * has filtered+sorted once up front, instead of [valueAt] which re-filters and re-sorts on every
     * call. Returns [default] when [kfs] is empty.
     */
    fun interpolateSorted(kfs: List<Keyframe>, clipTimeMs: Long, default: Float): Float {
        if (kfs.isEmpty()) return default
        if (clipTimeMs <= kfs.first().timeMs) return kfs.first().value
        if (clipTimeMs >= kfs.last().timeMs) return kfs.last().value

        for (i in 0 until kfs.size - 1) {
            val a = kfs[i]
            val b = kfs[i + 1]
            if (clipTimeMs in a.timeMs..b.timeMs) {
                if (a.hold) return a.value
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                val progress = (clipTimeMs - a.timeMs).toFloat() / span
                val eased = a.easing.ease(progress)
                return a.value + (b.value - a.value) * eased
            }
        }
        return kfs.last().value
    }

    /**
     * Convert a timeline time to the corresponding source-media time for [clip], honouring
     * [ClipFilters.speed] and SPEED keyframes. Constant stretches (no keyframes, before the first /
     * after the last keyframe, hold or flat segments) integrate in O(1); only eased ramps between two
     * different speeds are sampled numerically (10 ms steps), so a long clip no longer costs
     * O(length / 10 ms) per frame.
     */
    fun sourceTimeMs(clip: TimelineClip, timelineMs: Long): Long {
        val relMs = (timelineMs - clip.startTimeMs).coerceAtLeast(0L)
        val speedKfs = clip.keyframes.filter { it.property == KeyframeProperty.SPEED }.sortedBy { it.timeMs }
        if (speedKfs.isEmpty()) {
            if (clip.filters.speed == 1f) return clip.trimStartMs + relMs
            return clip.trimStartMs + (clip.filters.speed.toDouble() * relMs).toLong()
        }

        var sourceAdvance = 0.0
        // Before the first keyframe the curve holds its first value.
        var t = speedKfs.first().timeMs.coerceIn(0L, relMs)
        sourceAdvance += speedKfs.first().value.toDouble() * t
        for (i in 0 until speedKfs.size - 1) {
            val a = speedKfs[i]
            val b = speedKfs[i + 1]
            val segEnd = b.timeMs.coerceAtMost(relMs)
            if (segEnd <= t) continue
            if (a.hold || a.value == b.value) {
                sourceAdvance += a.value.toDouble() * (segEnd - t)
                t = segEnd
                continue
            }
            val step = 10L
            while (t < segEnd) {
                val dt = if (t + step > segEnd) segEnd - t else step
                sourceAdvance += interpolateSorted(speedKfs, t + dt / 2, clip.filters.speed) * dt
                t += dt
            }
        }
        // After the last keyframe the curve holds its last value.
        if (t < relMs) sourceAdvance += speedKfs.last().value.toDouble() * (relMs - t)
        return clip.trimStartMs + sourceAdvance.toLong()
    }

    /** Source-media time at the clip's out point — `trimStartMs + durationMs` only at 1x speed. */
    fun sourceEndMs(clip: TimelineClip): Long = sourceTimeMs(clip, clip.endTimeMs)

    /** The clip's playback speed at [relMs] (ms from clip start): its SPEED curve, else [ClipFilters.speed]. */
    fun speedAt(clip: TimelineClip, relMs: Long): Float {
        val kfs = clip.keyframes.filter { it.property == KeyframeProperty.SPEED }.sortedBy { it.timeMs }
        return interpolateSorted(kfs, relMs, clip.filters.speed)
    }

    /**
     * Inverse of [sourceTimeMs]: the smallest clip-relative timeline offset at which [clip] reaches
     * source time [sourceMs] (0 when [sourceMs] is at or before [TimelineClip.trimStartMs]). Not bounded
     * by the clip's duration — the speed curve holds past its end, as [sourceTimeMs] does — so callers
     * clamp to their own range.
     */
    fun timelineRelMs(clip: TimelineClip, sourceMs: Long): Long {
        val delta = sourceMs - clip.trimStartMs
        if (delta <= 0L) return 0L
        val hasCurve = clip.keyframes.any { it.property == KeyframeProperty.SPEED }
        if (!hasCurve) {
            if (clip.filters.speed == 1f) return delta
            return kotlin.math.ceil(delta / clip.filters.speed.toDouble()).toLong()
        }
        // Speed is always positive, so source time is monotonic in timeline time: bracket, then bisect.
        var hi = 1L
        while (sourceTimeMs(clip, clip.startTimeMs + hi) < sourceMs && hi < Long.MAX_VALUE / 4) hi *= 2
        var lo = 0L
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (sourceTimeMs(clip, clip.startTimeMs + mid) >= sourceMs) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * Apply a clip's frame-decimation ([ClipFilters.frameStep]) to a source-media time. When frameStep
     * is > 1, snaps [sourceMs] DOWN to the nearest kept source frame so the same picture is held for
     * `frameStep` output frames — a choppy/stutter look at the SAME duration (audio, which never routes
     * through here, stays in sync). [frameDurationMs] is one output frame's length in ms (`1000 / projectFps`).
     * Returns [sourceMs] unchanged when frameStep <= 1 or [frameDurationMs] is non-positive.
     *
     * The kept-frame grid is anchored at the clip's [TimelineClip.trimStartMs] so it is stable regardless
     * of where the clip sits on the timeline (and independent of any prior speed remap already folded into
     * [sourceMs]). Used by the desktop frame-grab preview/export; Android achieves the same via a Media3
     * FrameDropEffect targeting `projectFps / frameStep`.
     */
    fun decimateSourceMs(clip: TimelineClip, sourceMs: Long, frameDurationMs: Double): Long {
        val step = clip.filters.frameStep
        if (step <= 1 || frameDurationMs <= 0.0) return sourceMs
        val rel = (sourceMs - clip.trimStartMs).coerceAtLeast(0L)
        val frameIdx = (rel / frameDurationMs).toLong()
        val keptIdx = (frameIdx / step) * step
        return clip.trimStartMs + (keptIdx * frameDurationMs).toLong()
    }

    /**
     * The clip of [type] that should be shown/heard at [timelineMs]. When clips
     * overlap, the last one (topmost / most recently added) wins, which is
     * deterministic unlike a plain first-match.
     */
    fun activeClip(clips: List<TimelineClip>, type: ClipType, timelineMs: Long): TimelineClip? =
        clips.lastOrNull { it.type == type && timelineMs >= it.startTimeMs && timelineMs < it.endTimeMs }

    /**
     * Layer-aware variant: among clips of [type] active at [timelineMs], returns the one
     * on the topmost track (earliest in [trackOrder], which is top-of-panel = top layer).
     */
    fun topActiveClip(
        clips: List<TimelineClip>,
        type: ClipType,
        timelineMs: Long,
        trackOrder: List<String>,
    ): TimelineClip? = clips
        .filter { it.type == type && timelineMs >= it.startTimeMs && timelineMs < it.endTimeMs }
        .minByOrNull { trackOrder.indexOf(it.trackId).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    /** All clips of [type] active at [timelineMs] (e.g. overlapping text/caption clips). */
    fun activeClips(clips: List<TimelineClip>, type: ClipType, timelineMs: Long): List<TimelineClip> =
        clips.filter { it.type == type && timelineMs >= it.startTimeMs && timelineMs < it.endTimeMs }

    /**
     * True if the given source-media time falls inside a 'remove' segment of the
     * clip. Used to skip removed ranges during preview/export.
     */
    fun isRemoved(clip: TimelineClip, sourceMs: Long): Boolean =
        clip.edits.any { it.action == EditAction.REMOVE && sourceMs >= it.startMs && sourceMs < it.endMs }

    /**
     * The source time the **preview** should show for [clip] at [timelineMs] — [sourceTimeMs], advanced
     * past any AI-edit REMOVE it lands in.
     *
     * Export cuts those ranges ([keptRanges]) and preview used to play straight through them, so the
     * preview showed footage the rendered file would not contain. A preview that doesn't show what will
     * be rendered isn't a preview, so playback now skips a removed range the way the export drops it.
     *
     * Returns the raw source time when the clip has no removes, so the common case is untouched.
     */
    fun previewSourceTimeMs(clip: TimelineClip, timelineMs: Long): Long {
        val raw = sourceTimeMs(clip, timelineMs)
        if (clip.edits.none { it.action == EditAction.REMOVE }) return raw
        val kept = keptRanges(clip)
        if (kept.isEmpty()) return raw
        // The first kept range that still lies at or ahead of `raw`: inside it, keep `raw`; before it,
        // jump to where the kept footage resumes.
        for (r in kept) {
            if (raw <= r.last) return maxOf(raw, r.first)
        }
        // Past every kept range — hold at the end of the last one rather than showing removed tail.
        return kept.last().last
    }

    /**
     * The 'keep' ranges of a clip in source-media ms. If the clip has no edits,
     * the whole trimmed range is kept. Used to build export cut lists. The range ends at
     * [sourceEndMs], so a sped-up or slowed clip covers the source it actually plays.
     */
    fun keptRanges(clip: TimelineClip): List<LongRange> {
        val clipStart = clip.trimStartMs
        val clipEnd = sourceEndMs(clip)
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
