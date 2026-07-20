package com.hereliesaz.guillotine.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hereliesaz.guillotine.desktop.platform.DesktopKeyStore
import com.hereliesaz.guillotine.desktop.platform.DesktopMcpAuth
import com.hereliesaz.guillotine.desktop.platform.DesktopMcpTools
import com.hereliesaz.guillotine.desktop.platform.DesktopProjectAutosave
import com.hereliesaz.guillotine.desktop.ui.DesktopApp
import com.hereliesaz.guillotine.desktop.ui.DesktopUpdateGate
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.mcp.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() = application {
    val editor = remember { EditorViewModel() }
    val keyStore = remember { DesktopKeyStore() }
    val mcpTools = remember { DesktopMcpTools(editor) { keyStore.settings.value } }
    val server = remember { McpServer(7865) }

    LaunchedEffect(Unit) {
        runCatching {
            server.startServer(mcpTools) { DesktopMcpAuth.token() }
        }
        val doc = withContext(Dispatchers.IO) { DesktopProjectAutosave.load() }
        if (doc != null) editor.loadDocument(doc)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { DesktopProjectAutosave.save(editor.uiState.value.document) }
            runCatching { server.stopServer() }
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Guillotine",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        DesktopApp(editor, mcpTools, keyStore)
        // Check GitHub Releases for a newer installer on launch and offer to self-update.
        DesktopUpdateGate()
    }
}
