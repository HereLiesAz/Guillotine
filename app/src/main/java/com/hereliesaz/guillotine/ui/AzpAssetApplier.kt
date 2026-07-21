package com.hereliesaz.guillotine.ui

import android.content.Context
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.editor.EditorViewModel
import java.io.File

/**
 * Applies an installed azphalt **asset** package to a clip — the runtime step for the extension types
 * Guillotine already renders natively (a GLSL shader, a `.cube` LUT). No sandbox is needed: the asset is
 * declarative data written into the clip's existing render filters, so it takes effect in **both live
 * preview and export** immediately. (`code`-kind extensions are the separate WASM runtime.)
 *
 * The `.azp` is re-read and integrity-verified here before its bytes touch disk, so a corrupted package
 * fails instead of installing. The asset lands under `filesDir/shaders` or `filesDir/luts` with a stable,
 * package-derived name so [isApplied] can tell whether a clip currently uses this package.
 */
object AzpAssetApplier {

    sealed class Result {
        object Applied : Result()
        data class Unsupported(val message: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /** Whether [shaderOrLutPath] is the file this [panel] installs (i.e. the panel is applied to a clip). */
    fun isApplied(panel: AzpInstalledUi.Panel, shaderOrLutPath: String): Boolean {
        if (shaderOrLutPath.isBlank()) return false
        val ext = extensionFor(panel)
        return File(shaderOrLutPath).name == panel.installFileName(ext)
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
                // Seed the shader's params from the schema defaults so the controls start meaningful.
                vm.updateClipFilters(clipId) { it.copy(shaderPath = dest.absolutePath, shaderParams = panel.defaultParams) }
                Result.Applied
            }
            AzpInstalledUi.RenderKind.LUT -> {
                val dest = write(context, "luts", panel.installFileName(extensionFor(panel)), assetBytes)
                    ?: return Result.Failure("Could not write the LUT.")
                vm.updateClipFilters(clipId) { it.copy(lutPath = dest.absolutePath) }
                Result.Applied
            }
            AzpInstalledUi.RenderKind.OTHER ->
                Result.Unsupported("“${panel.assetType}” assets need the extension runtime, which isn't wired yet.")
        }
    }

    /** Remove this package's effect from the clip (shader or LUT), if it's the one applied. */
    fun remove(vm: EditorViewModel, clipId: String, panel: AzpInstalledUi.Panel, current: com.hereliesaz.guillotine.model.ClipFilters) {
        when (panel.renderKind) {
            AzpInstalledUi.RenderKind.SHADER ->
                if (isApplied(panel, current.shaderPath)) vm.updateClipFilters(clipId) { it.copy(shaderPath = "", shaderParams = emptyMap()) }
            AzpInstalledUi.RenderKind.LUT ->
                if (isApplied(panel, current.lutPath)) vm.updateClipFilters(clipId) { it.copy(lutPath = "") }
            AzpInstalledUi.RenderKind.OTHER -> {}
        }
    }

    private fun write(context: Context, subdir: String, name: String, bytes: ByteArray): File? = try {
        val dir = File(context.filesDir, subdir).apply { mkdirs() }
        File(dir, name).apply { writeBytes(bytes) }
    } catch (e: Exception) {
        null
    }
}
