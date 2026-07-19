package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.guillotine.desktop.platform.DesktopUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop auto-updater UI. On launch it checks GitHub Releases for a newer host-OS installer and,
 * if one exists, offers to download and run it. Mounted once near the window root (see `Main.kt`).
 * Everything is failure-tolerant, so a network hiccup never surfaces to the user.
 */
@Composable
fun DesktopUpdateGate() {
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<DesktopUpdater.Available?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        available = withContext(Dispatchers.IO) { runCatching { DesktopUpdater.check() }.getOrNull() }
    }

    val a = available ?: return
    AlertDialog(
        onDismissRequest = { if (!downloading) available = null },
        title = { Text("Update available") },
        text = {
            if (downloading) {
                Column {
                    Text("Downloading v${a.version}…")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Text(
                    buildString {
                        append("Guillotine v${a.version} is available.")
                        if (a.notes.isNotBlank()) {
                            append("\n\n")
                            append(a.notes.take(300))
                        }
                    },
                )
            }
        },
        confirmButton = {
            if (!downloading) {
                TextButton(onClick = {
                    scope.launch {
                        downloading = true
                        progress = 0f
                        val file = withContext(Dispatchers.IO) { DesktopUpdater.download(a) { progress = it } }
                        downloading = false
                        // Launch the installer; if we can't download or launch it, open the release page.
                        if (file == null || !DesktopUpdater.launchInstaller(file)) {
                            DesktopUpdater.openInBrowser(a.htmlUrl)
                        }
                        available = null
                    }
                }) { Text("Download & install") }
            }
        },
        dismissButton = {
            if (!downloading) TextButton(onClick = { available = null }) { Text("Later") }
        },
    )
}
