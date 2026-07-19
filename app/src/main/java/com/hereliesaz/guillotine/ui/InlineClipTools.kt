package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType

@Composable
fun InlineClipTools(
    vm: EditorViewModel,
    state: EditorUiState,
    onTranscribe: (CaptionStyle) -> Unit,
) {
    val sel = state.selectedClips
    if (sel.isEmpty()) return
    
    val video = sel.firstOrNull { it.type == ClipType.VIDEO }
    val text = sel.firstOrNull { it.type == ClipType.TEXT }
    val audioTarget = sel.firstOrNull { it.type == ClipType.AUDIO && it.linkedClipId == null } ?: video
    val processable = video ?: sel.firstOrNull { it.type == ClipType.AUDIO }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (state.tool) {
            EditorTool.KEYFRAME -> {
                if (video != null || processable != null || text != null) {
                    val target = video ?: processable ?: text
                    target?.let { KeyframesToolInline(vm, it) }
                }
            }
            EditorTool.CROP -> {
                // Crop tools
            }
            EditorTool.SPLIT -> {
                // Split tools
                if (processable != null) SplitToolButton(vm, processable)
            }
            EditorTool.SELECT, EditorTool.MARQUEE -> {
                if (text != null) {
                    TextToolInline(vm, text)
                    KineticTypeToolInline(vm, text)
                }
                if (video != null) {
                    BackgroundToolButton(vm, video)
                    FaceBlurToolButton(vm, video)
                    FiltersToolInline(vm, video)
                }
                if (audioTarget != null) AudioToolInline(vm, audioTarget)
                if (processable != null) {
                    TranscribeToolInline(state, onTranscribe)
                }
            }
        }
    }
}
