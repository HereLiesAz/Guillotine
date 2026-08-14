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
import com.hereliesaz.guillotine.desktop.platform.DesktopStorage
import com.hereliesaz.guillotine.desktop.ui.DesktopApp
import com.hereliesaz.guillotine.desktop.ui.DesktopUpdateGate
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.mcp.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Exclusive lock on a dedicated file in the app's data directory, acquired once at startup and held for
 * the whole process lifetime (never explicitly released/closed) -- the OS drops it automatically when
 * the process exits, including a crash. A second instance's [FileChannel.tryLock] then fails immediately
 * rather than blocking, which is what [acquireSingleInstanceLock] needs to detect "already running".
 */
private fun acquireSingleInstanceLock(): FileLock? {
    val lockFile = File(DesktopStorage.dataDir, "guillotine.lock")
    return try {
        val channel = RandomAccessFile(lockFile, "rw").channel
        channel.tryLock()
    } catch (e: OverlappingFileLockException) {
        null
    } catch (e: Exception) {
        // Couldn't even open/lock the file (permissions, read-only filesystem, ...) -- treat as "can't
        // guarantee single instance" rather than silently letting two copies run. Better to surface it.
        null
    }
}

fun main() {
    // Single-instance guard: nothing previously stopped launching the app twice. Both instances would
    // autosave to the exact same fixed path (DesktopProjectAutosave's "current_project.gilt") with no
    // locking, so the second window's debounced autosave silently overwrites the first's and vice versa,
    // and the second instance would also try to bind the same fixed MCP port (7865) and fail silently.
    // Acquire the lock BEFORE building any editor/server state; if another instance already holds it,
    // tell the user and exit cleanly instead of proceeding into that broken half-state.
    val lock = acquireSingleInstanceLock()
    if (lock == null) {
        val message = "Guillotine is already running. Close the other window first."
        System.err.println(message)
        runCatching {
            javax.swing.JOptionPane.showMessageDialog(
                null, message, "Guillotine", javax.swing.JOptionPane.WARNING_MESSAGE,
            )
        }
        return
    }

    application {
        val editor = remember { EditorViewModel() }
        val keyStore = remember { DesktopKeyStore() }
        val mcpTools = remember { DesktopMcpTools(editor) { keyStore.settings.value } }
        val server = remember { McpServer(7865) }

        LaunchedEffect(Unit) {
            runCatching {
                server.startServer(mcpTools) { DesktopMcpAuth.token() }
            }.onFailure {
                System.err.println("Guillotine: failed to start the MCP server on port 7865: ${it.message}")
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
}
