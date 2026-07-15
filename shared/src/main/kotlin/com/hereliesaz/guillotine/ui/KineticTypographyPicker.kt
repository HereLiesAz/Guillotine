package com.hereliesaz.guillotine.ui

import com.hereliesaz.guillotine.azphalt.AzpMotionInstaller
import com.hereliesaz.guillotine.azphalt.AzpMotionInterpreter

/**
 * A UI component/controller that presents installed kinetic typography `.azp` presets to the user.
 * It allows the user to apply, switch, or clear kinetic typography effects on a caption track.
 */
object KineticTypographyPicker {

    /**
     * Applies the given kinetic typography [preset] to the active caption track.
     * The host would fetch the bundled/remote JSON bytes via [AzpMotionInstaller],
     * parse them using [AzpMotionInterpreter.parse], and then "bake" the keyframes
     * into the native caption data structure.
     */
    fun applyPreset(preset: AzpMotionInstaller.PlannedMotion, captionId: String, rawJsonBytes: ByteArray) {
        val parsedPreset = AzpMotionInterpreter.parse(rawJsonBytes)
        
        // Pseudo-code for baking keyframes:
        // 1. Get Caption object by captionId.
        // 2. Read user overrides or preset defaults for stagger and staggerMode.
        // 3. For each character/word in the caption:
        //    a. Calculate its localized start time based on text stagger.
        //    b. Map the normalized tracks (0..1) to absolute timeline MS based on duration.
        //    c. Inject absolute keyframes into the Caption's timeline properties.
        // 4. Invalidate renderer.
        
        println("Applied kinetic preset '${preset.packageId}' to caption $captionId using format '${preset.format}'")
    }

    /**
     * Clears all kinetic typography keyframes from the given caption.
     */
    fun clearPreset(captionId: String) {
        // Pseudo-code:
        // Remove all baked keyframes (opacity, scale, position, rotation, blur) from the caption
        // that were inserted by the interpreter.
        println("Cleared kinetic preset from caption $captionId")
    }
}
