package com.hereliesaz.guillotine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the shared timeline math (no Android dependencies). */
class TimelineMathTest {

    private fun clip(
        start: Long,
        trim: Long,
        dur: Long,
        id: String = "c",
        type: ClipType = ClipType.VIDEO,
        trackId: String = "V1",
        kfs: List<Keyframe> = emptyList(),
        edits: List<EditSegment> = emptyList(),
    ) = TimelineClip(
        id = id, mediaId = "m", type = type, trackId = trackId,
        startTimeMs = start, trimStartMs = trim, durationMs = dur,
        keyframes = kfs, edits = edits,
    )

    private val linear = CubicBezier(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f)

    @Test fun bezier_endpoints() = with(TimelineMath) {
        val b = CubicBezier()
        assertEquals(0f, b.ease(0f), 0.02f)
        assertEquals(1f, b.ease(1f), 0.02f)
    }

    @Test fun bezier_linear_is_identity() = with(TimelineMath) {
        assertEquals(0.5f, linear.ease(0.5f), 0.02f)
        assertEquals(0.25f, linear.ease(0.25f), 0.03f)
    }

    @Test fun valueAt_returns_default_without_keyframes() {
        val c = clip(0, 0, 1000)
        assertEquals(0.7f, TimelineMath.valueAt(c, KeyframeProperty.OPACITY, 500, 0.7f), 0f)
    }

    @Test fun valueAt_clamps_and_interpolates() {
        val kfs = listOf(
            Keyframe("a", 0, 0f, KeyframeProperty.OPACITY, linear),
            Keyframe("b", 1000, 1f, KeyframeProperty.OPACITY, linear),
        )
        val c = clip(0, 0, 1000, kfs = kfs)
        assertEquals(0f, TimelineMath.valueAt(c, KeyframeProperty.OPACITY, -10, 9f), 0.001f)
        assertEquals(1f, TimelineMath.valueAt(c, KeyframeProperty.OPACITY, 2000, 9f), 0.001f)
        assertEquals(0.5f, TimelineMath.valueAt(c, KeyframeProperty.OPACITY, 500, 9f), 0.03f)
    }

    @Test fun keptRanges_whole_when_no_edits() {
        val r = TimelineMath.keptRanges(clip(0, 200, 1000))
        assertEquals(1, r.size)
        assertEquals(200L, r[0].first)
        assertEquals(1199L, r[0].last)
    }

    @Test fun keptRanges_splits_around_remove() {
        val c = clip(0, 0, 10000, edits = listOf(EditSegment(3000, 5000, EditAction.REMOVE)))
        val r = TimelineMath.keptRanges(c)
        assertEquals(2, r.size)
        assertEquals(0L, r[0].first); assertEquals(2999L, r[0].last)
        assertEquals(5000L, r[1].first); assertEquals(9999L, r[1].last)
    }

    @Test fun activeClip_last_wins_on_overlap() {
        val a = clip(0, 0, 5000, id = "a")
        val b = clip(0, 0, 5000, id = "b")
        assertEquals("b", TimelineMath.activeClip(listOf(a, b), ClipType.VIDEO, 1000)?.id)
        assertEquals(null, TimelineMath.activeClip(listOf(a, b), ClipType.VIDEO, 6000))
    }

    @Test fun sourceTime_maps_correctly() {
        assertEquals(1500L, TimelineMath.sourceTimeMs(clip(2000, 500, 4000), 3000))
    }

    @Test fun isRemoved_detects_range() {
        val c = clip(0, 0, 10000, edits = listOf(EditSegment(1000, 2000, EditAction.REMOVE)))
        assertTrue(TimelineMath.isRemoved(c, 1500))
        assertFalse(TimelineMath.isRemoved(c, 2500))
    }

    // ---- valueAt: multi-keyframe, sorting, property isolation, easing shape ----

    @Test fun valueAt_three_keyframes_picks_correct_segment() {
        val kfs = listOf(
            Keyframe("a", 0, 0f, KeyframeProperty.SCALE, linear),
            Keyframe("b", 1000, 1f, KeyframeProperty.SCALE, linear),
            Keyframe("c", 2000, 0f, KeyframeProperty.SCALE, linear),
        )
        val c = clip(0, 0, 2000, kfs = kfs)
        assertEquals(0.5f, TimelineMath.valueAt(c, KeyframeProperty.SCALE, 500, 9f), 0.03f)   // rising
        assertEquals(1f, TimelineMath.valueAt(c, KeyframeProperty.SCALE, 1000, 9f), 0.001f)   // middle kf
        assertEquals(0.5f, TimelineMath.valueAt(c, KeyframeProperty.SCALE, 1500, 9f), 0.03f)  // falling
    }

    @Test fun valueAt_sorts_unordered_keyframes() {
        val kfs = listOf(
            Keyframe("b", 1000, 1f, KeyframeProperty.OPACITY, linear),
            Keyframe("a", 0, 0f, KeyframeProperty.OPACITY, linear),
        )
        val c = clip(0, 0, 1000, kfs = kfs)
        assertEquals(0.5f, TimelineMath.valueAt(c, KeyframeProperty.OPACITY, 500, 9f), 0.03f)
    }

    @Test fun valueAt_ignores_other_properties() {
        val kfs = listOf(
            Keyframe("a", 0, 0.2f, KeyframeProperty.VOLUME, linear),
            Keyframe("b", 1000, 0.8f, KeyframeProperty.VOLUME, linear),
        )
        val c = clip(0, 0, 1000, kfs = kfs)
        // No OPACITY keyframes among the VOLUME ones → falls back to default.
        assertEquals(0.55f, TimelineMath.valueAt(c, KeyframeProperty.OPACITY, 500, 0.55f), 0f)
    }

    @Test fun valueAt_easeIn_is_below_linear_at_midpoint() {
        val easeIn = CubicBezier(0.42f, 0f, 1f, 1f) // CSS ease-in: slow start
        val kfs = listOf(
            Keyframe("a", 0, 0f, KeyframeProperty.OPACITY, easeIn),
            Keyframe("b", 1000, 1f, KeyframeProperty.OPACITY, easeIn),
        )
        val c = clip(0, 0, 1000, kfs = kfs)
        val v = TimelineMath.valueAt(c, KeyframeProperty.OPACITY, 500, 9f)
        assertTrue("ease-in midpoint should be below linear 0.5 but was $v", v < 0.45f)
    }

    // ---- topActiveClip: track-order tie-break + unknown-track fallback ----

    @Test fun topActiveClip_prefers_earlier_track_in_order() {
        val top = clip(0, 0, 5000, id = "top", trackId = "V1")
        val bottom = clip(0, 0, 5000, id = "bottom", trackId = "V2")
        val order = listOf("V1", "V2")
        assertEquals("top", TimelineMath.topActiveClip(listOf(bottom, top), ClipType.VIDEO, 1000, order)?.id)
    }

    @Test fun topActiveClip_unknown_track_sorts_last() {
        val known = clip(0, 0, 5000, id = "known", trackId = "V2")
        val unknown = clip(0, 0, 5000, id = "unknown", trackId = "VX")
        val order = listOf("V1", "V2")
        assertEquals("known", TimelineMath.topActiveClip(listOf(unknown, known), ClipType.VIDEO, 1000, order)?.id)
    }

    @Test fun topActiveClip_null_when_none_active() {
        val c = clip(0, 0, 1000, trackId = "V1")
        assertEquals(null, TimelineMath.topActiveClip(listOf(c), ClipType.VIDEO, 5000, listOf("V1")))
    }

    // ---- activeClips: all overlaps, exclusive end boundary ----

    @Test fun activeClips_returns_all_overlapping() {
        val a = clip(0, 0, 5000, id = "a")
        val b = clip(1000, 0, 5000, id = "b")
        assertEquals(2, TimelineMath.activeClips(listOf(a, b), ClipType.VIDEO, 2000).size)
        assertEquals(listOf("a"), TimelineMath.activeClips(listOf(a, b), ClipType.VIDEO, 500).map { it.id })
    }

    @Test fun activeClips_excludes_end_boundary() {
        val a = clip(0, 0, 1000, id = "a")
        assertTrue(TimelineMath.activeClips(listOf(a), ClipType.VIDEO, 1000).isEmpty())
        assertEquals(1, TimelineMath.activeClips(listOf(a), ClipType.VIDEO, 999).size)
    }

    // ---- keptRanges: overlapping/adjacent removes, whole-clip remove, trim coercion ----

    @Test fun keptRanges_overlapping_removes_merge() {
        val c = clip(
            0, 0, 10000,
            edits = listOf(
                EditSegment(2000, 5000, EditAction.REMOVE),
                EditSegment(4000, 6000, EditAction.REMOVE),
            ),
        )
        val r = TimelineMath.keptRanges(c)
        assertEquals(2, r.size)
        assertEquals(0L until 2000L, r[0])
        assertEquals(6000L until 10000L, r[1])
    }

    @Test fun keptRanges_adjacent_removes() {
        val c = clip(
            0, 0, 10000,
            edits = listOf(
                EditSegment(2000, 4000, EditAction.REMOVE),
                EditSegment(4000, 6000, EditAction.REMOVE),
            ),
        )
        val r = TimelineMath.keptRanges(c)
        assertEquals(2, r.size)
        assertEquals(0L until 2000L, r[0])
        assertEquals(6000L until 10000L, r[1])
    }

    @Test fun keptRanges_remove_spanning_whole_clip_is_empty() {
        val c = clip(0, 0, 5000, edits = listOf(EditSegment(0, 5000, EditAction.REMOVE)))
        assertTrue(TimelineMath.keptRanges(c).isEmpty())
    }

    @Test fun keptRanges_respects_trim_offset() {
        // trimStart=1000, dur=4000 → kept window [1000,5000); remove [2000,3000).
        val c = clip(0, 1000, 4000, edits = listOf(EditSegment(2000, 3000, EditAction.REMOVE)))
        val r = TimelineMath.keptRanges(c)
        assertEquals(2, r.size)
        assertEquals(1000L until 2000L, r[0])
        assertEquals(3000L until 5000L, r[1])
    }

    @Test fun keptRanges_remove_outside_trim_is_ignored() {
        // Remove sits entirely before the trimmed window → whole window kept.
        val c = clip(0, 2000, 3000, edits = listOf(EditSegment(0, 1000, EditAction.REMOVE)))
        val r = TimelineMath.keptRanges(c)
        assertEquals(1, r.size)
        assertEquals(2000L until 5000L, r[0])
    }
}
