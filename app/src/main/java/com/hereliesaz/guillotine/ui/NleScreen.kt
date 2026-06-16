package com.hereliesaz.guillotine.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hereliesaz.guillotine.R
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.Analysis
import com.hereliesaz.guillotine.ai.ApiKeyStore
import com.hereliesaz.guillotine.ai.ImageGen
import com.hereliesaz.guillotine.ai.Transcription
import com.hereliesaz.guillotine.data.ProjectStore
import com.hereliesaz.guillotine.data.rememberOpenProjectLauncher
import com.hereliesaz.guillotine.data.rememberSaveProjectLauncher
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.export.Exporter
import com.hereliesaz.guillotine.media.MediaImport
import com.hereliesaz.guillotine.media.rememberMediaImportLauncher
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineMath
import com.hereliesaz.guillotine.model.newId
import com.hereliesaz.guillotine.ui.theme.Black
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Neutral950
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NleScreen(widthClass: WindowWidthSizeClass, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val keyStore = remember { ApiKeyStore(context) }
    val settings by keyStore.settings.collectAsState(initial = AiSettings())
    val scope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(false) }
    var showProjectSettings by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportDone by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    // Which track an import should land on (set by a track header's "Import"; null = default).
    var importTargetTrack by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberMediaImportLauncher { uris ->
        val target = importTargetTrack
        scope.launch {
            val items = uris.mapNotNull { withContext(Dispatchers.IO) { MediaImport.probe(context, it) } }
            vm.addMedia(items, target)
        }
    }
    val onImportToTrack: (String) -> Unit = { track -> importTargetTrack = track; importLauncher() }
    val onCreateOnTrack: (String) -> Unit = { track ->
        val doc = vm.uiState.value.document
        when {
            // Text is just a clip on a video track: "create" adds an editable text clip there.
            track in doc.videoTracks -> vm.addEmptyTextClip(track)
            else -> { importTargetTrack = track; importLauncher() }
        }
    }
    val saveLauncher = rememberSaveProjectLauncher { uri ->
        scope.launch { withContext(Dispatchers.IO) { runCatching { ProjectStore.save(context, uri, vm.uiState.value.document) } } }
    }
    val openLauncher = rememberOpenProjectLauncher { uri ->
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ProjectStore.load(context, uri) }.getOrNull() }
                ?.let { vm.loadDocument(it) }
        }
    }

    val onAnalyze: () -> Unit = onAnalyze@{
        val targets = vm.uiState.value.selectedClips.filter { it.prompt.isNotBlank() }
        if (targets.isEmpty()) return@onAnalyze
        vm.setProcessing(true, null)
        vm.setAnalyzing(targets.map { it.id }, true)
        scope.launch {
            try {
                for (clip in targets) {
                    val media = vm.uiState.value.document.mediaFor(clip) ?: continue
                    val edits = Analysis.run(context, settings, Uri.parse(media.uri), media.kind, clip.prompt, clip.durationMs)
                    vm.applyEdits(clip.id, edits)
                }
                vm.setProcessing(false, null)
            } catch (e: Exception) {
                vm.setAnalyzing(targets.map { it.id }, false)
                vm.setProcessing(false, e.message ?: "Analysis failed")
            }
        }
    }

    val onTranscribe: () -> Unit = onTranscribe@{
        val clip = vm.uiState.value.selectedClips.singleOrNull() ?: return@onTranscribe
        val media = vm.uiState.value.document.mediaFor(clip) ?: return@onTranscribe
        vm.setProcessing(true, null)
        scope.launch {
            try {
                val cues = Transcription.transcribe(context, settings, Uri.parse(media.uri))
                vm.addTextClipsFromTranscript(clip.id, cues)
                vm.setProcessing(false, null)
            } catch (e: Exception) {
                vm.setProcessing(false, e.message ?: "Transcription failed")
            }
        }
    }

    // Playback clock: advances the timeline and skips 'remove' ranges.
    LaunchedEffectPlayback(vm, state.isPlaying)

    // Keyboard shortcuts (Chromebook/desktop).
    val focusRequester = remember { FocusRequester() }
    LaunchedEffectFocus(focusRequester)

    Column(
        modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            // onKeyEvent (bubble phase), NOT preview: a focused text field gets first crack
            // at the keys, so typing in the prompt doesn't trigger editor shortcuts. Shortcuts
            // still fire when the timeline (this Column) holds focus.
            .onKeyEvent { handleKey(it, vm) },
    ) {
        TopBar(
            state = state,
            onUndo = vm::undo,
            onRedo = vm::redo,
            onImport = { importTargetTrack = null; importLauncher() },
            onGenerate = { showGenerate = true },
            onSave = saveLauncher,
            onLoad = openLauncher,
            onExport = { exportDone = null; exportError = null; showExport = true },
            onSettings = { showSettings = true },
            onProjectSettings = { showProjectSettings = true },
        )

        if (widthClass == WindowWidthSizeClass.Expanded) {
            Row(Modifier.weight(0.6f).fillMaxWidth()) {
                Inspector(vm, state, onAnalyze, onTranscribe, Modifier.width(340.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    PreviewPlayer(state, Modifier.weight(1f).fillMaxWidth())
                    TransportControls(vm, state)
                }
            }
            EditorToolStrip(vm, state, onAnalyze, onGenerate = { showGenerate = true })
            TimelinePanel(vm, state, onImportToTrack, onCreateOnTrack, Modifier.weight(0.4f).fillMaxWidth())
        } else {
            PreviewPlayer(state, Modifier.weight(0.42f).fillMaxWidth())
            TransportControls(vm, state)
            var tab by remember { mutableIntStateOf(0) }
            CompactToolBar(vm, state, tab, { tab = it }, onAnalyze, onGenerate = { showGenerate = true })
            Box(Modifier.weight(0.58f).fillMaxWidth()) {
                if (tab == 0) TimelinePanel(vm, state, onImportToTrack, onCreateOnTrack, Modifier.fillMaxSize())
                else Inspector(vm, state, onAnalyze, onTranscribe, Modifier.fillMaxSize())
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            current = settings,
            onSave = { newSettings ->
                scope.launch { keyStore.save(newSettings) }
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
    if (showProjectSettings) {
        ProjectSettingsSheet(
            current = state.document.settings,
            onChange = { vm.setGlobalSettings(it) },
            onDismiss = { showProjectSettings = false },
        )
    }
    if (showGenerate) {
        GenerateSheet(
            fooocusUrl = settings.fooocusUrl,
            onGenerateFree = { url, name ->
                vm.addMedia(listOf(MediaItem(newId(), url, name, MediaKind.IMAGE, 5_000)))
                showGenerate = false
            },
            onGenerateFooocus = { prompt ->
                val uri = ImageGen.FooocusApi.generate(context, settings.fooocusUrl, prompt)
                vm.addMedia(listOf(MediaItem(newId(), uri.toString(), "Fooocus: ${prompt.take(20)}", MediaKind.IMAGE, 5_000)))
            },
            onDismiss = { showGenerate = false },
        )
    }
    if (showExport) {
        ExportSheet(
            totalDurationMs = state.document.totalDurationMs,
            isExporting = exporting,
            progress = exportProgress,
            doneMessage = exportDone,
            errorMessage = exportError,
            onStart = { name ->
                exporting = true; exportError = null; exportProgress = 0f
                scope.launch {
                    try {
                        Exporter.export(context, vm.uiState.value.document, name) { p -> exportProgress = p }
                        exportDone = "Saved to Movies/Guillotine."
                    } catch (e: Exception) {
                        exportError = e.message ?: "Export failed"
                    } finally {
                        exporting = false
                    }
                }
            },
            onDismiss = { if (!exporting) showExport = false },
        )
    }
}

@Composable
private fun TopBar(
    state: EditorUiState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onImport: () -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onProjectSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Neutral950).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.guillotine_icon),
            contentDescription = "Guillotine",
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text("Guillotine", color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        IconToolButton(Icons.Filled.Undo, "Undo", enabled = state.canUndo, onClick = onUndo)
        IconToolButton(Icons.Filled.Redo, "Redo", enabled = state.canRedo, onClick = onRedo)
        Box {
            IconToolButton(Icons.Filled.Menu, "Menu", onClick = { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuRow("Import media") { menuOpen = false; onImport() }
                MenuRow("Generate image (free)") { menuOpen = false; onGenerate() }
                MenuRow("Save project") { menuOpen = false; onSave() }
                MenuRow("Load project") { menuOpen = false; onLoad() }
                MenuRow("Export video") { menuOpen = false; onExport() }
                MenuRow("Project settings") { menuOpen = false; onProjectSettings() }
                MenuRow("Settings") { menuOpen = false; onSettings() }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = White, fontSize = 13.sp) }, onClick = onClick)
}

@Composable
private fun TransportControls(vm: EditorViewModel, state: EditorUiState) {
    val total = state.document.totalDurationMs
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Neutral950).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${"%.2f".format(state.currentTimeMs / 1000f)}s / ${"%.2f".format(total / 1000f)}s",
            color = Neutral400, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        IconToolButton(Icons.Filled.SkipPrevious, "Start") { vm.seekTo(0) }
        IconToolButton(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause") { vm.togglePlay() }
        IconToolButton(Icons.Filled.SkipNext, "End") { vm.seekTo(total) }
        Spacer(Modifier.weight(1f))
        val rates = listOf(0.5f, 1f, 1.5f, 2f)
        Text(
            "${state.playbackRate}x",
            color = Neutral400, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clickable {
                    val next = rates[(rates.indexOf(state.playbackRate).coerceAtLeast(0) + 1) % rates.size]
                    vm.setPlaybackRate(next)
                }
                .padding(8.dp),
        )
    }
}

/**
 * Compact (phone) bottom bar: the Timeline/Inspector tab switch stacked on top of
 * the shared [EditorToolStrip] (tools + AI prompt). The whole bar grows in height as
 * the multiline prompt wraps.
 */
@Composable
private fun CompactToolBar(
    vm: EditorViewModel,
    state: EditorUiState,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onAnalyze: () -> Unit,
    onGenerate: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Neutral900)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("Timeline", "Inspector").forEachIndexed { i, label ->
                Text(
                    label,
                    color = if (selectedTab == i) White else Neutral400,
                    fontSize = 12.sp,
                    fontWeight = if (selectedTab == i) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectTab(i) }
                        .background(if (selectedTab == i) Neutral800 else Neutral900)
                        .padding(vertical = 10.dp),
                )
            }
        }
        EditorToolStrip(vm, state, onAnalyze, onGenerate)
    }
}

/**
 * Shared editor tool strip used by both layouts: a horizontally-scrollable row of
 * tools (mirroring the web build — select, split, keyframe, add-track, delete, zoom)
 * and an AI prompt box. The prompt box grows up to several lines, so the strip's
 * height expands with multiline input. With a clip selected the box edits that clip's
 * prompt and the AI button runs the analyzer; with nothing selected AI opens Generate.
 */
@Composable
private fun EditorToolStrip(
    vm: EditorViewModel,
    state: EditorUiState,
    onAnalyze: () -> Unit,
    onGenerate: () -> Unit,
) {
    val selected = state.selectedClips
    // Submitting the prompt: analyze the selected clip(s), or open Generate when nothing
    // is selected. Used by both the Enter key and the AI button.
    val submit: () -> Unit = { if (selected.isEmpty()) onGenerate() else onAnalyze() }
    Column(Modifier.fillMaxWidth().background(Neutral900)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconToolButton(Icons.Filled.NearMe, "Select", active = state.tool == EditorTool.SELECT) {
                vm.setTool(EditorTool.SELECT)
            }
            IconToolButton(Icons.Filled.ContentCut, "Split", active = state.tool == EditorTool.SPLIT) {
                vm.setTool(EditorTool.SPLIT)
            }
            IconToolButton(Icons.Filled.Diamond, "Keyframe tool", active = state.tool == EditorTool.KEYFRAME) {
                vm.setTool(EditorTool.KEYFRAME)
            }
            IconToolButton(Icons.Filled.ShowChart, "Auto-ease keyframes", active = state.autoEase) {
                vm.toggleAutoEase()
            }
            IconToolButton(Icons.Filled.Add, "Add track") {
                vm.addTrack(selected.singleOrNull()?.type ?: ClipType.VIDEO)
            }
            IconToolButton(Icons.Filled.Delete, "Delete", enabled = state.selectedClipIds.isNotEmpty()) {
                vm.deleteSelected()
            }
            IconToolButton(Icons.Filled.ZoomOut, "Zoom out") { vm.setZoom(state.pixelsPerSecond * 0.8f) }
            IconToolButton(Icons.Filled.ZoomIn, "Zoom in") { vm.setZoom(state.pixelsPerSecond * 1.25f) }
            // Group / ungroup — only meaningful with a multi-clip selection.
            if (selected.size > 1) {
                val grouped = selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null }
                IconToolButton(
                    if (grouped) Icons.Filled.LinkOff else Icons.Filled.Link,
                    if (grouped) "Ungroup" else "Group",
                    active = grouped,
                ) { if (grouped) vm.ungroupSelected() else vm.groupSelected() }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = selected.firstOrNull()?.prompt ?: "",
                // Enter submits instead of inserting a newline. Soft keyboards send a '\n'
                // through onValueChange; hardware Enter is caught by onPreviewKeyEvent below.
                onValueChange = { v ->
                    if (v.contains('\n')) {
                        vm.setPromptForSelected(v.replace("\n", ""))
                        submit()
                    } else {
                        vm.setPromptForSelected(v)
                    }
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                            submit(); true
                        } else {
                            false
                        }
                    },
                placeholder = {
                    Text(
                        if (selected.isEmpty()) "Select a clip to prompt…" else "Describe the edit…",
                        color = Neutral500, fontSize = 12.sp,
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            ToolbarButton(if (selected.isEmpty()) "AI ▸" else "AI", tint = Red500, onClick = submit)
        }
    }
}

@Composable
private fun LaunchedEffectFocus(focusRequester: FocusRequester) {
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

@Composable
private fun LaunchedEffectPlayback(vm: EditorViewModel, isPlaying: Boolean) {
    androidx.compose.runtime.LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                val rate = vm.uiState.value.playbackRate
                val deltaMs = ((now - last) / 1_000_000.0 * rate).toLong()
                last = now
                if (deltaMs > 0) {
                    vm.advancePlayhead(deltaMs)
                    skipRemoved(vm)
                }
            }
        }
    }
}

/** If the playhead landed inside a 'remove' range, jump past it (preview cut). */
private fun skipRemoved(vm: EditorViewModel) {
    val s = vm.uiState.value
    val clip = TimelineMath.activeClip(s.document.clips, ClipType.VIDEO, s.currentTimeMs) ?: return
    val src = TimelineMath.sourceTimeMs(clip, s.currentTimeMs)
    val seg = clip.edits.firstOrNull { it.action == EditAction.REMOVE && src >= it.startMs && src < it.endMs } ?: return
    val jump = clip.startTimeMs + (seg.endMs - clip.trimStartMs)
    vm.seekTo(jump)
}

private fun handleKey(e: KeyEvent, vm: EditorViewModel): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    return when {
        e.key == Key.Spacebar -> { vm.togglePlay(); true }
        // Delete removes the selection; Backspace deliberately does NOT (avoids nuking a
        // clip when the user means to edit text or just backspace).
        e.key == Key.Delete -> { vm.deleteSelected(); true }
        ctrl && e.key == Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
        ctrl && e.key == Key.Y -> { vm.redo(); true }
        ctrl && e.isAltPressed && e.key == Key.C -> { vm.copySelectedFilters(); true }
        ctrl && e.isAltPressed && e.key == Key.V -> { vm.pasteFiltersToSelected(); true }
        ctrl && e.key == Key.C -> { vm.copySelected(); true }
        ctrl && e.key == Key.V -> { vm.pasteClip(); true }
        !ctrl && e.key == Key.S -> { vm.splitSelectedAtPlayhead(); true }
        else -> false
    }
}
