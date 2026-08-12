package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.hereliesaz.guillotine.desktop.media.DesktopPreviewPlayer
import com.hereliesaz.guillotine.editor.EditorViewModel

@Composable
fun VideoPreview(
    editor: EditorViewModel,
    modifier: Modifier = Modifier,
    cropMode: Boolean = false,
    onCropTransform: (zoom: Float, panXFrac: Float, panYFrac: Float, rotationDelta: Float) -> Unit = { _, _, _, _ -> },
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val state by editor.uiState.collectAsState()
    DesktopPreviewPlayer(
        state,
        modifier,
        cropMode = cropMode,
        onCropTransform = onCropTransform,
        onToggleFullscreen = onToggleFullscreen,
    )
}
