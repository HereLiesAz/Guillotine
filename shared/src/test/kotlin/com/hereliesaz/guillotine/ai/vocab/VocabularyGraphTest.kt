package com.hereliesaz.guillotine.ai.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the vocabulary graph (no Android/platform deps). */
class VocabularyGraphTest {

    @Test fun resolves_direct_synonym_to_tool() {
        val r = VocabularyGraph.resolve("please sharpen the clip")
        assertNotNull(r)
        assertEquals("sharpen", r!!.conceptId)
        assertEquals("sharpen", r.tool)
        assertFalse(r.inverted)
    }

    @Test fun negation_flips_to_antonym() {
        // "less sharp" → the opposite of sharpen (soften, whose tool is glow), marked inverted.
        val r = VocabularyGraph.resolve("make it less sharp")
        assertNotNull(r)
        assertEquals("soften", r!!.conceptId)
        assertEquals("glow", r.tool)
        assertTrue(r.inverted)
    }

    @Test fun reduce_grain_resolves_to_denoise() {
        val r = VocabularyGraph.resolve("reduce the grain")
        assertNotNull(r)
        assertEquals("denoise", r!!.conceptId)
        assertEquals("denoise_video", r.tool)
        assertTrue(r.inverted)
    }

    @Test fun antonym_pair_is_symmetric() {
        assertEquals("cool", VocabularyGraph.antonymOf("warm")?.id)
        assertEquals("warm", VocabularyGraph.antonymOf("cool")?.id)
    }

    @Test fun bigram_phrase_matches_before_single_token() {
        val r = VocabularyGraph.resolve("can you zoom in a bit")
        assertNotNull(r)
        assertEquals("zoom_in", r!!.conceptId)
    }

    @Test fun unknown_text_resolves_to_null() {
        assertNull(VocabularyGraph.resolve("quarterly revenue projections"))
    }

    @Test fun expander_generates_comparative_forms() {
        val syns = VocabularyGraph.synonyms.getValue("sharpen")
        assertTrue("sharp" in syns)
        assertTrue("sharper" in syns) // morphological expansion from the "sharp" seed
    }

    @Test fun lookup_returns_synonyms_and_antonym() {
        val l = VocabularyGraph.lookup("crisp")
        assertNotNull(l)
        assertEquals("sharpen", l!!.conceptId)
        assertEquals("soften", l.antonym)
        assertTrue(l.synonyms.isNotEmpty())
        assertTrue(l.antonymSynonyms.isNotEmpty())
    }

    @Test fun prompt_appendix_lists_tools_and_opposites() {
        val text = VocabularyGraph.promptAppendix()
        assertTrue(text.contains("sharpen"))
        assertTrue(text.contains("opposite"))
    }

    @Test fun normalize_strips_punctuation_and_case() {
        assertEquals("make it warm", VocabularyGraph.normalize("  Make it, WARM!!  "))
    }

    @Test fun no_seed_belongs_to_two_concepts() {
        // The reverse index is first-wins, so a seed shared by two concepts silently routes to one of them.
        val owners = HashMap<String, MutableList<String>>()
        for (c in VocabularyGraph.concepts) for (s in c.seeds) {
            owners.getOrPut(VocabularyGraph.normalize(s)) { mutableListOf() }.add(c.id)
        }
        val dupes = owners.filterValues { it.size > 1 }
        assertTrue("seeds in more than one concept: $dupes", dupes.isEmpty())
    }

    @Test fun formerly_colliding_terms_route_to_their_tools() {
        assertEquals("trim", VocabularyGraph.lookup("trim")!!.conceptId)
        assertEquals("mute", VocabularyGraph.lookup("mute")!!.conceptId)
        assertEquals("reframe", VocabularyGraph.lookup("punch in")!!.conceptId)
        assertEquals("stabilize", VocabularyGraph.lookup("smooth")!!.conceptId)
    }

    @Test fun prompt_appendix_does_not_invert_content_removal() {
        val text = VocabularyGraph.promptAppendix()
        assertTrue(text.contains("does NOT"))
    }
}
