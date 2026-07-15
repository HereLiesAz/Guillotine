package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.White

@Composable
fun AdvancedToolView(
    vm: EditorViewModel,
    state: EditorUiState,
    onTranscribe: (CaptionStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Neutral900)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val title = when (state.tool) {
            EditorTool.CROP -> "Crop & Transform"
            EditorTool.KEYFRAME -> "Keyframes"
            EditorTool.SPLIT -> "Split"
            EditorTool.MARQUEE -> "Marquee Selection"
            EditorTool.SELECT -> "Clip Properties"
        }

        Text(
            text = title,
            color = White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        if (state.selectedClips.isEmpty()) {
            Text(
                "Select a clip to view advanced properties.",
                color = com.hereliesaz.guillotine.ui.theme.Neutral500,
                fontSize = 12.sp
            )
        } else {
            // Render the inline clip tools instead of popups
            // We'll wrap the existing ClipToolButtons logic into an inline version.
            InlineClipTools(vm, state, onTranscribe)
        }
    }
}
