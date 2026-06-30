package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// ---- Icon key -----------------------------------------------------------------

private data class IconHelp(val icon: ImageVector, val name: String, val desc: String)

/**
 * Legend for every icon button in the app, grouped by where it lives. Kept here next to the help
 * UI; mirror any toolbar icon change in this list so the key stays accurate.
 */
private val ICON_KEY: List<Pair<String, List<IconHelp>>> = listOf(
    "Top bar" to listOf(
        IconHelp(Icons.Filled.Undo, "Undo", "Step backward through your edits."),
        IconHelp(Icons.Filled.Redo, "Redo", "Step forward again."),
        IconHelp(Icons.Filled.HelpOutline, "Help", "Open this icon key, the tutorial, or the FAQ."),
    ),
    "Tools — modes (tap to toggle)" to listOf(
        IconHelp(Icons.Filled.NearMe, "Select", "Tap clips to select; drag to move them."),
        IconHelp(Icons.Filled.SelectAll, "Select range", "Drag a rectangle over the timeline to select every clip it touches."),
        IconHelp(Icons.Filled.Crop, "Crop / transform", "Pinch, drag, and twist the selected clip on the preview to scale, place, and rotate it."),
        IconHelp(Icons.Filled.ShowChart, "Auto-ease", "When on, new keyframes get a smooth ease instead of a linear one."),
    ),
    "Tools — actions (tap to do)" to listOf(
        IconHelp(Icons.Filled.ContentCut, "Split at playhead", "Cut the selected clip/group at the playhead — or every clip on every track if nothing is selected."),
        IconHelp(Icons.Filled.Diamond, "Keyframe", "Record the selected clip's crop/placement at the playhead."),
        IconHelp(Icons.Filled.Add, "Add track", "Add a new video or audio track."),
        IconHelp(Icons.Filled.Delete, "Delete", "Delete the selected clip(s)."),
        IconHelp(Icons.Filled.Compress, "Ripple (close gaps)", "Pull clips left to remove empty space — the selected clips, or all clips if none are selected."),
        IconHelp(Icons.Filled.Link, "Group / Ungroup", "Lock the selected clips together so they move as one — or unlock them."),
    ),
    "Transport" to listOf(
        IconHelp(Icons.Filled.SkipPrevious, "Start", "Jump the playhead to the start."),
        IconHelp(Icons.Filled.ChevronLeft, "Back one frame", "Step the playhead back a single frame."),
        IconHelp(Icons.Filled.PlayArrow, "Play / Pause", "Play or pause the preview."),
        IconHelp(Icons.Filled.ChevronRight, "Forward one frame", "Step the playhead forward a single frame."),
        IconHelp(Icons.Filled.SkipNext, "End", "Jump the playhead to the end."),
    ),
)

/** The icon key: every icon button and what it does. */
@Composable
fun HelpKeyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Icon key", color = White) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                ICON_KEY.forEach { (group, entries) ->
                    Text(group, color = Red500, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    entries.forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Icon(e.icon, e.name, tint = Neutral400, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(e.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(e.desc, color = Neutral500, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { DialogAction("Close") { onDismiss() } },
        containerColor = Neutral900,
    )
}

// ---- Tutorial (stepper) -------------------------------------------------------

/** Multi-step walkthrough fetched from the repo's `TUTORIAL.md` (one step per `## ` heading). */
@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    val md = rememberRepoDoc("TUTORIAL.md")
    val steps = remember(md) { md?.let { parseSections(it) }.orEmpty() }
    var i by remember { mutableIntStateOf(0) }
    val idx = i.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    val last = steps.isEmpty() || idx >= steps.lastIndex
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                if (steps.isEmpty()) {
                    Text("Tutorial", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Tutorial · ${idx + 1} of ${steps.size}", color = Neutral500, fontSize = 11.sp)
                    Text(steps[idx].title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    md == null -> Text("Loading…", color = Neutral500, fontSize = 13.sp)
                    steps.isEmpty() -> Text("Couldn't load the tutorial — check your connection.",
                        color = Neutral500, fontSize = 13.sp)
                    else -> MarkdownBody(steps[idx].body)
                }
            }
        },
        confirmButton = { DialogAction(if (last) "Done" else "Next") { if (last) onDismiss() else i = idx + 1 } },
        dismissButton = {
            DialogAction(if (idx == 0) "Close" else "Back", tint = Neutral400) { if (idx == 0) onDismiss() else i = idx - 1 }
        },
        containerColor = Neutral900,
    )
}

// ---- FAQ (accordion) ----------------------------------------------------------

/** FAQ fetched from the repo's `FAQ.md` (one question per `## ` heading), as a tap-to-expand list. */
@Composable
fun FaqDialog(onDismiss: () -> Unit) {
    val md = rememberRepoDoc("FAQ.md")
    val entries = remember(md) { md?.let { parseSections(it) }.orEmpty() }
    var open by remember { mutableStateOf(-1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FAQ", color = White) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when {
                    md == null -> Text("Loading…", color = Neutral500, fontSize = 13.sp)
                    entries.isEmpty() -> Text("Couldn't load the FAQ — check your connection.",
                        color = Neutral500, fontSize = 13.sp)
                    else -> entries.forEachIndexed { idx, e ->
                        val expanded = open == idx
                        Text(
                            e.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clickable { open = if (expanded) -1 else idx }
                                .padding(vertical = 8.dp),
                        )
                        if (expanded) MarkdownBody(e.body, Modifier.padding(bottom = 6.dp))
                    }
                }
            }
        },
        confirmButton = { DialogAction("Close") { onDismiss() } },
        containerColor = Neutral900,
    )
}

// ---- shared bits --------------------------------------------------------------

@Composable
private fun DialogAction(label: String, tint: androidx.compose.ui.graphics.Color = Red500, onClick: () -> Unit) {
    Text(
        label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable(onClick = onClick).padding(12.dp),
    )
}

/** Render a small subset of Markdown: paragraphs, `- ` bullets, `### ` subheads, and **bold**. */
@Composable
private fun MarkdownBody(md: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        md.trim().lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(6.dp))
                line.startsWith("### ") ->
                    Text(line.removePrefix("### "), color = White, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                line.startsWith("- ") ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("•  ", color = Neutral400, fontSize = 13.sp)
                        Text(inlineMarkdown(line.removePrefix("- ")), color = Neutral400, fontSize = 13.sp)
                    }
                else -> Text(inlineMarkdown(line), color = Neutral400, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

/** Turn `**bold**` runs into a styled [AnnotatedString]; everything else is plain. */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val start = text.indexOf("**", i)
        if (start < 0) { append(text.substring(i)); break }
        append(text.substring(i, start))
        val end = text.indexOf("**", start + 2)
        if (end < 0) { append(text.substring(start)); break }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(start + 2, end)) }
        i = end + 2
    }
}

private data class DocSection(val title: String, val body: String)

/** Split a Markdown doc into sections, one per level-2 (`## `) heading. Preamble is ignored. */
private fun parseSections(md: String): List<DocSection> {
    val out = mutableListOf<DocSection>()
    var title: String? = null
    val body = StringBuilder()
    fun flush() { title?.let { out.add(DocSection(it, body.toString().trim())); body.setLength(0) } }
    md.lines().forEach { line ->
        if (line.startsWith("## ")) { flush(); title = line.removePrefix("## ").trim() }
        else if (title != null) body.appendLine(line)
    }
    flush()
    return out
}

/**
 * The repo's docs, fetched live from GitHub (the same source the About reader reads) so the in-app
 * Tutorial/FAQ ARE the repo docs — no bundled copy. `null` = still loading; `""` = fetch failed.
 */
private const val DOCS_BASE = "https://raw.githubusercontent.com/HereLiesAz/Guillotine/main/"

@Composable
private fun rememberRepoDoc(fileName: String): String? =
    produceState<String?>(initialValue = null, fileName) {
        value = withContext(Dispatchers.IO) { fetchRepoDoc(fileName) }
    }.value

private fun fetchRepoDoc(fileName: String): String = runCatching {
    OkHttpClient().newCall(Request.Builder().url(DOCS_BASE + fileName).build()).execute().use { resp ->
        if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
    }
}.getOrDefault("")
