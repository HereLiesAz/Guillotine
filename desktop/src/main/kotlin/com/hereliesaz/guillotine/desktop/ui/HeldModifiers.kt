package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed

/**
 * Which modifier keys are currently held, tracked globally from the app's own key-event handler
 * (`NleScreen.kt`'s root `onKeyEvent`). `detectDragGestures`'s callbacks don't expose live keyboard
 * state the way a raw `PointerEvent` does, so a mouse-drag on a timeline clip (`ClipView`) reads this
 * at drag-start to pick which Vegas-style edit mode applies: Ctrl held = time-stretch, Alt held over
 * the clip body = slip / over an edge = slide, Shift held = an independent (L/J-cut) trim.
 *
 * `Key.ControlLeft`/`ControlRight` etc. never reach `onKeyEvent` as their own dedicated key on most
 * platforms without also firing for the *combination* they modify, so tracking on every key event
 * (down AND up, not just the ones [handleKey] consumes) is the reliable way to know "is Ctrl still
 * down right now" independent of whatever other key was just pressed alongside it.
 */
object HeldModifiers {
    var ctrl = mutableStateOf(false)
    var alt = mutableStateOf(false)
    var shift = mutableStateOf(false)

    fun update(e: KeyEvent) {
        ctrl.value = e.isCtrlPressed || e.isMetaPressed
        alt.value = e.isAltPressed
        shift.value = e.isShiftPressed
    }
}
