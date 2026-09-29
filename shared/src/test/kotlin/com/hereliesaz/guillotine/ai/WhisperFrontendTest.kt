package com.hereliesaz.guillotine.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reference values come from a NumPy implementation of Whisper's log-mel frontend (the one checked
 * against sherpa-onnx's transcripts), on 2 s of 440 Hz + 1500 Hz tones followed by silence.
 */
class WhisperFrontendTest {

    private val mel: FloatArray by lazy {
        val tones = FloatArray(16_000 * 2) { i ->
            (0.3 * sin(2 * PI * 440 * i / 16_000) + 0.1 * sin(2 * PI * 1500 * i / 16_000)).toFloat()
        }
        WhisperFrontend.logMel(tones)
    }

    private fun at(m: Int, t: Int) = mel[m * WhisperFrontend.N_FRAMES + t]

    @Test fun shapeIsEightyByThreeThousand() {
        assertEquals(80 * 3000, mel.size)
    }

    @Test fun matchesTheNumPyReference() {
        val tol = 2e-3f
        assertEquals(1.0484686f, at(9, 50), tol)
        assertEquals(1.2378139f, at(10, 50), tol)
        assertEquals(1.3272785f, at(11, 50), tol)
        assertEquals(1.1826015f, at(12, 50), tol)
        assertEquals(0.891797661781311f, at(0, 0), tol)
    }

    @Test fun silenceSitsAtTheFloorEightBelowThePeak() {
        val peak = mel.max()
        assertEquals(peak - 2f, at(30, 2999), 1e-4f) // (log10 floor + 4) / 4 = peak - 8/4
    }

    @Test fun tokensDecodeAsJoinedBytes() {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        val vocab = WhisperFrontend.parseVocab(sequenceOf("${b64("Hel")} 0", "${b64("lo")} 1", "${b64(" wörld")} 2", "${b64("<|eot|>")} 3"))
        assertEquals("Hello wörld", WhisperFrontend.decodeTokens(listOf(0, 1, 2, 3), vocab, firstSpecial = 3))
    }
}
