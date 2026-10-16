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
import com.hereliesaz.guillotine.update.UpdateVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop auto-updater UI. On launch it checks GitHub Releases for a newer host-OS installer and,
 * if one exists, offers to download and run it. Mounted once near the window root (see `Main.kt`).
 * Everything is failure-tolerant, so a network hiccup never surfaces to the user.
 *
 * Only an installer whose SHA-256 matched the release's published digest is launched automatically.
 * When the release publishes no digest (`UpdateVerification.SizeOnly`) the dialog says the download
 * is unverified and asks: open the release page (default) or explicitly run it anyway.
 */
@Composable
fun DesktopUpdateGate() {
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<DesktopUpdater.Available?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    // A downloaded installer that only passed the size check (no published digest): needs explicit consent.
    var unverified by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(Unit) {
        available = withContext(Dispatchers.IO) { runCatching { DesktopUpdater.check() }.getOrNull() }
    }

    val a = available ?: return
    unverified?.let { file ->
        AlertDialog(
            onDismissRequest = { unverified = null; available = null },
            title = { Text("Unverified download") },
            text = {
                Text(
                    "This release didn't publish a SHA-256 checksum, so Guillotine can't verify the " +
                        "installer it downloaded (only its size matched). Running it means trusting the " +
                        "download as-is. You can instead get it from the release page yourself.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { DesktopUpdater.openInBrowser(a.htmlUrl) }
                        unverified = null
                        available = null
                    }
                }) { Text("Open release page") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        val launched = withContext(Dispatchers.IO) { DesktopUpdater.launchInstaller(file) }
                        if (!launched) withContext(Dispatchers.IO) { DesktopUpdater.openInBrowser(a.htmlUrl) }
                        unverified = null
                        available = null
                    }
                }) { Text("Run anyway") }
            },
        )
        return
    }
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
                        if (file != null && DesktopUpdater.lastVerification is UpdateVerification.SizeOnly) {
                            unverified = file // ask first — never run an unverified installer silently
                            return@launch
                        }
                        // Launch the installer; if we can't download or launch it, open the release page.
                        // launchInstaller/openInBrowser do blocking OS/AWT work, so keep them off the UI thread.
                        val launched = file != null && withContext(Dispatchers.IO) { DesktopUpdater.launchInstaller(file) }
                        if (!launched) withContext(Dispatchers.IO) { DesktopUpdater.openInBrowser(a.htmlUrl) }
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
