package com.hereliesaz.guillotine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CubeLutTest {

    /** An identity 2x2x2 LUT body (red fastest), optionally with extra header lines. */
    private fun identity(header: String = "LUT_3D_SIZE 2", scale: Float = 1f): String = buildString {
        appendLine("TITLE \"id\"")
        appendLine("# comment")
        appendLine(header)
        for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("${r * scale} ${g * scale} ${b * scale}")
    }

    @Test fun parsesIdentityInFileOrder() {
        val lut = CubeLut.parse(identity())
        assertEquals(2, lut.size)
        val i = lut.indexOf(1, 0, 1)
        assertEquals(listOf(1f, 0f, 1f), listOf(lut.entries[i], lut.entries[i + 1], lut.entries[i + 2]))
    }

    @Test fun domainRescalesIntoUnitRange() {
        val lut = CubeLut.parse(identity("LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 4 4 4", scale = 4f))
        assertEquals(1f, lut.entries[lut.indexOf(1, 1, 1)], 0f)
    }

    @Test fun inputRangeIsHonoured() {
        val lut = CubeLut.parse(identity("LUT_3D_SIZE 2\nLUT_3D_INPUT_RANGE 0 4", scale = 4f))
        assertEquals(1f, lut.entries[lut.indexOf(1, 0, 0)], 0f)
        assertEquals(0f, lut.entries[lut.indexOf(1, 0, 0) + 1], 0f)
    }

    @Test fun bareSizeLineIsAParseErrorNotACrash() {
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(identity("LUT_3D_SIZE")) }
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(identity("LUT_3D_SIZE x")) }
    }

    @Test fun nonFiniteValuesAreRejected() {
        val text = identity().replaceFirst("0.0 0.0 0.0", "NaN 0 0")
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(text) }
        val inf = identity().replaceFirst("0.0 0.0 0.0", "Infinity 0 0")
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(inf) }
    }

    @Test fun wrongRowCountAnd1dAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(identity() + "0 0 0\n") }
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n") }
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse("0 0 0\n") }
    }
}
