package com.hereliesaz.guillotine.ui

import com.hereliesaz.guillotine.azphalt.AzpMotionInstaller
import com.hereliesaz.guillotine.editor.EditorViewModel
import java.io.File

/**
 * Backs the caption kinetic-typography picker on both platforms: enumerates the installed motion `.azp`
 * packages and applies / clears the chosen one on a caption. Applying delegates to the editor, which
 * bakes the preset into `generated` keyframes (see [EditorViewModel.applyCaptionMotion]) so the effect
 * renders in preview and export and stays hand-editable. Pure JVM, so `:app` and `:desktop` share it —
 * each just hands in its own extensions directory.
 */
object KineticTypographyPicker {

    /** An installed kinetic-typography motion, ready to apply. */
    data class InstalledMotion(
        val packageId: String,
        val name: String,
        val motionBytes: ByteArray,
    ) {
        override fun equals(other: Any?) = other is InstalledMotion && packageId == other.packageId
        override fun hashCode() = packageId.hashCode()
    }

    /**
     * Enumerate the installed kinetic-typography motions in [extensionsDir] (the `.azp` install dir —
     * `filesDir/extensions` on Android, `DesktopStorage.dataDir/extensions` on desktop). A package
     * counts when it carries a bundled `motion` asset. Invalid / non-motion packages are skipped, and
     * the list is name-sorted for a stable UI. This is the single reader both the UI picker and the
     * `apply_azp_plugin` tool share.
     */
    fun listInstalled(extensionsDir: File): List<InstalledMotion> {
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".azp") } ?: return emptyList()
        val out = ArrayList<InstalledMotion>()
        for (f in files) {
            runCatching {
                val plan = AzpMotionInstaller.plan(f.readBytes(), emptySet())
                val motion = plan.motions.firstOrNull() ?: return@runCatching
                val bytes = AzpMotionInstaller.bundledBytes(plan, motion) ?: return@runCatching
                out.add(InstalledMotion(plan.loaded.manifest.id, plan.loaded.manifest.name, bytes))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Apply [motion] to caption [captionId]. Switching presets is clean: the editor strips any
     * previously baked keyframes before applying the new ones, leaving the user's own keyframes intact.
     */
    fun apply(vm: EditorViewModel, motion: InstalledMotion, captionId: String) =
        vm.applyCaptionMotion(captionId, motion.motionBytes, motion.packageId)

    /** Remove a caption's baked kinetic-typography keyframes and clear its applied-preset marker. */
    fun clear(vm: EditorViewModel, captionId: String) = vm.clearCaptionMotion(captionId)
}
