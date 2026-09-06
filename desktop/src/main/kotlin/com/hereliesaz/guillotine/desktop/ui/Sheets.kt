package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.hereliesaz.guillotine.ai.LeonardoDefaultModel
import com.hereliesaz.guillotine.ai.LeonardoModel
import com.hereliesaz.guillotine.ai.LeonardoModels
import com.hereliesaz.guillotine.ai.ModelCatalog
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.desktop.ui.theme.Black
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral700
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral900
import com.hereliesaz.guillotine.desktop.ui.theme.Red500
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.GlobalSettings
import com.hereliesaz.guillotine.model.Quality
import com.hereliesaz.guillotine.azphalt.AzphaltTrust
import com.hereliesaz.guillotine.azphalt.AzpModelInstall
import com.hereliesaz.guillotine.azphalt.AzpModelInstaller
import com.hereliesaz.guillotine.desktop.platform.DesktopStorage
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Desktop mirror of the app side's `AiCapabilitySummary` (`app/.../ui/Sheets.kt`) — see its doc. */
@Composable
private fun DesktopAiCapabilitySummary(settings: AiSettings) {
    val cloudConfigured = settings.provider != AiProviderType.MLKIT && settings.keyFor(settings.provider).isNotBlank()
    val rows = listOf(
        "Assistant brain" to (cloudConfigured || settings.agentModelPath.isNotBlank()),
        "Frame vision (recognition)" to true,
        "Transcription" to (settings.speechModelPath.isNotBlank() || settings.asrModelPath.isNotBlank() || settings.keyFor(AiProviderType.OPENAI).isNotBlank()),
        "Text-to-speech" to settings.ttsModelPath.isNotBlank(),
        "Frame captioning (VLM)" to settings.vlmModelPath.isNotBlank(),
        "Audio highlight detection" to settings.audioEventModelPath.isNotBlank(),
        "Speaker diarization" to (settings.diarizeSegModelPath.isNotBlank() && settings.diarizeEmbedModelPath.isNotBlank()),
        "Stem separation" to settings.stemModelPath.isNotBlank(),
        "Denoise" to settings.denoiseModelPath.isNotBlank(),
        "Image/video/music generation" to (settings.genKeys.values.any { it.isNotBlank() } || settings.leonardoKey.isNotBlank()),
        "Cloud may see the current frame" to settings.cloudVision,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Neutral800)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Your setup, at a glance", color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, on) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (on) "✓" else "—", color = if (on) Red500 else Neutral500, fontSize = 12.sp)
                        Text(label, color = Neutral400, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    current: AiSettings,
    onSave: (AiSettings) -> Unit,
    onDismiss: () -> Unit,
    /** See the app side's identical parameter (`app/.../ui/Sheets.kt`) — null shows all four tabs. */
    restrictToTabs: List<Int>? = null,
) {
    var provider by remember { mutableStateOf(current.provider) }
    var keys by remember { mutableStateOf(current.keys) }
    var models by remember { mutableStateOf(current.models) }
    var leonardoKey by remember { mutableStateOf(current.leonardoKey) }
    var leonardoModel by remember { mutableStateOf(current.leonardoModel) }
    var frameAnalysisCacheSize by remember { mutableIntStateOf(current.frameAnalysisCacheSize) }
    var cloudVision by remember { mutableStateOf(current.cloudVision) }

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
    val scope = rememberCoroutineScope()

    // Assemble settings from the current editable state — shared by Save and the .azp installer
    // (which folds newly-routed model paths into the visible fields first).
    fun buildSettings(): AiSettings = current.copy(
        provider = provider,
        keys = keys,
        models = models,
        leonardoKey = leonardoKey.trim(),
        leonardoModel = leonardoModel,
        cloudVision = cloudVision,
        frameAnalysisCacheSize = frameAnalysisCacheSize,
        agentModelPath = agentModelPath,
        idEmbedModelPath = idEmbedModelPath,
        faceEmbedModelPath = faceEmbedModelPath,
        effectModelPaths = effectModelPaths,
        audioEventModelPath = audioEventModelPath,
        asrModelPath = asrModelPath,
        ttsModelPath = ttsModelPath,
        vlmModelPath = vlmModelPath,
        diarizeSegModelPath = diarizeSegModelPath,
        diarizeEmbedModelPath = diarizeEmbedModelPath,
        stemModelPath = stemModelPath,
        denoiseModelPath = denoiseModelPath,
    )

    // --- Install an AI model from an azphalt .azp package ---------------------------------------
    var azpBusy by remember { mutableStateOf(false) }
    var azpStatus by remember { mutableStateOf<String?>(null) }
    var azpUntrusted by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    // A package whose id was first installed from a different publisher key — prompt before overwriting.
    var azpPublisherChange by remember {
        mutableStateOf<com.hereliesaz.guillotine.azphalt.AzpModelInstall.PublisherChangedException?>(null)
    }
    var azpChangeBytes by remember { mutableStateOf<ByteArray?>(null) }
    val publisherPins = remember {
        com.hereliesaz.guillotine.azphalt.AzpPublisherPins(
            java.io.File(DesktopStorage.dataDir, "azp-publishers.json"),
        )
    }

    fun applyInstalled(result: AzpModelInstall.Result) {
        result.installed.forEach { inst ->
        // (Legacy manual routing removed; models operate directly from ~/.azphalt/packages)
        }
        onSave(buildSettings())
    }

    fun installAzp(bytes: ByteArray, allowUntrusted: Boolean, allowPublisherChange: Boolean = false) {
        scope.launch {
            azpBusy = true
            azpStatus = "Reading package…"
            try {
                val dir = java.io.File(DesktopStorage.dataDir, "azp-models")
                val result = withContext(Dispatchers.IO) {
                    AzpModelInstall.install(
                        bytes, setOf(AzphaltTrust.FLAGSHIP_SIGNING_KEY), dir, allowUntrusted,
                        pins = publisherPins, allowPublisherChange = allowPublisherChange,
                    ) { p ->
                        val pct = p.bytesTotal?.takeIf { it > 0 }?.let { p.bytesDone * 100 / it }
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
            } catch (e: com.hereliesaz.guillotine.azphalt.AzpModelInstall.PublisherChangedException) {
                azpPublisherChange = e
                azpChangeBytes = bytes
                azpStatus = null
            } catch (e: com.hereliesaz.guillotine.azphalt.AzpModelInstall.UntrustedException) {
                azpUntrusted = bytes to e.trust.reason
                azpStatus = null
            } catch (e: Exception) {
                azpStatus = "Install failed: ${e.message}"
            } finally {
                azpBusy = false
            }
        }
    }

    val installModelLauncher = rememberModelInstallLauncher { file ->
        scope.launch {
            val bytes = runCatching { withContext(Dispatchers.IO) { file.readBytes() } }.getOrNull()
            if (bytes == null) { azpStatus = "Could not read ${file.name}."; return@launch }
            installAzp(bytes, allowUntrusted = false)
        }
    }

    val tabs = listOf("AI Analyzer", "Image Gen", "Transcription", "Advanced")
    val visibleTabs = restrictToTabs ?: tabs.indices.toList()
    var selectedTab by remember { mutableStateOf(visibleTabs.first()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Neutral900)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = White,
                modifier = Modifier.size(24.dp).clickable { onDismiss() },
            )
        }

        if (0 in visibleTabs) {
            DesktopAiCapabilitySummary(buildSettings())
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleTabs.forEach { index ->
                val title = tabs[index]
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selectedTab) {
                0 -> {
                    Text(
                        "Pick the AI that drives the editor. Cloud providers use your API key " +
                            "and process requests on their servers.",
                        color = Neutral400, fontSize = 12.sp,
                    )

                    Column(
                        Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
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
                                modifier = Modifier.clickable { uriHandler.openUri(url) }.padding(top = 2.dp),
                            )
                        }
                    }

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
                        onValueChange = { frameAnalysisCacheSize = it.roundToInt() },
                        valueRange = FrameAnalysisCache.MIN_MAX_ENTRIES.toFloat()..FrameAnalysisCache.MAX_MAX_ENTRIES.toFloat(),
                        steps = 31,
                    )
                    Text(
                        "How many per-frame vision results to keep so rescans are near-instant. " +
                            "Default ${FrameAnalysisCache.DEFAULT_MAX_ENTRIES}. 0 disables the cache.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    // Cloud vision (opt-in). Off by default — the ONLY path that sends a frame off-device,
                    // and only to the user's own cloud provider.
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
                            "(Claude / GPT / Gemini) — and only when the assistant chooses to look. Leave it " +
                            "off to keep your footage strictly on your machine.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                                        // On-device model catalogs: a model-path field + a curated download picker per slot
                    // Every download here runs and stays fully on-device — only the model *weights*
                    // themselves are ever fetched over the network.
                    
                    
                    
                    Text("AI assistant — on-device model (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = agentModelPath, hint = "assistant .task/.litertlm model", isDirectory = false) { agentModelPath = it }
                    Text("Run the assistant fully offline with no key. Blank = use the selected provider's key above.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=litert") }.padding(top = 2.dp)
                        )

                    Text("Recognition model — for \"teach a specific thing\" (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = idEmbedModelPath, hint = "recognition .tflite model", isDirectory = false) { idEmbedModelPath = it }
                    Text("A stronger embedder sharpens \"is this the same thing?\" matching. Blank = the bundled MobileNet-V3-small.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=tflite") }.padding(top = 2.dp)
                        )

                    Text("Face model — for identifying a specific person (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = faceEmbedModelPath, hint = "face .tflite model", isDirectory = false) { faceEmbedModelPath = it }
                    Text("When set, teaching a person uses face recognition. Blank = fall back to the general recognition model.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=tflite") }.padding(top = 2.dp)
                        )

                    Text("Image effects — on-device TFLite models (optional)", color = Neutral400, fontSize = 12.sp)
                    listOf(
                        Triple("depth", "Depth model path — depth map (e.g. bokeh)", "tflite"),
                        Triple("superres", "Super-resolution model path — upscale a frame", "tflite"),
                        Triple("lowlight", "Low-light model path — brighten a frame", "tflite"),
                        Triple("style", "Style transfer path — apply an artistic style", "tflite")
                    ).forEach { (kind, hint, cat) ->
                        ModelPathField(value = effectModelPaths[kind].orEmpty(), hint = hint, isDirectory = false) { effectModelPaths = effectModelPaths + (kind to it) }
                        Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=${cat}") }.padding(top = 2.dp)
                        )
                    }
                    Text("Enables \"apply the image effect\" ... Commands run the matching model.", color = Neutral500, fontSize = 10.sp)

                    Text("Audio-event model — highlight detection (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = audioEventModelPath, hint = "YAMNet .tflite model", isDirectory = false) { audioEventModelPath = it }
                    Text("Enables \"find the highlights / best moments\". Blank = feature off.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=tflite") }.padding(top = 2.dp)
                        )

                    Text("Speech (ASR) — offline transcription (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = asrModelPath, hint = "sherpa-onnx ASR model directory", isDirectory = true) { asrModelPath = it }
                    Text("Enables \"transcribe this accurately\" via offline Whisper (sherpa-onnx).", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=onnx") }.padding(top = 2.dp)
                        )

                    Text("Speech (TTS) — offline voiceover (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = ttsModelPath, hint = "sherpa-onnx TTS voice directory", isDirectory = true) { ttsModelPath = it }
                    Text("Enables \"add a voiceover saying …\" via offline neural TTS (sherpa-onnx).", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=onnx") }.padding(top = 2.dp)
                        )

                    Text("Frame captioning (VLM) — multimodal model (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = vlmModelPath, hint = "Multimodal VLM model (.task)", isDirectory = false) { vlmModelPath = it }
                    Text("Lets the assistant \"describe / understand this frame\" in rich language.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=litert") }.padding(top = 2.dp)
                        )

                    Text("Speaker diarization — who spoke when (optional, needs both models)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = diarizeSegModelPath, hint = "Diarization segmentation directory (pyannote)", isDirectory = true) { diarizeSegModelPath = it }
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=onnx") }.padding(top = 2.dp)
                        )
                    ModelPathField(value = diarizeEmbedModelPath, hint = "Speaker-embedding model (.onnx)", isDirectory = false) { diarizeEmbedModelPath = it }
                    Text("Enables \"who speaks when?\" — set BOTH a segmentation and an embedding model.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=onnx") }.padding(top = 2.dp)
                        )

                    Text("Stem separation — vocals / instrumental (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = stemModelPath, hint = "Spleeter model directory (ONNX)", isDirectory = true) { stemModelPath = it }
                    Text("Enables \"separate the stems / isolate the vocals\". Heavy — best on a capable device. Blank = feature off.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=onnx") }.padding(top = 2.dp)
                        )

                    Text("Noise reduction — clean up voice audio (optional)", color = Neutral400, fontSize = 12.sp)
                    ModelPathField(value = denoiseModelPath, hint = "Speech-denoiser model (.onnx)", isDirectory = false) { denoiseModelPath = it }
                    Text("Enables \"remove background noise / clean up the audio\" — strips hiss, hum, and background noise from voice.", color = Neutral500, fontSize = 10.sp)
                    Text(
                            "Get from Azphalt Store  ↗",
                            color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { uriHandler.openUri("https://azphalt.store/browse?category=onnx") }.padding(top = 2.dp)
                        )
                    
                    Text("Install AI model (.azp)", color = Neutral400, fontSize = 12.sp)
                    Text(
                        if (azpBusy) "Installing…" else "Install from file",
                        color = if (azpBusy) Neutral500 else Red500,
                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(enabled = !azpBusy) { installModelLauncher() },
                    )
                    if (azpBusy) LinearProgressIndicator(color = Red500, modifier = Modifier.fillMaxWidth())
                    azpStatus?.let { Text(it, color = Neutral400, fontSize = 10.sp) }
                    Text(
                        "Install an on-device AI model shipped as an azphalt package (ONNX / sherpa). " +
                            "The package is integrity-checked; a remote model is verified against its " +
                            "checksum before it's wired in. Saved automatically on success.",
                        color = Neutral500, fontSize = 10.sp,
                    )
                }
                1 -> {
                    Text("Image generation — Leonardo.ai (optional)", color = Neutral400, fontSize = 12.sp)
                    KeyField("Leonardo API key", leonardoKey) { leonardoKey = it }
                    Text("Default model", color = Neutral500, fontSize = 10.sp)
                    LeonardoModelDropdown(leonardoKey, leonardoModel) { leonardoModel = it }
                    Text("Leave the key blank to generate with free Pollinations.ai.", color = Neutral500, fontSize = 10.sp)
                    Text(
                        "Get a Leonardo API key  ↗",
                        color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { uriHandler.openUri("https://app.leonardo.ai/api-access") },
                    )
                }
                2 -> {
                }
                3 -> {
                    Text("MCP server", color = Neutral400, fontSize = 12.sp)
                    Text(
                        "The MCP server runs on port 7865. External AI tools can connect using " +
                            "the bearer token configured in the desktop key store.",
                        color = Neutral500, fontSize = 10.sp,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onSave(buildSettings()) },
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
                    modifier = Modifier.clickable { azpUntrusted = null; installAzp(bytes, allowUntrusted = true) },
                )
            },
            dismissButton = {
                Text("Cancel", modifier = Modifier.clickable { azpUntrusted = null })
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
                        ". This can mean a legitimate key change — or that someone else is trying to " +
                        "replace the plugin. Only continue if you trust the new publisher.",
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

/**
 * A model-path setting rendered as a **Browse** button (not a paste-a-path text box): it opens the
 * native file (or folder, when [isDirectory]) explorer and stores the chosen absolute path. Shows the
 * current path read-only, with a Clear affordance.
 */
@Composable
private fun ModelPathField(value: String, hint: String, isDirectory: Boolean, onSet: (String) -> Unit) {
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
        // Vertical padding is INSIDE the clickable so the whole padded area is the click target.
        Text(
            if (isDirectory) "Choose folder" else "Browse",
            color = Red500, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable {
                    val picked = if (isDirectory) pickFolder("Select model folder") else pickFile("Select model file")
                    if (picked != null) onSet(picked.absolutePath)
                }
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        )
        if (value.isNotBlank()) {
            Text(
                "Clear", color = Neutral400, fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onSet("") }
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ProviderRow(label: String, blurb: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, color = White, fontSize = 13.sp)
            Text(blurb, color = Neutral500, fontSize = 11.sp)
        }
    }
}

@Composable
fun ExportSheet(
    totalDurationMs: Long,
    /** Real output pixel size for the project's actual aspect ratio (see NleScreen's
     *  `exportDimensionsFor`) -- shown in the summary text instead of a hardcoded "1920x1080" that was
     *  wrong for anything but a 16:9/Original project. */
    exportWidth: Int,
    exportHeight: Int,
    isExporting: Boolean,
    progress: Float,
    doneMessage: String?,
    errorMessage: String?,
    /** The current playback/loop region, if one is set — offered as "Render Loop Region Only" (Vegas
     *  J.4) when non-null; the checkbox is hidden entirely when there's no region to offer. */
    playbackRegion: LongRange? = null,
    onStart: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("guillotine_export") }
    var regionOnly by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!isExporting) onDismiss() }) {
        SheetCard {
            Text("Export", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            when {
                doneMessage != null -> {
                    Text(doneMessage, color = Neutral400, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Neutral800)) {
                            Text("Close", fontSize = 12.sp, color = White)
                        }
                    }
                }
                isExporting -> {
                    Text("Rendering… ${(progress * 100).toInt()}%", color = Neutral400, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Red500, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text("Duration: ${"%.1f".format(totalDurationMs / 1000f)}s", color = Neutral500, fontSize = 11.sp)
                    Text("Output: H.264 + AAC in MP4, ${exportWidth}x$exportHeight @ 30fps", color = Neutral500, fontSize = 11.sp)
                    // "Render Loop Region Only" (Vegas J.4) — only offered when a region is actually set.
                    if (playbackRegion != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = regionOnly, onCheckedChange = { regionOnly = it })
                            Text(
                                "Render loop region only (${"%.1f".format((playbackRegion.last - playbackRegion.first) / 1000f)}s)",
                                color = Neutral400, fontSize = 12.sp,
                            )
                        }
                    }
                    errorMessage?.let { Text(it, color = Red500, fontSize = 11.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cancel", color = Neutral400, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp).clickable(onClick = onDismiss))
                        Button(
                            onClick = { onStart(name, regionOnly) },
                            enabled = name.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Red500),
                        ) {
                            Text("Start render", fontSize = 12.sp, color = White)
                        }
                    }
                }
            }
        }
    }
}

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
    var model by remember { mutableStateOf(leonardoModel.ifBlank { LeonardoDefaultModel }) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = { if (!generating) onDismiss() }) {
        SheetCard {
            Text("Generate image", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (generating) {
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
                    Text("Cancel", color = Neutral400, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp).clickable(onClick = onDismiss))
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
                                val encoded = java.net.URLEncoder.encode(prompt.trim(), "UTF-8")
                                val url = "https://image.pollinations.ai/prompt/$encoded?width=1280&height=720&nologo=true"
                                onGenerateFree(url, "Generated: ${prompt.take(20)}")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Red500),
                    ) { Text("Generate", fontSize = 12.sp, color = White) }
                }
            }
        }
    }
}

@Composable
private fun LeonardoModelDropdown(apiKey: String, selectedId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var live by remember(apiKey) { mutableStateOf<List<LeonardoModel>?>(null) }
    var loading by remember(apiKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val modelList = live?.takeIf { it.isNotEmpty() } ?: LeonardoModels
    val name = modelList.firstOrNull { it.id == selectedId }?.name
        ?: LeonardoModels.firstOrNull { it.id == selectedId }?.name ?: "Select a model"
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
            modelList.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.name, color = White, fontSize = 12.sp) },
                    onClick = { onSelect(m.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun LiveModelDropdown(
    current: String,
    defaultHint: String,
    load: suspend () -> List<String>,
    onSelect: (String) -> Unit,
    resetKey: Any? = null,
) {
    var open by remember { mutableStateOf(false) }
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

@Composable
private fun DropdownAnchor(label: String, onClick: () -> Unit) {
    Text(
        "$label  ▾",
        color = White, fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Neutral700, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, color = Neutral500, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun BackendRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = White, fontSize = 12.sp)
    }
}

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
