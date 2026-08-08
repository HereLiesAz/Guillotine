package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.editor.EditorViewModel
import java.io.File

/**
 * Desktop counterpart of the app's `AzpAssetApplier` (`app/.../ui/AzpAssetApplier.kt`) — applies an
 * installed azphalt **asset** package (a GLSL shader, a `.cube` LUT) to a clip by writing its bytes into
 * the clip's real render filters, the same fields [com.hereliesaz.guillotine.desktop.media.applyColorEffects]
 * already reads for preview and the same ones the exporter reads for the file.
 *
 * Before this existed, `applyAzpPlugin` (`DesktopMcpTools.kt`) only had a real apply path for
 * kinetic-typography motion; every other asset kind fell through to stamping the clip's unread
 * `azpPluginId` field and returning success — so applying a shader or LUT via the assistant reported
 * "Applied" while nothing changed in preview or export. Bytes land under
 * `DesktopStorage.dataDir/{shaders,luts}` with the same package-derived filename the app side uses, so
 * `isApplied` can tell whether a clip currently uses a given package.
 */
object DesktopAzpAssetApplier {

    sealed class Result {
        object Applied : Result()
        data class Unsupported(val message: String) : Result()
        data class Failure(val message: String) : Result()
    }

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

    fun apply(vm: EditorViewModel, clipId: String, panel: AzpInstalledUi.Panel): Result {
        val bytes = try {
            panel.sourceFile.readBytes()
        } catch (e: Exception) {
            return Result.Failure("Could not read the package: ${e.message}")
        }
        val loaded = try {
            AzpPackage.load(bytes) // integrity gate
        } catch (e: AzpPackage.AzpException) {
            return Result.Failure("\"${panel.packageName}\" failed verification: ${e.message}")
        }
        val assetBytes = loaded.payload[panel.assetPath]
            ?: return Result.Unsupported("This package ships the asset remotely; only bundled assets apply so far.")

        return when (panel.renderKind) {
            AzpInstalledUi.RenderKind.SHADER -> {
                val dest = write("shaders", panel.installFileName(extensionFor(panel)), assetBytes)
                    ?: return Result.Failure("Could not write the shader.")
                vm.updateClipFilters(clipId) { it.copy(shaderPath = dest.absolutePath, shaderParams = panel.defaultParams) }
                Result.Applied
            }
            AzpInstalledUi.RenderKind.LUT -> {
                val dest = write("luts", panel.installFileName(extensionFor(panel)), assetBytes)
                    ?: return Result.Failure("Could not write the LUT.")
                vm.updateClipFilters(clipId) { it.copy(lutPath = dest.absolutePath) }
                Result.Applied
            }
            AzpInstalledUi.RenderKind.OTHER ->
                Result.Unsupported("\"${panel.assetType}\" assets need the extension runtime, which isn't wired yet.")
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

    private fun write(subdir: String, name: String, bytes: ByteArray): File? = try {
        val dir = File(DesktopStorage.dataDir, subdir).apply { mkdirs() }
        File(dir, name).apply { writeBytes(bytes) }
    } catch (e: Exception) {
        null
    }
}
