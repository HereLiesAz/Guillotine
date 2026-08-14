package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.guillotine.azphalt.AzpHandoffInstaller
import com.hereliesaz.guillotine.azphalt.AzpInstallLink
import com.hereliesaz.guillotine.azphalt.AzpInstallSurfaces
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzphaltRegistry
import com.hereliesaz.guillotine.azphalt.AzphaltTrust
import com.hereliesaz.guillotine.azphalt.AzpPublisherPins
import com.hereliesaz.guillotine.desktop.platform.DesktopPluginApplier
import com.hereliesaz.guillotine.desktop.platform.DesktopStorage
import com.hereliesaz.guillotine.desktop.ui.theme.Black
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral700
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral900
import com.hereliesaz.guillotine.desktop.ui.theme.Red500
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop's own Azphalt Store — Guillotine had no Store entry point at all here before (only
 * `AzpModelInstall`'s file-picker route, buried in Settings → AI Analyzer, for models specifically).
 * Mirrors the Android [AzphaltStoreScreen]'s catalog browser over the same shared
 * [AzphaltRegistry.browseAll]/[AzpHandoffInstaller] pipeline — same trust gate, same install path,
 * both platforms reading and applying the identical `.azp` byte-for-byte.
 *
 * Applying an installed package to the selected clip goes through [DesktopPluginApplier] — the same
 * implementation the `apply_azp_plugin` MCP tool uses, so "installed from the Store" and "applied via
 * the assistant" agree on what "applied" means, same as the app side's `AzpPluginApplier` parity.
 */
@Composable
fun DesktopAzphaltStoreScreen(vm: EditorViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val extensionsDir = remember { File(DesktopStorage.dataDir, "extensions") }
    val publisherPins = remember { AzpPublisherPins(File(DesktopStorage.dataDir, "azp-publishers.json")) }

    var catalog by remember { mutableStateOf<List<AzphaltRegistry.CatalogEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingUntrusted by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    // A package whose signer differs from the one this package id was first installed under. Handled
    // right here with the SAME general-purpose AzpHandoffInstaller the rest of this screen already uses
    // — NOT by pointing the user at Settings → Advanced, which re-installs through AzpModelInstall (a
    // models-only installer whose asset filter yields an empty list for anything that isn't an AI model,
    // i.e. every LUT/shader/pack this Store actually browses — it used to report "Installed 0 model(s)…
    // (trusted)" as if that had worked, while nothing was ever written to the extensions directory).
    var pendingPublisherChange by remember { mutableStateOf<AzpHandoffInstaller.InstallResult.PublisherChanged?>(null) }
    var pendingPublisherChangeBytes by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(Unit) {
        installedIds = withContext(Dispatchers.IO) {
            AzpInstalledUi.list(extensionsDir, DesktopPluginApplier.HOST_APP_ID).map { it.packageId }.toSet()
        }
        val result = withContext(Dispatchers.IO) { runCatching { AzphaltRegistry.browseAll() } }
        result.fold(onSuccess = { catalog = it }, onFailure = { loadError = it.message ?: "Couldn't reach azphalt.store." })
    }

    fun runInstall(bytes: ByteArray, allowUntrusted: Boolean = false, allowPublisherChange: Boolean = false) {
        busy = "Verifying…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AzpHandoffInstaller.install(
                    bytes, extensionsDir.absolutePath,
                    trustedKeys = setOf(AzphaltTrust.FLAGSHIP_SIGNING_KEY),
                    pins = publisherPins,
                    allowUntrusted = allowUntrusted,
                    allowPublisherChange = allowPublisherChange,
                    hostAppId = DesktopPluginApplier.HOST_APP_ID,
                )
            }
            busy = null
            when (result) {
                is AzpHandoffInstaller.InstallResult.Success -> {
                    installedIds = installedIds + result.id
                    val clipId = vm.uiState.value.selectedClipIds.firstOrNull()
                    notice = if (clipId == null) {
                        "Installed “${result.name}”. Select a clip, then use it from the clip panel."
                    } else {
                        when (val outcome = DesktopPluginApplier.apply(vm, clipId, result.id, extensionsDir)) {
                            is DesktopPluginApplier.Outcome.Applied -> "Applied “${result.name}” to the selected clip."
                            is DesktopPluginApplier.Outcome.Unsupported -> "Installed “${result.name}”. ${outcome.message}"
                            is DesktopPluginApplier.Outcome.Failure -> "Installed “${result.name}”, but applying it failed: ${outcome.message}"
                        }
                    }
                }
                is AzpHandoffInstaller.InstallResult.WrongHost ->
                    notice = "“${result.name}” isn't built for this app — it targets ${result.targetApps.joinToString(", ")}."
                is AzpHandoffInstaller.InstallResult.Incompatible ->
                    notice = "“${result.name}” needs azphalt ${result.required}; this build provides ${result.hostVersion}."
                is AzpHandoffInstaller.InstallResult.Failure -> notice = result.message
                is AzpHandoffInstaller.InstallResult.Untrusted -> pendingUntrusted = bytes to result.reason
                is AzpHandoffInstaller.InstallResult.PublisherChanged -> {
                    pendingPublisherChangeBytes = bytes
                    pendingPublisherChange = result
                }
            }
        }
    }

    fun install(entry: AzphaltRegistry.CatalogEntry) {
        busy = "Downloading “${entry.name}”…"
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { AzphaltRegistry.download(AzpInstallLink(entry.id, entry.latest)) }
            }
            busy = null
            bytes.fold(onSuccess = { runInstall(it) }, onFailure = { notice = it.message ?: "Could not download “${entry.name}”." })
        }
    }

    val filtered = remember(catalog, query, category) {
        catalog?.filter { e ->
            e.targetsApp(DesktopPluginApplier.HOST_APP_ID) &&
                // Browsing shouldn't offer an "Install" button for something that can only ever land
                // on AzpInstallSurfaces.Surface.NONE (code/app/mcp/pack packages — nothing in this
                // build applies them to anything). See AzpInstallSurfaces.hasKnownConsumer.
                AzpInstallSurfaces.hasKnownConsumer(e.types) &&
                (category == null || e.category == category) &&
                (query.isBlank() || e.name.contains(query, ignoreCase = true) || e.description.contains(query, ignoreCase = true))
        }?.sortedWith(compareBy({ it.id !in installedIds }, { it.name }))
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .size(680.dp, 620.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Neutral900)
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Extensions", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = { uriHandler.openUri(AzphaltTrust.STORE_WEB_URL) }) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open the web store", tint = Neutral400)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Neutral400)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("Search shaders, LUTs, caption styles…", color = Neutral500) },
                textStyle = TextStyle(color = White, fontSize = 13.sp),
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DESKTOP_CATEGORY_CHIPS.forEach { (label, value) ->
                    val selected = category == value
                    Text(
                        label,
                        color = if (selected) Black else Neutral400,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) White else Neutral800)
                            .clickable { category = if (selected) null else value }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError ?: "", color = Neutral400, fontSize = 12.sp)
                    }
                    catalog == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Red500)
                    }
                    filtered.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No extensions match.", color = Neutral400, fontSize = 12.sp)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { entry ->
                            DesktopCatalogEntryRow(entry, installed = entry.id in installedIds, onInstall = { install(entry) })
                        }
                    }
                }
                busy?.let { message ->
                    Box(Modifier.fillMaxSize().background(Neutral900.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Red500, modifier = Modifier.size(18.dp))
                            Text(message, color = White, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }

    notice?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("Extensions") },
            text = { Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { notice = null }) { Text("Got it") }
            },
        )
    }

    pendingUntrusted?.let { (bytes, reason) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingUntrusted = null },
            title = { Text("Not from a trusted publisher") },
            text = { Text("This package passed integrity verification, but it's not from a signer this app already trusts ($reason). Install it anyway?") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { pendingUntrusted = null; runInstall(bytes, allowUntrusted = true) }) {
                    Text("Install anyway")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingUntrusted = null }) { Text("Cancel") }
            },
        )
    }

    // Mirrors the Android AzphaltStoreScreen's pendingPublisherChange dialog: re-installs right here
    // through AzpHandoffInstaller (the general installer this whole screen already uses), not by sending
    // the user off to a models-only installer that would silently no-op on a non-model package.
    pendingPublisherChange?.let { change ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingPublisherChange = null; pendingPublisherChangeBytes = null },
            title = { Text("Publisher changed") },
            text = {
                Text(
                    "“${change.packageId}” was signed by a different publisher than the installed version. " +
                        "This can mean a legitimate key rotation — or someone else trying to replace the " +
                        "extension. Only continue if you trust the new publisher.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val bytes = pendingPublisherChangeBytes
                    pendingPublisherChange = null
                    pendingPublisherChangeBytes = null
                    if (bytes != null) runInstall(bytes, allowUntrusted = true, allowPublisherChange = true)
                }) { Text("Trust new publisher", color = Red500) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingPublisherChange = null; pendingPublisherChangeBytes = null
                }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Apps/MCP/Packs/Skills are deliberately absent — those kinds always fail
 * [AzpInstallSurfaces.hasKnownConsumer] (nothing in this build applies them to anything), so
 * [filtered] already excludes every entry a tap on one of those chips would have shown; keeping a
 * chip that can only ever return zero results is worse than not offering it.
 */
private val DESKTOP_CATEGORY_CHIPS = listOf(
    "All" to null,
    "LUTs" to "lut",
    "Shaders" to "shader",
    "Kinetic type" to "motion",
    "AI models" to "onnx",
)

/**
 * A live preview of what this listing's LUT/shader actually looks like — the app's own icon run
 * through the real (not-yet-installed) effect asset, not a generic category icon. Lazily rendered
 * off the main thread and cached (see [DesktopStorePreviewRenderer]); silently absent (falls back
 * to nothing, not a placeholder image) while loading, on a network/parse failure, or for asset
 * types with no still-image render path (motion, AI models).
 */
@Composable
private fun CatalogEntryPreviewThumbnail(entry: AzphaltRegistry.CatalogEntry) {
    val preview by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, entry.id, entry.latest) {
        value = com.hereliesaz.guillotine.desktop.media.DesktopStorePreviewRenderer.render(entry)
    }
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Neutral900),
        contentAlignment = Alignment.Center,
    ) {
        preview?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DesktopCatalogEntryRow(entry: AzphaltRegistry.CatalogEntry, installed: Boolean, onInstall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Neutral800)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogEntryPreviewThumbnail(entry)
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (entry.description.isNotBlank()) {
                Text(entry.description, color = Neutral500, fontSize = 11.sp, maxLines = 2)
            }
            Text(
                listOfNotNull(
                    entry.category.replaceFirstChar { it.uppercase() },
                    if (entry.isFree) "Free" else "Paid",
                ).joinToString(" · "),
                color = Neutral700,
                fontSize = 10.sp,
            )
        }
        if (installed) {
            Text("Installed", color = Neutral500, fontSize = 12.sp)
        } else {
            Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = Red500)) {
                Text(if (entry.isFree) "Install" else "Install (paid)", fontSize = 12.sp, color = White)
            }
        }
    }
}
