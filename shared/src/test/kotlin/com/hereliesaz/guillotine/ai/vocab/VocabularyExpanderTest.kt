package com.hereliesaz.guillotine.ai.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the LLM expansion pipeline (prompt/parse/merge — no model call). */
class VocabularyExpanderTest {

    @Test fun prompt_lists_concept_ids() {
        val p = VocabularyExpander.buildPrompt()
        assertTrue(p.contains("sharpen"))
        assertTrue(p.contains("warm"))
    }

    @Test fun parse_tolerates_prose_and_code_fences() {
        val reply = """
            Sure! Here is the expansion:
            ```json
            { "sharpen": ["keen", "acute", ""], "warm": ["toasty", "TOASTY"] }
            ```
            Hope that helps.
        """.trimIndent()
        val map = VocabularyExpander.parse(reply)
        assertEquals(setOf("keen", "acute"), map["sharpen"])   // empty string dropped
        assertEquals(setOf("toasty"), map["warm"])              // lowercased + de-duplicated
    }

    @Test fun parse_returns_empty_on_garbage() {
        assertTrue(VocabularyExpander.parse("no json here").isEmpty())
    }

    @Test fun toJson_roundtrips_through_parse() {
        val exp = mapOf("sharpen" to setOf("keen", "acute"))
        val back = VocabularyExpander.parse(VocabularyExpander.toJson(exp))
        assertEquals(exp["sharpen"], back["sharpen"])
    }

    @Test fun applyExpansion_adds_recognised_synonyms() {
        try {
            VocabularyGraph.applyExpansion(mapOf("sharpen" to setOf("keen")))
            val r = VocabularyGraph.resolve("make it keen")
            assertNotNull(r)
            assertEquals("sharpen", r!!.conceptId)
            // Unknown concept ids are ignored, not injected.
            VocabularyGraph.applyExpansion(mapOf("not_a_concept" to setOf("zzz")))
            assertTrue(VocabularyGraph.resolve("zzz") == null)
        } finally {
            VocabularyGraph.applyExpansion(emptyMap()) // restore seed-only state for other tests
        }
    }
}
