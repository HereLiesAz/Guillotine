package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500

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
                sel.firstOrNull()?.let { CropToolInline(vm, it) }
            }
            EditorTool.SPLIT -> {
                // Split tools
                if (processable != null) SplitToolButton(vm, processable)
            }
            EditorTool.SELECT, EditorTool.MARQUEE -> {
                if (text != null) {
                    TextToolInline(vm, text)
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
                // Plugin/extension-contributed sections (kinetic typography, and any azphalt UI later)
                // render here through the standard host seam — see ClipPanelContribution.
                ClipPanelHost(vm, state)
            }
        }
    }
}

/**
 * The Crop & Transform tool's panel. The actual editing happens by dragging/pinching/twisting
 * directly on the preview (see [PreviewPlayer]'s `cropMode`, wired to
 * [EditorViewModel.transformSelectedClip]) — this panel used to render nothing at all while that
 * tool was active, leaving a user who opened it with a title and a blank space with no clue that
 * the preview itself was the control surface, and no way back to identity once they over-rotated
 * or panned a clip off-frame.
 */
@Composable
private fun CropToolInline(vm: EditorViewModel, clip: TimelineClip) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Drag the preview to move · pinch to scale · twist with two fingers to rotate.",
            color = Neutral400, fontSize = 12.sp,
        )
        Text(
            "Scale ${"%.2f".format(clip.scale)}x · Rotation ${"%.0f".format(clip.rotation)}° · " +
                "Offset (${"%.2f".format(clip.offsetX)}, ${"%.2f".format(clip.offsetY)})",
            color = Neutral500, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { vm.resetSelectedClipTransform() },
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) { Text("Reset", fontSize = 12.sp) }
        }
    }
}
