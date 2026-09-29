package com.hereliesaz.guillotine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class StreamingStftTest {

    private val signal = FloatArray(16_000) { i -> (0.3 * sin(2 * Math.PI * 220 * i / 16_000) + 0.01 * ((i * 7919) % 101 - 50) / 50.0).toFloat() }

    @Test fun identityReconstructsTheInput() {
        val out = StreamingStft.run(signal) { re, im -> re to im }
        assertEquals(signal.size, out.size)
        val maxErr = signal.indices.maxOf { abs(out[it] - signal[it]) }
        assertTrue("max reconstruction error $maxErr", maxErr < 1e-5f)
    }

    @Test fun zeroingEveryFrameSilencesTheOutput() {
        val out = StreamingStft.run(signal) { re, im -> FloatArray(re.size) to FloatArray(im.size) }
        assertTrue(out.all { it == 0f })
    }

    @Test fun framesHaveTheModelsBinCount() {
        var seen = 0
        StreamingStft.run(FloatArray(1024)) { re, im -> seen = re.size; re to im }
        assertEquals(257, seen)
    }
}
