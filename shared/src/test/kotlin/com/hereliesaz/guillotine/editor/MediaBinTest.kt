package com.hereliesaz.guillotine.editor

import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the Media Bin's editor-side operations: reusing an already-imported media item as
 * a new clip without re-adding it to the pool, tagging (the "Smart Bin" search's data source), and
 * dropping a still-unused media item from the project.
 */
class MediaBinTest {

    private val video = MediaItem(id = "m1", uri = "file:///a.mp4", name = "a.mp4", kind = MediaKind.VIDEO, durationMs = 5_000, hasAudio = true)
    private val image = MediaItem(id = "m2", uri = "file:///b.png", name = "b.png", kind = MediaKind.IMAGE, durationMs = 0)

    private fun vmWith(mediaItems: List<MediaItem>, clips: List<TimelineClip> = emptyList()): EditorViewModel {
        val vm = EditorViewModel()
        vm.loadDocument(Document(mediaItems = mediaItems, clips = clips))
        return vm
    }

    // ---- addClipFromMedia ----

    @Test
    fun `addClipFromMedia adds a clip without duplicating the media pool entry`() {
        val vm = vmWith(listOf(video))
        vm.addClipFromMedia("m1")
        val doc = vm.uiState.value.document
        assertEquals(1, doc.mediaItems.size) // not duplicated
        // Video with audio -> a video clip + its linked audio shadow, same as a fresh import.
        assertEquals(2, doc.clips.size)
        assertEquals(1, doc.clips.count { it.type == ClipType.VIDEO })
        assertEquals(1, doc.clips.count { it.type == ClipType.AUDIO })
    }

    @Test
    fun `addClipFromMedia gives an image its default duration`() {
        val vm = vmWith(listOf(image))
        vm.addClipFromMedia("m2")
        val clip = vm.uiState.value.document.clips.single()
        assertTrue("expected a positive default duration, got ${clip.durationMs}", clip.durationMs > 0)
    }

    @Test
    fun `addClipFromMedia is a no-op for an unknown media id`() {
        val vm = vmWith(listOf(video))
        vm.addClipFromMedia("missing")
        assertTrue(vm.uiState.value.document.clips.isEmpty())
    }

    // ---- tags ----

    @Test
    fun `addMediaTag dedupes and trims, removeMediaTag drops it`() {
        val vm = vmWith(listOf(video))
        vm.addMediaTag("m1", "  B-Roll  ")
        vm.addMediaTag("m1", "B-Roll") // duplicate, already trimmed
        vm.addMediaTag("m1", "Interview")
        val tags = vm.uiState.value.document.mediaItems.single().tags
        assertEquals(listOf("B-Roll", "Interview"), tags)

        vm.removeMediaTag("m1", "B-Roll")
        assertEquals(listOf("Interview"), vm.uiState.value.document.mediaItems.single().tags)
    }

    @Test
    fun `addMediaTag ignores a blank tag`() {
        val vm = vmWith(listOf(video))
        vm.addMediaTag("m1", "   ")
        assertTrue(vm.uiState.value.document.mediaItems.single().tags.isEmpty())
    }

    // ---- removeUnusedMedia ----

    @Test
    fun `removeUnusedMedia drops an item with no referencing clips`() {
        val vm = vmWith(listOf(video, image))
        vm.removeUnusedMedia("m2")
        assertEquals(listOf("m1"), vm.uiState.value.document.mediaItems.map { it.id })
    }

    @Test
    fun `removeUnusedMedia is a no-op when a clip still references the media`() {
        val clip = TimelineClip(id = "c1", mediaId = "m1", type = ClipType.VIDEO, trackId = "V1", startTimeMs = 0, trimStartMs = 0, durationMs = 1000)
        val vm = vmWith(listOf(video), listOf(clip))
        vm.removeUnusedMedia("m1")
        assertEquals(listOf("m1"), vm.uiState.value.document.mediaItems.map { it.id })
    }
}
