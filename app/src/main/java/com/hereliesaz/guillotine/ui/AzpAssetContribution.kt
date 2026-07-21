package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import java.io.File

/**
 * Renders the control panels ([AzpUiSchema]) of installed azphalt asset packages in the clip-properties
 * panel, through the standard [ClipPanelContribution] seam, and **applies** the ones Guillotine can run
 * natively (GLSL shaders, `.cube` LUTs) to the selected clip.
 *
 * When a shader package is applied, its schema controls drive the clip's real `shaderParams`, so moving a
 * slider changes the picture in preview and export — the loop is closed end-to-end for asset extensions.
 * For not-yet-applicable types the controls edit the extension's stored params ([AzpParamStore]) and the
 * section says applying awaits the `code` runtime (azphalt jobs #2–#5).
 */
class AzpAssetContribution : ClipPanelContribution {
    override val id: String = "com.hereliesaz.guillotine.azphalt-assets"
    override val title: String = "Extensions"

    override fun appliesTo(clip: TimelineClip, state: EditorUiState): Boolean = true

    @Composable
    override fun Content(vm: EditorViewModel, clip: TimelineClip) {
        val context = LocalContext.current
        val hostAppId = context.packageName
        val panels by produceState(emptyList<AzpInstalledUi.Panel>(), hostAppId) {
            value = withContext(Dispatchers.IO) {
                AzpInstalledUi.list(File(context.filesDir, "extensions"), hostAppId)
            }
        }
        panels.forEach { panel -> PanelSection(vm, clip, panel) }
    }

    @Composable
    private fun PanelSection(vm: EditorViewModel, clip: TimelineClip, panel: AzpInstalledUi.Panel) {
        val context = LocalContext.current
        val filters = clip.filters
        val renderPath = when (panel.renderKind) {
            AzpInstalledUi.RenderKind.SHADER -> filters.shaderPath
            AzpInstalledUi.RenderKind.LUT -> filters.lutPath
            AzpInstalledUi.RenderKind.OTHER -> ""
        }
        val applied = AzpAssetApplier.isApplied(panel, renderPath)
        // A shader's controls edit the LIVE render params once applied; otherwise they edit stored config.
        val liveShader = applied && panel.renderKind == AzpInstalledUi.RenderKind.SHADER

        var storedParams by remember(panel.packageId) { mutableStateOf(AzpParamStore.load(context, panel.packageId)) }
        var status by remember(panel.packageId) { mutableStateOf<String?>(null) }

        ClipPanelSection(panel.packageName) {
            AzpUiSchemaControls(
                schema = panel.schema,
                value = { key ->
                    if (liveShader) filters.shaderParams[key]?.let { JsonPrimitive(it) } else storedParams[key]
                },
                onValue = { key, v ->
                    if (liveShader) {
                        (v as? JsonPrimitive)?.floatOrNull?.let { f ->
                            vm.updateClipFilters(clip.id) { it.copy(shaderParams = it.shaderParams + (key to f)) }
                        }
                    } else {
                        storedParams = storedParams + (key to v)
                        AzpParamStore.save(context, panel.packageId, storedParams)
                    }
                },
                onAction = { },
            )

            when (panel.renderKind) {
                AzpInstalledUi.RenderKind.SHADER, AzpInstalledUi.RenderKind.LUT -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (applied) {
                            Text("Applied to this clip", color = Neutral500, fontSize = 11.sp)
                            TextButton(onClick = { AzpAssetApplier.remove(vm, clip.id, panel, filters) }) {
                                Text("Remove", color = White, fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    status = when (val r = AzpAssetApplier.apply(context, vm, clip.id, panel)) {
                                        is AzpAssetApplier.Result.Applied -> null
                                        is AzpAssetApplier.Result.Unsupported -> r.message
                                        is AzpAssetApplier.Result.Failure -> r.message
                                    }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(),
                            ) { Text("Apply to clip", fontSize = 12.sp) }
                        }
                    }
                    status?.let { Text(it, color = Neutral500, fontSize = 10.sp) }
                }
                AzpInstalledUi.RenderKind.OTHER ->
                    Text(
                        "Saved as this extension's settings. Applying to media arrives with the extension runtime.",
                        color = Neutral500, fontSize = 10.sp,
                    )
            }
        }
    }
}
