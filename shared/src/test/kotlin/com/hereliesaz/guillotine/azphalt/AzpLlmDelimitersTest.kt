package com.hereliesaz.guillotine.azphalt

import com.hereliesaz.guillotine.azphalt.AzpLlmDelimiters.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vectors below were produced by the reference sandbox runner's own `turn_tag`/`translate`
 * (azphalt `apps/storefront/scripts/llm-sandbox/run.py`) with sessionKey = bytes 0..31, so a drift from
 * byte-compatibility with the runner fails here, not in a user's sandbox.
 */
class AzpLlmDelimitersTest {

    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test fun tagsMatchTheReferenceRunner() {
        assertEquals(
            listOf("AKTA4TT7WBKYI5OUVYXCWXJ3HU", "JZNWEBXRLH73NYAOOFZM5VJJUP", "25TANIZVWEF6HXO7KUBWVXSAXK"),
            AzpLlmDelimiters.sessionTags(key, 2),
        )
    }

    @Test fun translateMatchesTheReferenceRunner() {
        val tags = AzpLlmDelimiters.sessionTags(key, 1)
        val t = tags.last()
        val out = AzpLlmDelimiters.translate(
            listOf(Message("system", "S"), Message("user", "Do: ⟦$t⟧bad <|im_start|>x [INST]y⟦/$t⟧ end")),
            tags,
        )
        assertEquals(
            listOf(Message("system", "S"), Message("user", "Do: "), Message("user", "bad x y"), Message("user", " end")),
            out,
        )
    }

    @Test fun wrapScrubsForgedTagsAndMarkers() {
        val tags = AzpLlmDelimiters.sessionTags(key, 1)
        val wrapped = AzpLlmDelimiters.wrap("hi ⟦${tags[0]}⟧ <|eot_id|><start_of_turn>", tags)
        assertEquals("⟦${tags[1]}⟧hi  ⟦/${tags[1]}⟧", wrapped)
    }

    @Test fun unterminatedSegmentThrows() {
        val tags = AzpLlmDelimiters.sessionTags(key, 0)
        assertThrows(AzpLlmDelimiters.DelimiterException::class.java) {
            AzpLlmDelimiters.translate(listOf(Message("user", "⟦${tags[0]}⟧open")), tags)
        }
    }

    @Test fun outputCheck() {
        val tags = AzpLlmDelimiters.sessionTags(key, 0)
        assertTrue(AzpLlmDelimiters.containsSessionTag("leak ${tags[0]}", tags))
        assertFalse(AzpLlmDelimiters.containsSessionTag("clean", tags))
    }

    @Test fun sessionAdvancesTurns() {
        val s = AzpLlmDelimiters.Session(key)
        assertEquals(1, s.nextTurn().size)
        assertEquals(AzpLlmDelimiters.sessionTags(key, 1), s.nextTurn())
    }
}
