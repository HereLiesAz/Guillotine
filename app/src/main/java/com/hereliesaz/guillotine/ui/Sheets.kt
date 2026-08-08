package com.hereliesaz.guillotine.ui




import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.hereliesaz.guillotine.ui.theme.Black

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.FrameAnalysisCache
import com.hereliesaz.guillotine.ai.ImageGen
import kotlin.math.roundToInt
import com.hereliesaz.guillotine.ai.ModelCatalog
import com.hereliesaz.guillotine.ai.agent.BundledModelExtractor
import com.hereliesaz.guillotine.ai.agent.ModelDownloadManager
import com.hereliesaz.guillotine.ai.agent.OnDeviceModel
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_FACE_MODELS
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_ON_DEVICE_MODELS
import com.hereliesaz.guillotine.ai.ModelImport
import com.hereliesaz.guillotine.azphalt.AzphaltTrust
import com.hereliesaz.guillotine.azphalt.AzpModelInstall
import com.hereliesaz.guillotine.azphalt.AzpModelInstaller
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_RECOGNITION_MODELS
import com.hereliesaz.guillotine.ai.agent.ModelCategory
import com.hereliesaz.guillotine.ai.agent.recommendedModelsFor
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.ai.gen.GenKind
import com.hereliesaz.guillotine.ai.gen.GenProviderType
import com.hereliesaz.guillotine.ai.gen.genMeta
import com.hereliesaz.guillotine.ai.gen.providersFor
import kotlinx.coroutines.launch
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.GlobalSettings
import com.hereliesaz.guillotine.model.Quality
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral700
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Neutral900)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = { content() },
    )
}

@Composable
fun SettingsScreen(current: AiSettings, onSave: (AiSettings) -> Unit, onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf(current.provider) }
    var keys by remember { mutableStateOf(current.keys) }
    var models by remember { mutableStateOf(current.models) }
    var leonardoKey by remember { mutableStateOf(current.leonardoKey) }
    var leonardoModel by remember { mutableStateOf(current.leonardoModel) }

    var ffmpegPath by remember { mutableStateOf(current.ffmpegPath) }
    var cloudVision by remember { mutableStateOf(current.cloudVision) }
    var frameAnalysisCacheSize by remember { mutableIntStateOf(current.frameAnalysisCacheSize) }
    var genKeys by remember { mutableStateOf(current.genKeys) }
    var genModels by remember { mutableStateOf(current.genModels) }
    var genExtras by remember { mutableStateOf(current.genExtras) }

    // On-device model paths (§1 AI Analyzer model pickers below, §3 Transcription's Vosk field).
    // Each is set by typing a path directly, or a model picker's "✓ Use" — see docs/SETTINGS.md.
    var speechModelPath by remember { mutableStateOf(current.speechModelPath) }
    var agentModelPath by remember { mutableStateOf(current.agentModelPath) }
    var idEmbedModelPath by remember { mutableStateOf(current.idEmbedModelPath) }
    var faceEmbedModelPath by remember { mutableStateOf(current.faceEmbedModelPath) }
    var effectModelPaths by remember { mutableStateOf(current.effectModelPaths) }
    var audioEventModelPath by remember { mutableStateOf(current.audioEventModelPath) }
    var asrModelPath by remember { mutableStateOf(current.asrModelPath) }
    var ttsModelPath by remember { mutableStateOf(current.ttsModelPath) }
    var vlmModelPath by remember { mutableStateOf(current.vlmModelPath) }
    var diarizeSegModelPath by remember { mutableStateOf(current.diarizeSegModelPath) }
    var diarizeEmbedModelPath by remember { mutableStateOf(current.diarizeEmbedModelPath) }
    var stemModelPath by remember { mutableStateOf(current.stemModelPath) }
    var denoiseModelPath by remember { mutableStateOf(current.denoiseModelPath) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var crashRelayUrl by remember { mutableStateOf(com.hereliesaz.guillotine.crash.CrashConfig.relayUrl(context)) }

    // Encrypted Cloudflare relay
    var relayEnabled by remember { mutableStateOf(false) }
    var relayUrl by remember { mutableStateOf("") }
    var relayAccessKey by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val relay0 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.hereliesaz.guillotine.mcp.McpRelayConfig.read(context)
        }
        relayEnabled = relay0.enabled
        relayUrl = relay0.workerUrl
        relayAccessKey = relay0.accessKey
    }

    // MCP access token
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var mcpToken by remember { mutableStateOf(com.hereliesaz.guillotine.mcp.McpAuth.token(context)) }

    val scope = rememberCoroutineScope()

    // --- Native file/folder pickers for model paths ---------------------------------------------
    // The picker returns a SAF content: URI, but the on-device runtimes (Vosk/sherpa/ONNX) load from a
    // real path — so the selection is copied into app storage and the resulting path is stored.
    var modelPickPending by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var modelImporting by remember { mutableStateOf(false) }
    fun runImport(uri: android.net.Uri?, isDirectory: Boolean) {
        val cb = modelPickPending
        modelPickPending = null
        if (uri == null || cb == null) return
        scope.launch {
            modelImporting = true
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (isDirectory) ModelImport.importTree(context, uri) else ModelImport.importFile(context, uri)
            }
            modelImporting = false
            if (path != null) cb(path)
        }
    }
    val modelFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> runImport(uri, isDirectory = false) }
    val modelTreeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> runImport(uri, isDirectory = true) }
    fun browseModel(isDirectory: Boolean, onResult: (String) -> Unit) {
        modelPickPending = onResult
        if (isDirectory) modelTreeLauncher.launch(null) else modelFileLauncher.launch(arrayOf("*/*"))
    }

    // Assemble the settings from the current editable state — shared by the Save button and the
    // .azp installer (which folds newly-routed model paths into the visible fields first).
    fun buildSettings(): AiSettings = AiSettings(
        provider = provider,
        keys = keys,
        models = models,
        leonardoKey = leonardoKey.trim(),
        leonardoModel = leonardoModel,

        ffmpegPath = ffmpegPath.trim(),
        cloudVision = cloudVision,
        frameAnalysisCacheSize = frameAnalysisCacheSize,
        genKeys = genKeys.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
        genModels = genModels,
        genExtras = genExtras.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
        genDefaults = current.genDefaults,

        speechModelPath = speechModelPath.trim(),
        agentModelPath = agentModelPath.trim(),
        idEmbedModelPath = idEmbedModelPath.trim(),
        faceEmbedModelPath = faceEmbedModelPath.trim(),
        effectModelPaths = effectModelPaths.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
        audioEventModelPath = audioEventModelPath.trim(),
        asrModelPath = asrModelPath.trim(),
        ttsModelPath = ttsModelPath.trim(),
        vlmModelPath = vlmModelPath.trim(),
        diarizeSegModelPath = diarizeSegModelPath.trim(),
        diarizeEmbedModelPath = diarizeEmbedModelPath.trim(),
        stemModelPath = stemModelPath.trim(),
        denoiseModelPath = denoiseModelPath.trim(),
    )

    // --- Install an AI model from an azphalt .azp package ---------------------------------------
    var azpBusy by remember { mutableStateOf(false) }
    var azpStatus by remember { mutableStateOf<String?>(null) }
    // When a package is valid but unsigned/untrusted, hold its bytes so the user can confirm.
    var azpUntrusted by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    // A package whose id was first installed from a different publisher key — confirm before overwriting.
    var azpPublisherChange by remember { mutableStateOf<AzpModelInstall.PublisherChangedException?>(null) }
    var azpChangeBytes by remember { mutableStateOf<ByteArray?>(null) }
    val publisherPins = remember {
        com.hereliesaz.guillotine.azphalt.AzpPublisherPins(
            java.io.File(context.filesDir, "azp-publishers.json"),
        )
    }

    // Fold each installed model's on-disk path into the matching visible field, then persist. Slots
    // the app renders as ML Kit built-ins (segmentation/face-detect/labeling) are stored on desktop;
    // here we surface the ones with an editable field.
    fun applyInstalled(result: AzpModelInstall.Result) {
        result.installed.forEach { inst ->
            Unit // Now handled entirely by ModelResolver
        }
        onSave(buildSettings())
    }

    fun installAzp(bytes: ByteArray, allowUntrusted: Boolean, allowPublisherChange: Boolean = false) {
        scope.launch {
            azpBusy = true
            azpStatus = "Reading package…"
            try {
                val dir = java.io.File(context.filesDir, "azp-models")
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AzpModelInstall.install(
                        bytes, setOf(AzphaltTrust.FLAGSHIP_SIGNING_KEY), dir, allowUntrusted,
                        pins = publisherPins, allowPublisherChange = allowPublisherChange,
                    ) { p ->
                        val pct = p.bytesTotal?.takeIf { it > 0 }?.let { (p.bytesDone * 100 / it) }
                        azpStatus = when (p.phase) {
                            AzpModelInstall.Phase.DOWNLOADING ->
                                "Downloading ${p.model.filename}${pct?.let { " — $it%" } ?: ""}…"
                            AzpModelInstall.Phase.VERIFYING -> "Verifying ${p.model.filename}…"
                            AzpModelInstall.Phase.WRITING -> "Writing ${p.model.filename}…"
                        }
                    }
                }
                applyInstalled(result)
                val routed = result.installed.count { it.slot != null }
                azpStatus = "Installed ${result.installed.size} model(s) from ${result.packageId}" +
                    (if (result.trust.trusted) " (trusted)" else " (unsigned)") +
                    if (routed < result.installed.size) " — ${result.installed.size - routed} need manual wiring." else "."
            } catch (e: AzpModelInstall.PublisherChangedException) {
                azpPublisherChange = e
                azpChangeBytes = bytes
                azpStatus = null
            } catch (e: AzpModelInstall.UntrustedException) {
                azpUntrusted = bytes to e.trust.reason
                azpStatus = null
            } catch (e: Exception) {
                azpStatus = "Install failed: ${e.message}"
            } finally {
                azpBusy = false
            }
        }
    }

    val azpLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            }.getOrNull()
            if (bytes == null) { azpStatus = "Could not read the selected file."; return@launch }
            installAzp(bytes, allowUntrusted = false)
        }
    }

    // Settings backup/restore via SAF
    val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            com.hereliesaz.guillotine.data.SettingsBackup.export(context, uri, buildSettings())
        }
    }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val restored = com.hereliesaz.guillotine.data.SettingsBackup.`import`(context, uri)
            provider = restored.provider
            keys = restored.keys
            models = restored.models
            leonardoKey = restored.leonardoKey
            leonardoModel = restored.leonardoModel

            ffmpegPath = restored.ffmpegPath
            cloudVision = restored.cloudVision
            frameAnalysisCacheSize = restored.frameAnalysisCacheSize
            genKeys = restored.genKeys
            genModels = restored.genModels
            genExtras = restored.genExtras

            speechModelPath = restored.speechModelPath
            agentModelPath = restored.agentModelPath
            idEmbedModelPath = restored.idEmbedModelPath
            faceEmbedModelPath = restored.faceEmbedModelPath
            effectModelPaths = restored.effectModelPaths
            audioEventModelPath = restored.audioEventModelPath
            asrModelPath = restored.asrModelPath
            ttsModelPath = restored.ttsModelPath
            vlmModelPath = restored.vlmModelPath
            diarizeSegModelPath = restored.diarizeSegModelPath
            diarizeEmbedModelPath = restored.diarizeEmbedModelPath
            stemModelPath = restored.stemModelPath
            denoiseModelPath = restored.denoiseModelPath
        }
    }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("AI Analyzer", "Generation", "Transcription", "Advanced")

    Column(
        Modifier
            .fillMaxSize()
            .background(Neutral900)
            .padding(16.dp)
            .systemBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Settings", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Close,
                contentDescription = "Close",
                tint = White,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onDismiss() }
            )
        }

        // Tabs
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Text(
                    text = title,
                    color = if (isSelected) Black else Neutral400,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) White else Color.Transparent)
                        .border(1.dp, if (isSelected) White else Neutral800, RoundedCornerShape(6.dp))
                        .clickable { selectedTab = index }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Tab Content
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {
                0 -> { // AI Analyzer
                    Text(
                        "Analysis always runs on-device — your video never leaves the device. Pick the AI " +
                            "that drives the editor below. It can reference the current preview frame: " +
                            "when you say \"this frame\" the AI asks the on-device vision to describe what's " +
                            "in it, so it knows what to act on. Only text (your prompt, the tool " +
                            "descriptions this AI needs to see, and the on-device vision's text results) is " +
                            "sent to the AI — your raw frames or audio never are.",
                        color = Neutral400, fontSize = 12.sp,
                    )

                    Column(
                        Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        AiProviderType.values().forEach { p ->
                            val meta = p.meta
                            ProviderRow(meta.label, meta.blurb, selected = provider == p) { provider = p }
                        }
                    }

                    if (provider.meta.keyUrl != null) {
                        val meta = provider.meta
                        KeyField("${meta.label} API key", keys[provider].orEmpty()) { keys = keys + (provider to it) }
                        Text("Model", color = Neutral500, fontSize = 10.sp)
                        LiveModelDropdown(
                            current = models[provider].orEmpty(),
                            defaultHint = "Default: ${meta.defaultModel}",
                            load = { ModelCatalog.analyzerModels(provider, keys[provider].orEmpty()) },
                            onSelect = { models = models + (provider to it) },
                            resetKey = keys[provider].orEmpty(),
                        )
                        Text("Pick from the provider's live list, or Default.", color = Neutral500, fontSize = 10.sp)
                        meta.keyUrl?.let { url ->
                            Text(
                                "Get a ${meta.label} API key  ↗",
                                color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickableText { uriHandler.openUri(url) }
                                    .padding(top = 2.dp),
                            )
                        }
                    }

                    // Frame-analysis cache: how many per-frame ML Kit results (per signal — object
                    // labels + scene labels) to keep in RAM so rescanning the same clip with a
                    // different prompt reuses last time's work instead of re-decoding + re-running
                    // vision. Applied at runtime via FrameAnalysisCache.setMaxEntries.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Frame-analysis cache", color = Neutral400, fontSize = 12.sp)
                        Text(
                            when (frameAnalysisCacheSize) {
                                0 -> "Off"
                                else -> "$frameAnalysisCacheSize frames"
                            },
                            color = Neutral500, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Slider(
                        value = frameAnalysisCacheSize.toFloat(),
                        // roundToInt (not toInt) — toInt truncates, so a stop that lands at 1024.0f
                        // but is stored as 1023.9999f would jitter down to 1023.
                        onValueChange = { frameAnalysisCacheSize = it.roundToInt() },
                        valueRange = FrameAnalysisCache.MIN_MAX_ENTRIES.toFloat()..FrameAnalysisCache.MAX_MAX_ENTRIES.toFloat(),
                        // steps = intermediate stops (Compose's contract), so 31 here → 33 total
                        // positions (min + 31 intermediates + max) at exactly 1024-frame intervals.
                        steps = 31,
                    )
                    Text(
                        "How many per-frame vision results to keep so rescans of the same clip are near-" +
                            "instant. Default ${FrameAnalysisCache.DEFAULT_MAX_ENTRIES}. Higher = more scans " +
                            "stay fast but a bit more memory. 0 disables the cache.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    // Cloud vision (opt-in). Off by default — on-device vision is always local; this is the
                    // ONLY path that sends a frame off-device, and only to the user's own cloud provider.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Let cloud AI see frames (opt-in)", color = Neutral400, fontSize = 12.sp)
                        androidx.compose.material3.Switch(
                            checked = cloudVision,
                            onCheckedChange = { cloudVision = it },
                        )
                    }
                    Text(
                        "OFF by default. When on, the current frame is sent to your CLOUD provider " +
                            "(Claude / GPT / Gemini) — and only when the assistant chooses to look. On-device " +
                            "models always see frames locally and never need this. Leave it off to keep your " +
                            "footage strictly on-device.",
                        color = Neutral500, fontSize = 10.sp,
                    )



                    // On-device model catalogs: a model-path field + a curated download picker per slot
                    // (see the "Model picker" / "Model-path field" reusable controls in docs/SETTINGS.md).
                    // Every download here runs and stays fully on-device — only the model *weights*
                    // themselves are ever fetched over the network.
                    Text("AI assistant — on-device model (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = agentModelPath, hint = "assistant .task/.litertlm model", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { agentModelPath = it }
                    ModelPicker(context, "Assistant models", RECOMMENDED_ON_DEVICE_MODELS, agentModelPath) { agentModelPath = it }
                    Text(
                        "Run the assistant fully offline with no key. Blank = use the selected provider's key above.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Recognition model — for \"teach a specific thing\" (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = idEmbedModelPath, hint = "recognition .tflite model", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { idEmbedModelPath = it }
                    ModelPicker(context, "Recognition models", RECOMMENDED_RECOGNITION_MODELS, idEmbedModelPath) { idEmbedModelPath = it }
                    Text(
                        "A stronger embedder sharpens \"is this the same thing?\" matching. Blank = the bundled MobileNet-V3-small.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Face model — for identifying a specific person (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = faceEmbedModelPath, hint = "face .tflite model", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { faceEmbedModelPath = it }
                    ModelPicker(context, "Face models", RECOMMENDED_FACE_MODELS, faceEmbedModelPath) { faceEmbedModelPath = it }
                    Text(
                        "When set, teaching a person uses face recognition. Blank = fall back to the general recognition model.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Image effects — on-device TFLite models (optional)", color = Neutral400, fontSize = 12.sp)
                    listOf(
                        Triple(ModelCategory.DEPTH, "depth", "Depth model path — depth map (e.g. bokeh)" to "Depth models"),
                        Triple(ModelCategory.SUPERRES, "superres", "Super-resolution model path — upscale a frame" to "Super-resolution models"),
                        Triple(ModelCategory.LOWLIGHT, "lowlight", "Low-light enhance model path — brighten a dark frame" to "Low-light models"),
                        // STYLE has no recommended catalog (recommendedModelsFor returns emptyList, so
                        // ModelPicker below just renders nothing for it) — custom path only, per MODELS.md.
                        Triple(ModelCategory.STYLE, "style", "Style-transfer model path — stylize a frame" to "Style models"),
                    ).forEach { (category, key, hintAndTitle) ->
                        val (hint, pickerTitle) = hintAndTitle
                        val path = effectModelPaths[key].orEmpty()
                        ModelPathField(value = path, hint = hint, isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) {
                            effectModelPaths = effectModelPaths + (key to it)
                        }
                        ModelPicker(context, pickerTitle, recommendedModelsFor(category), path) {
                            effectModelPaths = effectModelPaths + (key to it)
                        }
                    }
                    Text(
                        "Each is optional. The assistant's \"upscale / stylize / depth / brighten this frame\" " +
                            "commands run the matching model. Style has no curated download — point it at a " +
                            "compatible single-image-in/single-image-out .tflite model of your own.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Audio highlights — on-device YAMNet (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = audioEventModelPath, hint = "audio-event .tflite model", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { audioEventModelPath = it }
                    ModelPicker(context, "Audio-event models", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_AUDIO_EVENT_MODELS, audioEventModelPath) { audioEventModelPath = it }
                    Text(
                        "Enables \"find the highlights / best moments\". Blank = feature off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Speech (ASR) — offline transcription (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = asrModelPath, hint = "sherpa-onnx ASR model directory", isDirectory = true, importing = modelImporting, onBrowse = ::browseModel) { asrModelPath = it }
                    ModelPicker(context, "ASR models", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_ASR_MODELS, asrModelPath) { asrModelPath = it }
                    Text(
                        "Enables \"transcribe this accurately\" via offline Whisper. Blank = feature off. " +
                            "(Distinct from the Transcription tab's Vosk field below.)",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Speech (TTS) — offline voiceover (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = ttsModelPath, hint = "sherpa-onnx TTS voice directory", isDirectory = true, importing = modelImporting, onBrowse = ::browseModel) { ttsModelPath = it }
                    ModelPicker(context, "TTS voices", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_TTS_MODELS, ttsModelPath) { ttsModelPath = it }
                    Text(
                        "Enables \"add a voiceover saying …\" via offline neural TTS. Blank = feature off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Frame captioning (VLM) — multimodal model (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = vlmModelPath, hint = "multimodal .task model", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { vlmModelPath = it }
                    ModelPicker(context, "VLM models", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_VLM_MODELS, vlmModelPath) { vlmModelPath = it }
                    Text(
                        "Enables \"describe / understand this frame\" in rich language. Blank = feature off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Speaker diarization — who spoke when (optional, needs both models)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = diarizeSegModelPath, hint = "pyannote segmentation model directory", isDirectory = true, importing = modelImporting, onBrowse = ::browseModel) { diarizeSegModelPath = it }
                    ModelPicker(context, "Speaker-segmentation models", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_DIARIZE_SEG_MODELS, diarizeSegModelPath) { diarizeSegModelPath = it }
                    ModelPathField(value = diarizeEmbedModelPath, hint = "speaker-embedding .onnx model", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { diarizeEmbedModelPath = it }
                    ModelPicker(context, "Speaker-embedding models", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_DIARIZE_EMBED_MODELS, diarizeEmbedModelPath) { diarizeEmbedModelPath = it }
                    Text(
                        "Enables \"who speaks when?\". Both default empty; diarization only works when both are set.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Stem separation — vocals / instrumental (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = stemModelPath, hint = "Spleeter model directory (ONNX)", isDirectory = true, importing = modelImporting, onBrowse = ::browseModel) { stemModelPath = it }
                    ModelPicker(context, "Stem models", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_STEM_MODELS, stemModelPath) { stemModelPath = it }
                    Text(
                        "Enables \"separate the stems / isolate the vocals\". Heavy — best on a capable device. Blank = feature off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Noise reduction — clean up voice audio (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = denoiseModelPath, hint = "GTCRN denoiser .onnx model", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { denoiseModelPath = it }
                    ModelPicker(context, "Denoiser models", com.hereliesaz.guillotine.ai.agent.RECOMMENDED_DENOISE_MODELS, denoiseModelPath) { denoiseModelPath = it }
                    Text(
                        "Strips hiss, hum and background noise from voice. Blank = feature off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    // FFmpeg / Frei0r filtergraph baking (advanced; desktop-first).
                    Text("FFmpeg / Frei0r filters — bake a -vf graph (advanced)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = ffmpegPath, hint = "ffmpeg executable", isDirectory = false, importing = modelImporting, onBrowse = ::browseModel) { ffmpegPath = it }
                    Text(
                        "Enables \"apply the ffmpeg filter …\" — bakes a standard FFmpeg -vf filtergraph " +
                            "(and Frei0r plugins via frei0r=name:params) onto a clip. Needs an ffmpeg " +
                            "binary; heavy — best on desktop or a capable device.",
                        color = Neutral500, fontSize = 10.sp,
                    )
                }
                1 -> { // Generation (image / video / music)
                    Text(
                        "Bring your own AI keys to generate images, video, and music. Configure as many " +
                            "providers as you like — only the categories and providers you set up are " +
                            "offered when generating. Legacy Leonardo image key is picked up automatically.",
                        color = Neutral400, fontSize = 12.sp,
                    )
                    GenCategorySection(
                        title = "Image", kind = GenKind.IMAGE,
                        genKeys = genKeys, genModels = genModels, genExtras = genExtras, uriHandler = uriHandler,
                        onKey = { p, v -> genKeys = genKeys + (p to v) },
                        onModel = { p, v -> genModels = genModels + (p to v) },
                        onExtra = { p, v -> genExtras = genExtras + (p to v) },
                        legacyLeonardoKey = leonardoKey, onLegacyLeonardoKey = { leonardoKey = it },
                    )
                    GenCategorySection(
                        title = "Video", kind = GenKind.VIDEO,
                        genKeys = genKeys, genModels = genModels, genExtras = genExtras, uriHandler = uriHandler,
                        onKey = { p, v -> genKeys = genKeys + (p to v) },
                        onModel = { p, v -> genModels = genModels + (p to v) },
                        onExtra = { p, v -> genExtras = genExtras + (p to v) },
                    )
                    GenCategorySection(
                        title = "Music", kind = GenKind.MUSIC,
                        genKeys = genKeys, genModels = genModels, genExtras = genExtras, uriHandler = uriHandler,
                        onKey = { p, v -> genKeys = genKeys + (p to v) },
                        onModel = { p, v -> genModels = genModels + (p to v) },
                        onExtra = { p, v -> genExtras = genExtras + (p to v) },
                    )
                }
                2 -> { // Transcription
                    Text("Transcription is now fully offline or via OpenAI.", color = Neutral400, fontSize = 12.sp)
                    Text("On-device speech model (Vosk, optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = speechModelPath, hint = "Vosk model folder", isDirectory = true, importing = modelImporting, onBrowse = ::browseModel) { speechModelPath = it }
                    Text(
                        "Point at a Vosk model folder to transcribe fully on-device. Blank = fall back to " +
                            "cloud OpenAI Whisper (needs a key in the AI Analyzer tab). (Distinct from that " +
                            "tab's Speech (ASR) model, which is the more accurate offline Whisper.)",
                        color = Neutral500, fontSize = 10.sp,
                    )
                }
                3 -> { // Advanced
                    Text("Crash reporting", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = crashRelayUrl,
                        onValueChange = { crashRelayUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Crash relay URL (your deployed endpoint)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text("Set the URL of your crash-relay (see tools/crash-relay) to auto-file issues.", color = Neutral500, fontSize = 10.sp)

                    Text("MCP access token (external AI tools)", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = mcpToken,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Copy", color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickableText {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(mcpToken))
                            },
                        )
                        Text(
                            "Regenerate", color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickableText {
                                mcpToken = com.hereliesaz.guillotine.mcp.McpAuth.regenerate(context)
                            },
                        )
                    }
                    Text(
                        "Send as 'Authorization: Bearer <token>' when POSTing to /mcp on port 6274. " +
                            "Regenerate to revoke tools that have the old token.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Encrypted cloud relay (optional)", color = Neutral400, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = relayEnabled,
                            onCheckedChange = { relayEnabled = it },
                        )
                        Text("Reach the editor via Cloudflare (no port-forwarding)", color = Neutral400, fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = { relayUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Worker URL (wss://…workers.dev/relay)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = relayAccessKey,
                        onValueChange = { relayAccessKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Worker access key (optional)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text(
                        "Deploy tools/mcp-relay, then run the local proxy with the same MCP token. " +
                            "Traffic is end-to-end encrypted; Cloudflare only relays ciphertext.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Backup & Restore", color = Neutral400, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Export settings", color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickableText { backupLauncher.launch("guillotine-settings.json") },
                        )
                        Text(
                            "Import settings", color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickableText { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                        )
                    }
                    Text("Export saves all AI settings and user-defined tools to a file. Import restores them (overwriting current settings).", color = Neutral500, fontSize = 10.sp)

                    Text("Install AI model (.azp)", color = Neutral400, fontSize = 12.sp)
                    Text(
                        if (azpBusy) "Installing…" else "Install",
                        color = if (azpBusy) Neutral500 else Red500,
                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickableText {
                            if (!azpBusy) azpLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                    )
                    if (azpBusy) LinearProgressIndicator(color = Red500, modifier = Modifier.fillMaxWidth())
                    azpStatus?.let { Text(it, color = Neutral400, fontSize = 10.sp) }
                    Text(
                        "Install an on-device AI model shipped as an azphalt package (ONNX / TFLite / sherpa). " +
                            "The package is integrity-checked; a remote model is verified against its checksum " +
                            "before it's wired in. Press Save to keep the change.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    // Direct-download (github) build only: manual "check for updates" — the mounted
                    // UpdatePrompt (near the app root) watches UpdateSignals and shows the dialog.
                    if (com.hereliesaz.guillotine.BuildConfig.UPDATER_ENABLED) {
                        Text("App updates", color = Neutral400, fontSize = 12.sp)
                        Text(
                            "Check for updates",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickableText {
                                com.hereliesaz.guillotine.update.UpdateSignals.checkNow.value++
                            },
                        )
                        Text(
                            "This build updates itself from GitHub Releases — you're prompted when a newer " +
                                "version is available. (The Play build updates through Google Play instead.)",
                            color = Neutral500, fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    com.hereliesaz.guillotine.crash.CrashConfig.setRelayUrl(context, crashRelayUrl)
                    com.hereliesaz.guillotine.mcp.McpRelayConfig.save(
                        context,
                        com.hereliesaz.guillotine.mcp.RelayConfig(
                            enabled = relayEnabled,
                            workerUrl = relayUrl.trim(),
                            accessKey = relayAccessKey.trim(),
                        ),
                    )
                    onSave(buildSettings())
                },
                colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Color.Black),
            ) { Text("Save", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }

    azpUntrusted?.let { (bytes, reason) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { azpUntrusted = null },
            title = { Text("Install an unverified model?") },
            text = {
                Text(
                    "This package isn't from a signer you trust ($reason). Its integrity checks passed, " +
                        "but a malicious model could produce misleading results. Only install packages " +
                        "from a source you trust.",
                )
            },
            confirmButton = {
                Text(
                    "Install anyway", color = Red500, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickableText { azpUntrusted = null; installAzp(bytes, allowUntrusted = true) },
                )
            },
            dismissButton = {
                Text("Cancel", modifier = Modifier.clickableText { azpUntrusted = null })
            },
        )
    }

    azpPublisherChange?.let { change ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { azpPublisherChange = null; azpChangeBytes = null },
            title = { Text("Different publisher") },
            text = {
                Text(
                    "\"${change.packageId}\" was first installed from one publisher, but this update is " +
                        "signed by " + (if (change.newSignerKey == null) "no key" else "a different key") +
                        ". This can be a legitimate key change — or someone else trying to replace the " +
                        "plugin. Only continue if you trust the new publisher.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val bytes = azpChangeBytes
                    azpPublisherChange = null; azpChangeBytes = null
                    if (bytes != null) installAzp(bytes, allowUntrusted = true, allowPublisherChange = true)
                }) { Text("Trust new publisher", color = Red500, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { azpPublisherChange = null; azpChangeBytes = null },
                ) { Text("Cancel") }
            },
        )
    }
}

/**
 * Curated on-device model picker: recommended `.task` models with size/license, a one-tap in-app
 * download for the ungated ones (progress + cancel; auto-adopted as the assistant brain on success),
 * and a Hugging Face link-out for gated ones. Observes the process-level [ModelDownloadManager].
 */
@Composable
private fun ModelPicker(
    context: android.content.Context,
    title: String,
    models: List<OnDeviceModel>,
    selectedPath: String,
    onUse: (String) -> Unit,
) {
    if (models.isEmpty()) return
    val state by ModelDownloadManager.state.collectAsState()
    val uriHandler = LocalUriHandler.current

    // Ensure the bundled model is extracted so installedPath picks it up (only relevant when this
    // group actually contains a bundled model).
    var bundledReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (models.any { it.bundled }) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                BundledModelExtractor.ensureExtracted(context)
            }
        }
        bundledReady = true
    }

    Text(title, color = Neutral400, fontSize = 12.sp)
    // Gemma redistribution notice: shown whenever this group offers a Gemma model. We re-host the
    // weights un-gated, so we pass through the Gemma Terms of Use per the license.
    if (models.any { it.license.contains("Gemma", ignoreCase = true) }) {
        Text(
            "Built with Gemma — provided under and subject to the Gemma Terms of Use ↗",
            color = Neutral500,
            fontSize = 10.sp,
            modifier = Modifier.clickableText { uriHandler.openUri("https://ai.google.dev/gemma/terms") },
        )
    }
    models.forEach { model ->
        // Keyed on bundledReady so the bundled model flips to "Installed" once extraction finishes
        // (without this the row never refreshes after the LaunchedEffect completes).
        val installed = remember(state, bundledReady, model.id) {
            ModelDownloadManager.installedPath(context, model)
        }
        val downloading = (state as? ModelDownloadManager.DownloadState.Downloading)
            ?.takeIf { it.modelId == model.id }
        val failed = (state as? ModelDownloadManager.DownloadState.Failed)
            ?.takeIf { it.modelId == model.id }
        // Recompute on every state change so a cancel/finish refreshes the resume offset.
        val partial = remember(state, model.id) { ModelDownloadManager.partialBytes(context, model) }
        val inUse = installed != null && installed == selectedPath

        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.label, color = White, fontSize = 12.sp)
                    Text("${model.sizeLabel} · ${model.license}", color = Neutral500, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        inUse -> Text("In use", color = Neutral500, fontSize = 11.sp)
                        installed != null -> ActionText("✓ Use") { onUse(installed) }
                        downloading != null -> ActionText("Cancel") { ModelDownloadManager.cancel() }
                        model.bundled -> {} // bundled but not yet extracted; will appear once ready
                        model.gated -> ActionText("Get ↗") { uriHandler.openUri(model.repoUrl) }
                        partial > 0 -> ActionText("Resume") { ModelDownloadManager.start(context, model) }
                        else -> ActionText("Download") { ModelDownloadManager.start(context, model) }
                    }
                    // Remove anything actually on disk (finished or a paused partial); bundled can't be removed.
                    if (!model.bundled && downloading == null && (installed != null || partial > 0)) {
                        ActionText("Remove") {
                            ModelDownloadManager.delete(context, model)
                            if (installed != null && installed == selectedPath) onUse("")
                        }
                    }
                }
            }
            if (downloading != null) {
                LinearProgressIndicator(
                    progress = { downloading.fraction },
                    color = Red500,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    "${(downloading.fraction * 100).toInt()}% · ${model.sizeLabel}",
                    color = Neutral500, fontSize = 10.sp,
                )
            }
            if (downloading == null && partial > 0 && installed == null) {
                Text(
                    "Paused at ${(partial * 100 / model.sizeBytes).toInt()}% · resumes where it left off",
                    color = Neutral500, fontSize = 10.sp,
                )
            }
            if (failed != null) Text(failed.message, color = Red500, fontSize = 10.sp)
            if (model.gated) {
                Text(
                    "Free Hugging Face sign-in required; then paste the .task path above.",
                    color = Neutral500, fontSize = 10.sp,
                )
            }
        }
    }

    // A freshly finished download from THIS group is auto-selected (applies on Save). Guard on the
    // model id so a recognition-model download doesn't get adopted as the assistant path, etc.
    val done = state as? ModelDownloadManager.DownloadState.Done
    androidx.compose.runtime.LaunchedEffect(done?.path) {
        if (done != null && models.any { it.id == done.modelId } && done.path != selectedPath) onUse(done.path)
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(
        label, color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clickableText { onClick() },
    )
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(label, color = Neutral500, fontSize = 12.sp) },
        textStyle = TextStyle(color = White, fontSize = 12.sp),
        singleLine = true,
    )
    Text("Stored encrypted on this device.", color = Neutral500, fontSize = 10.sp)
}

@Composable
private fun ProviderRow(label: String, blurb: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableText(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, color = White, fontSize = 13.sp)
            Text(blurb, color = Neutral500, fontSize = 11.sp)
        }
    }
}

/**
 * One generation category (Image / Video / Music): lists every provider that serves [kind] as a
 * collapsible card with a key field, model field, and a "get a key" link. A red dot marks configured
 * providers. Leonardo reuses the legacy top-level key so existing users don't have to re-enter it.
 */
@Composable
private fun GenCategorySection(
    title: String,
    kind: GenKind,
    genKeys: Map<GenProviderType, String>,
    genModels: Map<GenProviderType, String>,
    genExtras: Map<GenProviderType, String>,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    onKey: (GenProviderType, String) -> Unit,
    onModel: (GenProviderType, String) -> Unit,
    onExtra: (GenProviderType, String) -> Unit,
    legacyLeonardoKey: String = "",
    onLegacyLeonardoKey: ((String) -> Unit)? = null,
) {
    Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    providersFor(kind).forEach { p ->
        val meta = genMeta(p)
        var expanded by remember(p) { mutableStateOf(false) }
        val isLeonardo = p == GenProviderType.LEONARDO
        val keyValue = if (isLeonardo) legacyLeonardoKey else genKeys[p].orEmpty()
        val configured = !meta.needsKey || keyValue.isNotBlank()
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Neutral800, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickableText { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (configured) Red500 else Neutral700),
                ) {}
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(meta.label, color = White, fontSize = 13.sp)
                    Text(meta.blurb, color = Neutral500, fontSize = 11.sp)
                }
                Text(if (expanded) "–" else "+", color = Neutral400, fontSize = 18.sp)
            }
            if (expanded) {
                if (!meta.needsKey) {
                    Text("Free — no key needed.", color = Neutral500, fontSize = 11.sp)
                } else {
                    KeyField("${meta.label} API key", keyValue) { v ->
                        if (isLeonardo) onLegacyLeonardoKey?.invoke(v) else onKey(p, v)
                    }
                }
                if (p == GenProviderType.SUNO_WRAPPER || p == GenProviderType.UDIO_WRAPPER) {
                    OutlinedTextField(
                        value = genExtras[p].orEmpty(),
                        onValueChange = { onExtra(p, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Wrapper base URL (https://…)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                }
                if (meta.models.isNotEmpty()) {
                    OutlinedTextField(
                        value = genModels[p].orEmpty(),
                        onValueChange = { onModel(p, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Model (default: ${meta.defaultModel})", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text("Options: " + meta.models.joinToString { it.name }, color = Neutral500, fontSize = 10.sp)
                }
                meta.disclaimer?.let { Text(it, color = Neutral500, fontSize = 10.sp) }
                meta.keyUrl?.let { url -> ActionText("Get a ${meta.label} key  ↗") { uriHandler.openUri(url) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExportSheet(
    totalDurationMs: Long,
    isExporting: Boolean,
    progress: Float,
    exportPhase: String?,
    doneMessage: String?,
    errorMessage: String?,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Share the just-exported video; null until the export produced a shareable file. */
    onShare: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf("guillotine_export") }
    var errorExpanded by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = { if (!isExporting) onDismiss() }) {
        SheetCard {
            Text("Export", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            when {
                doneMessage != null -> {
                    Text(doneMessage, color = Neutral400, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (onShare != null) {
                            Button(onClick = onShare, colors = ButtonDefaults.buttonColors(containerColor = White)) {
                                Text("Share", fontSize = 12.sp, color = Color.Black)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Neutral800)) {
                            Text("Close", fontSize = 12.sp, color = White)
                        }
                    }
                }
                isExporting -> {
                    LoadingIndicator()
                    Text("Rendering… ${(progress * 100).toInt()}%", color = Neutral400, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Red500, modifier = Modifier.fillMaxWidth())
                    // The user reported "no idea why it failed" — showing the current phase means
                    // they can also tell WHEN it's slow and WHERE it dies if it fails.
                    exportPhase?.let { Text(it, color = Neutral500, fontSize = 11.sp) }
                }
                else -> {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text("Duration: ${"%.1f".format(totalDurationMs / 1000f)}s → Movies/Guillotine", color = Neutral500, fontSize = 11.sp)
                    errorMessage?.let { msg ->
                        // Collapsed: headline (first line). Expanded: full cause chain + Copy button.
                        // The Media3 diagnostic string carries the errorCodeName, code, and every cause
                        // — long, but every line is useful when reporting a bug.
                        val headline = msg.substringBefore("\n").ifBlank { msg }
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                headline,
                                color = Red500,
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth().clickableText { errorExpanded = !errorExpanded },
                            )
                            if (errorExpanded && msg.length > headline.length) {
                                Text(
                                    msg.substringAfter("\n").trim(),
                                    color = Neutral400,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(top = 4.dp, bottom = 4.dp),
                                )
                            }
                            // Left-align these — Cancel / Start render live on the right. Keeping the
                            // secondary actions on the LEFT means an accidental tap can't land on
                            // Cancel and dismiss the sheet mid-diagnosis. clickable-before-padding
                            // so the padding is part of the touch target for the 10.sp text.
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Start) {
                                Text(
                                    if (errorExpanded) "Hide details" else "Show details",
                                    color = Neutral400,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .clickableText { errorExpanded = !errorExpanded }
                                        .padding(end = 12.dp),
                                )
                                // Report path — try the Cloudflare Worker relay first so end users
                                // without a GH account still land a real issue. Falls back to
                                // opening the pre-filled GitHub URL in the browser when the relay
                                // is unconfigured/offline; last-ditch drops the diagnostic on the
                                // clipboard so nothing is ever silently lost.
                                Text(
                                    "Report",
                                    color = Neutral400,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .clickableText {
                                            val report = com.hereliesaz.guillotine.export.Exporter
                                                .buildIssueReport(context, msg)
                                            ActivityLog.info("Reporting failure…")
                                            com.hereliesaz.guillotine.crash.CrashReporter.reportManual(
                                                context, report.title, report.body,
                                                labels = listOf("bug", "export"),
                                            ) { ok ->
                                                if (ok) {
                                                    ActivityLog.success("Reported. Thanks!")
                                                } else {
                                                    // Fallback: open the pre-filled issue URL —
                                                    // the user still needs a GH account for this
                                                    // path but the diagnostic is ready to submit.
                                                    val url = com.hereliesaz.guillotine.export
                                                        .Exporter.buildIssueUrl(report)
                                                    runCatching {
                                                        val intent = android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            android.net.Uri.parse(url),
                                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(intent)
                                                    }.onFailure {
                                                        clipboard.setText(
                                                            androidx.compose.ui.text.AnnotatedString(msg),
                                                        )
                                                        ActivityLog.error(
                                                            "Couldn't reach the relay or a browser; " +
                                                                "diagnostic copied to clipboard.",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        .padding(4.dp),
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cancel", color = Neutral400, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp).clickableText(onDismiss))
                        Button(onClick = { onStart(name) }, colors = ButtonDefaults.buttonColors(containerColor = Red500)) {
                            Text("Start render", fontSize = 12.sp, color = White)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerateSheet(
    leonardoKey: String,
    leonardoModel: String,
    onGenerateFree: (url: String, name: String) -> Unit,
    onGenerateLeonardo: suspend (prompt: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    val leonardoAvailable = leonardoKey.isNotBlank()
    var useLeonardo by remember { mutableStateOf(leonardoAvailable) }
    var model by remember { mutableStateOf(leonardoModel.ifBlank { com.hereliesaz.guillotine.ai.LeonardoDefaultModel }) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = { if (!generating) onDismiss() }) {
        SheetCard {
            Text("Generate image", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (generating) {
                LoadingIndicator()
                Text("Generating with Leonardo… this can take a little while.", color = Neutral400, fontSize = 12.sp)
            } else {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Describe the image…", color = Neutral500, fontSize = 12.sp) },
                    textStyle = TextStyle(color = White, fontSize = 12.sp),
                    minLines = 2,
                )
                if (leonardoAvailable) {
                    BackendRow("Free (Pollinations.ai, no key)", !useLeonardo) { useLeonardo = false }
                    BackendRow("Leonardo.ai (your key)", useLeonardo) { useLeonardo = true }
                    if (useLeonardo) {
                        Text("Model", color = Neutral500, fontSize = 10.sp)
                        LeonardoModelDropdown(leonardoKey, model) { model = it }
                    }
                } else {
                    Text(
                        "Pollinations.ai — no key required. Add a Leonardo API key in Settings to pick from Leonardo's models.",
                        color = Neutral500, fontSize = 11.sp,
                    )
                }
                error?.let { Text(it, color = Red500, fontSize = 11.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("Cancel", color = Neutral400, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp).clickableText(onDismiss))
                    Button(
                        enabled = prompt.isNotBlank(),
                        onClick = {
                            error = null
                            if (useLeonardo) {
                                generating = true
                                scope.launch {
                                    try {
                                        onGenerateLeonardo(prompt.trim(), model)
                                        onDismiss()
                                    } catch (e: Exception) {
                                        error = e.message ?: "Generation failed"
                                        generating = false
                                    }
                                }
                            } else {
                                onGenerateFree(ImageGen.Pollinations.url(prompt), "Generated: ${prompt.take(20)}")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Red500),
                    ) { Text("Generate", fontSize = 12.sp, color = White) }
                }
            }
        }
    }
}

/**
 * Picks a Leonardo platform model. Fetches Leonardo's live model list on first open (when a
 * key is present); falls back to the curated [ImageGen.LeonardoModels] if that's unavailable.
 */
@Composable
private fun LeonardoModelDropdown(apiKey: String, selectedId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Keyed on apiKey so editing the key in the same dialog re-fetches instead of showing a stale list.
    var live by remember(apiKey) { mutableStateOf<List<com.hereliesaz.guillotine.ai.LeonardoModel>?>(null) }
    var loading by remember(apiKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val models = live?.takeIf { it.isNotEmpty() } ?: com.hereliesaz.guillotine.ai.LeonardoModels
    val name = models.firstOrNull { it.id == selectedId }?.name
        ?: com.hereliesaz.guillotine.ai.LeonardoModels.firstOrNull { it.id == selectedId }?.name ?: "Select a model"
    Box {
        DropdownAnchor(name) {
            open = true
            if (live == null && !loading && apiKey.isNotBlank()) {
                loading = true
                scope.launch { live = ModelCatalog.leonardoModels(apiKey); loading = false }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (loading) MenuLabel("Loading…")
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.name, color = White, fontSize = 12.sp) },
                    onClick = { onSelect(m.id); open = false },
                )
            }
        }
    }
}

/**
 * Picks a model from a source's live list, loaded on first open. Shows [current] (or
 * [defaultHint]); offers "Default" (clears the override) plus each fetched id.
 */
@Composable
private fun LiveModelDropdown(
    current: String,
    defaultHint: String,
    load: suspend () -> List<String>,
    onSelect: (String) -> Unit,
    resetKey: Any? = null,
) {
    var open by remember { mutableStateOf(false) }
    // resetKey (the API key) invalidates the cached list when it changes mid-dialog.
    var items by remember(resetKey) { mutableStateOf<List<String>?>(null) }
    var loading by remember(resetKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        DropdownAnchor(current.ifBlank { defaultHint }) {
            open = true
            if (items == null && !loading) {
                loading = true
                scope.launch { items = load(); loading = false }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            when {
                loading -> MenuLabel("Loading…")
                items.isNullOrEmpty() -> MenuLabel("No models — check your key")
                else -> {
                    DropdownMenuItem(text = { Text("Default", color = White, fontSize = 12.sp) }, onClick = { onSelect(""); open = false })
                    items!!.forEach { id ->
                        DropdownMenuItem(text = { Text(id, color = White, fontSize = 12.sp) }, onClick = { onSelect(id); open = false })
                    }
                }
            }
        }
    }
}

/** Bordered, full-width row that shows a value and a ▾, opening a dropdown on tap. */
@Composable
private fun DropdownAnchor(label: String, onClick: () -> Unit) {
    Text(
        "$label  ▾",
        color = White, fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Neutral700, RoundedCornerShape(6.dp))
            .clickableText(onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, color = Neutral500, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun BackendRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickableText(onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = White, fontSize = 12.sp)
    }
}

/** Project-wide options (formerly the inspector's "Global settings"), now reached from the menu. */
@Composable
fun ProjectSettingsSheet(current: GlobalSettings, onChange: (GlobalSettings) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        SheetCard {
            Text("Project settings", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)

            Text("Aspect ratio", color = Neutral400, fontSize = 12.sp)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AspectRatio.values().forEach { ar ->
                    SettingChip(ar.label(), current.aspectRatio == ar) { onChange(current.copy(aspectRatio = ar)) }
                }
            }

            Text("Quality", color = Neutral400, fontSize = 12.sp)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Quality.values().forEach { q ->
                    SettingChip(q.label(), current.quality == q) { onChange(current.copy(quality = q)) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Color.Black),
                ) { Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Neutral400,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) White else Color.Transparent)
            .border(1.dp, if (selected) White else Neutral800, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun AspectRatio.label() = when (this) {
    AspectRatio.RATIO_16_9 -> "16:9"
    AspectRatio.RATIO_9_16 -> "9:16"
    AspectRatio.RATIO_1_1 -> "1:1"
    AspectRatio.ORIGINAL -> "Original"
}

private fun Quality.label() = when (this) {
    Quality.ORIGINAL -> "Original"
    Quality.UHD_4K -> "4K"
    Quality.FHD_1080P -> "1080p"
    Quality.HD_720P -> "720p"
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * A model-path setting rendered as a **Browse** button (not a paste-a-path text box): it opens the
 * native file (or folder, when [isDirectory]) explorer via [onBrowse], which copies the selection into
 * app storage and hands back the stored path. Shows the current path read-only, with a Clear affordance.
 */
@Composable
private fun ModelPathField(
    value: String,
    hint: String,
    isDirectory: Boolean,
    importing: Boolean,
    onBrowse: (isDirectory: Boolean, onResult: (String) -> Unit) -> Unit,
    onSet: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Neutral700, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value.ifBlank { hint },
            color = if (value.isBlank()) Neutral500 else White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        // Vertical padding is INSIDE the clickable so the whole padded area is the touch target.
        Text(
            if (importing) "Copying…" else if (isDirectory) "Choose folder" else "Browse",
            color = if (importing) Neutral500 else Red500,
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickableText { if (!importing) onBrowse(isDirectory) { onSet(it) } }
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        )
        if (value.isNotBlank()) {
            Text(
                "Clear", color = Neutral400, fontSize = 12.sp,
                modifier = Modifier
                    .clickableText { onSet("") }
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}
