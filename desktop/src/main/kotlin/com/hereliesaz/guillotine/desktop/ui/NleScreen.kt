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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder
import com.hereliesaz.guillotine.desktop.media.DesktopVoskTranscriber
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
    // One backend instance reused across turns so the assistant keeps its conversation memory. Rebuilt only
    // when the AI settings change (provider/model/key) — which naturally begins a fresh conversation.
    val agentBackend = remember(
        settings.provider,
        settings.keyFor(settings.provider),
        settings.modelFor(settings.provider),
        settings.cloudVision,
    ) { DesktopMcpAgent.forSettings(settings, mcpTools) }
    // Read through this in remembered lambdas (e.g. openLauncher) so they always reset the CURRENT backend,
    // not a stale one captured before a settings-driven rebuild.
    val currentAgentBackend by androidx.compose.runtime.rememberUpdatedState(agentBackend)

    // Headless assistant: the single prompt field in the tool strip runs the agent through this
    // when nothing is selected, and shows its status inline.
    val assistantVm = remember { AssistantViewModel() }
    val assistantState by assistantVm.state.collectAsState()

    // Integrated activity feed (AI chat output, running process, progress, errors) shown in the
    // bottom panel. The single AI prompt stays in the tool strip; this panel is output-only.
    val logEntries by ActivityLog.entries.collectAsState()
    val logPanelState = remember { mutableStateOf(LogPanelState.HIDDEN) }

    // Analysis progress stages -> log (deduped inside ActivityLog).
    LaunchedEffect(Unit) {
        vm.uiState.map { it.analysisProgress?.stage }.distinctUntilChanged().collect { stage ->
            if (!stage.isNullOrBlank()) ActivityLog.progress(stage)
        }
    }
    // Editor errors -> log. Clear after logging so a later identical error is reported again.
    LaunchedEffect(Unit) {
        vm.uiState.map { it.error }.distinctUntilChanged().collect { err ->
            if (err != null) { ActivityLog.error(err); vm.clearError() }
        }
    }
    // Push the user-configurable frame-analysis cache bound to FrameAnalysisCache. Keyed on the
    // value so it re-applies whenever Settings saves a new size. First fire, on cold start, seeds
    // the cache from persisted settings before any scan runs.
    LaunchedEffect(settings.frameAnalysisCacheSize) {
        FrameAnalysisCache.setMaxEntries(settings.frameAnalysisCacheSize)
    }


    var showSettings by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    var showStore by remember { mutableStateOf(false) }
    var showExtensionsManager by remember { mutableStateOf(false) }
    // Double-clicking the "X" over two already-overlapping (auto-crossfading) clips on the timeline
    // opens a transition picker for this pair; null when no picker is showing.
    var pendingTransitionSwap by remember { mutableStateOf<Pair<String, String>?>(null) }
    var transitionSwapBusy by remember { mutableStateOf(false) }
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
    // Which track an import should land on (set by a track header's "Import"; null = default).
    var importTargetTrack by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberMediaImportLauncher { files ->
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                files.mapNotNull { com.hereliesaz.guillotine.desktop.media.DesktopMediaImport.probe(it) }
            }
            if (items.isEmpty()) {
                ActivityLog.info("No supported media found in selected files.")
            } else {
                vm.addMedia(items, importTargetTrack)
                ActivityLog.info("Imported ${items.size} clip(s).")
            }
        }
    }
    val onImportToTrack: (String) -> Unit = { track ->
        importTargetTrack = track; importLauncher()
    }
    val onCreateOnTrack: (String) -> Unit = { track ->
        val doc = vm.uiState.value.document
        when {
            // Text is just a clip on a video track: "create" adds an editable text clip there.
            track in doc.videoTracks -> vm.addEmptyTextClip(track)
            else -> { importTargetTrack = track; importLauncher() }
        }
    }

    // The project is auto-saved internally; "Save" exports it as a .gilt file. Load imports a .gilt copy.
    val openLauncher = rememberOpenProjectLauncher { file ->
        scope.launch {
            val doc = withContext(Dispatchers.IO) {
                runCatching { DesktopProjectAutosave.loadFromFile(file) }.getOrNull()
            }
            if (doc != null) {
                vm.loadDocument(doc)
                currentAgentBackend?.reset() // new project → fresh conversation (don't carry prior edits over)
            }
        }
    }
    
    val saveLauncher = rememberSaveProjectLauncher { file ->
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { DesktopProjectAutosave.saveToFile(file, vm.uiState.value.document) }
            }
        }
    }

    // Auto-save / restore: load the autosaved project on launch (fresh editor only), then
    // continuously persist the document to internal storage on every change (debounced via
    // collectLatest -- a new edit cancels the pending write). The user never has to save.
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
            // A clip is selected but no prompt was typed -- guide the user instead of no-op.
            vm.setProcessing(false, "Type what to keep or cut first -- e.g. \"keep shots with a face\".")
            return@onAnalyze
        }
        vm.setProcessing(true, null)
        vm.setAnalyzing(targets.map { it.id }, true)
        scope.launch {
            try {
                // Analysis not available on desktop yet -- stub.
                vm.setProcessing(false, "Analysis is not yet available on desktop.")
            } finally {
                vm.setAnalyzing(targets.map { it.id }, false)
                vm.setAnalysisProgress(null)
            }
        }
    }

    val onTranscribe: (CaptionStyle) -> Unit = onTranscribe@{ style ->
        val clip = vm.uiState.value.selectedClips.singleOrNull() ?: return@onTranscribe
        val media = vm.uiState.value.document.mediaFor(clip) ?: return@onTranscribe
        // Was a hardcoded path assuming one specific azphalt package id (com.azphalt.model.vosk) that
        // has never existed in the live catalog — a total dead end regardless of what the user actually
        // configured. ModelResolver.resolve("speechModelPath") is the real, user-settable Vosk path
        // (Settings → Transcription), the same one DesktopMcpTools.transcribeClip() (the MCP tool this
        // button duplicates) already correctly uses.
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("speechModelPath")
        if (model.isBlank() || !java.io.File(model).exists()) {
            vm.setProcessing(false, "No on-device speech model set. Set a Vosk model folder in Settings → Transcription.")
            return@onTranscribe
        }
        vm.setProcessing(true, null)
        scope.launch {
            try {
                // Decode + Vosk are blocking/CPU-heavy — keep them off the UI dispatcher.
                val cues = withContext(Dispatchers.IO) {
                    val pcm = DesktopMediaDecoder.decodePcmMono(media.uri, 16_000)
                        ?: throw IllegalStateException("No audio track in \"${media.name}\" to transcribe.")
                    DesktopVoskTranscriber.transcribe(model, pcm.samples)
                }
                // Animated captions need per-word timing; fall back to plain subtitles when the
                // on-device model didn't emit it (so the tap never silently no-ops).
                val words = if (style == CaptionStyle.ANIMATED) cues.flatMap { it.words } else emptyList()
                if (style == CaptionStyle.ANIMATED && words.isNotEmpty()) {
                    vm.addAnimatedCaptionsFromTranscript(clip.id, words)
                } else {
                    vm.addTextClipsFromTranscript(clip.id, cues)
                }
                vm.setProcessing(false, null)
            } catch (e: Exception) {
                vm.setProcessing(false, e.message ?: "Transcription failed")
            }
        }
    }

    // Playback clock: advances the timeline and skips 'remove' ranges.
    LaunchedEffectPlayback(vm, state.isPlaying)

    // Keyboard shortcuts.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffectFocus(focusRequester)

    val providerLabel = settings.provider.meta.label

    // What the bottom panel shows as the "active process" line: export takes precedence over
    // analysis (they can't both run -- but export is the one started from NleScreen's own local
    // state). Null when nothing is running.
    val processLabel: String? = when {
        exporting -> "Exporting..."
        state.isProcessing -> state.analysisProgress?.stage ?: "Analyzing with $providerLabel..."
        else -> null
    }
    val processFraction: Float? = when {
        exporting -> exportProgress
        state.isProcessing -> state.analysisProgress?.fraction
        else -> null
    }

    // The menu is a standard DropdownMenu in the TopBar -- its trigger icon sits right next to
    // the project name.
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier
            .fillMaxSize()
            .background(Black),
    ) {
        // Editor area (weighted) + the activity-log panel share this Box.
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                // onKeyEvent (bubble phase), NOT preview: a focused text field gets first crack
                // at the keys, so typing in the prompt doesn't trigger editor shortcuts.
                .onKeyEvent {
                    // Tracked on every key event, not just the ones handleKey consumes below — a
                    // mouse-drag on the timeline (ClipView) reads HeldModifiers live to pick which
                    // Vegas-style edit mode a Ctrl/Alt/Shift-held drag enters.
                    HeldModifiers.update(it)
                    handleKey(it, vm)
                },
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
            onNewProject = { showNewProjectConfirm = true },
            onOpenProject = { openLauncher() },
            onSaveProject = { saveLauncher() },
            onExport = { exportDone = null; exportError = null; showExport = true },
            onProjectSettings = { showProjectSettings = true },
            onOpenStore = { showStore = true },
            onOpenExtensions = { showExtensionsManager = true },
            onSettings = { showSettings = true },
            onOpenAiSettings = { showAiSettings = true },
            onAiComparison = { showAiComparison = true },
            onHelp = { showHelp = true },
            onTutorial = { showTutorial = true },
            onFaq = { showFaq = true },
        )

        // Preview (top) over the timeline (bottom), split resizable via the divider below the preview.
        // Defaults to 60/40; the tool strip in between is fixed-height, so dragging trades preview for
        // timeline space.
        var previewWeight by remember { mutableFloatStateOf(0.6f) }
        Column(Modifier.weight(previewWeight).fillMaxWidth()) {
            VideoPreview(vm, Modifier.weight(1f).fillMaxWidth())
            TransportControls(vm, state)
        }
        DraggableSplitDivider(
            onDrag = { dragAmount ->
                // Fixed sensitivity (no measured height here); ~1px → 0.0011 weight feels right on a
                // typical desktop editor pane. Positive drag = down = grow the preview.
                previewWeight = (previewWeight + dragAmount * 0.0011f).coerceIn(0.25f, 0.85f)
            },
        )
        EditorToolStrip(
            vm, state, onAnalyze, onTranscribe, providerLabel,
            { showSettings = true },
            assistant = assistantState,
            onAgentInput = assistantVm::setInput,
            onAgentRun = { t ->
                assistantVm.run(t, mcpTools, agentBackend)
            },
            onImport = { importTargetTrack = null; importLauncher() },
            onHelp = { showHelp = true },
        )
        TimelinePanel(
            vm, state, onImportToTrack, onCreateOnTrack,
            Modifier.weight(1f - previewWeight).fillMaxWidth(),
            onSwapTransition = { from, to -> pendingTransitionSwap = from to to },
        )
        } // editor Column

        // Integrated activity log (AI chat, running process, progress, errors) -- anchored to
        // the bottom of the editor Box.
        ActivityLogPanel(
            // Actually dock it to the bottom: this Box has no contentAlignment, so without an
            // explicit align the height-wrapping panel defaults to top-start (the reported bug).
            modifier = Modifier.align(Alignment.BottomCenter),
            panelState = logPanelState,
            entries = logEntries,
            processLabel = processLabel,
            processFraction = processFraction,
            onClear = { ActivityLog.clear() },
            awaitingReply = assistantState.awaitingReply,
            // Route the clarification reply back into the agent as a follow-up run: the ViewModel
            // synthesizes an "original request + question + reply" continuation prompt so any
            // backend picks up where it left off without needing message-log state.
            onReply = { t ->
                assistantVm.sendReply(t, mcpTools, agentBackend)
            },
        )
        } // editor + panel Box
    }

    if (showSettings) {
        SettingsScreen(
            current = settings,
            onSave = { newSettings ->
                scope.launch { keyStore.save(newSettings) }
                showSettings = false
            },
            onDismiss = { showSettings = false },
            restrictToTabs = listOf(3),
        )
    }

    if (showAiSettings) {
        SettingsScreen(
            current = settings,
            onSave = { newSettings ->
                scope.launch { keyStore.save(newSettings) }
                showAiSettings = false
            },
            onDismiss = { showAiSettings = false },
            restrictToTabs = listOf(0, 1, 2),
        )
    }

    if (showStore) {
        DesktopAzphaltStoreScreen(vm = vm, onDismiss = { showStore = false })
    }

    if (showExtensionsManager) {
        DesktopExtensionsManagerScreen(vm = vm, onDismiss = { showExtensionsManager = false })
    }

    // Swap the free, instant crossfade an overlap already produces for a named FFmpeg xfade
    // transition — a deliberate, real bake (needs an ffmpeg binary configured, takes real time), not
    // something that happens automatically just from overlapping two clips.
    pendingTransitionSwap?.let { (fromId, toId) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!transitionSwapBusy) pendingTransitionSwap = null },
            title = { androidx.compose.material3.Text(if (transitionSwapBusy) "Building transition…" else "Swap crossfade for…") },
            text = {
                if (transitionSwapBusy) {
                    androidx.compose.material3.CircularProgressIndicator()
                } else {
                    Column {
                        com.hereliesaz.guillotine.model.TransitionCatalog.TYPES.forEach { (type, label) ->
                            androidx.compose.material3.TextButton(onClick = {
                                transitionSwapBusy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            mcpTools.call(
                                                "apply_transition",
                                                org.json.JSONObject()
                                                    .put("from_clip_id", fromId)
                                                    .put("to_clip_id", toId)
                                                    .put("type", type),
                                            )
                                        }
                                    }
                                    transitionSwapBusy = false
                                    pendingTransitionSwap = null
                                    result.onFailure { vm.setProcessing(false, it.message ?: "Couldn't build the transition.") }
                                }
                            }) { androidx.compose.material3.Text(label) }
                        }
                    }
                }
            },
            confirmButton = {
                if (!transitionSwapBusy) {
                    androidx.compose.material3.TextButton(onClick = { pendingTransitionSwap = null }) { androidx.compose.material3.Text("Cancel") }
                }
            },
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
        // "New" replaces the autosaved document with a fresh one; confirm before doing that.
        AlertDialog(
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
                TextButton(
                    onClick = {
                        vm.loadDocument(Document())
                        currentAgentBackend?.reset() // fresh project → fresh conversation
                        showNewProjectConfirm = false
                    },
                ) {
                    Text("New", color = Red500, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(
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
            onGenerateLeonardo = { prompt, _ ->
                // TODO: Leonardo generation requires an Android-specific download path.
                // The desktop build can reach the Leonardo API but cannot download + cache
                // the result the same way. Stub until a desktop download helper is added.
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
            onStart = { name ->
                exporting = true
                exportProgress = 0f
                exportError = null
                exportDone = null
                scope.launch {
                    try {
                        val exportDoc = vm.uiState.value.document
                        // Export at the project's aspect ratio (what the preview already shows) instead of
                        // a fixed 1920x1080 — otherwise a 9:16 vertical or 1:1 square project exported as
                        // letterboxed landscape.
                        val (exportW, exportH) = when (exportDoc.settings.aspectRatio) {
                            com.hereliesaz.guillotine.model.AspectRatio.RATIO_9_16 -> 1080 to 1920
                            com.hereliesaz.guillotine.model.AspectRatio.RATIO_1_1 -> 1080 to 1080
                            else -> 1920 to 1080 // RATIO_16_9 and ORIGINAL → 1080p landscape
                        }
                        val config = com.hereliesaz.guillotine.desktop.media.DesktopExporter.ExportConfig(
                            name = name, width = exportW, height = exportH,
                        )
                        val file = com.hereliesaz.guillotine.desktop.media.DesktopExporter.export(
                            document = exportDoc,
                            config = config,
                            onProgress = { p, ms ->
                                exportProgress = p
                                vm.seekTo(ms)
                            },
                        )
                        exportDone = "Saved to ${file.absolutePath}"
                        ActivityLog.info("Export complete: ${file.absolutePath}")
                    } catch (e: Exception) {
                        exportError = e.message ?: "Export failed"
                        ActivityLog.error("Export failed: ${e.message}")
                    } finally {
                        exporting = false
                    }
                }
            },
            onDismiss = { if (!exporting) showExport = false },
        )
    }
    if (showAiComparison) {
        AiComparisonSheet(onDismiss = { showAiComparison = false })
    }
    if (showHelp) HelpKeyDialog(onDismiss = { showHelp = false })
    if (showTutorial) TutorialDialog(onDismiss = { showTutorial = false })
    if (showFaq) FaqDialog(settings = settings, onDismiss = { showFaq = false })
}

// -------------------------------------------------------------------------------------
// TopBar
// -------------------------------------------------------------------------------------

/** Slim top bar: menu icon + project name + zoom + undo/redo. */
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
    onNewProject: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onExport: () -> Unit,
    onProjectSettings: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenExtensions: () -> Unit,
    onSettings: () -> Unit,
    onOpenAiSettings: () -> Unit,
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
        // Standard Material3 DropdownMenu (replaces Android AzDropdownMenu).
        Box {
            IconToolButton(Icons.Filled.Menu, "Menu") { menuExpanded = true }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("New") }, onClick = { menuExpanded = false; onNewProject() })
                DropdownMenuItem(text = { Text("Open") }, onClick = { menuExpanded = false; onOpenProject() })
                DropdownMenuItem(text = { Text("Save") }, onClick = { menuExpanded = false; onSaveProject() })
                DropdownMenuItem(text = { Text("Import") }, onClick = { menuExpanded = false; onImport() })
                DropdownMenuItem(text = { Text("Generate") }, onClick = { menuExpanded = false; onGenerate() })
                DropdownMenuItem(text = { Text("Render") }, onClick = { menuExpanded = false; onExport() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Project") }, onClick = { menuExpanded = false; onProjectSettings() })
                DropdownMenuItem(text = { Text("Store") }, onClick = { menuExpanded = false; onOpenStore() })
                DropdownMenuItem(text = { Text("Extensions") }, onClick = { menuExpanded = false; onOpenExtensions() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menuExpanded = false; onSettings() })
                DropdownMenuItem(text = { Text("AI") }, onClick = { menuExpanded = false; onOpenAiSettings() })
                DropdownMenuItem(text = { Text("Compare AI") }, onClick = { menuExpanded = false; onAiComparison() })
                DropdownMenuItem(text = { Text("Tutorial") }, onClick = { menuExpanded = false; onTutorial() })
                DropdownMenuItem(text = { Text("FAQ") }, onClick = { menuExpanded = false; onFaq() })
                DropdownMenuItem(text = { Text("Icon Key") }, onClick = { menuExpanded = false; onHelp() })
            }
        }
        Text(
            state.document.name.ifBlank { "Untitled project" },
            color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            // Cap the name so a long project title can't push the button row off the screen; the
            // weighted spacer below still absorbs any leftover room on wider displays.
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.weight(1f))
        // Right-side toolbar in its own horizontal scroll. On wide screens all six icons fit
        // straight through; on narrow windows the row scrolls internally.
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

// -------------------------------------------------------------------------------------
// NameProjectDialog
// -------------------------------------------------------------------------------------

/**
 * "Save": name the (always-autosaved) project. The project is continuously autosaved regardless --
 * this dialog only sets its user-facing name.
 */
@Composable
private fun NameProjectDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
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
            TextButton(onClick = { onConfirm(name) }) {
                Text("Save", color = Red500, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Neutral400, fontSize = 14.sp)
            }
        },
        containerColor = Neutral900,
    )
}

// -------------------------------------------------------------------------------------
// TransportControls
// -------------------------------------------------------------------------------------

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
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconToolButton(Icons.Filled.SkipPrevious, "Start") { vm.seekTo(0) }
            IconToolButton(Icons.Filled.ChevronLeft, "Back 1 frame") { vm.seekTo(state.currentTimeMs - frameMs) }
            IconToolButton(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause") { vm.togglePlay() }
            IconToolButton(Icons.Filled.ChevronRight, "Forward 1 frame") { vm.seekTo(state.currentTimeMs + frameMs) }
            IconToolButton(Icons.Filled.SkipNext, "End") { vm.seekTo(total) }
        }
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

// -------------------------------------------------------------------------------------
// ToolGroupSeparator
// -------------------------------------------------------------------------------------

/** Thin vertical rule that visually separates groups of toolbar buttons (modes | actions | help). */
@Composable
private fun ToolGroupSeparator() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(Neutral800))
}

// -------------------------------------------------------------------------------------
// EditorToolStrip
// -------------------------------------------------------------------------------------

/**
 * Shared editor tool strip used by both layouts: a horizontally-scrollable row of
 * tools (mirroring the web build -- select, split, keyframe, add-track, delete, zoom)
 * plus context-sensitive per-clip tools (filters, audio, background, text, keyframes,
 * transcribe, split -- these replaced the old Inspector), and an AI prompt box. The
 * prompt box grows up to several lines, so the strip's height expands with multiline
 * input. With a clip selected the box edits that clip's prompt and the AI button runs
 * the analyzer; with nothing selected AI opens Generate. The provider chip shows which
 * engine the AI button will use (on-device or BYO key) and opens Settings on tap.
 */
@Composable
private fun EditorToolStrip(
    vm: EditorViewModel,
    state: EditorUiState,
    onAnalyze: () -> Unit,
    onTranscribe: (CaptionStyle) -> Unit,
    providerLabel: String,
    onOpenSettings: () -> Unit,
    assistant: AssistantViewModel.UiState,
    onAgentInput: (String) -> Unit,
    onAgentRun: (String) -> Unit,
    onImport: () -> Unit,
    onHelp: () -> Unit,
) {
    val selected = state.selectedClips
    // On desktop we only need clearFocus() -- there is no soft keyboard to dismiss.
    val focusManager = LocalFocusManager.current

    // The single AI prompt field does both jobs:
    //  - a clip/group is selected  -> the text is an analysis prompt for it; run on-device analysis
    //    (free, no LLM brain needed) -- exactly as before.
    //  - nothing is selected       -> the text is a general instruction; hand it to the agent, which
    //    drives the whole editor through the MCP tools (this is what the old assistant bar did).
    // Used by both the Enter key and the AI button.
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

    // Whether the prompt field has focus -- drives the recent-prompts history dropdown.
    var promptFocused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(Neutral900)) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---- Group 1: Modes (toggle a tool on/off; active one is highlighted) ----
            IconToolButton(Icons.Filled.NearMe, "Select", active = state.tool == EditorTool.SELECT) {
                vm.setTool(EditorTool.SELECT)
            }
            // Marquee: drag a rectangle over a time range to select every clip it touches.
            IconToolButton(Icons.Filled.SelectAll, "Select range (drag)", active = state.tool == EditorTool.MARQUEE) {
                vm.setTool(EditorTool.MARQUEE)
            }
            IconToolButton(Icons.Filled.Crop, "Crop / transform", active = state.tool == EditorTool.CROP) {
                vm.setTool(EditorTool.CROP)
            }

            ToolGroupSeparator()

            // ---- Group 2: Create (add something new to the timeline) ----
            // Text is just a clip on a video track — a discoverable, toolbar-level way to add one.
            IconToolButton(Icons.Filled.TextFields, "Add text") {
                val track = state.document.videoTracks.firstOrNull()
                if (track != null) vm.selectClip(vm.addEmptyTextClip(track))
            }
            // Import media (new tracks come from the track-head popup or dragging a clip past the edge).
            IconToolButton(Icons.Filled.Add, "Import media", onClick = onImport)

            ToolGroupSeparator()

            // ---- Group 3: Clip editing actions (act on the current selection) ----
            // Scissors splits at the playhead immediately -- the selected clip/group, or
            // every clip on every track when nothing is selected.
            IconToolButton(Icons.Filled.ContentCut, "Split at playhead") {
                vm.splitAtPlayhead()
            }
            // Record the selected clip's crop/placement (+opacity) at the playhead.
            IconToolButton(Icons.Filled.Diamond, "Keyframe crop/placement at playhead") {
                vm.addKeyframeAtPlayhead()
            }
            // onTranscribe already no-ops unless exactly one clip is selected.
            IconToolButton(
                Icons.Filled.ClosedCaption,
                "Transcribe selected clip",
                enabled = selected.singleOrNull()?.type == com.hereliesaz.guillotine.model.ClipType.VIDEO,
            ) {
                onTranscribe(CaptionStyle.PLAIN)
            }
            IconToolButton(Icons.Filled.Delete, "Delete", enabled = state.selectedClipIds.isNotEmpty()) {
                vm.deleteSelected()
            }
            // Group / ungroup -- only meaningful with a multi-clip selection.
            if (selected.size > 1) {
                val grouped = selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null }
                IconToolButton(
                    if (grouped) Icons.Filled.LinkOff else Icons.Filled.Link,
                    if (grouped) "Ungroup" else "Group",
                    active = grouped,
                ) { if (grouped) vm.ungroupSelected() else vm.groupSelected() }
            }

            ToolGroupSeparator()

            // ---- Group 4: Timeline & playback behavior toggles (change how OTHER actions behave) ----
            IconToolButton(Icons.Filled.ShowChart, "Auto-ease keyframes", active = state.autoEase) {
                vm.toggleAutoEase()
            }
            IconToolButton(Icons.Filled.Repeat, "Loop playback", active = state.loopPlayback) { vm.toggleLoop() }
            // Ripple: close the gaps among the selected clips (or all clips if none selected) --
            // a one-shot action, distinct from the Auto-Ripple toggle right after it.
            IconToolButton(Icons.Filled.Compress, "Ripple (close gaps)") {
                vm.rippleCloseGaps()
            }
            // Auto-Ripple: from here on, deleting a clip closes the gap on its own track automatically
            // (Vegas's "Affected Tracks" mode) instead of leaving a hole for "Ripple (close gaps)" above
            // to clean up later.
            IconToolButton(Icons.Filled.Bolt, "Auto-Ripple: close gaps on delete", active = state.autoRippleEnabled) {
                vm.toggleAutoRipple()
            }
            IconToolButton(Icons.Filled.GridOn, "Snap to edges, playhead, and grid (F8)", active = state.snapEnabled) {
                vm.toggleSnap()
            }

            // Context-sensitive per-clip tools (filters, audio, background, text,
            // keyframes, transcribe, split) -- formerly the Inspector panel. Shown for a
            // single clip, or a single group (e.g. a linked video+audio pair) so its parts
            // can be edited without ungrouping.
            val oneUnit = selected.size == 1 ||
                (selected.size > 1 && selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null })
            if (oneUnit) {
                ToolGroupSeparator()
                ClipToolButtons(vm, state, onTranscribe)
            }

            ToolGroupSeparator()

            // Help is always last: opens the icon key (what every button does).
            IconToolButton(Icons.Filled.HelpOutline, "Help / icon key", onClick = onHelp)
        }

        // The agent's running status/output now streams into the activity-log bottom panel; the
        // spinner on the AI button (below) is the only inline "it's working" cue.
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // With a clip selected the field edits/runs that clip's analysis prompt; with nothing
            // selected it holds the assistant input and the agent runs it.
            val hasClip = selected.isNotEmpty()
            val fieldValue = if (hasClip) (selected.firstOrNull()?.prompt ?: "") else assistant.input
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = fieldValue,
                    // Enter submits instead of inserting a newline. Hardware Enter is caught by
                    // onPreviewKeyEvent below.
                    onValueChange = { v ->
                        val submitNow = v.contains('\n')
                        val text = v.replace("\n", "")
                        if (hasClip) vm.setPromptForSelected(text) else onAgentInput(text)
                        if (submitNow) submit()
                    },
                    // `readOnly` (not `enabled=false`) while the agent is running: keeps the field
                    // enabled and focusable while blocking mid-run keystrokes and accidental re-submits.
                    // AssistantViewModel.run also guards overlaps (`if (running) return`).
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
                            "Tell the AI what to do -- e.g. \"cut the silences in clip 1\""
                        }
                        Text(hint, color = Neutral500, fontSize = 12.sp)
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                // Recent-prompts history: appears when the empty field is focused. Tapping one
                // fills the field (tap-to-reuse). focusable=false keeps the keyboard up.
                DropdownMenu(
                    expanded = promptFocused && fieldValue.isBlank() && state.promptHistory.isNotEmpty(),
                    onDismissRequest = { promptFocused = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    state.promptHistory.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p, color = White, fontSize = 12.sp, maxLines = 1) },
                            onClick = {
                                if (hasClip) vm.setPromptForSelected(p) else onAgentInput(p)
                                promptFocused = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!hasClip && assistant.running) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Red500)
                } else {
                    IconToolButton(
                        Icons.Filled.Send,
                        if (hasClip) "Apply prompt to selected clip" else "Send prompt to AI",
                        active = true,
                        onClick = submit,
                    )
                }
                // Shows which engine the AI button uses; tap to change it in Settings.
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

// -------------------------------------------------------------------------------------
// Playback / keyboard
// -------------------------------------------------------------------------------------

@Composable
private fun LaunchedEffectFocus(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

@Composable
private fun LaunchedEffectPlayback(vm: EditorViewModel, isPlaying: Boolean) {
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        var elapsedNanos = 0.0
        while (isActive) {
            withFrameNanos { now ->
                val rate = vm.uiState.value.playbackRate
                elapsedNanos += (now - last) * rate
                last = now
                val deltaMs = (elapsedNanos / 1_000_000.0).toLong()
                if (deltaMs > 0) {
                    elapsedNanos -= deltaMs * 1_000_000.0
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
        !ctrl && e.key == Key.S -> { vm.splitAtPlayhead(); true }
        e.key == Key.F8 -> { vm.toggleSnap(); true }
        else -> false
    }
}
