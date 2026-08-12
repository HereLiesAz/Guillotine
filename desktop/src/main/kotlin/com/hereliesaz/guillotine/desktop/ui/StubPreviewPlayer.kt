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
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val state by editor.uiState.collectAsState()
    DesktopPreviewPlayer(state, modifier, onToggleFullscreen = onToggleFullscreen)
}
