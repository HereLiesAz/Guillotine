package com.hereliesaz.guillotine.editor

import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for looped playback + playback-region bounds in [EditorViewModel.advancePlayhead]. */
class EditorPlaybackTest {

    private fun vmWithTimeline(totalMs: Long): EditorViewModel {
        val clip = TimelineClip(
            id = "a", mediaId = "m", type = ClipType.VIDEO, trackId = "V1",
            startTimeMs = 0, trimStartMs = 0, durationMs = totalMs,
        )
        return EditorViewModel().apply { loadDocument(Document(clips = listOf(clip))) }
    }

    @Test fun loopOff_stopsAtEndOfTimeline() {
        val vm = vmWithTimeline(1000)
        vm.seekTo(900)
        vm.setPlaying(true)
        vm.advancePlayhead(200) // 900 + 200 = 1100 >= 1000
        assertEquals(1000L, vm.uiState.value.currentTimeMs)
        assertFalse(vm.uiState.value.isPlaying)
    }

    @Test fun loopOn_wrapsToTimelineStart() {
        val vm = vmWithTimeline(1000)
        vm.toggleLoop()
        vm.seekTo(900)
        vm.setPlaying(true)
        vm.advancePlayhead(200)
        assertEquals(0L, vm.uiState.value.currentTimeMs)
        assertTrue("looping keeps playing", vm.uiState.value.isPlaying)
    }

    @Test fun region_boundsPlayback_loopOffStopsAtRegionEnd() {
        val vm = vmWithTimeline(5000)
        vm.setPlaybackRegion(1000, 2000)
        vm.seekTo(1900)
        vm.setPlaying(true)
        vm.advancePlayhead(200) // 2100 >= region end 2000
        assertEquals(2000L, vm.uiState.value.currentTimeMs)
        assertFalse(vm.uiState.value.isPlaying)
    }

    @Test fun region_loopOnWrapsToRegionStart() {
        val vm = vmWithTimeline(5000)
        vm.setPlaybackRegion(1000, 2000)
        vm.toggleLoop()
        vm.seekTo(1900)
        vm.setPlaying(true)
        vm.advancePlayhead(200)
        assertEquals(1000L, vm.uiState.value.currentTimeMs)
        assertTrue(vm.uiState.value.isPlaying)
    }

    @Test fun setPlaybackRegion_normalizesReversedBoundsAndClampsToTimeline() {
        val vm = vmWithTimeline(3000)
        vm.setPlaybackRegion(2500, 500) // reversed + within timeline
        assertEquals(500L..2500L, vm.uiState.value.playbackRegion)
        vm.setPlaybackRegion(1000, 99999) // hi beyond timeline clamps to total
        assertEquals(1000L..3000L, vm.uiState.value.playbackRegion)
    }

    @Test fun setPlaybackRegion_tinySpanClearsRegion() {
        val vm = vmWithTimeline(3000)
        vm.setPlaybackRegion(1000, 2000)
        assertTrue(vm.uiState.value.playbackRegion != null)
        vm.setPlaybackRegion(1000, 1010) // < MIN_REGION_MS span
        assertNull(vm.uiState.value.playbackRegion)
    }

    @Test fun clearPlaybackRegion_removesRegion() {
        val vm = vmWithTimeline(3000)
        vm.setPlaybackRegion(1000, 2000)
        vm.clearPlaybackRegion()
        assertNull(vm.uiState.value.playbackRegion)
    }
}
