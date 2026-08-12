package com.hereliesaz.guillotine.editor

import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the Vegas-style trim mechanics added to [EditorViewModel]: slip, slide, roll edit,
 * time-stretch, ripple-aware delete, and independent (L/J-cut) trims. No Android/device dependency —
 * [EditorViewModel] and [Document]/[TimelineClip] are pure Kotlin, so these run under a plain JVM test,
 * which is the only way any of this logic was verified (no emulator in this environment).
 */
class TimelineMechanicsTest {

    private val media = MediaItem(id = "m1", uri = "file:///a.mp4", name = "a.mp4", kind = MediaKind.VIDEO, durationMs = 10_000)

    private fun clip(id: String, trackId: String, startMs: Long, trimStartMs: Long, durationMs: Long, mediaId: String = "m1") =
        TimelineClip(id = id, mediaId = mediaId, type = ClipType.VIDEO, trackId = trackId, startTimeMs = startMs, trimStartMs = trimStartMs, durationMs = durationMs)

    private fun vmWith(vararg clips: TimelineClip, mediaItems: List<MediaItem> = listOf(media)): EditorViewModel {
        val vm = EditorViewModel()
        vm.loadDocument(Document(mediaItems = mediaItems, clips = clips.toList()))
        return vm
    }

    // ---- slip ----

    @Test
    fun `slip shifts source window without moving timeline position or duration`() {
        val vm = vmWith(clip("c1", "V1", startMs = 1000, trimStartMs = 2000, durationMs = 3000))
        vm.slipClip("c1", 500)
        val c = vm.uiState.value.document.clips.single()
        assertEquals(1000L, c.startTimeMs)
        assertEquals(3000L, c.durationMs)
        assertEquals(2500L, c.trimStartMs)
    }

    @Test
    fun `slip is bounded so the source window never leaves the source`() {
        val vm = vmWith(clip("c1", "V1", startMs = 0, trimStartMs = 0, durationMs = 3000))
        vm.slipClip("c1", -500) // can't slip before the start of the source
        assertEquals(0L, vm.uiState.value.document.clips.single().trimStartMs)

        val vm2 = vmWith(clip("c2", "V1", startMs = 0, trimStartMs = 6000, durationMs = 3000))
        vm2.slipClip("c2", 5000) // media is 10_000ms; max trimStart with a 3000ms window is 7000
        assertEquals(7000L, vm2.uiState.value.document.clips.single().trimStartMs)
    }

    // ---- slide ----

    @Test
    fun `slide moves the clip and trims the touching neighbor to make room`() {
        // c1 [0,2000) touching c2 [2000,5000) touching c3 [5000,7000)
        val vm = vmWith(
            clip("c1", "V1", 0, 0, 2000),
            clip("c2", "V1", 2000, 0, 3000),
            clip("c3", "V1", 5000, 0, 2000),
        )
        vm.slideClip("c2", 500) // slide c2 right by 500ms
        val byId = vm.uiState.value.document.clips.associateBy { it.id }
        assertEquals(2500L, byId["c2"]!!.startTimeMs)
        assertEquals(3000L, byId["c2"]!!.durationMs) // c2's own window is unchanged
        assertEquals(2000L, byId["c1"]!!.durationMs) // prev untouched (gap just grew)
        // next (c3) retreats its start by 500ms and shrinks by the same amount, keeping its OUT point
        assertEquals(5500L, byId["c3"]!!.startTimeMs)
        assertEquals(500L, byId["c3"]!!.trimStartMs)
        assertEquals(1500L, byId["c3"]!!.durationMs)
    }

    @Test
    fun `slide never shrinks a neighbor below the minimum duration`() {
        val vm = vmWith(
            clip("c1", "V1", 0, 0, 2000),
            clip("c2", "V1", 2000, 0, 500), // very short neighbor
        )
        vm.slideClip("c1", 10_000) // try to slide far past what c2 can absorb
        val byId = vm.uiState.value.document.clips.associateBy { it.id }
        assertTrue(byId["c2"]!!.durationMs >= 100L) // EditorViewModel's private MIN_CLIP_DURATION_MS
    }

    // ---- roll edit ----

    @Test
    fun `roll edit moves the shared boundary and keeps the combined span fixed`() {
        val vm = vmWith(
            clip("left", "V1", 0, 0, 2000),
            clip("right", "V1", 2000, 0, 3000),
        )
        vm.rollEdit("left", "right", 400)
        val byId = vm.uiState.value.document.clips.associateBy { it.id }
        assertEquals(2400L, byId["left"]!!.durationMs)
        assertEquals(2400L, byId["right"]!!.startTimeMs)
        assertEquals(2600L, byId["right"]!!.durationMs)
        // Combined span [0, left.end) + [right.start, right.end) covers the same total range as before.
        assertEquals(5000L, byId["right"]!!.startTimeMs + byId["right"]!!.durationMs)
    }

    // ---- time-stretch ----

    @Test
    fun `time-stretch preserves the total source span while changing duration and speed`() {
        val vm = vmWith(clip("c1", "V1", 0, 1000, 2000)) // 2000ms at speed 1 = 2000ms of source
        vm.timeStretchClip("c1", 4000) // stretch to double length -> half speed, same source span
        val c = vm.uiState.value.document.clips.single()
        assertEquals(4000L, c.durationMs)
        assertEquals(0.5f, c.filters.speed, 0.001f)
    }

    @Test
    fun `time-stretch anchorEnd keeps the end time fixed and moves the start instead`() {
        val vm = vmWith(clip("c1", "V1", startMs = 5000, trimStartMs = 0, durationMs = 2000)) // ends at 7000
        vm.timeStretchClip("c1", 4000, anchorEnd = true)
        val c = vm.uiState.value.document.clips.single()
        assertEquals(4000L, c.durationMs)
        assertEquals(3000L, c.startTimeMs)
        assertEquals(7000L, c.endTimeMs) // end time unchanged
    }

    @Test
    fun `time-stretch speed is bounded to the documented 0_1x to 10x range`() {
        val vm = vmWith(clip("c1", "V1", 0, 0, 10_000))
        vm.timeStretchClip("c1", 1) // absurdly short -> speed would blow past 10x
        val c = vm.uiState.value.document.clips.single()
        assertTrue(c.filters.speed <= 10f)
    }

    // ---- L/J-cut (independent trim) ----

    @Test
    fun `independent trim does not move the linked shadow when includeLinked is false`() {
        val video = clip("v1", "V1", 1000, 0, 3000).copy(groupId = "g1")
        val shadow = clip("a1", "A1", 1000, 0, 3000).copy(groupId = "g1", linkedClipId = "v1")
        val vm = vmWith(video, shadow)
        vm.trimClipStart("v1", 200, includeLinked = false)
        val byId = vm.uiState.value.document.clips.associateBy { it.id }
        assertEquals(1200L, byId["v1"]!!.startTimeMs) // video trimmed
        assertEquals(1000L, byId["a1"]!!.startTimeMs) // shadow untouched -> L/J cut
    }

    @Test
    fun `default trim still mirrors onto the linked shadow`() {
        val video = clip("v1", "V1", 1000, 0, 3000).copy(groupId = "g1")
        val shadow = clip("a1", "A1", 1000, 0, 3000).copy(groupId = "g1", linkedClipId = "v1")
        val vm = vmWith(video, shadow)
        vm.trimClipStart("v1", 200)
        val byId = vm.uiState.value.document.clips.associateBy { it.id }
        assertEquals(1200L, byId["v1"]!!.startTimeMs)
        assertEquals(1200L, byId["a1"]!!.startTimeMs)
    }

    // ---- auto-ripple delete ----

    @Test
    fun `delete without auto-ripple leaves a gap`() {
        val vm = vmWith(
            clip("c1", "V1", 0, 0, 1000),
            clip("c2", "V1", 1000, 0, 1000),
            clip("c3", "V1", 2000, 0, 1000),
        )
        vm.selectClip("c2")
        vm.deleteSelected()
        val c3 = vm.uiState.value.document.clips.single { it.id == "c3" }
        assertEquals(2000L, c3.startTimeMs) // gap left behind
    }

    @Test
    fun `delete with auto-ripple closes the gap on the affected track only`() {
        val vm = vmWith(
            clip("v1", "V1", 1000, 0, 1000),
            clip("v2", "V1", 2000, 0, 1000),
            clip("a1", "A1", 1000, 0, 3000), // different track, must not shift
        )
        vm.toggleAutoRipple()
        vm.selectClip("v1")
        vm.deleteSelected()
        val byId = vm.uiState.value.document.clips.associateBy { it.id }
        assertEquals(1000L, byId["v2"]!!.startTimeMs) // rippled left by the deleted clip's 1000ms
        assertEquals(1000L, byId["a1"]!!.startTimeMs) // untouched: different track
    }

    // ---- snap toggle ----

    @Test
    fun `snap toggle flips the shared editor state`() {
        val vm = EditorViewModel()
        assertTrue(vm.uiState.value.snapEnabled)
        vm.toggleSnap()
        assertTrue(!vm.uiState.value.snapEnabled)
    }

    // ---- track solo ----

    private fun vmWithTracks(videoTracks: List<String>, vararg clips: TimelineClip): EditorViewModel {
        val vm = EditorViewModel()
        vm.loadDocument(Document(mediaItems = listOf(media), clips = clips.toList(), videoTracks = videoTracks, audioTracks = emptyList()))
        return vm
    }

    @Test
    fun `solo hides every other track from preview but export is unaffected`() {
        val vm = vmWithTracks(
            listOf("V1", "V2"),
            clip("v1", "V1", 0, 0, 1000),
            clip("v2", "V2", 0, 0, 1000),
        )
        vm.toggleTrackSolo("V1")
        val st = vm.uiState.value
        assertTrue("V1" !in st.effectivePreviewDisabledTrackIds)
        assertTrue("V2" in st.effectivePreviewDisabledTrackIds)
        // Export reads Document.disabledTrackIds directly, never the UI-only solo set.
        assertTrue(st.document.disabledTrackIds.isEmpty())
    }

    @Test
    fun `solo is additive and empty when nothing is soloed`() {
        val vm = vmWithTracks(
            listOf("V1", "V2"),
            clip("v1", "V1", 0, 0, 1000),
            clip("v2", "V2", 0, 0, 1000),
        )
        assertTrue(vm.uiState.value.effectivePreviewDisabledTrackIds.isEmpty())
        vm.toggleTrackSolo("V1")
        vm.toggleTrackSolo("V2")
        assertTrue(vm.uiState.value.effectivePreviewDisabledTrackIds.isEmpty()) // both soloed = same as none
        vm.toggleTrackSolo("V1")
        assertEquals(setOf("V1"), vm.uiState.value.effectivePreviewDisabledTrackIds) // only V2 left soloed
    }

    @Test
    fun `track name and color are independently settable`() {
        val vm = vmWith(clip("v1", "V1", 0, 0, 1000))
        vm.setTrackName("V1", "Interview")
        vm.setTrackColor("V1", "#FF8800")
        val ts = vm.uiState.value.document.trackSettingsFor("V1")
        assertEquals("Interview", ts.name)
        assertEquals("#FF8800", ts.colorHex)
    }
}
