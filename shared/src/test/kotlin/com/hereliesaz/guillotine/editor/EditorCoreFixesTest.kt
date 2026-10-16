package com.hereliesaz.guillotine.editor

import com.hereliesaz.guillotine.model.BeatMap
import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.FxLayer
import com.hereliesaz.guillotine.model.Keyframe
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the shared-core editor fixes (ripple, speed, keyframes, undo, project load). */
class EditorCoreFixesTest {

    private val media = MediaItem(id = "m1", uri = "file:///a.mp4", name = "a.mp4", kind = MediaKind.VIDEO, durationMs = 60_000)

    private fun clip(
        id: String,
        startMs: Long,
        durationMs: Long,
        trimStartMs: Long = 0,
        trackId: String = "V1",
        speed: Float = 1f,
        keyframes: List<Keyframe> = emptyList(),
    ) = TimelineClip(
        id = id, mediaId = "m1", type = ClipType.VIDEO, trackId = trackId, startTimeMs = startMs,
        trimStartMs = trimStartMs, durationMs = durationMs, filters = ClipFilters(speed = speed), keyframes = keyframes,
    )

    private fun vmWith(vararg clips: TimelineClip): EditorViewModel =
        EditorViewModel().apply { loadDocument(Document(mediaItems = listOf(media), clips = clips.toList())) }

    private fun EditorViewModel.clips() = uiState.value.document.clips.associateBy { it.id }

    // ---- auto-ripple delete of several clips on one track ----

    @Test fun `auto-ripple deleting several clips on one track leaves no gaps`() {
        val vm = vmWith(clip("a", 0, 1000), clip("b", 1000, 1000), clip("c", 2000, 1000), clip("d", 3000, 1000))
        vm.toggleAutoRipple()
        vm.selectClip("a")
        vm.selectClip("c", additive = true)
        vm.deleteSelected()
        val byId = vm.clips()
        assertEquals(setOf("b", "d"), byId.keys)
        assertEquals(0L, byId["b"]!!.startTimeMs)
        assertEquals(1000L, byId["d"]!!.startTimeMs) // was left at 2000 (a 1000ms gap) before the fix
    }

    @Test fun `auto-ripple with overlapping removed clips closes their union once`() {
        val vm = vmWith(clip("a", 0, 2000), clip("b", 1000, 2000, trackId = "V1"), clip("c", 3000, 1000))
        vm.toggleAutoRipple()
        vm.selectClip("a")
        vm.selectClip("b", additive = true)
        vm.deleteSelected()
        assertEquals(0L, vm.clips()["c"]!!.startTimeMs) // union [0,3000) closed, not 4000ms
    }

    // ---- speed-aware split / trim ----

    @Test fun `splitting a 2x clip starts the right half at the source it actually reached`() {
        val vm = vmWith(clip("a", 0, 4000, trimStartMs = 1000, speed = 2f))
        vm.splitClip("a", 1000)
        val pieces = vm.uiState.value.document.clips.sortedBy { it.startTimeMs }
        assertEquals(2, pieces.size)
        // 1000ms of timeline at 2x consumed 2000ms of source.
        assertEquals(3000L, pieces[1].trimStartMs)
        // The two halves together still show exactly the original source span.
        assertEquals(TimelineMath.sourceEndMs(clip("a", 0, 4000, trimStartMs = 1000, speed = 2f)), TimelineMath.sourceEndMs(pieces[1]))
    }

    @Test fun `split at playhead is speed aware too`() {
        val vm = vmWith(clip("a", 0, 4000, speed = 0.5f))
        vm.seekTo(2000)
        vm.splitAtPlayhead()
        val right = vm.uiState.value.document.clips.maxBy { it.startTimeMs }
        assertEquals(1000L, right.trimStartMs)
    }

    @Test fun `trimming the start of a 2x clip advances the source twice as far`() {
        val vm = vmWith(clip("a", 0, 4000, trimStartMs = 0, speed = 2f))
        vm.trimClipStart("a", 500)
        val c = vm.clips()["a"]!!
        assertEquals(500L, c.startTimeMs)
        assertEquals(1000L, c.trimStartMs)
        assertEquals(3500L, c.durationMs)
    }

    @Test fun `keptRanges cover the source a sped-up clip plays`() {
        val c = clip("a", 0, 1000, trimStartMs = 0, speed = 2f)
        assertEquals(listOf(0L until 2000L), TimelineMath.keptRanges(c))
    }

    @Test fun `extending the start past timeline zero is clamped, keeping source and timeline in sync`() {
        val vm = vmWith(clip("a", 100, 1000, trimStartMs = 1000))
        vm.trimClipStart("a", -500)
        val c = vm.clips()["a"]!!
        assertEquals(0L, c.startTimeMs)
        assertEquals(900L, c.trimStartMs) // moved by the same 100ms the start did, not 500
        assertEquals(1100L, c.durationMs)
        assertEquals(1100L, c.endTimeMs) // end didn't move
    }

    // ---- split keeps keyframe animation ----

    @Test fun `split inserts boundary keyframes so both halves keep the animation`() {
        val kfs = listOf(
            Keyframe(id = "k0", timeMs = 0, value = 0f, property = KeyframeProperty.OPACITY),
            Keyframe(id = "k1", timeMs = 2000, value = 1f, property = KeyframeProperty.OPACITY),
        )
        val original = clip("a", 0, 2000, keyframes = kfs)
        val vm = vmWith(original)
        vm.splitClip("a", 1000)
        val (left, right) = vm.uiState.value.document.clips.sortedBy { it.startTimeMs }
        val mid = TimelineMath.valueAt(original, KeyframeProperty.OPACITY, 1000, 1f)
        assertEquals(mid, TimelineMath.valueAt(left, KeyframeProperty.OPACITY, 1000, 1f), 1e-4f)
        assertEquals(mid, TimelineMath.valueAt(right, KeyframeProperty.OPACITY, 0, 1f), 1e-4f)
        assertEquals(1f, TimelineMath.valueAt(right, KeyframeProperty.OPACITY, 1000, 1f), 1e-4f)
        // Before the fix the left half had only k0 and so sat flat at 0 throughout.
        assertTrue(left.keyframes.size >= 2)
    }

    // ---- undo keeps non-undoable fields ----

    @Test fun `undo does not revert a rename or the prompt history`() {
        val vm = vmWith(clip("a", 0, 1000))
        vm.splitClip("a", 500)
        vm.setProjectName("Renamed")
        vm.rememberPrompt("make it pop")
        vm.undo()
        val doc = vm.uiState.value.document
        assertEquals(1, doc.clips.size)
        assertEquals("Renamed", doc.name)
        assertEquals(listOf("make it pop"), doc.promptHistory)
        vm.redo()
        assertEquals("Renamed", vm.uiState.value.document.name)
    }

    @Test fun `undo does not resurrect or clear an analyzing flag`() {
        val vm = vmWith(clip("a", 0, 1000))
        vm.updateClip("a") { it.copy(prompt = "x") }
        vm.setAnalyzing(listOf("a"), true)
        vm.undo()
        assertTrue(vm.clips()["a"]!!.isAnalyzing)
    }

    // ---- loadDocument resets per-project state ----

    @Test fun `loading another project clears per-project view state and the clip clipboard`() {
        val vm = vmWith(clip("a", 0, 1000))
        vm.setPlaybackRegion(0, 500)
        vm.setBeatMap("a", BeatMap(bpm = 120f, beatsMs = listOf(0L, 500L), downbeatsMs = listOf(0L), onsetsMs = emptyList()))
        vm.setLufs("a", -14.0)
        vm.selectClip("a")
        vm.copySelected()

        vm.loadDocument(Document(mediaItems = listOf(media), clips = listOf(clip("a", 0, 1000))))
        val st = vm.uiState.value
        assertNull(st.playbackRegion)
        assertTrue(st.beatMaps.isEmpty())
        assertTrue(st.lufsByClip.isEmpty())
        assertNull(st.selectedKeyframeId)
        vm.pasteClip()
        assertEquals(1, vm.uiState.value.document.clips.size) // nothing to paste from the old project
    }

    // ---- FX chain: removing the last layer of a legacy clip ----

    @Test fun `removing the last legacy fx layer does not resurrect it`() {
        val legacy = clip("a", 0, 1000).copy(filters = ClipFilters(lutPath = "/luts/a.cube"))
        val vm = vmWith(legacy)
        val id = vm.clips()["a"]!!.filters.effectiveFxChain.single().id
        vm.removeFxLayer("a", id)
        assertTrue(vm.clips()["a"]!!.filters.effectiveFxChain.isEmpty())
    }

    @Test fun `legacy fx layer ids are stable across reads`() {
        val f = ClipFilters(shaderPath = "/s.fs")
        assertEquals(f.effectiveFxChain.single().id, f.effectiveFxChain.single().id)
        assertEquals(FxLayer.KIND_SHADER, f.effectiveFxChain.single().kind)
    }
}
