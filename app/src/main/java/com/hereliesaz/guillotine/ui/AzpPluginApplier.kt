package com.hereliesaz.guillotine.ui

import android.content.Context
import com.hereliesaz.guillotine.azphalt.AzpInstallSurfaces
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import java.io.File

/**
 * Applies an installed azphalt package [pluginId] to clip [clipId] using whichever real apply path its
 * content actually supports — the single implementation the Azphalt Store's install flow
 * ([AzphaltStoreScreen]) and the `apply_azp_plugin` MCP tool both call, so "installed from the store"
 * and "applied via the assistant" can't silently diverge on what "applied" means.
 *
 * Before this existed, both call sites either did nothing beyond stamping an unread `azpPluginId` field
 * (the store) or claimed success for that same inert stamp (the MCP tool) for every package kind except
 * kinetic-typography motion — so installing a shader/LUT never actually rendered anything.
 */
object AzpPluginApplier {

    sealed class Outcome {
        /** [surface] is where the applied thing now lives, so a caller can say so without re-deriving it. */
        data class Applied(val surface: AzpInstallSurfaces.Surface) : Outcome()
        /** Verified and installed, but this package's content has no apply path for this clip (yet). */
        data class Unsupported(val message: String) : Outcome()
        /** [pluginId] isn't installed, the clip doesn't exist, or applying it failed outright. */
        data class Failure(val message: String) : Outcome()
    }

    fun apply(context: Context, vm: EditorViewModel, clipId: String, pluginId: String): Outcome {
        val extensionsDir = File(context.filesDir, "extensions")
        val clip = vm.uiState.value.document.clips.find { it.id == clipId }
            ?: return Outcome.Failure("Clip $clipId not found.")

        // Kinetic-typography (motion) packages bake into real caption keyframes — captions only.
        if (clip.type == ClipType.TEXT) {
            val motion = KineticTypographyPicker.listInstalled(extensionsDir).find { it.packageId == pluginId }
            if (motion != null) {
                KineticTypographyPicker.apply(vm, motion, clipId)
                return Outcome.Applied(AzpInstallSurfaces.Surface.CAPTION_MOTION)
            }
        }

        // Otherwise, an asset (shader/LUT) package Guillotine renders natively.
        val panel = AzpInstalledUi.list(extensionsDir, context.packageName).find { it.packageId == pluginId }
        if (panel != null) {
            return when (val r = AzpAssetApplier.apply(context, vm, clipId, panel)) {
                is AzpAssetApplier.Result.Applied -> Outcome.Applied(AzpInstallSurfaces.Surface.CLIP_EXTENSIONS)
                is AzpAssetApplier.Result.Unsupported -> Outcome.Unsupported(r.message)
                is AzpAssetApplier.Result.Failure -> Outcome.Failure(r.message)
            }
        }

        // A caption animation on a non-caption clip isn't "unsupported" — it's the wrong clip. Saying
        // otherwise sent users of most of the catalog looking for a missing feature instead of a caption.
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
}
