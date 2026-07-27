package com.hereliesaz.guillotine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * [BeatAnalyzer] has real signal-processing logic (spectral-flux onset detection, autocorrelation
 * tempo estimation, phase-aligned beat grid, downbeat anchoring) behind the "edit to the beat" MCP
 * tools (`get_beat_map`, `cut_to_beats`, `apply_on_beat`, `assemble_music_video`) — but had zero test
 * coverage anywhere in the repo. These tests build a synthetic click track at a known BPM (a
 * decaying tone burst on every beat, near-silence between) and confirm the analyzer actually
 * recovers that tempo and beat grid, rather than only ever having been exercised on a real device.
 */
class BeatAnalyzerTest {

    private val sampleRate = 22050

    /**
     * A mono click track: a short decaying [clickFreqHz] burst at every beat of [bpm], repeated for
     * [seconds], with a faint noise floor between clicks (never literal silence, like real audio).
     */
    private fun syntheticClickTrack(bpm: Float, seconds: Double, clickFreqHz: Double = 2000.0): FloatArray {
        val n = (sampleRate * seconds).toInt()
        val periodSamples = (60.0 / bpm * sampleRate).toInt()
        val clickSamples = (0.03 * sampleRate).toInt() // a 30ms decaying burst
        val rng = java.util.Random(42)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val posInBeat = i % periodSamples
            out[i] = if (posInBeat < clickSamples) {
                val t = posInBeat.toDouble() / sampleRate
                (exp(-t * 120.0) * sin(2.0 * PI * clickFreqHz * t)).toFloat()
            } else {
                (rng.nextFloat() - 0.5f) * 0.01f
            }
        }
        return out
    }

    @Test fun recoversKnownTempo() {
        val bpm = 120f
        val samples = syntheticClickTrack(bpm, seconds = 8.0)

        val map = BeatAnalyzer.analyze(samples, sampleRate)

        assertTrue("expected a bpm near $bpm, got ${map.bpm}", abs(map.bpm - bpm) < 3f)
    }

    @Test fun findsRoughlyOneBeatPerPeriod() {
        val bpm = 120f
        val seconds = 8.0
        val samples = syntheticClickTrack(bpm, seconds)

        val map = BeatAnalyzer.analyze(samples, sampleRate)

        val expectedBeats = (seconds * bpm / 60.0).toInt()
        assertTrue(
            "expected roughly $expectedBeats beats, got ${map.beatsMs.size}",
            abs(map.beatsMs.size - expectedBeats) <= 2,
        )
    }

    @Test fun beatsAreEvenlySpacedAtThePeriod() {
        val bpm = 100f
        val samples = syntheticClickTrack(bpm, seconds = 8.0)

        val map = BeatAnalyzer.analyze(samples, sampleRate)
        assertTrue("need at least 2 beats to check spacing", map.beatsMs.size >= 2)

        val expectedPeriodMs = 60_000.0 / bpm
        val gaps = map.beatsMs.zipWithNext { a, b -> (b - a).toDouble() }
        gaps.forEach { gap ->
            assertTrue(
                "gap $gap ms should be near the beat period $expectedPeriodMs ms",
                abs(gap - expectedPeriodMs) < expectedPeriodMs * 0.15,
            )
        }
    }

    @Test fun downbeatsAreASubsetOfBeatsEveryFourth() {
        val samples = syntheticClickTrack(bpm = 128f, seconds = 8.0)

        val map = BeatAnalyzer.analyze(samples, sampleRate)

        assertTrue("expected at least 4 beats for downbeat grouping", map.beatsMs.size >= 4)
        map.downbeatsMs.forEach { assertTrue("downbeat $it should also be a beat", it in map.beatsMs) }
    }

    @Test fun tooShortAudioReturnsEmptyMapRatherThanCrashing() {
        val map = BeatAnalyzer.analyze(FloatArray(10), sampleRate)
        assertEquals(0f, map.bpm, 0f)
        assertTrue(map.beatsMs.isEmpty())
    }
}
