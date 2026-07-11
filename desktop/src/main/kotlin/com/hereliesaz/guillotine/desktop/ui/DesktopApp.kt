package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.desktop.platform.DesktopKeyStore
import com.hereliesaz.guillotine.desktop.ui.theme.GuillotineTheme
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.mcp.McpToolsSurface

@Composable
fun DesktopApp(
    editor: EditorViewModel,
    mcpTools: McpToolsSurface,
    keyStore: DesktopKeyStore,
) {
    GuillotineTheme {
        NleScreen(
            vm = editor,
            keyStore = keyStore,
            mcpTools = mcpTools,
        )
    }
}
