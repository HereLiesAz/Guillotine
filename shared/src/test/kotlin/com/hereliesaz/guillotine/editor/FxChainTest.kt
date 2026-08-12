package com.hereliesaz.guillotine.editor

import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.FxLayer
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [ClipFilters.effectiveFxChain] (the legacy single-slot → chain fallback) and the
 * new [EditorViewModel] chain-manipulation functions — the "VST-style hosting" azphalt gained: applying
 * a second shader/LUT stacks instead of silently replacing the first.
 */
class FxChainTest {

    private val media = MediaItem(id = "m1", uri = "file:///a.mp4", name = "a.mp4", kind = MediaKind.VIDEO, durationMs = 10_000)

    private fun vmWith(filters: ClipFilters): EditorViewModel {
        val vm = EditorViewModel()
        val clip = TimelineClip(id = "c1", mediaId = "m1", type = ClipType.VIDEO, trackId = "V1", startTimeMs = 0, trimStartMs = 0, durationMs = 1000, filters = filters)
        vm.loadDocument(Document(mediaItems = listOf(media), clips = listOf(clip)))
        return vm
    }

    // ---- effectiveFxChain fallback ----

    @Test
    fun `fresh filters have an empty effective chain`() {
        assertTrue(ClipFilters().effectiveFxChain.isEmpty())
    }

    @Test
    fun `legacy lut and shader synthesize into a chain, lut first`() {
        val f = ClipFilters(lutPath = "/luts/a.cube", shaderPath = "/shaders/b.fs", shaderParams = mapOf("intensity" to 0.5f))
        val chain = f.effectiveFxChain
        assertEquals(2, chain.size)
        assertEquals(FxLayer.KIND_LUT, chain[0].kind)
        assertEquals("/luts/a.cube", chain[0].path)
        assertEquals(FxLayer.KIND_SHADER, chain[1].kind)
        assertEquals("/shaders/b.fs", chain[1].path)
        assertEquals(0.5f, chain[1].params["intensity"])
    }

    @Test
    fun `once fxChain is set it wins over the legacy fields entirely`() {
        val f = ClipFilters(
            lutPath = "/luts/ignored.cube",
            fxChain = listOf(FxLayer(kind = FxLayer.KIND_SHADER, path = "/shaders/real.fs")),
        )
        assertEquals(1, f.effectiveFxChain.size)
        assertEquals("/shaders/real.fs", f.effectiveFxChain.single().path)
    }

    // ---- addFxLayer / removeFxLayer / moveFxLayer ----

    @Test
    fun `adding a second layer stacks instead of replacing`() {
        val vm = vmWith(ClipFilters(shaderPath = "/shaders/first.fs"))
        vm.addFxLayer("c1", FxLayer(kind = FxLayer.KIND_LUT, path = "/luts/second.cube"))
        val chain = vm.uiState.value.document.clips.single().filters.effectiveFxChain
        assertEquals(2, chain.size)
        assertEquals("/shaders/first.fs", chain[0].path) // migrated from the legacy field, kept first
        assertEquals("/luts/second.cube", chain[1].path)
    }

    @Test
    fun `removeFxLayer drops only the targeted layer`() {
        val vm = vmWith(ClipFilters())
        vm.addFxLayer("c1", FxLayer(id = "a", kind = FxLayer.KIND_LUT, path = "/a.cube"))
        vm.addFxLayer("c1", FxLayer(id = "b", kind = FxLayer.KIND_SHADER, path = "/b.fs"))
        vm.removeFxLayer("c1", "a")
        val chain = vm.uiState.value.document.clips.single().filters.effectiveFxChain
        assertEquals(listOf("b"), chain.map { it.id })
    }

    @Test
    fun `moveFxLayer reorders and is a no-op past either edge`() {
        val vm = vmWith(ClipFilters())
        vm.addFxLayer("c1", FxLayer(id = "a", kind = FxLayer.KIND_LUT, path = "/a.cube"))
        vm.addFxLayer("c1", FxLayer(id = "b", kind = FxLayer.KIND_SHADER, path = "/b.fs"))
        vm.moveFxLayer("c1", 0, +1) // swap a and b
        assertEquals(listOf("b", "a"), vm.uiState.value.document.clips.single().filters.effectiveFxChain.map { it.id })
        vm.moveFxLayer("c1", 0, -1) // already at the start; no-op
        assertEquals(listOf("b", "a"), vm.uiState.value.document.clips.single().filters.effectiveFxChain.map { it.id })
    }

    @Test
    fun `setFxLayerEnabled toggles just that layer`() {
        val vm = vmWith(ClipFilters())
        vm.addFxLayer("c1", FxLayer(id = "a", kind = FxLayer.KIND_LUT, path = "/a.cube"))
        vm.setFxLayerEnabled("c1", "a", false)
        assertTrue(!vm.uiState.value.document.clips.single().filters.effectiveFxChain.single().enabled)
    }
}
