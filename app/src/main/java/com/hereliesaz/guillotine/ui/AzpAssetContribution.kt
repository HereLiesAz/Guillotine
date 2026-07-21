package com.hereliesaz.guillotine.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.theme.Neutral500
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders the control panels ([AzpUiSchema]) of installed azphalt asset packages in the clip-properties
 * panel, through the standard [ClipPanelContribution] seam — the concrete host side of azphalt's
 * UI-schema job (#6). Each installed package that ships a `ui` schema appears as a section whose native
 * controls edit and persist that extension's `params` ([AzpParamStore]).
 *
 * Honest scope: this **renders the schema and stores the params** (both real). It does not yet transform
 * the clip — running the extension against media is the azphalt runtime (jobs #2–#5), still ahead — so
 * the section says so rather than implying an effect. When the runtime lands, it reads these same params.
 */
class AzpAssetContribution : ClipPanelContribution {
    override val id: String = "com.hereliesaz.guillotine.azphalt-assets"
    override val title: String = "Extensions"

    // Cheap gate only; the actual "is anything installed?" check is I/O and lives in Content, which
    // renders nothing when no installed package ships a ui schema.
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
        panels.forEach { panel ->
            var params by remember(panel.packageId) {
                mutableStateOf(AzpParamStore.load(context, panel.packageId))
            }
            ClipPanelSection(panel.packageName) {
                AzpUiSchemaControls(
                    schema = panel.schema,
                    value = { key -> params[key] },
                    onValue = { key, v ->
                        params = params + (key to v)
                        AzpParamStore.save(context, panel.packageId, params)
                    },
                    // Actions have no runtime to fire into yet; ignore rather than fake a result.
                    onAction = { },
                )
                Text(
                    "Saved as this extension's settings. Applying it to media arrives with the extension runtime.",
                    color = Neutral500,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
