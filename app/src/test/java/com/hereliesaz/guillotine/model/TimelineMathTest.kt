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
        kfs: List<Keyframe> = emptyList(),
        edits: List<EditSegment> = emptyList(),
    ) = TimelineClip(
        id = id, mediaId = "m", type = type, trackId = "V1",
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
}
