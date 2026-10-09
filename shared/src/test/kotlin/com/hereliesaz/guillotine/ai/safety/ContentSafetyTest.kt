package com.hereliesaz.guillotine.ai.safety

import com.hereliesaz.guillotine.model.AiProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSafetyTest {

    @Test fun blankPromptsAreRefused() {
        assertNotNull(ContentSafety.promptRefusal(""))
        assertNotNull(ContentSafety.promptRefusal("   \n"))
    }

    @Test fun sexualPromptsAreRefused() {
        listOf(
            "a nude woman on a beach", "NAKED man", "topless model", "Pornographic scene",
            "woman in lingerie", "girl with open shirt", "see-through dress", "masturbating", "n.s.f.w nsfw art",
        ).forEach { assertNotNull(it, ContentSafety.promptRefusal(it)) }
    }

    @Test fun ordinaryPromptsPass() {
        listOf(
            "a mountain lake at dawn", "a woman in a white shirt walking a dog", "nudge the camera left",
            "title card that says Chapter One", "a sextant on a ship's deck",
        ).forEach { assertNull(it, ContentSafety.promptRefusal(it)) }
    }

    @Test fun inpaintingNeverAddsPeople() {
        assertTrue(ContentSafety.inpaintAddsPeople(before = 0, after = 1))
        assertTrue(ContentSafety.inpaintAddsPeople(before = 2, after = 3))
        assertEquals(false, ContentSafety.inpaintAddsPeople(before = 2, after = 2))
        assertEquals(false, ContentSafety.inpaintAddsPeople(before = 1, after = 0))
    }

    @Test fun nsfwVerdict() {
        assertEquals(0.5f, NsfwModel.nsfwProbability(floatArrayOf(1f, 1f)), 1e-6f)
        assertTrue(NsfwModel.nsfwProbability(floatArrayOf(-4f, 5f)) > 0.99f)
        NsfwModel.requireClean(listOf(0.01f, 0.2f))
        assertThrows(NsfwModel.FlaggedException::class.java) { NsfwModel.requireClean(listOf(0.01f, 0.9f)) }
        // Fails closed: nothing checked is not clean.
        assertThrows(NsfwModel.FlaggedException::class.java) { NsfwModel.requireClean(emptyList()) }
    }

    @Test fun preprocessIsChannelPlanarAndNormalised() {
        // 2×1 image: pure red, pure white. Upscaled to 224², the left edge is red, the right white.
        val out = NsfwModel.preprocess(intArrayOf(0xFFFF0000.toInt(), 0xFFFFFFFF.toInt()), 2, 1)
        val plane = 224 * 224
        assertEquals(3 * plane, out.size)
        assertEquals(1f, out[0], 1e-5f) // R at (0,0)
        assertEquals(-1f, out[plane], 1e-5f) // G at (0,0)
        assertEquals(1f, out[plane + 223], 1e-5f) // G at the white edge
    }

    @Test fun reportCarriesTheProvenance() {
        val p = AiProvenance("Pollinations", "flux", "a fox & a hound", 0L)
        val uri = ContentReport.mailtoUri(p)
        assertTrue(uri, uri.startsWith("mailto:hereliesaz@gmail.com?subject="))
        val body = java.net.URLDecoder.decode(uri.substringAfter("&body="), "UTF-8")
        assertTrue(body, "Provider: Pollinations" in body && "Prompt: a fox & a hound" in body)
    }
}
