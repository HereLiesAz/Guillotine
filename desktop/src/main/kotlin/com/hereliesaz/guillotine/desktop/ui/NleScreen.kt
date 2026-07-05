package com.hereliesaz.guillotine.desktop.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.HelpOutline
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
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.FrameAnalysisCache
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.desktop.platform.DesktopKeyStore
import com.hereliesaz.guillotine.desktop.platform.DesktopMcpAgent
import com.hereliesaz.guillotine.desktop.platform.DesktopProjectAutosave
import com.hereliesaz.guillotine.desktop.ui.theme.Black
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral900
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral950
import com.hereliesaz.guillotine.desktop.ui.theme.Red500
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineMath
import com.hereliesaz.guillotine.model.newId
import com.hereliesaz.guillotine.ui.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NleScreen(
    vm: EditorViewModel,
    keyStore: DesktopKeyStore,
    mcpTools: McpToolsSurface,
    modifier: Modifier = Modifier,
) {
    val state by vm.uiState.collectAsState()
    val settings by keyStore.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val assistantVm = remember { AssistantViewModel() }
    val assistantState by assistantVm.state.collectAsState()

    val logEntries by ActivityLog.entries.collectAsState()
    val logPanelState = remember { mutableStateOf(LogPanelState.HIDDEN) }

    LaunchedEffect(Unit) {
        vm.uiState.map { it.analysisProgress?.stage }.distinctUntilChanged().collect { stage ->
            if (!stage.isNullOrBlank()) ActivityLog.progress(stage)
        }
    }
    LaunchedEffect(Unit) {
        vm.uiState.map { it.error }.distinctUntilChanged().collect { err ->
            if (err != null) { ActivityLog.error(err); vm.clearError() }
        }
    }
    LaunchedEffect(settings.frameAnalysisCacheSize) {
        FrameAnalysisCache.setMaxEntries(settings.frameAnalysisCacheSize)
    }

    var showSettings by remember { mutableStateOf(false) }
    var showAiComparison by remember { mutableStateOf(false) }
    var showProjectSettings by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var showNewProjectConfirm by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var showFaq by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportDone by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var importTargetTrack by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberMediaImportLauncher { _ ->
        ActivityLog.info("Media import is not yet available on desktop.")
    }
    val onImportToTrack: (String) -> Unit = { track -> importTargetTrack = track; importLauncher() }
    val onCreateOnTrack: (String) -> Unit = { track ->
        val doc = vm.uiState.value.document
        when {
            track in doc.videoTracks -> vm.addEmptyTextClip(track)
            else -> { importTargetTrack = track; importLauncher() }
        }
    }
    val openLauncher = rememberOpenProjectLauncher { file ->
        scope.launch {
            val doc = withContext(Dispatchers.IO) {
                runCatching { DesktopProjectAutosave.loadFromFile(file) }.getOrNull()
            }
            if (doc != null) vm.loadDocument(doc)
        }
    }

    LaunchedEffect(Unit) {
        if (vm.uiState.value.document.clips.isEmpty()) {
            val doc = withContext(Dispatchers.IO) { DesktopProjectAutosave.load() }
            if (doc != null) vm.loadDocument(doc)
        }
        vm.uiState
            .map { it.document }
            .distinctUntilChanged()
            .collectLatest { doc ->
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.IO) { runCatching { DesktopProjectAutosave.save(doc) } }
            }
    }

    val onAnalyze: () -> Unit = onAnalyze@{
        val targets = vm.uiState.value.selectedClips.filter { it.prompt.isNotBlank() }
        if (targets.isEmpty()) {
            vm.setProcessing(false, "Type what to keep or cut first — e.g. \"keep shots with a face\".")
            return@onAnalyze
        }
        vm.setProcessing(true, null)
        vm.setAnalyzing(targets.map { it.id }, true)
        scope.launch {
            try {
                vm.setProcessing(false, "Analysis is not yet available on desktop.")
            } finally {
                vm.setAnalyzing(targets.map { it.id }, false)
                vm.setAnalysisProgress(null)
            }
        }
    }

    val onTranscribe: () -> Unit = {
        vm.setProcessing(false, "Transcription is not yet available on desktop.")
    }

    LaunchedEffectPlayback(vm, state.isPlaying)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffectFocus(focusRequester)

    val providerLabel = settings.provider.meta.label

    val processLabel: String? = when {
        exporting -> "Exporting…"
        state.isProcessing -> state.analysisProgress?.stage ?: "Analyzing with $providerLabel…"
        else -> null
    }
    val processFraction: Float? = when {
        exporting -> exportProgress
        state.isProcessing -> state.analysisProgress?.fraction
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier
                .fillMaxSize()
                .background(Black),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { handleKey(it, vm) },
                ) {
                    TopBar(
                        state = state,
                        onUndo = vm::undo,
                        onRedo = vm::redo,
                        onZoomIn = vm::zoomIn,
                        onZoomOut = vm::zoomOut,
                        onFitAll = vm::fitAllToViewport,
                        onImport = { importTargetTrack = null; importLauncher() },
                        onGenerate = { showGenerate = true },
                        onNameProject = { showNameDialog = true },
                        onNewProject = { showNewProjectConfirm = true },
                        onOpenProject = { openLauncher() },
                        onExport = { exportDone = null; exportError = null; showExport = true },
                        onProjectSettings = { showProjectSettings = true },
                        onSettings = { showSettings = true },
                        onAiComparison = { showAiComparison = true },
                        onHelp = { showHelp = true },
                        onTutorial = { showTutorial = true },
                        onFaq = { showFaq = true },
                    )

                    // Always use expanded layout on desktop: preview top, timeline bottom.
                    Column(Modifier.weight(0.6f).fillMaxWidth()) {
                        VideoPreview(vm, Modifier.weight(1f).fillMaxWidth())
                        TransportControls(vm, state)
                    }
                    EditorToolStrip(
                        vm, state, onAnalyze, onTranscribe, providerLabel,
                        { showSettings = true },
                        assistant = assistantState,
                        onAgentInput = assistantVm::setInput,
                        onAgentRun = { t ->
                            assistantVm.run(
                                t, mcpTools,
                                DesktopMcpAgent.forSettings(settings),
                            )
                        },
                        onImport = { importTargetTrack = null; importLauncher() },
                        onHelp = { showHelp = true },
                    )
                    TimelinePanel(vm, state, onImportToTrack, onCreateOnTrack, Modifier.weight(0.4f).fillMaxWidth())
                }

                ActivityLogPanel(
                    panelState = logPanelState,
                    entries = logEntries,
                    processLabel = processLabel,
                    processFraction = processFraction,
                    onClear = { ActivityLog.clear() },
                    awaitingReply = assistantState.awaitingReply,
                    onReply = { t ->
                        assistantVm.sendReply(
                            t, mcpTools,
                            DesktopMcpAgent.forSettings(settings),
                        )
                    },
                )
            }
        }

        if (showSettings) {
            SettingsScreen(
                current = settings,
                onSave = { newSettings ->
                    scope.launch { keyStore.save(newSettings) }
                    showSettings = false
                },
                onDismiss = { showSettings = false },
            )
        }
    }

    if (showProjectSettings) {
        ProjectSettingsSheet(
            current = state.document.settings,
            onChange = { vm.setGlobalSettings(it) },
            onDismiss = { showProjectSettings = false },
        )
    }
    if (showNameDialog) {
        NameProjectDialog(
            current = state.document.name,
            onConfirm = { vm.setProjectName(it); showNameDialog = false },
            onDismiss = { showNameDialog = false },
        )
    }
    if (showNewProjectConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNewProjectConfirm = false },
            title = { Text("Start a new project?", color = White) },
            text = {
                Text(
                    "Your current project will be replaced by an empty one and this will overwrite " +
                        "the autosave. Export any work you want to keep first (menu → Render).",
                    color = Neutral400, fontSize = 12.sp,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.loadDocument(Document())
                        showNewProjectConfirm = false
                    },
                ) {
                    Text("New", color = Red500, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showNewProjectConfirm = false },
                ) {
                    Text("Cancel", color = Neutral400, fontSize = 14.sp)
                }
            },
            containerColor = Neutral900,
        )
    }
    if (showGenerate) {
        GenerateSheet(
            leonardoKey = settings.leonardoKey,
            leonardoModel = settings.leonardoModel,
            onGenerateFree = { url, name ->
                vm.addMedia(listOf(MediaItem(newId(), url, name, MediaKind.IMAGE, 5_000)))
                showGenerate = false
            },
            onGenerateLeonardo = { prompt, modelId ->
                ActivityLog.info("Leonardo generation is not yet available on desktop.")
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
            onStart = { _ ->
                exportError = "Export is not yet available on desktop."
            },
            onDismiss = { if (!exporting) showExport = false },
        )
    }
    if (showAiComparison) {
        AiComparisonSheet(onDismiss = { showAiComparison = false })
    }
    if (showHelp) HelpKeyDialog(onDismiss = { showHelp = false })
    if (showTutorial) TutorialDialog(onDismiss = { showTutorial = false })
    if (showFaq) FaqDialog(onDismiss = { showFaq = false })
}

@Composable
private fun TopBar(
    state: EditorUiState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitAll: () -> Unit,
    onImport: () -> Unit,
    onGenerate: () -> Unit,
    onNameProject: () -> Unit,
    onNewProject: () -> Unit,
    onOpenProject: () -> Unit,
    onExport: () -> Unit,
    onProjectSettings: () -> Unit,
    onSettings: () -> Unit,
    onAiComparison: () -> Unit,
    onHelp: () -> Unit,
    onTutorial: () -> Unit,
    onFaq: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Neutral950).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconToolButton(Icons.Filled.Menu, "Menu") { menuExpanded = true }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("New") }, onClick = { menuExpanded = false; onNewProject() })
                DropdownMenuItem(text = { Text("Open") }, onClick = { menuExpanded = false; onOpenProject() })
                DropdownMenuItem(text = { Text("Save") }, onClick = { menuExpanded = false; onNameProject() })
                DropdownMenuItem(text = { Text("Import") }, onClick = { menuExpanded = false; onImport() })
                DropdownMenuItem(text = { Text("Generate") }, onClick = { menuExpanded = false; onGenerate() })
                DropdownMenuItem(text = { Text("Render") }, onClick = { menuExpanded = false; onExport() })
                androidx.compose.material3.HorizontalDivider()
                DropdownMenuItem(text = { Text("Project") }, onClick = { menuExpanded = false; onProjectSettings() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menuExpanded = false; onSettings() })
                DropdownMenuItem(text = { Text("Compare AI") }, onClick = { menuExpanded = false; onAiComparison() })
                DropdownMenuItem(text = { Text("Tutorial") }, onClick = { menuExpanded = false; onTutorial() })
                DropdownMenuItem(text = { Text("FAQ") }, onClick = { menuExpanded = false; onFaq() })
                DropdownMenuItem(text = { Text("Icon Key") }, onClick = { menuExpanded = false; onHelp() })
            }
        }
        Text(
            state.document.name.ifBlank { "Untitled project" },
            color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconToolButton(Icons.Filled.FitScreen, "Fit all", onClick = onFitAll)
            IconToolButton(Icons.Filled.ZoomOut, "Zoom out", onClick = onZoomOut)
            IconToolButton(Icons.Filled.ZoomIn, "Zoom in", onClick = onZoomIn)
            IconToolButton(Icons.Filled.Undo, "Undo", enabled = state.canUndo, onClick = onUndo)
            IconToolButton(Icons.Filled.Redo, "Redo", enabled = state.canRedo, onClick = onRedo)
            IconToolButton(Icons.Filled.HelpOutline, "Help", onClick = onHelp)
        }
    }
}

@Composable
private fun NameProjectDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save project", color = White) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace("\n", "") },
                singleLine = true,
                placeholder = { Text("Untitled project", color = Neutral500, fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(name) }) {
                Text("Save", color = Red500, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = Neutral400, fontSize = 14.sp)
            }
        },
        containerColor = Neutral900,
    )
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
        val frameMs = Math.round(state.document.settings.frameDurationMs)
        IconToolButton(Icons.Filled.SkipPrevious, "Start") { vm.seekTo(0) }
        IconToolButton(Icons.Filled.ChevronLeft, "Back 1 frame") { vm.seekTo(state.currentTimeMs - frameMs) }
        IconToolButton(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause") { vm.togglePlay() }
        IconToolButton(Icons.Filled.ChevronRight, "Forward 1 frame") { vm.seekTo(state.currentTimeMs + frameMs) }
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

@Composable
private fun ToolGroupSeparator() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(Neutral800))
}

@Composable
private fun EditorToolStrip(
    vm: EditorViewModel,
    state: EditorUiState,
    onAnalyze: () -> Unit,
    onTranscribe: () -> Unit,
    providerLabel: String,
    onOpenSettings: () -> Unit,
    assistant: AssistantViewModel.UiState,
    onAgentInput: (String) -> Unit,
    onAgentRun: (String) -> Unit,
    onImport: () -> Unit,
    onHelp: () -> Unit,
) {
    val selected = state.selectedClips
    val focusManager = LocalFocusManager.current
    val submit: () -> Unit = submit@{
        focusManager.clearFocus()
        if (selected.isEmpty()) {
            val text = assistant.input.ifBlank { state.lastPrompt }
            if (text.isNotBlank()) { vm.rememberPrompt(text); onAgentRun(text) }
            return@submit
        }
        val current = selected.firstOrNull()?.prompt.orEmpty()
        val effective = current.ifBlank { state.lastPrompt }
        if (current.isBlank() && effective.isNotBlank()) vm.setPromptForSelected(effective)
        vm.rememberPrompt(effective)
        onAnalyze()
    }
    var promptFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Neutral900)) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconToolButton(Icons.Filled.NearMe, "Select", active = state.tool == EditorTool.SELECT) {
                vm.setTool(EditorTool.SELECT)
            }
            IconToolButton(Icons.Filled.SelectAll, "Select range (drag)", active = state.tool == EditorTool.MARQUEE) {
                vm.setTool(EditorTool.MARQUEE)
            }
            IconToolButton(Icons.Filled.Crop, "Crop / transform", active = state.tool == EditorTool.CROP) {
                vm.setTool(EditorTool.CROP)
            }
            IconToolButton(Icons.Filled.ShowChart, "Auto-ease keyframes", active = state.autoEase) {
                vm.toggleAutoEase()
            }

            ToolGroupSeparator()

            IconToolButton(Icons.Filled.ContentCut, "Split at playhead") {
                vm.splitAtPlayhead()
            }
            IconToolButton(Icons.Filled.Diamond, "Keyframe crop/placement at playhead") {
                vm.addKeyframeAtPlayhead()
            }
            IconToolButton(Icons.Filled.Add, "Import media", onClick = onImport)
            IconToolButton(Icons.Filled.Delete, "Delete", enabled = state.selectedClipIds.isNotEmpty()) {
                vm.deleteSelected()
            }
            IconToolButton(Icons.Filled.Compress, "Ripple (close gaps)") {
                vm.rippleCloseGaps()
            }
            if (selected.size > 1) {
                val grouped = selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null }
                IconToolButton(
                    if (grouped) Icons.Filled.LinkOff else Icons.Filled.Link,
                    if (grouped) "Ungroup" else "Group",
                    active = grouped,
                ) { if (grouped) vm.ungroupSelected() else vm.groupSelected() }
            }

            ToolGroupSeparator()

            IconToolButton(Icons.Filled.HelpOutline, "Help / icon key", onClick = onHelp)

            val oneUnit = selected.size == 1 ||
                (selected.size > 1 && selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null })
            if (oneUnit) {
                ToolGroupSeparator()
                ClipToolButtons(vm, state, onTranscribe)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hasClip = selected.isNotEmpty()
            val fieldValue = if (hasClip) (selected.firstOrNull()?.prompt ?: "") else assistant.input
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { v ->
                        val submitNow = v.contains('\n')
                        val text = v.replace("\n", "")
                        if (hasClip) vm.setPromptForSelected(text) else onAgentInput(text)
                        if (submitNow) submit()
                    },
                    readOnly = assistant.running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { promptFocused = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                                submit(); true
                            } else {
                                false
                            }
                        },
                    placeholder = {
                        val hint = if (hasClip) {
                            state.lastPrompt.ifBlank { "e.g. \"keep shots with a face\" or \"cut clips with a car\"" }
                        } else {
                            "Tell the AI what to do — e.g. \"cut the silences in clip 1\""
                        }
                        Text(hint, color = Neutral500, fontSize = 12.sp)
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                DropdownMenu(
                    expanded = promptFocused && fieldValue.isBlank() && state.promptHistory.isNotEmpty(),
                    onDismissRequest = { promptFocused = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    state.promptHistory.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p, color = White, fontSize = 12.sp, maxLines = 1) },
                            onClick = { if (hasClip) vm.setPromptForSelected(p) else onAgentInput(p); promptFocused = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!hasClip && assistant.running) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Red500)
                } else {
                    ToolbarButton(if (hasClip) "AI" else "AI ▸", tint = Red500, onClick = submit)
                }
                Text(
                    providerLabel,
                    color = Neutral500,
                    fontSize = 9.sp,
                    modifier = Modifier.clickable(onClick = onOpenSettings).padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LaunchedEffectFocus(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

@Composable
private fun LaunchedEffectPlayback(vm: EditorViewModel, isPlaying: Boolean) {
    LaunchedEffect(isPlaying) {
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
        e.key == Key.Delete -> { vm.deleteSelected(); true }
        ctrl && e.key == Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
        ctrl && e.key == Key.Y -> { vm.redo(); true }
        ctrl && e.isAltPressed && e.key == Key.C -> { vm.copySelectedFilters(); true }
        ctrl && e.isAltPressed && e.key == Key.V -> { vm.pasteFiltersToSelected(); true }
        ctrl && e.key == Key.C -> { vm.copySelected(); true }
        ctrl && e.key == Key.V -> { vm.pasteClip(); true }
        !ctrl && e.key == Key.S -> { vm.splitAtPlayhead(); true }
        else -> false
    }
}
