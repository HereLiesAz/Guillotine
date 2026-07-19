package com.hereliesaz.guillotine.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Repeat
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.Analysis
import com.hereliesaz.guillotine.ai.ApiKeyStore
import com.hereliesaz.guillotine.ai.ImageGen
import com.hereliesaz.aznavrail.AzDropdownMenu
import com.hereliesaz.aznavrail.bottomsheet.rememberAzSheetController
import com.hereliesaz.aznavrail.model.AzDropdownDesign
import com.hereliesaz.aznavrail.model.AzSheetDetent
import com.hereliesaz.guillotine.GuillotineApplication
import com.hereliesaz.guillotine.ads.BannerAd
import com.hereliesaz.guillotine.ai.Transcription
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.data.ProjectAutosave
import com.hereliesaz.guillotine.data.ProjectStore
import com.hereliesaz.guillotine.data.rememberOpenProjectLauncher
import com.hereliesaz.guillotine.data.rememberSaveProjectLauncher
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.AndroidEditorViewModel
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
import com.hereliesaz.guillotine.operation.OperationController
import com.hereliesaz.guillotine.operation.OperationKind
import com.hereliesaz.guillotine.ui.theme.Black
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Neutral950
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fire an Android share sheet for the exported video [uri] (a MediaStore content URI). */
private fun shareVideo(context: android.content.Context, uri: Uri) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(send, "Share video")
        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }
}

@Composable
fun NleScreen(widthClass: WindowWidthSizeClass, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel<AndroidEditorViewModel>().editor
    val state by vm.uiState.collectAsState()
    val keyStore = remember { ApiKeyStore(context) }
    val settings by keyStore.settings.collectAsState(initial = AiSettings())
    val scope = rememberCoroutineScope()

    // One shared MCP tool surface: the embedded server, the optional relay, and the in-app AI
    // assistant all drive the editor through this same object ({ settings } reads live).
    val sharedMcpTools = remember { com.hereliesaz.guillotine.mcp.McpTools(context, vm) { settings } }
    // One backend instance reused across turns so the assistant keeps its conversation memory. Rebuilt only
    // when the AI settings change (provider/model/key) — which naturally begins a fresh conversation.
    val agentBackend = remember(
        settings.provider,
        settings.keyFor(settings.provider),
        settings.modelFor(settings.provider),
        // onDevice() reads the on-device model path (not a settings field), so include it or an on-device
        // model change would leave a stale backend.
        com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "agentModelPath"),
    ) {
        com.hereliesaz.guillotine.ai.agent.McpAgent.forSettings(context, settings, sharedMcpTools)
    }
    // Headless assistant (no separate bar): the single prompt field below the tools runs the agent
    // through this when nothing is selected, and shows its status inline.
    val assistantVm: AssistantViewModel = viewModel()
    val assistantState by assistantVm.state.collectAsState()
    // Give the assistant a disk cache so the one-time LLM vocabulary expansion persists across launches.
    androidx.compose.runtime.LaunchedEffect(context) {
        assistantVm.vocabCache = com.hereliesaz.guillotine.platform.AndroidVocabularyCache(context)
    }

    // Integrated activity feed (AI chat output, running process, progress, errors) shown in the
    // AzNavRail bottom sheet. The single AI prompt stays in the tool strip; this sheet is output-only.
    val logEntries by ActivityLog.entries.collectAsState()
    val sheetController = rememberAzSheetController(initial = AzSheetDetent.HIDDEN)
    // Analysis progress → log. The frame counter ("Frame X of Y") stays live on the process
    // indicator and is intentionally NOT dumped into the feed (that was all the sheet used to show).
    // Instead the feed gets the per-region findings (what vision saw + keep/cut decision) and the
    // narrative stages ("Decoding audio…", "Transcribing…").
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.uiState.map { it.analysisProgress }.distinctUntilChanged().collect { p ->
            if (p == null) return@collect
            p.finding?.let { ActivityLog.info(it) }
            if (p.stage.isNotBlank() && !p.stage.startsWith("Frame ") && p.stage != "Analyzed image") {
                ActivityLog.progress(p.stage)
            }
        }
    }
    // Editor errors → log. Clear after logging so a later identical error is reported again.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.uiState.map { it.error }.distinctUntilChanged().collect { err ->
            if (err != null) { ActivityLog.error(err); vm.clearError() }
        }
    }
    // Push the user-configurable frame-analysis cache bound to FrameAnalysisCache. Keyed on the
    // value so it re-applies whenever Settings saves a new size. First fire, on cold start, seeds
    // the cache from persisted settings before any scan runs.
    androidx.compose.runtime.LaunchedEffect(settings.frameAnalysisCacheSize) {
        com.hereliesaz.guillotine.ai.FrameAnalysisCache.setMaxEntries(settings.frameAnalysisCacheSize)
    }

    var showOnboarding by remember { mutableStateOf(!keyStore.onboardingDone) }
    var showSettings by remember { mutableStateOf(false) }
    // Cloudflare relay config; loaded off the main thread (EncryptedSharedPreferences touches the
    // KeyStore + disk) and re-read whenever Settings closes so changes restart the bridge.
    var relayConfig by remember { mutableStateOf(com.hereliesaz.guillotine.mcp.RelayConfig()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        relayConfig = withContext(Dispatchers.IO) { com.hereliesaz.guillotine.mcp.McpRelayConfig.read(context) }
    }
    var showAiComparison by remember { mutableStateOf(false) }
    var showProjectSettings by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var showNewProjectConfirm by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    // Settings & state for non-editor overlays (Help, Faq, Upgrades, Store).
    var showHelp by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var showFaq by remember { mutableStateOf(false) }
    var showAdFree by remember { mutableStateOf(false) }
    var showAzphaltStore by remember { mutableStateOf(false) }
    
    // UMP consent form state.
    var canRequestAds by remember { mutableStateOf(false) }  
    val billingManager = remember { com.hereliesaz.guillotine.billing.BillingManager(context, scope).apply { initialize() } }
    
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportDone by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportedUri by remember { mutableStateOf<Uri?>(null) }
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
    // The project is auto-saved internally; "Rename" only names it. Load imports a .gilt copy.
    val openLauncher = rememberOpenProjectLauncher { uri ->
        scope.launch {
            val doc = withContext(Dispatchers.IO) { runCatching { ProjectStore.load(context, uri) }.getOrNull() }
            if (doc != null) {
                vm.loadDocument(doc)
                agentBackend?.reset() // new project → fresh conversation (don't carry prior edits over)
            }
        }
    }
    
    val saveLauncher = rememberSaveProjectLauncher { uri ->
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ProjectStore.save(context, uri, vm.uiState.value.document) } }
        }
    }

    // Auto-save / restore: load the autosaved project on launch (fresh editor only), then
    // continuously persist the document to internal storage on every change (debounced via
    // collectLatest — a new edit cancels the pending write). The user never has to save.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (vm.uiState.value.document.clips.isEmpty()) {
            val doc = withContext(Dispatchers.IO) { ProjectAutosave.load(context) }
            if (doc != null) vm.loadDocument(doc)
        }
        vm.uiState
            .map { it.document }
            .distinctUntilChanged()
            .collectLatest { doc ->
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.IO) { runCatching { ProjectAutosave.save(context, doc) } }
            }
    }

    // Flush the autosave immediately on pause (app backgrounded), so the debounce window
    // above can never drop the last edit before the process is stopped.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                runCatching { ProjectAutosave.save(context, vm.uiState.value.document) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onAnalyze: () -> Unit = onAnalyze@{
        val targets = vm.uiState.value.selectedClips.filter { it.prompt.isNotBlank() }
        if (targets.isEmpty()) {
            // A clip is selected but no prompt was typed — guide the user instead of no-op.
            vm.setProcessing(false, "Type what to keep or cut first — e.g. \"keep shots with a face\".")
            return@onAnalyze
        }
        vm.setProcessing(true, null)
        vm.setAnalyzing(targets.map { it.id }, true)
        vm.setAnalysisProgress(com.hereliesaz.guillotine.ai.AnalysisProgress("Starting\u2026"))
        val ids = targets.map { it.id }
        // Run in the background via the foreground service so it survives backgrounding and can be
        // paused/cancelled from the notification; results + status still flow into the editor state.
        val started = OperationController.start(
            context, OperationKind.ANALYZE, "Analyzing\u2026", pausable = true,
            onError = { e ->
                vm.setAnalyzing(ids, false)
                vm.setProcessing(false, e.message ?: "Analysis failed")
                vm.setAnalysisProgress(null)
            },
            onComplete = {
                vm.setProcessing(false, null)
                vm.setAnalysisProgress(null)
                ActivityLog.success("Analysis complete.")
            },
        ) { sink ->
            for (clip in targets) {
                val media = vm.uiState.value.document.mediaFor(clip) ?: continue
                val edits = Analysis.run(
                    context, settings, Uri.parse(media.uri), media.kind, clip.prompt, clip.durationMs,
                    onProgress = { progress ->
                        vm.setAnalysisProgress(progress)
                        sink.report(progress.fraction, progress.stage)
                    },
                    checkpoint = sink::checkpointBlocking,
                )
                // Report the exact actions to the feed so the user sees what was cut and why.
                val name = media.name
                val removed = edits.filter { it.action == com.hereliesaz.guillotine.model.EditAction.REMOVE }
                if (removed.isEmpty()) {
                    ActivityLog.info("No matching frames in \"$name\" — nothing to cut.")
                } else {
                    val totalMs = removed.sumOf { it.endMs - it.startMs }
                    ActivityLog.info(
                        "Cutting ${removed.size} region(s) (${fmtSecs(totalMs)}) from \"$name\":",
                    )
                    removed.take(12).forEach {
                        ActivityLog.info("  · ${fmtSecs(it.startMs)}–${fmtSecs(it.endMs)} — ${it.reason}")
                    }
                    if (removed.size > 12) ActivityLog.info("  · …and ${removed.size - 12} more")
                }
                // Real cut, atomically: split into the kept pieces and delete the matched ranges (ripple
                // closed), never persisting REMOVE marks on the timeline. Matches the agent's analyze_clip.
                if (removed.isNotEmpty()) {
                    vm.applyCuts(clip.id, edits)
                }
            }
        }
        if (!started) {
            vm.setAnalyzing(ids, false)
            vm.setProcessing(false, "Another operation is already running.")
            vm.setAnalysisProgress(null)
        }
    }

    val onTranscribe: (CaptionStyle) -> Unit = onTranscribe@{ style ->
        val clip = vm.uiState.value.selectedClips.singleOrNull() ?: return@onTranscribe
        val media = vm.uiState.value.document.mediaFor(clip) ?: return@onTranscribe
        vm.setProcessing(true, null)
        scope.launch {
            try {
                val cues = Transcription.transcribe(context, settings, Uri.parse(media.uri))
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

    // Keyboard shortcuts (Chromebook/desktop).
    val focusRequester = remember { FocusRequester() }
    LaunchedEffectFocus(focusRequester)

    val providerLabel = settings.provider.meta.label

    // What the bottom sheet shows as the "active process" line: export takes precedence over
    // analysis (they can't both run — the OperationController is single-slot — but export is the
    // one started from NleScreen's own local state). Null when nothing is running.
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

    // The menu is a standalone, inline AzDropdownMenu (AzNavRail 10.19) in the TopBar — its trigger
    // icon sits right next to the project name. There is no AzNavRail host wrapper here, so nothing
    // reserves horizontal space on the left edge.
    //
    // Insets: exactly the pre-AzNavRail scheme — background drawn full-bleed, then systemBarsPadding
    // keeps content clear of the status/navigation bars. No extra insets beyond what was here before.
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding(),
    ) {
        // Editor area (weighted) + the activity-log sheet share this Box; the banner ad sits
        // below it so the sheet's HIDDEN strip never overlaps the ad.
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                // onKeyEvent (bubble phase), NOT preview: a focused text field gets first crack
                // at the keys, so typing in the prompt doesn't trigger editor shortcuts.
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
            onSaveProject = { saveLauncher() },
            onExport = { exportDone = null; exportError = null; showExport = true },
            onProjectSettings = { showProjectSettings = true },
            onSettings = { showSettings = true },
            onAiComparison = { showAiComparison = true },
            onHelp = { showHelp = true },
            onTutorial = { showTutorial = true },
            onFaq = { showFaq = true },
            onAdFree = { showAdFree = true },
        )

        // Analysis/export status & errors now stream into the activity-log bottom sheet below,
        // so there's no separate status strip here.
        var timelineWeight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.4f) }

        // Split between the preview and the clip-properties panel (AdvancedToolView), resizable by a
        // divider in BOTH arrangements: a vertical grip when they sit side-by-side (wide) and a
        // horizontal grip when stacked (tall). Each orientation remembers its own fraction.
        var previewWeightWide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.65f) }
        var previewWeightTall by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.5f) }
        androidx.compose.foundation.layout.BoxWithConstraints(
            androidx.compose.ui.Modifier
                .weight(1f - timelineWeight)
                .fillMaxWidth()
        ) {
            val isWide = maxWidth > maxHeight * 1.1f
            val totalWidthPx = constraints.maxWidth.toFloat()
            val totalHeightPx = constraints.maxHeight.toFloat()
            if (isWide) {
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier
                            .weight(previewWeightWide)
                            .fillMaxHeight()
                    ) {
                        PreviewPlayer(
                            state,
                            androidx.compose.ui.Modifier.weight(1f).fillMaxWidth(),
                            cropMode = state.tool == EditorTool.CROP,
                            showSafeZones = state.tool == EditorTool.CROP,
                            onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
                        )
                        TransportControls(vm, state)
                    }
                    DraggableVerticalDivider(
                        onDrag = { dragAmount ->
                            if (totalWidthPx > 0f) {
                                previewWeightWide = (previewWeightWide + dragAmount / totalWidthPx).coerceIn(0.25f, 0.85f)
                            }
                        }
                    )
                    AdvancedToolView(
                        vm = vm,
                        state = state,
                        onTranscribe = onTranscribe,
                        modifier = androidx.compose.ui.Modifier
                            .weight(1f - previewWeightWide)
                            .fillMaxHeight()
                    )
                }
            } else {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(previewWeightTall).fillMaxWidth()) {
                        PreviewPlayer(
                            state,
                            androidx.compose.ui.Modifier.weight(1f).fillMaxWidth(),
                            cropMode = state.tool == EditorTool.CROP,
                            showSafeZones = state.tool == EditorTool.CROP,
                            onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
                        )
                        TransportControls(vm, state)
                    }
                    DraggableTimelineDivider(
                        onDrag = { dragAmount ->
                            if (totalHeightPx > 0f) {
                                previewWeightTall = (previewWeightTall + dragAmount / totalHeightPx).coerceIn(0.25f, 0.85f)
                            }
                        }
                    )
                    AdvancedToolView(
                        vm = vm,
                        state = state,
                        onTranscribe = onTranscribe,
                        modifier = androidx.compose.ui.Modifier
                            .weight(1f - previewWeightTall)
                            .fillMaxWidth()
                    )
                }
            }
        }
        
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
        DraggableTimelineDivider(
            onDrag = { dragAmount ->
                timelineWeight = (timelineWeight - dragAmount * 0.0015f).coerceIn(0.2f, 0.8f)
            }
        )
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
        
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier
                .weight(timelineWeight)
                .fillMaxWidth()
        ) {
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, assistant = assistantState, onAgentInput = assistantVm::setInput, onAgentRun = { t -> assistantVm.run(t, sharedMcpTools, agentBackend) }, onImport = { importTargetTrack = null; importLauncher() }, onHelp = { showHelp = true }, onOpenStore = { showAzphaltStore = true }, asrModelPath = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "asrModelPath"))
            
            TimelinePanel(
                vm, state, onImportToTrack, onCreateOnTrack, 
                androidx.compose.ui.Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .animateContentSize()
            )
        }

        } // editor Column

        // Integrated activity log (AI chat, running process, progress, errors) — AzNavRail's
        // four-detent bottom sheet, anchored to the bottom of the editor Box (above the ad).
        ActivityLogSheet(
            controller = sheetController,
            entries = logEntries,
            processLabel = processLabel,
            processFraction = processFraction,
            onClear = { ActivityLog.clear() },
            awaitingReply = assistantState.awaitingReply,
            // Route the clarification reply back into the agent as a follow-up run: the ViewModel
            // synthesizes an "original request + question + reply" continuation prompt so any
            // backend picks up where it left off without needing message-log state.
            onReply = { t ->
                assistantVm.sendReply(t, sharedMcpTools, agentBackend)
            },
        )
        } // editor + sheet Box

        // Bottom banner ad (renders only after ad consent is resolved).
        BannerAd(Modifier.fillMaxWidth())
    }


    if (showSettings) {
        SettingsScreen(
            current = settings,
            onSave = { newSettings ->
                scope.launch {
                    keyStore.save(newSettings)
                    relayConfig = withContext(Dispatchers.IO) { com.hereliesaz.guillotine.mcp.McpRelayConfig.read(context) }
                }
                showSettings = false
            },
            onDismiss = {
                scope.launch {
                    relayConfig = withContext(Dispatchers.IO) { com.hereliesaz.guillotine.mcp.McpRelayConfig.read(context) }
                }
                showSettings = false
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
                        vm.loadDocument(com.hereliesaz.guillotine.model.Document())
                        agentBackend?.reset() // fresh project → fresh conversation
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
                val uri = ImageGen.Leonardo.generate(context, settings.leonardoKey, modelId, prompt)
                vm.addMedia(listOf(MediaItem(newId(), uri.toString(), "Leonardo: ${prompt.take(20)}", MediaKind.IMAGE, 5_000)))
            },
            onDismiss = { showGenerate = false },
        )
    }
    if (showExport) {
        ExportSheet(
            totalDurationMs = state.document.totalDurationMs,
            isExporting = exporting,
            progress = exportProgress,
            exportPhase = state.exportPhase,
            doneMessage = exportDone,
            onShare = exportedUri?.let { uri -> { shareVideo(context, uri) } },
            errorMessage = exportError,
            onStart = { name ->
                // Show the "render" interstitial as the export begins; rendering continues underneath.
                (context as? android.app.Activity)?.let { act ->
                    (context.applicationContext as? GuillotineApplication)?.interstitialAdManager?.show(act)
                }
                exporting = true; exportError = null; exportProgress = 0f
                ActivityLog.info("Exporting \"$name\"…")
                // Export in the background via the foreground service (cancel-only — Media3 can't pause
                // an encode). Progress feeds the in-app sheet and the notification.
                val startedExport = OperationController.start(
                    context, OperationKind.EXPORT, "Exporting…", pausable = false,
                    onError = { e ->
                        val detail = Exporter.describeExportError(e)
                        exportError = detail; exporting = false
                        vm.setExportPhase(null)
                        ActivityLog.error(detail)
                    },
                    onComplete = {
                        exportDone = "Saved to Movies/Guillotine."; exporting = false
                        vm.setExportPhase(null)
                        ActivityLog.success("Exported to Movies/Guillotine.")
                    },
                ) { sink ->
                    val uri = Exporter.export(
                        context,
                        vm.uiState.value.document,
                        name,
                        onProgress = { p, ms ->
                            scope.launch { exportProgress = p } // hop to the main thread for Compose state
                            vm.seekTo(ms)
                            sink.report(p, "Exporting…")
                        },
                        onPhase = { phase ->
                            scope.launch { vm.setExportPhase(phase) }
                        },
                    )
                    scope.launch { exportedUri = uri } // for the Share action once done
                }
                if (!startedExport) { exportError = "Another operation is already running."; exporting = false }
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
    if (showAdFree) AdFreeDialog(billingManager = billingManager, onDismiss = { showAdFree = false })
    if (showAzphaltStore) {
        AzphaltStoreScreen(
            onApplyPlugin = { pluginId ->
                val clipId = vm.uiState.value.selectedClipIds.firstOrNull()
                if (clipId != null) {
                    vm.updateClip(clipId) { it.copy(azpPluginId = pluginId) }
                }
                showAzphaltStore = false
            },
            onDismiss = { showAzphaltStore = false }
        )
    }
    if (showOnboarding) {
        OnboardingDialog(
            onComplete = { selectedModelPath ->
                scope.launch {
                    keyStore.save(settings)
                    keyStore.markOnboardingDone()
                }
                showOnboarding = false
            },
        )
    }

    // Embedded MCP server: external AI tools interact with the editor over HTTP on port 6274.
    val mcpServer = remember { com.hereliesaz.guillotine.mcp.McpServer() }
    DisposableEffect(Unit) {
        // /mcp requires this bearer token; the supplier reads the (cached) live token so a
        // regenerate from Settings takes effect without restarting the server.
        runCatching { mcpServer.startServer(sharedMcpTools) { com.hereliesaz.guillotine.mcp.McpAuth.token(context) } }
        onDispose { runCatching { mcpServer.stop() } }
    }

    // Optional outbound, end-to-end-encrypted Cloudflare relay. Re-read on Settings close so
    // toggling it on/off (or editing the Worker URL) restarts the bridge.
    DisposableEffect(relayConfig) {
        val client = if (relayConfig.isUsable) {
            com.hereliesaz.guillotine.mcp.McpRelayClient(
                sharedMcpTools,
                { com.hereliesaz.guillotine.mcp.McpAuth.token(context) },
                relayConfig,
            ).also { runCatching { it.start() } }
        } else {
            null
        }
        onDispose { client?.stop() }
    }
}

/** Slim top bar: AzDropdownMenu trigger icon + project name + zoom + undo/redo. */
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
    onSaveProject: () -> Unit,
    onExport: () -> Unit,
    onProjectSettings: () -> Unit,
    onSettings: () -> Unit,
    onAiComparison: () -> Unit,
    onHelp: () -> Unit,
    onTutorial: () -> Unit,
    onFaq: () -> Unit,
    onAdFree: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Neutral950).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // AzNavRail 10.19 DSL: the standalone AzDropdownMenu's trigger is the app icon, styled
        // via azConfig (no icon/tint/alignment params anymore). design = MENU gives full-width
        // rows; items auto-close (closeOnClick defaults true - no dismiss() in 10.7).
        // AzDivider() uses LocalContentColor.current for its line — override to the app accent
        // so the group separator matches item text instead of appearing as a white bar. Items
        // resolve their own color via `takeOrElse { MaterialTheme.colorScheme.primary }` and
        // aren't affected by this override.
        CompositionLocalProvider(LocalContentColor provides Red500) {
            AzDropdownMenu {
                // showFooter=true: the AzNavRail footer adds About / Feedback / @HereLiesAz. "About"
                // opens the in-app markdown reader, which auto-discovers the repo's root + docs/ .md
                // files (a .azignore at the repo root excludes dev-only docs from that list).
                azConfig(design = AzDropdownDesign.MENU, headerIconSize = 40.dp, showFooter = true)
                azItem("New") { onNewProject() }
                azItem("Open") { onOpenProject() }
                azItem("Save") { onSaveProject() }
                azItem("Rename") { onNameProject() }
                azItem("Import") { onImport() }
                azItem("Generate") { onGenerate() }
                azItem("Render") { onExport() }
                azDivider()
                azItem("Project") { onProjectSettings() }
                azItem("Settings") { onSettings() }
                azItem("Compare AI") { onAiComparison() }
                azItem("Tutorial") { onTutorial() }
                azItem("FAQ") { onFaq() }
                azItem("Icon Key") { onHelp() }
                if (!com.hereliesaz.guillotine.ads.AdsState.isAdFreePermanently.value) {
                    azItem("Ad-Free") { onAdFree() }
                }
                // AzNavRail draws its own primary-tinted divider above the footer (About / Feedback /
                // @HereLiesAz) automatically when showFooter = true — an explicit azDivider() here
                // would double up as two white/red lines. Let the library handle it.
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
        // straight through; on narrow screens (phone in portrait) the row is constrained by the
        // parent's remaining width and scrolls internally instead of being clipped or overflowing.
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

/**
 * "Save": name the (always-autosaved) project. The project is continuously autosaved regardless —
 * this dialog only sets its user-facing name.
 */
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
        // Preview quality (left, next to the time readout): lower = smoother playback, less clarity.
        // Labelled by pixel height (240p/480p/720p/1080p/Full); tap to cycle.
        Text(
            state.previewQuality.label,
            color = Neutral400, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { vm.cyclePreviewQuality() }.padding(8.dp),
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
        // Loop toggle (right, before the speed control): restart at the region/timeline start instead
        // of stopping at its end.
        IconToolButton(Icons.Filled.Repeat, "Loop playback", active = state.loopPlayback) { vm.toggleLoop() }
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

/** Thin vertical rule that visually separates groups of toolbar buttons (modes | actions | help). */
@Composable
private fun ToolGroupSeparator() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(Neutral800))
}

/**
 * Shared editor tool strip used by both layouts: a horizontally-scrollable row of
 * tools (mirroring the web build — select, split, keyframe, add-track, delete, zoom)
 * plus context-sensitive per-clip tools (filters, audio, background, text, keyframes,
 * transcribe, split — these replaced the old Inspector), and an AI prompt box. The
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
    onOpenStore: () -> Unit,
    /** Offline ASR model dir for voice-command dictation; blank hides the mic button. */
    asrModelPath: String = "",
) {
    val selected = state.selectedClips
    // Dismiss the soft keyboard AND drop focus on submit — without this the IME stays up after
    // send/enter, and the disabled state below (when the agent starts running) can leave the IME
    // orphaned so the back button won't dismiss it (reads as a "freeze").
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The single AI prompt field does both jobs:
    //  • a clip/group is selected  -> the text is an analysis prompt for it; run on-device analysis
    //    (free, no LLM brain needed) — exactly as before.
    //  • nothing is selected        -> the text is a general instruction; hand it to the agent, which
    //    drives the whole editor through the MCP tools (this is what the old assistant bar did).
    // Used by both the Enter key and the AI button.
    val submit: () -> Unit = submit@{
        // Always dismiss the IME on submit (even for a no-op blank send), so the field never
        // sits behind a stuck keyboard.
        keyboard?.hide()
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
    // Whether the prompt field has focus — drives the recent-prompts history dropdown.
    var promptFocused by remember { mutableStateOf(false) }

    // ---- Voice-command dictation (offline ASR) --------------------------------------------------
    // Tap the mic → record → tap again → transcribe on-device → drop the text into the prompt field
    // (the user reviews, then hits send). Only offered when an offline ASR model is configured.
    val voiceCtx = LocalContext.current
    val voiceScope = rememberCoroutineScope()
    var capture by remember { mutableStateOf<com.hereliesaz.guillotine.ai.VoiceCapture?>(null) }
    var listening by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    val beginListening: () -> Unit = {
        // Assign capture from start()'s result directly: a prior `VoiceCapture().also { …capture = null }`
        // form was buggy — .also returns the receiver, so the outer assignment overwrote the null and
        // capture was never null on failure (listening stuck true, no error shown).
        val cap = com.hereliesaz.guillotine.ai.VoiceCapture()
        if (cap.start()) {
            capture = cap
            listening = true
        } else {
            capture = null
            listening = false
            android.widget.Toast.makeText(voiceCtx, "Couldn't start the mic — it may be in use.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginListening()
        else android.widget.Toast.makeText(voiceCtx, "Mic permission is needed for voice commands.", android.widget.Toast.LENGTH_SHORT).show()
    }
    val toggleVoice: () -> Unit = {
        if (listening) {
            val cap = capture; capture = null; listening = false
            if (cap != null) {
                transcribing = true
                voiceScope.launch(Dispatchers.Default) {
                    val pcm = cap.stop()
                    // Distinguish the failure modes so the mic never fails silently: engine couldn't
                    // run (e.g. the ASR native lib/runtime is incompatible on this device) vs. no audio
                    // captured vs. audio heard but no words recognized.
                    val result = if (asrModelPath.isNotBlank() && pcm.isNotEmpty()) {
                        // Catch Exception + LinkageError (native ASR lib/ABI faults), rethrow
                        // CancellationException; don't swallow fatal VM errors (OOM) — transcription is
                        // memory-heavy, and a swallowed OutOfMemoryError leaves the process unstable.
                        try {
                            Result.success(com.hereliesaz.guillotine.ai.SherpaAsr.transcribe(asrModelPath, pcm))
                        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            Result.failure(e)
                        } catch (l: LinkageError) {
                            Result.failure(l)
                        }
                    } else null
                    val text = result?.getOrNull()
                    withContext(Dispatchers.Main) {
                        transcribing = false
                        if (!text.isNullOrBlank()) {
                            val existing = assistant.input
                            onAgentInput(if (existing.isBlank()) text.trim() else "${existing.trim()} ${text.trim()}")
                        } else {
                            val msg = when {
                                pcm.isEmpty() -> "No audio captured — check the mic permission."
                                result?.isFailure == true -> "The speech engine couldn't run on this device."
                                else -> "Didn't catch any speech — try again."
                            }
                            android.widget.Toast.makeText(voiceCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            val granted = ContextCompat.checkSelfPermission(voiceCtx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) beginListening() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Column(Modifier.fillMaxWidth().background(Neutral900)) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Azphalt Store button
            androidx.compose.material3.IconButton(
                onClick = { onOpenStore() },
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Icon(Icons.Default.Storefront, contentDescription = "Azphalt Store", tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
            }

            // ---- Modes (toggle a tool on/off; active one is highlighted) ----
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
            IconToolButton(Icons.Filled.ShowChart, "Auto-ease keyframes", active = state.autoEase) {
                vm.toggleAutoEase()
            }

            ToolGroupSeparator()

            // ---- Actions (do something immediately; no mode) ----
            // Scissors splits at the playhead immediately (Vegas-style) — the selected clip/group, or
            // every clip on every track when nothing is selected.
            IconToolButton(Icons.Filled.ContentCut, "Split at playhead") {
                vm.splitAtPlayhead()
            }
            // Record the selected clip's crop/placement (+opacity) at the playhead.
            IconToolButton(Icons.Filled.Diamond, "Keyframe crop/placement at playhead") {
                vm.addKeyframeAtPlayhead()
            }
            // Import media (new tracks come from the track-head popup or dragging a clip past the edge).
            IconToolButton(Icons.Filled.Add, "Import media", onClick = onImport)
            IconToolButton(Icons.Filled.Delete, "Delete", enabled = state.selectedClipIds.isNotEmpty()) {
                vm.deleteSelected()
            }
            // Ripple: close the gaps among the selected clips (or all clips if none selected).
            IconToolButton(Icons.Filled.Compress, "Ripple (close gaps)") {
                vm.rippleCloseGaps()
            }
            // Zoom is pinch-only (horizontal = width, vertical = track height); no toolbar buttons.
            // Group / ungroup — only meaningful with a multi-clip selection.
            if (selected.size > 1) {
                val grouped = selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null }
                IconToolButton(
                    if (grouped) Icons.Filled.LinkOff else Icons.Filled.Link,
                    if (grouped) "Ungroup" else "Group",
                    active = grouped,
                ) { if (grouped) vm.ungroupSelected() else vm.groupSelected() }
            }

            ToolGroupSeparator()

            // Help: opens the icon key (what every button does).
            IconToolButton(Icons.Filled.HelpOutline, "Help / icon key", onClick = onHelp)

            // Context-sensitive per-clip tools (filters, audio, background, text,
            // keyframes, transcribe, split) — formerly the Inspector panel. Shown for a
            // single clip, or a single group (e.g. a linked video+audio pair) so its parts
            // can be edited without ungrouping.
            val oneUnit = selected.size == 1 ||
                (selected.size > 1 && selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null })
            if (oneUnit) {
                ToolGroupSeparator()
                // Removed inline clip tools because they are now in AdvancedToolView
            }
        }
        // The agent's running status/output now streams into the activity-log bottom sheet; the
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
                    // Enter submits instead of inserting a newline. Soft keyboards send a '\n'
                    // through onValueChange; hardware Enter is caught by onPreviewKeyEvent below.
                    onValueChange = { v ->
                        val submitNow = v.contains('\n')
                        val text = v.replace("\n", "")
                        if (hasClip) vm.setPromptForSelected(text) else onAgentInput(text)
                        if (submitNow) submit()
                    },
                    // `readOnly` (not `enabled=false`) while the agent is running: keeps the field
                    // enabled and focusable so the IME stays owned by it — disabling a focused
                    // TextField orphans the keyboard and back-press can't dismiss it (feels like a
                    // freeze) — while still blocking mid-run keystrokes and accidental re-submits.
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
                            "Tell the AI what to do — e.g. \"cut the silences in clip 1\""
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
                    properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                ) {
                    state.promptHistory.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p, color = White, fontSize = 12.sp, maxLines = 1) },
                            onClick = { if (hasClip) vm.setPromptForSelected(p) else onAgentInput(p); promptFocused = false },
                        )
                    }
                }
            }
            // Voice-command mic: dictate an instruction on-device. Only when ASR is configured and no
            // clip is selected (the field is an AI instruction, not a per-clip analysis prompt).
            if (!hasClip && asrModelPath.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                if (transcribing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Red500)
                } else {
                    IconToolButton(
                        if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        if (listening) "Stop dictation" else "Dictate a command",
                        active = listening,
                        enabled = !assistant.running,
                        onClick = toggleVoice,
                    )
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
        !ctrl && e.key == Key.S -> { vm.splitAtPlayhead(); true }
        else -> false
    }
}

/** Compact seconds / M:SS timestamp for activity-feed action lines. */
private fun fmtSecs(ms: Long): String {
    val abs = ms.coerceAtLeast(0L)
    return if (abs < 60_000L) String.format(java.util.Locale.US, "%.1fs", abs / 1000.0)
    else String.format(java.util.Locale.US, "%d:%02d", abs / 60_000L, (abs % 60_000L) / 1000L)
}


@Composable
fun AdFreeDialog(billingManager: com.hereliesaz.guillotine.billing.BillingManager, onDismiss: () -> Unit) {
    val productDetails by billingManager.adFreeProductDetails.collectAsState(initial = null)
    val context = LocalContext.current as? android.app.Activity
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Ad-Free Experience") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text("Enjoy the app without ads! Unlock the ad-free experience permanently with a one-time purchase.")
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                context?.let { billingManager.purchaseAdFree(it) }
                onDismiss()
            }) {
                androidx.compose.material3.Text(productDetails?.oneTimePurchaseOfferDetails?.formattedPrice ?: "Buy Ad-Free")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Not now")
            }
        }
    )
}
