package com.hereliesaz.guillotine

/**
 * Volume-Up + Volume-Down pressed together = one "combo" (play / stop). Pure state machine so it can
 * be unit-tested without an Activity; [MainActivity] feeds it key events and acts on the result.
 *
 * Every volume press is consumed so the combo never leaks a volume tick and fires on the second
 * key-down. A lone key's volume step is applied on key-UP (deferring is what distinguishes a lone tap
 * from a combo), and won't auto-repeat.
 *
 * [reset] must be called when the window loses focus or the Activity pauses: a key-UP delivered to
 * another window (a dialog, the notification shade, another app) never reaches us, and without a
 * reset the stale "held" flag would make the next single press fire a phantom combo.
 */
class VolumeComboDetector {
    enum class Action { NONE, COMBO, VOLUME_UP, VOLUME_DOWN }

    private var upHeld = false
    private var downHeld = false
    private var comboFired = false

    fun onKeyDown(isUp: Boolean): Action {
        if (isUp) upHeld = true else downHeld = true
        if (upHeld && downHeld && !comboFired) {
            comboFired = true
            return Action.COMBO
        }
        return Action.NONE
    }

    fun onKeyUp(isUp: Boolean): Action {
        val partOfCombo = comboFired // this release belongs to a combo → don't bump volume
        // A key-UP with no matching key-DOWN (pressed before we had focus, or after a reset) is
        // passed through as a normal volume step only if we'd seen its press; otherwise ignore it.
        val wasHeld = if (isUp) upHeld else downHeld
        if (isUp) upHeld = false else downHeld = false
        if (!upHeld && !downHeld) comboFired = false
        return when {
            partOfCombo || !wasHeld -> Action.NONE
            isUp -> Action.VOLUME_UP
            else -> Action.VOLUME_DOWN
        }
    }

    fun reset() {
        upHeld = false
        downHeld = false
        comboFired = false
    }
}
