package com.hereliesaz.guillotine.ui

import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.TimelineClip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Registry semantics for the clip-properties-panel plugin seam: registration order, id-based replace,
 * unregister, and clear. (The composable [ClipPanelContribution.Content] and [ClipPanelContribution.appliesTo]
 * are UI concerns exercised in the app, not here.)
 */
class ClipPanelContributionsTest {

    /** Minimal fake; appliesTo/Content are never invoked in these registry-only tests. */
    private class Fake(override val id: String) : ClipPanelContribution {
        override val title: String = id
        override fun appliesTo(clip: TimelineClip, state: EditorUiState): Boolean = false
        @Composable override fun Content(vm: EditorViewModel, clip: TimelineClip) {}
    }

    @Before fun setUp() = ClipPanelContributions.clear()
    @After fun tearDown() = ClipPanelContributions.clear()

    @Test fun `register preserves order`() {
        val a = Fake("a"); val b = Fake("b"); val c = Fake("c")
        ClipPanelContributions.register(a)
        ClipPanelContributions.register(b)
        ClipPanelContributions.register(c)
        assertEquals(listOf("a", "b", "c"), ClipPanelContributions.all.map { it.id })
    }

    @Test fun `duplicate id replaces in place`() {
        val first = Fake("dup"); val other = Fake("other"); val replacement = Fake("dup")
        ClipPanelContributions.register(first)
        ClipPanelContributions.register(other)
        ClipPanelContributions.register(replacement)

        // Same count, same positions — the second "dup" swaps the first without appending.
        assertEquals(listOf("dup", "other"), ClipPanelContributions.all.map { it.id })
        assertSame(replacement, ClipPanelContributions.all.first { it.id == "dup" })
    }

    @Test fun `unregister removes by id`() {
        ClipPanelContributions.register(Fake("a"))
        ClipPanelContributions.register(Fake("b"))
        ClipPanelContributions.unregister("a")
        assertEquals(listOf("b"), ClipPanelContributions.all.map { it.id })
    }

    @Test fun `clear empties the registry`() {
        ClipPanelContributions.register(Fake("a"))
        ClipPanelContributions.clear()
        assertTrue(ClipPanelContributions.all.isEmpty())
    }
}
