package com.hereliesaz.guillotine.ui

import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.TimelineClip

/**
 * The first real [ClipPanelContribution]: the caption kinetic-typography picker. It applies to a
 * selected text clip and, when at least one motion `.azp` is installed, lets the user bake / clear an
 * animation on the caption. This is the built-in proof that plugin UI flows through the panel seam —
 * the same path an azphalt UI-schema section will use later.
 *
 * The "any motions installed?" check is I/O, so it stays inside [Content] (which renders nothing when
 * none are installed, exactly as before); [appliesTo] only does the cheap clip-type gate.
 */
class KineticTypographyContribution : ClipPanelContribution {
    override val id: String = "com.hereliesaz.guillotine.kinetic-typography"
    override val title: String = "Kinetic type"

    override fun appliesTo(clip: TimelineClip, state: EditorUiState): Boolean =
        clip.type == ClipType.TEXT

    @Composable
    override fun Content(vm: EditorViewModel, clip: TimelineClip) {
        KineticTypeToolInline(vm, clip)
    }
}
