package com.hereliesaz.guillotine.ui

import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.Analysis
import com.hereliesaz.guillotine.ai.ApiKeyStore
import com.hereliesaz.guillotine.ai.ImageGen
import com.hereliesaz.aznavrail.AzDropdownMenu
import com.hereliesaz.aznavrail.model.AzDropdownDesign
import com.hereliesaz.guillotine.GuillotineApplication
import com.hereliesaz.guillotine.ads.BannerAd
import com.hereliesaz.guillotine.ai.Transcription
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.data.ProjectAutosave
import com.hereliesaz.guillotine.data.ProjectStore
import com.hereliesaz.guillotine.data.rememberOpenProjectLauncher
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

@Composable
fun NleScreen(widthClass: WindowWidthSizeClass, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val keyStore = remember { ApiKeyStore(context) }
    val settings by keyStore.settings.collectAsState(initial = AiSettings())
    val scope = rememberCoroutineScope()

    // One shared MCP tool surface: the embedded server, the optional relay, and the in-app AI
    // assistant all drive the editor through this same object ({ settings } reads live).
    val sharedMcpTools = remember { com.hereliesaz.guillotine.mcp.McpTools(context, vm) { settings } }
    val assistantVm: AssistantViewModel = viewModel()
    val assistantState by assistantVm.state.collectAsState()

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
    // The project is auto-saved internally; "Save" only names it. Load imports a .gilt copy.
    val openLauncher = rememberOpenProjectLauncher { uri ->
        scope.launch {
            val doc = withContext(Dispatchers.IO) { runCatching { ProjectStore.load(context, uri) }.getOrNull() }
            if (doc != null) vm.loadDocument(doc)
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
                vm.applyEdits(clip.id, edits)
            }
        }
        if (!started) {
            vm.setAnalyzing(ids, false)
            vm.setProcessing(false, "Another operation is already running.")
            vm.setAnalysisProgress(null)
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

    val providerLabel = settings.provider.meta.label

    // The menu is a standalone, inline AzDropdownMenu (AzNavRail 10.7) in the TopBar — its trigger
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
            .systemBarsPadding()
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
            onImport = { importTargetTrack = null; importLauncher() },
            onGenerate = { showGenerate = true },
            onNameProject = { showNameDialog = true },
            onOpenProject = { openLauncher() },
            onExport = { exportDone = null; exportError = null; showExport = true },
            onProjectSettings = { showProjectSettings = true },
            onSettings = { showSettings = true },
            onAiComparison = { showAiComparison = true },
        )

        // Processing/error feedback for AI analysis (formerly shown in the Inspector).
        AnalysisStatusBar(state, providerLabel) { vm.clearError() }
        if (widthClass == WindowWidthSizeClass.Expanded) {
            Column(Modifier.weight(0.6f).fillMaxWidth()) {
                PreviewPlayer(
                    state,
                    Modifier.weight(1f).fillMaxWidth(),
                    cropMode = state.tool == EditorTool.CROP,
                    onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
                )
                TransportControls(vm, state)
            }
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, onGenerate = { showGenerate = true })
            TimelinePanel(vm, state, onImportToTrack, onCreateOnTrack, Modifier.weight(0.4f).fillMaxWidth())
        } else {
            PreviewPlayer(
                state,
                Modifier.weight(0.42f).fillMaxWidth(),
                cropMode = state.tool == EditorTool.CROP,
                onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
            )
            TransportControls(vm, state)
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, onGenerate = { showGenerate = true })
            TimelinePanel(vm, state, onImportToTrack, onCreateOnTrack, Modifier.weight(0.58f).fillMaxWidth())
        }

        // In-app AI assistant: type an instruction and the agent drives the editor via the MCP
        // tools (timeline updates live; edits are undoable).
        AssistantBar(
            state = assistantState,
            onInput = assistantVm::setInput,
            onSend = {
                val text = assistantState.input
                assistantVm.run(
                    text,
                    sharedMcpTools,
                    com.hereliesaz.guillotine.ai.agent.McpAgent.forSettings(context, settings, sharedMcpTools),
                )
            },
        )

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
            doneMessage = exportDone,
            errorMessage = exportError,
            onStart = { name ->
                // Show the "render" interstitial as the export begins; rendering continues underneath.
                (context as? android.app.Activity)?.let { act ->
                    (context.applicationContext as? GuillotineApplication)?.interstitialAdManager?.show(act)
                }
                exporting = true; exportError = null; exportProgress = 0f
                // Export in the background via the foreground service (cancel-only — Media3 can't pause
                // an encode). Progress feeds the in-app sheet and the notification.
                val startedExport = OperationController.start(
                    context, OperationKind.EXPORT, "Exporting…", pausable = false,
                    onError = { e -> exportError = e.message ?: "Export failed"; exporting = false },
                    onComplete = { exportDone = "Saved to Movies/Guillotine."; exporting = false },
                ) { sink ->
                    Exporter.export(context, vm.uiState.value.document, name) { p ->
                        scope.launch { exportProgress = p } // hop to the main thread for Compose state
                        sink.report(p, "Exporting…")
                    }
                }
                if (!startedExport) { exportError = "Another operation is already running."; exporting = false }
            },
            onDismiss = { if (!exporting) showExport = false },
        )
    }
    if (showAiComparison) {
        AiComparisonSheet(onDismiss = { showAiComparison = false })
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

/** Slim top bar: AzDropdownMenu trigger icon + project name + undo/redo. */
@Composable
private fun TopBar(
    state: EditorUiState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onImport: () -> Unit,
    onGenerate: () -> Unit,
    onNameProject: () -> Unit,
    onOpenProject: () -> Unit,
    onExport: () -> Unit,
    onProjectSettings: () -> Unit,
    onSettings: () -> Unit,
    onAiComparison: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Neutral950).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // AzNavRail 10.7 DSL: the standalone AzDropdownMenu's trigger is the app icon, styled
        // via azConfig (no icon/tint/alignment params anymore). design = MENU gives full-width
        // rows; items auto-close (closeOnClick defaults true - no dismiss() in 10.7).
        AzDropdownMenu {
            // showFooter=true: the AzNavRail footer adds About / Feedback / @HereLiesAz. "About"
            // opens the in-app markdown reader, which auto-discovers the repo's root + docs/ .md
            // files (a .azignore at the repo root excludes dev-only docs from that list).
            azConfig(design = AzDropdownDesign.MENU, headerIconSize = 40.dp, showFooter = true)
            azItem("Import media") { onImport() }
            azItem("Generate image") { onGenerate() }
            azItem("Name project") { onNameProject() }
            azItem("Open project file\u2026") { onOpenProject() }
            azItem("Export video") { onExport() }
            azDivider()
            azItem("Project settings") { onProjectSettings() }
            azItem("Settings") { onSettings() }
            azItem("Compare AI providers") { onAiComparison() }
        }
        Text(
            state.document.name.ifBlank { "Untitled project" },
            color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        IconToolButton(Icons.Filled.Undo, "Undo", enabled = state.canUndo, onClick = onUndo)
        IconToolButton(Icons.Filled.Redo, "Redo", enabled = state.canRedo, onClick = onRedo)
    }
}

/** Simple dialog to name the (always-autosaved) project. */
@Composable
private fun NameProjectDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name project", color = White) },
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
            Text(
                "Save", color = Red500, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onConfirm(name) }.padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                "Cancel", color = Neutral400, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
            )
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
        val frameMs = 33L // ~1 frame at 30fps
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

/**
 * Thin status strip for AI analysis: a spinner + "Analyzing with <provider>…" while a run
 * is in flight, or the error (dismissable) if one failed. This is the feedback surface that
 * used to live in the Inspector — without it, running the on-device analyzer from the prompt
 * looked like it did nothing.
 */
@Composable
private fun AnalysisStatusBar(state: EditorUiState, providerLabel: String, onDismiss: () -> Unit) {
    val error = state.error
    val progress = state.analysisProgress
    when {
        state.isProcessing -> Row(
            Modifier.fillMaxWidth().background(Neutral900).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progress?.fraction != null) {
                CircularProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Red500,
                )
            } else {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Red500)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    progress?.stage ?: "Analyzing with $providerLabel\u2026",
                    color = Neutral400, fontSize = 12.sp,
                )
                if (progress != null && progress.segmentsFound > 0) {
                    Text(
                        "${progress.segmentsFound} segments found",
                        color = Neutral500, fontSize = 10.sp,
                    )
                }
            }
            if (progress?.fraction != null) {
                Text(
                    "${(progress.fraction * 100).toInt()}%",
                    color = Neutral500, fontSize = 11.sp,
                )
            }
        }
        error != null -> Row(
            Modifier.fillMaxWidth().background(Red500.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(error, color = Red500, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Close, "Dismiss", tint = Red500,
                modifier = Modifier.size(16.dp).clickable(onClick = onDismiss),
            )
        }
    }
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
    onTranscribe: () -> Unit,
    providerLabel: String,
    onOpenSettings: () -> Unit,
    onGenerate: () -> Unit,
) {
    val selected = state.selectedClips
    // Submitting the prompt: analyze the selected clip(s), or open Generate when nothing
    // is selected. Used by both the Enter key and the AI button. If the field is empty we
    // fall back to the user's last prompt (also shown as the inline hint), so pressing
    // Enter/AI on an empty field re-runs the previous instruction.
    val submit: () -> Unit = submit@{
        if (selected.isEmpty()) { onGenerate(); return@submit }
        val current = selected.firstOrNull()?.prompt.orEmpty()
        val effective = current.ifBlank { state.lastPrompt }
        if (current.isBlank() && effective.isNotBlank()) vm.setPromptForSelected(effective)
        vm.rememberPrompt(effective)
        onAnalyze()
    }
    // Whether the prompt field has focus — drives the recent-prompts history dropdown.
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
            // Scissors is an action, not a mode: it splits at the playhead immediately (Vegas-style) —
            // the selected clip/group, or every clip on every track when nothing is selected.
            IconToolButton(Icons.Filled.ContentCut, "Split at playhead") {
                vm.splitAtPlayhead()
            }
            IconToolButton(Icons.Filled.Crop, "Crop / transform", active = state.tool == EditorTool.CROP) {
                vm.setTool(EditorTool.CROP)
            }
            // Action (not a mode): record the selected clip's crop/placement (+opacity) at the playhead.
            IconToolButton(Icons.Filled.Diamond, "Keyframe crop/placement at playhead") {
                vm.addKeyframeAtPlayhead()
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
            // Context-sensitive per-clip tools (filters, audio, background, text,
            // keyframes, transcribe, split) — formerly the Inspector panel. Shown for a
            // single clip, or a single group (e.g. a linked video+audio pair) so its parts
            // can be edited without ungrouping.
            val oneUnit = selected.size == 1 ||
                (selected.size > 1 && selected.mapTo(HashSet()) { it.groupId }.let { it.size == 1 && it.first() != null })
            if (oneUnit) {
                Box(Modifier.width(1.dp).height(20.dp).background(Neutral800))
                ClipToolButtons(vm, state, onTranscribe)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                val currentPrompt = selected.firstOrNull()?.prompt ?: ""
                OutlinedTextField(
                    value = currentPrompt,
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
                        // Inline hint = the user's last prompt (re-used on empty submit), or an
                        // example before they've entered anything.
                        val hint = state.lastPrompt.ifBlank {
                            "e.g. \"keep shots with a face\" or \"cut clips with a car\""
                        }
                        Text(
                            if (selected.isEmpty()) "Select a clip to prompt…" else hint,
                            color = Neutral500, fontSize = 12.sp,
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                // Recent-prompts history: appears when the empty field is focused. Tapping one
                // fills the field (tap-to-reuse). focusable=false keeps the keyboard up.
                DropdownMenu(
                    expanded = promptFocused && currentPrompt.isBlank() && state.promptHistory.isNotEmpty(),
                    onDismissRequest = { promptFocused = false },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                ) {
                    state.promptHistory.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p, color = White, fontSize = 12.sp, maxLines = 1) },
                            onClick = { vm.setPromptForSelected(p); promptFocused = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ToolbarButton(if (selected.isEmpty()) "AI ▸" else "AI", tint = Red500, onClick = submit)
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
