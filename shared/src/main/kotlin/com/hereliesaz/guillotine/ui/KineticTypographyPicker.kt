package com.hereliesaz.guillotine.ui

import com.hereliesaz.guillotine.azphalt.AzpMotionInstaller
import com.hereliesaz.guillotine.editor.EditorViewModel

/**
 * Thin controller the caption UI uses to apply / switch / clear kinetic-typography `.azp` presets on a
 * caption. It delegates to the editor, which bakes the preset into `generated` keyframes (see
 * [EditorViewModel.applyCaptionMotion]) so the effect renders in preview and export and stays editable.
 */
object KineticTypographyPicker {

    /**
     * Apply [motionJsonBytes] (the `az-motion` asset payload for [plannedMotion]) to caption [captionId].
     * Switching presets is clean: the editor strips any previously baked keyframes before applying the
     * new ones, leaving the user's own keyframes intact.
     */
    fun applyPreset(
        vm: EditorViewModel,
        plannedMotion: AzpMotionInstaller.PlannedMotion,
        captionId: String,
        motionJsonBytes: ByteArray,
    ) = vm.applyCaptionMotion(captionId, motionJsonBytes, plannedMotion.packageId)

    /** Remove a caption's baked kinetic-typography keyframes and clear its applied-preset marker. */
    fun clearPreset(vm: EditorViewModel, captionId: String) = vm.clearCaptionMotion(captionId)
}
