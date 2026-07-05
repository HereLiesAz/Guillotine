package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Black
import com.hereliesaz.guillotine.editor.EditorViewModel

@Composable
fun VideoPreview(
    @Suppress("UNUSED_PARAMETER") editor: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Black), contentAlignment = Alignment.Center) {
        Text("Preview unavailable — media pipeline coming in Phase 3", color = Neutral500)
    }
}
