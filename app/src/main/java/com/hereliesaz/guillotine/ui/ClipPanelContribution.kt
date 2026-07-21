package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.theme.Neutral400

/**
 * A section a plugin/extension contributes to the clip-properties panel ([AdvancedToolView]) — the
 * standard host surface for extension UI in Guillotine.
 *
 * A contribution renders its own controls, options, and/or preview **inside the panel**, scoped to the
 * selected clip, and drives edits ONLY through [EditorViewModel] — the same operation surface the MCP
 * tools use. It never touches raw media, players, the engine, or the filesystem, which keeps azphalt's
 * "never-list" / Guillotine's on-device boundary intact. Because it lives in the panel it inherits the
 * panel's resize / collapse / re-orient / persistence for free.
 *
 * Built-in features register contributions today (see [KineticTypographyContribution]); the azphalt
 * UI-schema renderer becomes just another contributor later (docs/ECOSYSTEM.md §3b, job #6) — nothing
 * special-cases the panel. See docs/PLUGIN_PANELS.md.
 */
interface ClipPanelContribution {
    /** Stable id (reverse-DNS for plugins, e.g. an azphalt package id). De-dupes / unregisters. */
    val id: String

    /** Default section heading. Contributions typically pass this to [ClipPanelSection]. */
    val title: String

    /** True when this contribution applies to the currently-selected [clip] in [state]. Must be a cheap,
     *  synchronous check (no I/O) — it runs on every recomposition. Gate on clip type/capability here and
     *  do any expensive lookup (e.g. scanning installed packages) inside [Content]. */
    fun appliesTo(clip: TimelineClip, state: EditorUiState): Boolean

    /** The contributed UI, rendered in the clip-properties panel. Wrap it in [ClipPanelSection] for the
     *  standard look, and render nothing when there is nothing to show. */
    @Composable
    fun Content(vm: EditorViewModel, clip: TimelineClip)
}

/**
 * Registry of [ClipPanelContribution]s the clip-properties panel renders after its built-in tools.
 * Registration happens once at app start (see `GuillotineApplication`), but the backing snapshot list is
 * observable, so a contribution registered later (e.g. right after an Azphalt Store install) makes the
 * panel recompose. Registration order is preserved; a duplicate [ClipPanelContribution.id] replaces.
 */
object ClipPanelContributions {
    private val entries = mutableStateListOf<ClipPanelContribution>()

    /** All registered contributions, in registration order. Read from composition to observe changes. */
    val all: List<ClipPanelContribution> get() = entries

    /** Add [contribution], replacing any existing one with the same [ClipPanelContribution.id]. */
    fun register(contribution: ClipPanelContribution) {
        val i = entries.indexOfFirst { it.id == contribution.id }
        if (i >= 0) entries[i] = contribution else entries.add(contribution)
    }

    fun unregister(id: String) {
        entries.removeAll { it.id == id }
    }

    /** Test hook: drop all registrations. */
    fun clear() = entries.clear()
}

/**
 * Renders every registered [ClipPanelContribution] that applies to the current selection, once each,
 * for the first selected clip it matches. Called by [InlineClipTools] after the built-in tools, so
 * plugin sections appear beneath them in the same panel.
 */
@Composable
fun ClipPanelHost(vm: EditorViewModel, state: EditorUiState) {
    val contributions = ClipPanelContributions.all
    if (contributions.isEmpty()) return
    contributions.forEach { c ->
        val clip = state.selectedClips.firstOrNull { c.appliesTo(it, state) }
        if (clip != null) {
            key(c.id, clip.id) { c.Content(vm, clip) }
        }
    }
}

/**
 * The standard container for a clip-panel section: a section [title] over vertically-spaced [content].
 * Contributions should wrap their UI in this so plugin controls read consistently with the built-in
 * tools. (The panel itself — not the section — provides resize/collapse; per-section collapse can come
 * later without changing this signature.)
 */
@Composable
fun ClipPanelSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Neutral400, fontSize = 12.sp)
        content()
    }
}
