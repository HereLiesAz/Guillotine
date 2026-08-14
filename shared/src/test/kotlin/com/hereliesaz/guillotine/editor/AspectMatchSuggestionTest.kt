package com.hereliesaz.guillotine.editor

import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.GlobalSettings
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vegas B.4: the first clip landing on an otherwise-empty project offers to match project
 * properties to it, rather than silently letterboxing a clip inside whatever fixed ratio the
 * project happened to already be set to. Covers [EditorViewModel.addMedia] raising the suggestion
 * and [EditorViewModel.resolveAspectMatchSuggestion] resolving it.
 */
class AspectMatchSuggestionTest {

    private val clip = MediaItem(id = "m1", uri = "file:///a.mp4", name = "a.mp4", kind = MediaKind.VIDEO, durationMs = 5_000)

    @Test
    fun `first clip on a fixed-ratio empty project raises the suggestion`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_9_16)))
        vm.addMedia(listOf(clip))
        assertTrue(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `first clip on an already-ORIGINAL project does not raise the suggestion`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.ORIGINAL)))
        vm.addMedia(listOf(clip))
        assertFalse(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `an audio-only first import never raises the suggestion — there's no picture to letterbox`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_9_16)))
        val audio = MediaItem(id = "a1", uri = "file:///a.mp3", name = "a.mp3", kind = MediaKind.AUDIO, durationMs = 5_000)
        vm.addMedia(listOf(audio))
        assertFalse(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `a clip whose real aspect already matches the project does not raise the suggestion`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_16_9)))
        val matching = clip.copy(widthPx = 1920, heightPx = 1080) // exactly 16:9
        vm.addMedia(listOf(matching))
        assertFalse(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `a clip with a genuine dimension mismatch raises the suggestion`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_9_16)))
        val landscape = clip.copy(widthPx = 1920, heightPx = 1080) // 16:9 into a 9:16 project
        vm.addMedia(listOf(landscape))
        assertTrue(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `a clip with unknown dimensions still raises the suggestion rather than assuming it fits`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_9_16)))
        vm.addMedia(listOf(clip)) // no widthPx/heightPx set — probe couldn't determine them
        assertTrue(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `switching projects mid-dialog does not carry a stale suggestion into the new project`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_9_16)))
        vm.addMedia(listOf(clip))
        assertTrue(vm.uiState.value.suggestAspectMatch) // dialog is up, unanswered
        val projectB = Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_1_1))
        vm.loadDocument(projectB)
        // Loading a different project must clear the stale flag — otherwise "match" would silently
        // rewrite THIS project's aspect ratio in response to a suggestion raised by the last one.
        assertFalse(vm.uiState.value.suggestAspectMatch)
        assertEquals(AspectRatio.RATIO_1_1, vm.uiState.value.document.settings.aspectRatio)
    }

    @Test
    fun `a later clip on a non-empty project does not re-raise the suggestion`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_9_16)))
        vm.addMedia(listOf(clip))
        vm.resolveAspectMatchSuggestion(match = false)
        vm.addMedia(listOf(clip.copy(id = "m2")))
        assertFalse(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `resolving with match switches the project to ORIGINAL and clears the flag`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_16_9)))
        vm.addMedia(listOf(clip))
        vm.resolveAspectMatchSuggestion(match = true)
        assertEquals(AspectRatio.ORIGINAL, vm.uiState.value.document.settings.aspectRatio)
        assertFalse(vm.uiState.value.suggestAspectMatch)
    }

    @Test
    fun `dismissing without match keeps the project's ratio and clears the flag`() {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = AspectRatio.RATIO_16_9)))
        vm.addMedia(listOf(clip))
        vm.resolveAspectMatchSuggestion(match = false)
        assertEquals(AspectRatio.RATIO_16_9, vm.uiState.value.document.settings.aspectRatio)
        assertFalse(vm.uiState.value.suggestAspectMatch)
    }
}
