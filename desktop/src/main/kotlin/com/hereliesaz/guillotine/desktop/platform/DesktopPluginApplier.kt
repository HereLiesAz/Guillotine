package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.azphalt.AzpInstallSurfaces
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.ui.KineticTypographyPicker
import java.io.File

/**
 * Desktop mirror of the app's [com.hereliesaz.guillotine.ui.AzpPluginApplier] — applies an installed
 * azphalt package [pluginId] to clip [clipId] using whichever real apply path its content supports, so
 * the desktop Store's install flow and the `apply_azp_plugin` MCP tool (previously two independent
 * copies of this logic in `DesktopMcpTools`) share one implementation and can't diverge on what
 * "applied" means.
 */
object DesktopPluginApplier {

    /** No per-app host scoping exists on desktop yet — see [DesktopMcpTools]'s identical constant. */
    const val HOST_APP_ID = "com.hereliesaz.guillotine.desktop"

    sealed class Outcome {
        data class Applied(val surface: AzpInstallSurfaces.Surface) : Outcome()
        data class Unsupported(val message: String) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    fun apply(vm: EditorViewModel, clipId: String, pluginId: String, extensionsDir: File): Outcome {
        val clip = vm.uiState.value.document.clips.find { it.id == clipId }
            ?: return Outcome.Failure("Clip $clipId not found.")

        if (clip.type == ClipType.TEXT) {
            val motion = KineticTypographyPicker.listInstalled(extensionsDir).find { it.packageId == pluginId }
            if (motion != null) {
                KineticTypographyPicker.apply(vm, motion, clipId)
                return Outcome.Applied(AzpInstallSurfaces.Surface.CAPTION_MOTION)
            }
        }

        val panel = AzpInstalledUi.list(extensionsDir, HOST_APP_ID).find { it.packageId == pluginId }
        if (panel != null) {
            return when (val r = DesktopAzpAssetApplier.apply(vm, clipId, panel)) {
                is DesktopAzpAssetApplier.Result.Applied -> Outcome.Applied(AzpInstallSurfaces.Surface.CLIP_EXTENSIONS)
                is DesktopAzpAssetApplier.Result.Unsupported -> Outcome.Unsupported(r.message)
                is DesktopAzpAssetApplier.Result.Failure -> Outcome.Failure(r.message)
            }
        }

        val isMotion = KineticTypographyPicker.listInstalled(extensionsDir).any { it.packageId == pluginId }
        if (isMotion) {
            return Outcome.Unsupported(
                "“$pluginId” is a caption animation. Select a caption (a text clip) to apply it.",
            )
        }

        val installed = extensionsDir.listFiles { _, name -> name.endsWith(".azp") }
            ?.any { f -> runCatching { AzpPackage.load(f.readBytes()).manifest.id == pluginId }.getOrDefault(false) }
            ?: false
        return if (installed) {
            Outcome.Unsupported(
                "“$pluginId” doesn't have a shader, LUT, or caption-motion asset Guillotine can apply to a clip yet.",
            )
        } else {
            Outcome.Failure("Plugin $pluginId not found in the extensions directory.")
        }
    }

    /** Clears whatever of [pluginId]'s effect is currently applied to [clipId] — caption motion or asset filters. */
    fun clear(vm: EditorViewModel, clipId: String, extensionsDir: File) {
        vm.clearCaptionMotion(clipId)
        val clip = vm.uiState.value.document.clips.find { it.id == clipId } ?: return
        AzpInstalledUi.list(extensionsDir, HOST_APP_ID).forEach { panel ->
            DesktopAzpAssetApplier.remove(vm, clipId, panel, clip.filters)
        }
    }
}
