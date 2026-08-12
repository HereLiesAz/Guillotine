package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.desktop.platform.DesktopAzpAssetApplier
import com.hereliesaz.guillotine.desktop.platform.DesktopPluginApplier
import com.hereliesaz.guillotine.desktop.platform.DesktopStorage
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral900
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.KineticTypographyPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Desktop mirror of the app's [com.hereliesaz.guillotine.ui.ExtensionsManagerScreen] — see its doc. */
@Composable
fun DesktopExtensionsManagerScreen(vm: EditorViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val extensionsDir = remember { File(DesktopStorage.dataDir, "extensions") }
    val state by vm.uiState.collectAsState()

    var rows by remember { mutableStateOf<List<DesktopExtensionRow>?>(null) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) {
            buildDesktopRows(extensionsDir, DesktopPluginApplier.HOST_APP_ID, state.document.clips)
        }
    }
    LaunchedEffect(state.document.clips) { reload() }

    fun clearFromClips(row: DesktopExtensionRow) {
        scope.launch {
            withContext(Dispatchers.IO) {
                row.appliedClipIds.forEach { clipId ->
                    when (row.kind) {
                        DesktopExtensionKind.MOTION -> vm.clearCaptionMotion(clipId)
                        DesktopExtensionKind.ASSET -> row.panel?.let { panel ->
                            state.document.clips.find { it.id == clipId }?.let { clip ->
                                DesktopAzpAssetApplier.remove(vm, clipId, panel, clip.filters)
                            }
                        }
                        DesktopExtensionKind.OTHER -> {}
                    }
                }
            }
            reload()
        }
    }

    fun uninstall(row: DesktopExtensionRow) {
        scope.launch {
            withContext(Dispatchers.IO) {
                row.appliedClipIds.forEach { clipId ->
                    when (row.kind) {
                        DesktopExtensionKind.MOTION -> vm.clearCaptionMotion(clipId)
                        DesktopExtensionKind.ASSET -> row.panel?.let { panel ->
                            state.document.clips.find { it.id == clipId }?.let { clip ->
                                DesktopAzpAssetApplier.remove(vm, clipId, panel, clip.filters)
                            }
                        }
                        DesktopExtensionKind.OTHER -> {}
                    }
                }
                row.sourceFile.delete()
            }
            reload()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .size(600.dp, 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Neutral900)
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Installed extensions", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Neutral400) }
            }
            val current = rows
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = Neutral400, fontSize = 12.sp)
                }
                current.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing installed yet — add extensions from the Store.", color = Neutral400, fontSize = 12.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(current, key = { it.id }) { row ->
                        DesktopExtensionRowCard(row, onRemoveFromClips = { clearFromClips(row) }, onUninstall = { uninstall(row) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopExtensionRowCard(row: DesktopExtensionRow, onRemoveFromClips: () -> Unit, onUninstall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Neutral800)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(row.categoryLabel, color = Neutral500, fontSize = 11.sp)
            Text(
                if (row.appliedClipIds.isEmpty()) "Not applied to anything right now." else "Applied to ${row.appliedClipIds.size} clip(s).",
                color = Neutral500,
                fontSize = 11.sp,
            )
        }
        if (row.appliedClipIds.isNotEmpty()) {
            TextButton(onClick = onRemoveFromClips) { Text("Remove", fontSize = 12.sp) }
        }
        TextButton(onClick = onUninstall) { Text("Uninstall", fontSize = 12.sp) }
    }
}

private enum class DesktopExtensionKind { ASSET, MOTION, OTHER }

private data class DesktopExtensionRow(
    val id: String,
    val name: String,
    val categoryLabel: String,
    val kind: DesktopExtensionKind,
    val sourceFile: File,
    val appliedClipIds: List<String>,
    val panel: AzpInstalledUi.Panel? = null,
)

private fun buildDesktopRows(extensionsDir: File, hostAppId: String, clips: List<TimelineClip>): List<DesktopExtensionRow> {
    val manifestsByFile = extensionsDir.listFiles { _, name -> name.endsWith(".azp") }.orEmpty()
        .mapNotNull { f -> runCatching { AzpPackage.read(f.readBytes()).manifest }.getOrNull()?.let { f to it } }
    val fileForId = manifestsByFile.associate { (f, m) -> m.id to f }

    val assetPanels = AzpInstalledUi.list(extensionsDir, hostAppId)
    val motions = KineticTypographyPicker.listInstalled(extensionsDir)
    val assetRows = assetPanels.map { panel ->
        val applied = clips.filter { it.type == ClipType.VIDEO }
            .filter { DesktopAzpAssetApplier.isApplied(panel, it.filters.shaderPath) || DesktopAzpAssetApplier.isApplied(panel, it.filters.lutPath) }
            .map { it.id }
        DesktopExtensionRow(
            id = "${panel.packageId}/${panel.assetType}",
            name = panel.packageName,
            categoryLabel = panel.assetType.replaceFirstChar { it.uppercase() },
            kind = DesktopExtensionKind.ASSET,
            sourceFile = panel.sourceFile,
            appliedClipIds = applied,
            panel = panel,
        )
    }
    val motionRows = motions.mapNotNull { motion ->
        val sourceFile = fileForId[motion.packageId] ?: return@mapNotNull null
        val applied = clips.filter { it.type == ClipType.TEXT && it.azpPluginId == motion.packageId }.map { it.id }
        DesktopExtensionRow(
            id = motion.packageId,
            name = motion.name,
            categoryLabel = "Kinetic type",
            kind = DesktopExtensionKind.MOTION,
            sourceFile = sourceFile,
            appliedClipIds = applied,
        )
    }
    val claimedIds = (assetPanels.map { it.packageId } + motions.map { it.packageId }).toSet()
    val otherRows = manifestsByFile
        .filter { (_, manifest) -> manifest.id !in claimedIds && manifest.targetsApp(hostAppId) }
        .map { (f, manifest) ->
            DesktopExtensionRow(
                id = manifest.id,
                name = manifest.name,
                categoryLabel = manifest.kind.replaceFirstChar { it.uppercase() },
                kind = DesktopExtensionKind.OTHER,
                sourceFile = f,
                appliedClipIds = emptyList(),
            )
        }
    return (assetRows + motionRows + otherRows).sortedBy { it.name.lowercase() }
}
