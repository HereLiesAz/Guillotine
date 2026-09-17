package com.hereliesaz.guillotine.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCoachTest {

    @Test
    fun partialBoringPromptGetsSuggestionsImmediately() {
        val suggestions = PromptCoach.suggest("cut the bor")
        assertEquals("Cut dead air", suggestions.first().label)
        assertTrue(suggestions.any { it.prompt.contains("pacing", ignoreCase = true) })
    }

    @Test
    fun fullBoringPromptOffersDialoguePreservingOption() {
        val suggestions = PromptCoach.suggest("cut the boring parts")
        assertTrue(suggestions.any { it.label == "Keep dialogue" })
    }

    @Test
    fun borderDoesNotTriggerBoringGuidance() {
        assertTrue(PromptCoach.suggest("add a border").isEmpty())
    }

    @Test
    fun audioAndCinematicPromptsRouteToDifferentGuidance() {
        assertTrue(PromptCoach.suggest("fix the sound").any { it.label == "Clean dialogue" })
        assertTrue(PromptCoach.suggest("make it cinematic").any { it.label == "Cinematic grade" })
    }

    @Test
    fun blankAndVeryShortInputStayQuiet() {
        assertTrue(PromptCoach.suggest("").isEmpty())
        assertTrue(PromptCoach.suggest("cu").isEmpty())
    }

    @Test
    fun modelParserRejectsToolLookingJunkAndKeepsUsefulLines() {
        val parsed = PromptCoach.parseModelSuggestions(
            """
            1. Cut long pauses but keep every spoken line
            analyze_clip_with_reference
            2) Make the pacing faster without removing dialogue
            """.trimIndent(),
        )
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.prompt.endsWith(".") })
    }
}
