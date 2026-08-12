package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.FxLayer
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

    /** Whether any layer in [chain] is the file this [panel] installs (i.e. the panel is applied to the clip). */
    fun isApplied(panel: AzpInstalledUi.Panel, chain: List<FxLayer>): Boolean {
        val ext = extensionFor(panel)
        val name = panel.installFileName(ext)
        return chain.any { File(it.path).name == name }
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
                vm.addFxLayer(clipId, FxLayer(kind = FxLayer.KIND_SHADER, path = dest.absolutePath, params = panel.defaultParams))
                Result.Applied
            }
            AzpInstalledUi.RenderKind.LUT -> {
                val dest = write("luts", panel.installFileName(extensionFor(panel)), assetBytes)
                    ?: return Result.Failure("Could not write the LUT.")
                vm.addFxLayer(clipId, FxLayer(kind = FxLayer.KIND_LUT, path = dest.absolutePath))
                Result.Applied
            }
            AzpInstalledUi.RenderKind.OTHER ->
                Result.Unsupported("\"${panel.assetType}\" assets need the extension runtime, which isn't wired yet.")
        }
    }

    /** Removes this package's layer(s) from the clip's FX chain, if any are present. */
    fun remove(vm: EditorViewModel, clipId: String, panel: AzpInstalledUi.Panel, current: com.hereliesaz.guillotine.model.ClipFilters) {
        if (panel.renderKind == AzpInstalledUi.RenderKind.OTHER) return
        val ext = extensionFor(panel)
        val name = panel.installFileName(ext)
        current.effectiveFxChain.filter { File(it.path).name == name }.forEach { vm.removeFxLayer(clipId, it.id) }
    }

    private fun write(subdir: String, name: String, bytes: ByteArray): File? = try {
        val dir = File(DesktopStorage.dataDir, subdir).apply { mkdirs() }
        File(dir, name).apply { writeBytes(bytes) }
    } catch (e: Exception) {
        null
    }
}
