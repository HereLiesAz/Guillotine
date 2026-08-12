package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything currently installed from the Azphalt Store, and — the part no store's install flow can
 * answer on its own — which of it is actually applied right now, and to what. [AzphaltStoreScreen]'s
 * "Installed" badge only says a package is *on disk*; this is the "what have I got, and where is it
 * doing something" view, separate because that question spans the whole project's clips, not one
 * install action.
 *
 * Combines the three installed-package enumerations that already exist for different apply paths —
 * asset panels ([AzpInstalledUi.list], shader/LUT), motion presets
 * ([KineticTypographyPicker.listInstalled], captions) — plus any other installed `.azp` neither one
 * claims (models, `code`/`app`/`mcp`/`pack` packages with no in-app renderer yet), so nothing installed
 * is invisible here even if it isn't wired to a clip.
 */
@Composable
fun ExtensionsManagerScreen(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extensionsDir = remember { File(context.filesDir, "extensions") }
    val state by vm.uiState.collectAsState()

    var items by remember { mutableStateOf<List<ExtensionRow>?>(null) }

    suspend fun reload() {
        items = withContext(Dispatchers.IO) {
            buildRows(extensionsDir, context.packageName, state.document.clips)
        }
    }

    LaunchedEffect(state.document.clips) { reload() }

    fun uninstall(row: ExtensionRow) {
        scope.launch {
            withContext(Dispatchers.IO) {
                row.appliedClipIds.forEach { clipId ->
                    when (row.kind) {
                        ExtensionKind.MOTION -> vm.clearCaptionMotion(clipId)
                        ExtensionKind.ASSET -> row.panel?.let { panel ->
                            val clip = state.document.clips.find { it.id == clipId }
                            if (clip != null) AzpAssetApplier.remove(vm, clipId, panel, clip.filters)
                        }
                        ExtensionKind.OTHER -> {}
                    }
                }
                row.sourceFile.delete()
            }
            reload()
        }
    }

    fun removeFromClips(row: ExtensionRow) {
        scope.launch {
            withContext(Dispatchers.IO) {
                row.appliedClipIds.forEach { clipId ->
                    when (row.kind) {
                        ExtensionKind.MOTION -> vm.clearCaptionMotion(clipId)
                        ExtensionKind.ASSET -> row.panel?.let { panel ->
                            val clip = state.document.clips.find { it.id == clipId }
                            if (clip != null) AzpAssetApplier.remove(vm, clipId, panel, clip.filters)
                        }
                        ExtensionKind.OTHER -> {}
                    }
                }
            }
            reload()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Installed extensions", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                val rows = items
                when {
                    rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Nothing installed yet — add extensions from the Store.", color = Neutral400)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(rows, key = { it.id }) { row ->
                            ExtensionRowCard(
                                row,
                                onRemoveFromClips = { removeFromClips(row) },
                                onUninstall = { uninstall(row) },
                            )
                            Spacer(Modifier.width(0.dp).padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionRowCard(row: ExtensionRow, onRemoveFromClips: () -> Unit, onUninstall: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, fontWeight = FontWeight.Bold)
                Text(row.categoryLabel, color = Neutral500)
                Text(
                    if (row.appliedClipIds.isEmpty()) {
                        "Not applied to anything right now."
                    } else {
                        "Applied to ${row.appliedClipIds.size} clip${if (row.appliedClipIds.size == 1) "" else "s"} in this project."
                    },
                    color = if (row.appliedClipIds.isEmpty()) Neutral500 else Neutral400,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (row.appliedClipIds.isNotEmpty()) {
                    TextButton(onClick = onRemoveFromClips) { Text("Remove from clips") }
                }
                TextButton(onClick = onUninstall) { Text("Uninstall") }
            }
        }
    }
}

private enum class ExtensionKind { ASSET, MOTION, OTHER }

private data class ExtensionRow(
    val id: String,
    val name: String,
    val categoryLabel: String,
    val kind: ExtensionKind,
    val sourceFile: File,
    val appliedClipIds: List<String>,
    /** Non-null only for [ExtensionKind.ASSET] — needed to remove a shader/LUT from a clip's filters. */
    val panel: AzpInstalledUi.Panel? = null,
)

/** Blocking — call off the main thread. Walks the extensions dir once and cross-references [clips]. */
private fun buildRows(extensionsDir: File, hostAppId: String, clips: List<com.hereliesaz.guillotine.model.TimelineClip>): List<ExtensionRow> {
    // One parse pass over every installed .azp, reused for the asset/motion cross-reference below and
    // for whatever neither of those two claims (a motion's own InstalledMotion has no sourceFile).
    val manifestsByFile = extensionsDir.listFiles { _, name -> name.endsWith(".azp") }.orEmpty()
        .mapNotNull { f -> runCatching { AzpPackage.read(f.readBytes()).manifest }.getOrNull()?.let { f to it } }
    val fileForId = manifestsByFile.associate { (f, m) -> m.id to f }

    val assetPanels = AzpInstalledUi.list(extensionsDir, hostAppId)
    val motions = KineticTypographyPicker.listInstalled(extensionsDir)
    val assetRows = assetPanels.map { panel ->
        val applied = clips.filter { it.type == ClipType.VIDEO }
            .filter { AzpAssetApplier.isApplied(panel, it.filters.shaderPath) || AzpAssetApplier.isApplied(panel, it.filters.lutPath) }
            .map { it.id }
        ExtensionRow(
            id = "${panel.packageId}/${panel.assetType}",
            name = panel.packageName,
            categoryLabel = panel.assetType.replaceFirstChar { it.uppercase() },
            kind = ExtensionKind.ASSET,
            sourceFile = panel.sourceFile,
            appliedClipIds = applied,
            panel = panel,
        )
    }
    val motionRows = motions.mapNotNull { motion ->
        val sourceFile = fileForId[motion.packageId] ?: return@mapNotNull null
        val applied = clips.filter { it.type == ClipType.TEXT && it.azpPluginId == motion.packageId }.map { it.id }
        ExtensionRow(
            id = motion.packageId,
            name = motion.name,
            categoryLabel = "Kinetic type",
            kind = ExtensionKind.MOTION,
            sourceFile = sourceFile,
            appliedClipIds = applied,
        )
    }
    // Anything else installed that neither list above claims (AI models, or code/app/mcp/pack packages
    // with no in-app renderer yet) — still shown, honestly, as "installed" with nowhere it applies.
    val claimedIds = (assetPanels.map { it.packageId } + motions.map { it.packageId }).toSet()
    val otherRows = manifestsByFile
        .filter { (_, manifest) -> manifest.id !in claimedIds && manifest.targetsApp(hostAppId) }
        .map { (f, manifest) ->
            ExtensionRow(
                id = manifest.id,
                name = manifest.name,
                categoryLabel = manifest.kind.replaceFirstChar { it.uppercase() },
                kind = ExtensionKind.OTHER,
                sourceFile = f,
                appliedClipIds = emptyList(),
            )
        }
    return (assetRows + motionRows + otherRows).sortedBy { it.name.lowercase() }
}
