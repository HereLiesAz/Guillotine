package com.hereliesaz.guillotine.ui

import android.content.Context
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.FxLayer
import java.io.File

/**
 * Applies an installed azphalt **asset** package to a clip — the runtime step for the extension types
 * Guillotine already renders natively (a GLSL shader, a `.cube` LUT). No sandbox is needed: the asset is
 * declarative data appended to the clip's [com.hereliesaz.guillotine.model.ClipFilters.fxChain], so it
 * takes effect in **both live preview and export** immediately. (`code`-kind extensions are the separate
 * WASM runtime.)
 *
 * Applying a package STACKS it onto the chain rather than replacing whatever was applied before — the
 * "VST-style hosting" this is built to give azphalt packages: several shaders/LUTs on one clip, ordered,
 * individually removable. Before [FxLayer]/`fxChain` existed a clip had exactly one shader slot and one
 * LUT slot; applying a second one silently discarded the first.
 *
 * The `.azp` is re-read and integrity-verified here before its bytes touch disk, so a corrupted package
 * fails instead of installing. The asset lands under `filesDir/shaders` or `filesDir/luts` with a stable,
 * package-derived name so [isApplied] can tell whether a clip currently has a layer for this package.
 */
object AzpAssetApplier {

    sealed class Result {
        object Applied : Result()
        data class Unsupported(val message: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /** Whether any layer in [chain] is the file this [panel] installs (i.e. the panel is applied to the clip). */
    fun isApplied(panel: AzpInstalledUi.Panel, chain: List<FxLayer>): Boolean = appliedLayer(panel, chain) != null

    /** The first layer in [chain] backed by this [panel]'s installed file, if any — the layer whose
     *  `params` a schema control edits live once the panel is applied. */
    fun appliedLayer(panel: AzpInstalledUi.Panel, chain: List<FxLayer>): FxLayer? {
        val ext = extensionFor(panel)
        val name = panel.installFileName(ext)
        return chain.firstOrNull { File(it.path).name == name }
    }

    private fun extensionFor(panel: AzpInstalledUi.Panel): String {
        val fromAsset = panel.assetPath.substringAfterLast('.', "").lowercase()
        return when (panel.renderKind) {
            AzpInstalledUi.RenderKind.SHADER -> if (fromAsset in setOf("isf", "fs", "glsl", "frag")) ".$fromAsset" else ".fs"
            AzpInstalledUi.RenderKind.LUT -> ".cube"
            else -> ".bin"
        }
    }

    fun apply(context: Context, vm: EditorViewModel, clipId: String, panel: AzpInstalledUi.Panel): Result {
        val bytes = try {
            panel.sourceFile.readBytes()
        } catch (e: Exception) {
            return Result.Failure("Could not read the package: ${e.message}")
        }
        val loaded = try {
            AzpPackage.load(bytes) // integrity gate
        } catch (e: AzpPackage.AzpException) {
            return Result.Failure("“${panel.packageName}” failed verification: ${e.message}")
        }
        val assetBytes = loaded.payload[panel.assetPath]
            ?: return Result.Unsupported("This package ships the asset remotely; only bundled assets apply so far.")

        return when (panel.renderKind) {
            AzpInstalledUi.RenderKind.SHADER -> {
                val dest = write(context, "shaders", panel.installFileName(extensionFor(panel)), assetBytes)
                    ?: return Result.Failure("Could not write the shader.")
                // Seed the layer's params from the schema defaults so the controls start meaningful.
                vm.addFxLayer(clipId, FxLayer(kind = FxLayer.KIND_SHADER, path = dest.absolutePath, params = panel.defaultParams))
                Result.Applied
            }
            AzpInstalledUi.RenderKind.LUT -> {
                val dest = write(context, "luts", panel.installFileName(extensionFor(panel)), assetBytes)
                    ?: return Result.Failure("Could not write the LUT.")
                vm.addFxLayer(clipId, FxLayer(kind = FxLayer.KIND_LUT, path = dest.absolutePath))
                Result.Applied
            }
            AzpInstalledUi.RenderKind.OTHER ->
                Result.Unsupported("“${panel.assetType}” assets need the extension runtime, which isn't wired yet.")
        }
    }

    /** Removes this package's layer(s) from the clip's FX chain, if any are present. */
    fun remove(vm: EditorViewModel, clipId: String, panel: AzpInstalledUi.Panel, current: com.hereliesaz.guillotine.model.ClipFilters) {
        if (panel.renderKind == AzpInstalledUi.RenderKind.OTHER) return
        val ext = extensionFor(panel)
        val name = panel.installFileName(ext)
        current.effectiveFxChain.filter { File(it.path).name == name }.forEach { vm.removeFxLayer(clipId, it.id) }
    }

    private fun write(context: Context, subdir: String, name: String, bytes: ByteArray): File? = try {
        val dir = File(context.filesDir, subdir).apply { mkdirs() }
        File(dir, name).apply { writeBytes(bytes) }
    } catch (e: Exception) {
        null
    }
}
