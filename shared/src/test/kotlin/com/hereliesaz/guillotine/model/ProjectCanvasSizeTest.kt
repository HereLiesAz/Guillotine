package com.hereliesaz.guillotine.model

import com.hereliesaz.guillotine.editor.EditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectCanvasSizeTest {

    private fun projectWith(
        width: Int,
        height: Int,
        aspect: AspectRatio = AspectRatio.ORIGINAL,
        quality: Quality = Quality.ORIGINAL,
    ): Document {
        val vm = EditorViewModel()
        vm.loadDocument(Document(settings = GlobalSettings(aspectRatio = aspect, quality = quality)))
        vm.addMedia(
            listOf(
                MediaItem(
                    id = "m1",
                    uri = "file:///source.mp4",
                    name = "source.mp4",
                    kind = MediaKind.VIDEO,
                    durationMs = 5_000,
                    widthPx = width,
                    heightPx = height,
                ),
            ),
        )
        return vm.uiState.value.document
    }

    @Test
    fun `original preserves landscape source width and height verbatim`() {
        assertEquals(ProjectCanvasSize(1920, 1080), projectWith(1920, 1080).projectCanvasSize())
    }

    @Test
    fun `original preserves portrait source width and height verbatim without swapping`() {
        assertEquals(ProjectCanvasSize(1080, 1920), projectWith(1080, 1920).projectCanvasSize())
    }

    @Test
    fun `changing fixed aspect changes canvas width but not reference height`() {
        val doc = projectWith(1920, 1080, aspect = AspectRatio.RATIO_9_16)
        assertEquals(ProjectCanvasSize(608, 1080), doc.projectCanvasSize())
    }

    @Test
    fun `explicit quality scales original while preserving source aspect`() {
        val doc = projectWith(1920, 1080, quality = Quality.HD_720P)
        assertEquals(ProjectCanvasSize(1280, 720), doc.projectCanvasSize())
    }
}
