package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.model.KeyframeProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipFilterValidationTest {

    @Test
    fun acceptsInRangeValueCaseInsensitively() {
        val v = ClipFilterValidation.validate("Brightness", 1.2f)
        assertEquals(KeyframeProperty.BRIGHTNESS, v.property)
        assertEquals(1.2f, v.value)
        assertFalse(v.clamped)
    }

    @Test
    fun clampsOutOfRangeValues() {
        val speed = ClipFilterValidation.validate("speed", 0f)
        assertEquals(0.1f, speed.value)
        assertTrue(speed.clamped)
        assertEquals(2f, ClipFilterValidation.validate("volume", 50f).value)
        assertEquals(-1f, ClipFilterValidation.validate("pan", -3f).value)
    }

    @Test
    fun rejectsOpacityWhichHasNoStaticValue() {
        assertThrows(IllegalArgumentException::class.java) { ClipFilterValidation.validate("opacity", 0.5f) }
    }

    @Test
    fun rejectsUnknownPropertyAndNonFinite() {
        assertThrows(IllegalArgumentException::class.java) { ClipFilterValidation.validate("sharpness", 1f) }
        assertThrows(IllegalArgumentException::class.java) { ClipFilterValidation.validate("contrast", Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            ClipFilterValidation.validate("contrast", Float.POSITIVE_INFINITY)
        }
    }
}
