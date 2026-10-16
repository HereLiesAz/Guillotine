package com.hereliesaz.guillotine

import com.hereliesaz.guillotine.VolumeComboDetector.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeComboDetectorTest {
    @Test
    fun loneKeyAppliesOnRelease() {
        val d = VolumeComboDetector()
        assertEquals(Action.NONE, d.onKeyDown(isUp = true))
        assertEquals(Action.VOLUME_UP, d.onKeyUp(isUp = true))
    }

    @Test
    fun comboFiresOnceAndSwallowsReleases() {
        val d = VolumeComboDetector()
        d.onKeyDown(isUp = true)
        assertEquals(Action.COMBO, d.onKeyDown(isUp = false))
        assertEquals(Action.NONE, d.onKeyDown(isUp = false)) // auto-repeat
        assertEquals(Action.NONE, d.onKeyUp(isUp = true))
        assertEquals(Action.NONE, d.onKeyUp(isUp = false))
    }

    @Test
    fun resetPreventsPhantomComboAfterFocusLoss() {
        val d = VolumeComboDetector()
        d.onKeyDown(isUp = true) // its UP goes to another window
        d.reset()
        assertEquals(Action.NONE, d.onKeyDown(isUp = false))
        assertEquals(Action.VOLUME_DOWN, d.onKeyUp(isUp = false))
    }
}
