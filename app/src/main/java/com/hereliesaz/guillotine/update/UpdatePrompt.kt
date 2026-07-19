package com.hereliesaz.guillotine.update

import android.widget.Toast
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Cross-screen trigger for a manual "Check for updates" (from Settings). Incrementing [checkNow]
 * makes the mounted [UpdatePrompt] re-run its check. Lives outside the composition so the Settings
 * sheet — which isn't a child of [UpdatePrompt] — can poke it.
 */
object UpdateSignals {
    val checkNow = mutableStateOf(0)
}

/**
 * The github-build auto-updater UI: checks GitHub Releases on launch (and on each manual
 * [UpdateSignals.checkNow] bump), and when a newer APK exists shows a dialog to download + install
 * it. Mounted once, near the app root, only in the github distribution (see `MainActivity`). Silent
 * when up to date on the launch check; a manual check surfaces an "up to date" toast so the tap has
 * visible feedback.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var available by remember { mutableStateOf<AndroidUpdater.Available?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val manualCheck = UpdateSignals.checkNow.value

    LaunchedEffect(manualCheck) {
        val found = AndroidUpdater.check()
        if (found != null) {
            available = found
        } else if (manualCheck > 0) {
            Toast.makeText(context, "You're on the latest version.", Toast.LENGTH_SHORT).show()
        }
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
                        append("Version ${a.version} is available.")
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
                    // Package installs need the "install unknown apps" permission on O+. If it isn't
                    // granted, send the user to grant it and let them tap Update again on return.
                    if (!AndroidUpdater.canInstall(context)) {
                        AndroidUpdater.requestInstallPermission(context)
                        Toast.makeText(
                            context,
                            "Allow installs from Guillotine, then tap Update again.",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@TextButton
                    }
                    scope.launch {
                        downloading = true
                        progress = 0f
                        val file = AndroidUpdater.download(context, a) { progress = it }
                        downloading = false
                        if (file != null) {
                            available = null
                            AndroidUpdater.install(context, file)
                        } else {
                            Toast.makeText(context, "Download failed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Update") }
            }
        },
        dismissButton = {
            if (!downloading) TextButton(onClick = { available = null }) { Text("Later") }
        },
    )
}
