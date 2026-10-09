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
import androidx.compose.runtime.LaunchedEffect
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
import com.hereliesaz.guillotine.ai.ModelCatalog
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.ai.agent.DeviceModelAdvisor
import com.hereliesaz.guillotine.ai.agent.DeviceModelFit
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_DESKTOP_ASSISTANT_MODELS
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
import com.hereliesaz.guillotine.azphalt.AzpLlm
import com.hereliesaz.guillotine.azphalt.AzpLlmSandbox
import com.hereliesaz.guillotine.azphalt.AzpModelInstall
import com.hereliesaz.guillotine.azphalt.AzpModelInstaller
import com.hereliesaz.guillotine.desktop.platform.DesktopDeviceModelProfile
import com.hereliesaz.guillotine.desktop.platform.DesktopOllama
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

@Composable
private fun DesktopModelSlotStatus(
    title: String,
    slot: String,
    description: String,
    storeCategory: String,
) {
    val resolved = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve(slot)
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Neutral400, fontSize = 12.sp)
        Text(description, color = Neutral500, fontSize = 10.sp)
        Text(
            if (resolved.isBlank()) "Not installed" else "Installed · ${java.io.File(resolved).name}",
            color = if (resolved.isBlank()) Neutral500 else White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Get desktop model from Azphalt Store ↗",
            color = Red500,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable { uriHandler.openUri("https://azphalt.store/browse?category=$storeCategory") }
                .padding(top = 2.dp),
        )
    }
}

/** Desktop mirror of the app side's `AiCapabilitySummary` (`app/.../ui/Sheets.kt`) — see its doc. */
@Composable
private fun DesktopAiCapabilitySummary(settings: AiSettings) {
    val cloudConfigured = settings.provider != AiProviderType.MLKIT && settings.keyFor(settings.provider).isNotBlank()
    val localTag = settings.agentModelPath.removePrefix("ollama:")
    val localMultimodal =
        localTag.startsWith("qwen3.5", ignoreCase = true) ||
            localTag.startsWith("gemma4", ignoreCase = true)
    fun installed(slot: String) =
        com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve(slot).isNotBlank()

    val rows = listOf(
        "Assistant brain" to (cloudConfigured || settings.agentModelPath.startsWith("ollama:") || settings.azpLlmId.isNotBlank()),
        "Frame vision" to (localMultimodal || installed("labelModelPath") || (cloudConfigured && settings.cloudVision)),
        "Transcription (Vosk)" to installed("speechModelPath"),
        "Text-to-speech (ONNX)" to installed("ttsModelPath"),
        "Local multimodal planner" to localMultimodal,
        "Audio highlight detection" to installed("audioEventModelPath"),
        "Speaker diarization" to installed("diarizeEmbedModelPath"),
        "Stem separation" to installed("stemModelPath"),
        "Speech denoise" to installed("denoiseModelPath"),
        "Video/music generation" to (settings.genKeys.values.any { it.isNotBlank() } || settings.leonardoKey.isNotBlank()),
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
    var azpLlmId by remember { mutableStateOf(current.azpLlmId) }
    var azpLlmKeys by remember { mutableStateOf(current.azpLlmKeys) }
    var azpLlmModels by remember { mutableStateOf(current.azpLlmModels) }
    var azpLlmTextId by remember { mutableStateOf(current.azpLlmTextId) }
    var azpSandboxToken by remember { mutableStateOf(current.azpSandboxToken) }
    var azpSandboxRepo by remember { mutableStateOf(current.azpSandboxRepo) }
    var azpSandboxInstalls by remember { mutableStateOf(current.azpSandboxInstalls) }

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

    var desktopProfile by remember {
        mutableStateOf<com.hereliesaz.guillotine.ai.agent.DeviceModelProfile?>(null)
    }
    var ollamaStatus by remember { mutableStateOf<DesktopOllama.Status?>(null) }
    var ollamaBusyModel by remember { mutableStateOf<String?>(null) }
    var ollamaMessage by remember { mutableStateOf<String?>(null) }

    fun refreshDesktopModelState() {
        scope.launch {
            val pair = withContext(Dispatchers.IO) {
                DesktopDeviceModelProfile.read() to DesktopOllama.status()
            }
            desktopProfile = pair.first
            ollamaStatus = pair.second
        }
    }

    LaunchedEffect(Unit) { refreshDesktopModelState() }

    fun installAndUseDesktopModel(selector: String) {
        val tag = selector.removePrefix("ollama:")
        if (tag.isBlank() || ollamaBusyModel != null) return
        scope.launch {
            ollamaBusyModel = tag
            ollamaMessage = "Preparing the dedicated router (${DesktopOllama.ROUTER_MODEL})…"
            val result = withContext(Dispatchers.IO) {
                val installed = if (DesktopOllama.ensureRunning()) DesktopOllama.listModels() else emptySet()
                val routerResult = if (DesktopOllama.ROUTER_MODEL in installed) {
                    Result.success(Unit)
                } else {
                    DesktopOllama.pull(DesktopOllama.ROUTER_MODEL)
                }
                if (routerResult.isFailure) routerResult else DesktopOllama.pull(tag)
            }
            ollamaBusyModel = null
            if (result.isSuccess) {
                agentModelPath = "ollama:$tag"
                provider = AiProviderType.LOCAL
                ollamaMessage = "Installed and selected $tag. Router: ${DesktopOllama.ROUTER_MODEL}."
                refreshDesktopModelState()
            } else {
                ollamaMessage = "Ollama install failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            }
        }
    }

    // Assemble settings from the current editable state — shared by Save and the .azp installer
    // (which folds newly-routed model paths into the visible fields first).
    fun buildSettings(): AiSettings = current.copy(
        provider = provider,
        keys = keys,
        models = models,
        leonardoKey = leonardoKey.trim(),
        leonardoModel = leonardoModel,
        cloudVision = cloudVision,
        azpLlmId = azpLlmId,
        azpLlmKeys = azpLlmKeys.filterValues { it.isNotEmpty() },
        azpLlmModels = azpLlmModels.filterValues { it.isNotEmpty() },
        azpLlmTextId = azpLlmTextId,
        azpSandboxToken = azpSandboxToken,
        azpSandboxRepo = azpSandboxRepo,
        azpSandboxInstalls = azpSandboxInstalls,
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
                        bytes, AzphaltTrust.FLAGSHIP_SIGNING_KEYS, dir, allowUntrusted,
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

    val tabs = listOf("AI Analyzer", "Generation", "Transcription", "Advanced")
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

                    AzpLlmSection(
                        extensionsDir = remember { java.io.File(com.hereliesaz.guillotine.desktop.platform.DesktopStorage.dataDir, "extensions") },
                        hostAppId = com.hereliesaz.guillotine.desktop.platform.DesktopPluginApplier.HOST_APP_ID,
                        selectedId = azpLlmId,
                        keys = azpLlmKeys,
                        models = azpLlmModels,
                        textId = azpLlmTextId,
                        sandboxToken = azpSandboxToken,
                        sandboxRepo = azpSandboxRepo,
                        sandboxInstalls = azpSandboxInstalls,
                        onSelect = { azpLlmId = it },
                        onKey = { id, k -> azpLlmKeys = azpLlmKeys + (id to k) },
                        onModel = { id, m -> azpLlmModels = azpLlmModels + (id to m) },
                        onTextId = { azpLlmTextId = it },
                        onSandboxToken = { azpSandboxToken = it },
                        onSandboxRepo = { azpSandboxRepo = it },
                        onSandboxInstalled = { id, json -> azpSandboxInstalls = azpSandboxInstalls + (id to json) },
                    )

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
                    
                    
                    
                    Text("AI assistant — desktop local model (optional)", color = Neutral400, fontSize = 12.sp)
                    Text(
                        "Desktop uses its own larger local-model catalog through Ollama; phone/tablet LiteRT " +
                            "weights are not offered here. Selecting a local model switches the analyzer to Local.",
                        color = Neutral500,
                        fontSize = 10.sp,
                    )

                    desktopProfile?.let { profile ->
                        Text(profile.shortSummary, color = White, fontSize = 10.sp)
                        Text(
                            "Fit is estimated locally from RAM, free storage, CPU cores, architecture and any " +
                                "accelerator Guillotine can identify. Ollama chooses the actual CPU/GPU backend.",
                            color = Neutral500,
                            fontSize = 10.sp,
                        )
                        val recommendations = remember(profile) {
                            DeviceModelAdvisor.advise(profile, RECOMMENDED_DESKTOP_ASSISTANT_MODELS)
                        }
                        val installed = ollamaStatus?.installedModels.orEmpty()
                        val ollamaAvailable = ollamaStatus?.executableAvailable == true
                        val routerInstalled = DesktopOllama.ROUTER_MODEL in installed
                        Text(
                            if (routerInstalled) {
                                "Dedicated router: ${DesktopOllama.ROUTER_MODEL} · ready"
                            } else {
                                "Dedicated router: ${DesktopOllama.ROUTER_MODEL} · installs with the first desktop-local planner"
                            },
                            color = if (routerInstalled) White else Neutral500,
                            fontSize = 10.sp,
                        )

                        recommendations.forEach { recommendation ->
                            val model = recommendation.model
                            val tag = model.fileName.removePrefix("ollama:")
                            val selected = agentModelPath == model.fileName
                            val isInstalled = tag in installed
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Neutral800)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(model.label, color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        Text("${model.sizeLabel} · ${model.license}", color = Neutral500, fontSize = 10.sp)
                                    }
                                    Text(
                                        recommendation.badge,
                                        color = when (recommendation.fit) {
                                            DeviceModelFit.BEST_FIT -> Red500
                                            DeviceModelFit.RECOMMENDED -> White
                                            DeviceModelFit.CAUTION -> Neutral400
                                            DeviceModelFit.NOT_RECOMMENDED -> Red500
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Text(model.abilities, color = Neutral400, fontSize = 10.sp)
                                Text(
                                    recommendation.reason,
                                    color = if (recommendation.fit == DeviceModelFit.NOT_RECOMMENDED) Red500 else Neutral500,
                                    fontSize = 10.sp,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    when {
                                        selected -> Text("In use", color = Red500, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                        ollamaBusyModel == tag -> Text("Installing…", color = Neutral400, fontSize = 10.sp)
                                        isInstalled -> Text(
                                            "Use",
                                            color = Red500,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.clickable {
                                                agentModelPath = model.fileName
                                                provider = AiProviderType.LOCAL
                                            },
                                        )
                                        ollamaAvailable -> Text(
                                            "Install & use",
                                            color = Red500,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.clickable { installAndUseDesktopModel(model.fileName) },
                                        )
                                        else -> Text(
                                            "Install Ollama ↗",
                                            color = Red500,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.clickable { uriHandler.openUri("https://ollama.com/download") },
                                        )
                                    }
                                    Text(
                                        "Model details ↗",
                                        color = Neutral400,
                                        fontSize = 10.sp,
                                        modifier = Modifier.clickable { uriHandler.openUri(model.repoUrl) },
                                    )
                                }
                            }
                        }
                    } ?: Text("Reading desktop hardware…", color = Neutral500, fontSize = 10.sp)

                    ollamaMessage?.let { Text(it, color = Neutral400, fontSize = 10.sp) }
                    ModelPathField(
                        value = agentModelPath,
                        hint = "advanced: ollama:<tag> or custom desktop local selector",
                        isDirectory = false,
                    ) { agentModelPath = it }
                    Text(
                        "Blank = use the selected cloud provider. Desktop-local models stay on this machine; " +
                            "Guillotine connects only to Ollama on 127.0.0.1.",
                        color = Neutral500,
                        fontSize = 10.sp,
                    )

                    Text(
                        "Desktop specialist models",
                        color = White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Desktop specialists use ONNX/Vosk packages from the local Azphalt registry — not the " +
                            "Android TFLite/sherpa bundles. Only models that have a working desktop executor are shown.",
                        color = Neutral500,
                        fontSize = 10.sp,
                    )

                    DesktopModelSlotStatus(
                        "Footage search / image labeling",
                        "labelModelPath",
                        "Desktop ONNX image classifier used by frame description, prompt-driven analysis and clip search.",
                        "onnx",
                    )
                    DesktopModelSlotStatus(
                        "Concept recognition / teach a specific thing",
                        "idEmbedModelPath",
                        "Desktop ONNX image embedder for learned visual concepts.",
                        "onnx",
                    )
                    DesktopModelSlotStatus(
                        "Face detection / tracking",
                        "faceDetectModelPath",
                        "Desktop ONNX face detector used for blur and auto-reframe.",
                        "onnx",
                    )
                    DesktopModelSlotStatus(
                        "Face recognition",
                        "faceEmbedModelPath",
                        "Desktop ONNX face embedding model for identifying a taught person.",
                        "onnx",
                    )
                    DesktopModelSlotStatus(
                        "Background segmentation",
                        "segModelPath",
                        "Desktop ONNX segmentation model for background replacement and portrait bokeh.",
                        "onnx",
                    )

                    Text("Image effects — desktop ONNX", color = Neutral400, fontSize = 12.sp)
                    listOf(
                        Triple("effect_depth", "Depth", "Monocular depth / parallax effects."),
                        Triple("effect_superres", "Super-resolution", "Frame upscaling / enhancement."),
                        Triple("effect_lowlight", "Low-light", "Dark-frame enhancement."),
                        Triple("effect_style", "Style", "Single-model style transformation."),
                    ).forEach { (slot, label, description) ->
                        val path = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve(slot)
                        Text(
                            "$label · " + if (path.isBlank()) "not installed" else "installed (${java.io.File(path).name})",
                            color = if (path.isBlank()) Neutral500 else White,
                            fontSize = 10.sp,
                        )
                        Text(description, color = Neutral500, fontSize = 10.sp)
                    }
                    Text(
                        "Browse desktop ONNX effects ↗",
                        color = Red500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { uriHandler.openUri("https://azphalt.store/browse?category=onnx") }
                            .padding(top = 2.dp),
                    )

                    DesktopModelSlotStatus(
                        "Audio-event highlights",
                        "audioEventModelPath",
                        "Desktop YAMNet ONNX classifier used to find applause, cheering, laughter and other highlight events.",
                        "onnx",
                    )
                    DesktopModelSlotStatus(
                        "Transcription / captions",
                        "speechModelPath",
                        "Desktop uses a Vosk model directory for local captions, animated captions and filler-word timing.",
                        "vosk",
                    )
                    DesktopModelSlotStatus(
                        "Text-to-speech / voiceover",
                        "ttsModelPath",
                        "Desktop uses a VITS/Piper-compatible ONNX voice model — not the Android sherpa TTS bundle.",
                        "onnx",
                    )

                    val localPlannerTag = agentModelPath.removePrefix("ollama:")
                    val localPlannerVision =
                        localPlannerTag.startsWith("qwen3.5", ignoreCase = true) ||
                            localPlannerTag.startsWith("gemma4", ignoreCase = true)
                    Text("Frame understanding", color = Neutral400, fontSize = 12.sp)
                    Text(
                        if (localPlannerVision) {
                            "The selected desktop planner ($localPlannerTag) can inspect the current frame locally through Ollama."
                        } else {
                            "Text-only planners use the installed ONNX image labeler for frame descriptions. " +
                                "Choose Qwen3.5 or Gemma 4 for richer local frame understanding."
                        },
                        color = if (localPlannerVision) White else Neutral500,
                        fontSize = 10.sp,
                    )

                    DesktopModelSlotStatus(
                        "Speaker diarization",
                        "diarizeEmbedModelPath",
                        "Desktop uses energy VAD plus an ONNX speaker embedder; it does not need Android's separate segmentation bundle.",
                        "onnx",
                    )
                    DesktopModelSlotStatus(
                        "Stem separation",
                        "stemModelPath",
                        "Desktop runs the Spleeter ONNX pair through ONNX Runtime for vocals + accompaniment.",
                        "onnx",
                    )

                    Text("Speech denoise", color = Neutral400, fontSize = 12.sp)
                    Text(
                        "No desktop denoiser is recommended yet: the existing GTCRN slot has no desktop executor. " +
                            "Guillotine will not pretend an installed model makes denoise_clip available.",
                        color = Neutral500,
                        fontSize = 10.sp,
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
                    Text("Generation", color = Neutral400, fontSize = 12.sp)
                    Text(
                        "Guillotine doesn't generate images. Object removal runs on-device (LaMa .azp).",
                        color = Neutral500, fontSize = 10.sp,
                    )
                    Text(com.hereliesaz.guillotine.ai.safety.ContentSafety.NOTICE, color = Neutral500, fontSize = 10.sp)
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

/**
 * **Azphalt model** — installed azphalt `kind: "llm"` packages, in addition to the providers above.
 *
 * - **Assistant brain:** packages that speak `openai-chat`. Picking one makes it drive the editor; "None"
 *   leaves the provider selection exactly as it was.
 * - **Private sandbox:** packages that speak `github-actions-runner` (every `sandbox-weights` package) are
 *   set up in the user's own private GitHub repository ([AzpLlmSandbox]) and can then take over the
 *   background text jobs. Too slow (minutes per call) to drive the editor.
 *
 * Each row shows the package's disclosure (`dataHandling`, or the sandbox/weights/licence line) before it
 * can be picked or set up, as azphalt `spec/llm.md` requires.
 */
@Composable
private fun AzpLlmSection(
    extensionsDir: java.io.File,
    hostAppId: String,
    selectedId: String,
    keys: Map<String, String>,
    models: Map<String, String>,
    textId: String,
    sandboxToken: String,
    sandboxRepo: String,
    sandboxInstalls: Map<String, String>,
    onSelect: (String) -> Unit,
    onKey: (String, String) -> Unit,
    onModel: (String, String) -> Unit,
    onTextId: (String) -> Unit,
    onSandboxToken: (String) -> Unit,
    onSandboxRepo: (String) -> Unit,
    onSandboxInstalled: (String, String) -> Unit,
) {
    val installed by androidx.compose.runtime.produceState<List<AzpLlm.Endpoint>?>(null, extensionsDir, hostAppId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AzpLlm.installed(extensionsDir, hostAppId) }
    }
    val list = installed ?: return
    if (list.isEmpty() && selectedId.isBlank() && textId.isBlank()) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sandboxStatus by remember { mutableStateOf<String?>(null) }
    var sandboxBusy by remember { mutableStateOf(false) }

    val chat = list.filter { it.supportsChat }
    if (chat.isNotEmpty() || selectedId.isNotBlank()) {
        Text("Azphalt model (optional)", color = Neutral400, fontSize = 12.sp)
        Text(
            "Language models installed from the Azphalt Store. Picking one makes it the assistant brain; it " +
                "gets the same text a cloud provider does, never your video or audio.",
            color = Neutral500, fontSize = 10.sp,
        )
        ProviderRow("None", "Use the provider selected above.", selected = selectedId.isBlank()) { onSelect("") }
        chat.forEach { e ->
            ProviderRow(e.name, e.disclosure, selected = selectedId == e.packageId) { onSelect(e.packageId) }
        }
        if (selectedId.isNotBlank() && chat.none { it.packageId == selectedId }) {
            Text(
                "The picked package is no longer installed, so the provider above is used.",
                color = Neutral500, fontSize = 10.sp,
            )
        }
        chat.firstOrNull { it.packageId == selectedId }?.let { chosen ->
            chosen.keyInput?.let { input ->
                val label = input.description.ifBlank { "${chosen.name} key" } + if (chosen.keyRequired) "" else " (optional)"
                KeyField(label, keys[chosen.packageId].orEmpty()) { onKey(chosen.packageId, it.trim()) }
                if (chosen.keyRequired && keys[chosen.packageId].isNullOrBlank()) {
                    Text("This model needs a key; until one is set the provider above is used.", color = Neutral500, fontSize = 10.sp)
                }
            }
            Text("Model", color = Neutral500, fontSize = 10.sp)
            OutlinedTextField(
                value = models[chosen.packageId].orEmpty(),
                onValueChange = { onModel(chosen.packageId, it.trim()) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Default: ${chosen.defaultModel}", color = Neutral500, fontSize = 12.sp) },
                textStyle = TextStyle(color = White, fontSize = 12.sp),
                singleLine = true,
            )
        }
    }

    val runner = list.filter { it.supportsRunner }
    if (runner.isEmpty() && textId.isBlank()) return
    Text("Azphalt models — private sandbox", color = Neutral400, fontSize = 12.sp)
    Text(
        "These run in your own private GitHub repository (GitHub Actions), not on this device and not on a " +
            "model operator's servers. A run takes minutes, so they take over background text jobs only, " +
            "never the assistant. Use a fine-grained token scoped to that one repository.",
        color = Neutral500, fontSize = 10.sp,
    )
    KeyField("GitHub token for the sandbox", sandboxToken) { onSandboxToken(it.trim()) }
    OutlinedTextField(
        value = sandboxRepo,
        onValueChange = { onSandboxRepo(it.trim()) },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Sandbox repository, owner/repo (created private if missing)", color = Neutral500, fontSize = 12.sp) },
        textStyle = TextStyle(color = White, fontSize = 12.sp),
        singleLine = true,
    )
    runner.forEach { e ->
        val setUp = sandboxInstalls[e.packageId]?.let { AzpLlmSandbox.Install.fromJson(it) }
        val current = setUp?.version == e.version
        Text(e.name, color = White, fontSize = 13.sp)
        Text(e.disclosure, color = Neutral500, fontSize = 11.sp)
        Text(
            "Setup needs token permissions: ${e.setupTokenPermissions.joinToString().ifBlank { "none declared" }}. " +
                "Downloads: ${e.fetches.size} pinned file(s)" +
                (if (e.secrets.isNotEmpty()) "; your key is stored as an encrypted Actions secret there." else "."),
            color = Neutral500, fontSize = 10.sp,
        )
        // A chat-capable package's key is entered above; a runner-only one needs its own field here.
        if (!e.supportsChat) {
            e.keyInput?.let { input ->
                val label = input.description.ifBlank { "${e.name} key" } + if (e.keyRequired) "" else " (optional)"
                KeyField(label, keys[e.packageId].orEmpty()) { onKey(e.packageId, it.trim()) }
            }
        }
        ActionText(
            when {
                sandboxBusy -> "Working…"
                current -> "Set up ✓ — set up again"
                setUp != null -> "Update in sandbox (v${setUp.version} → v${e.version})"
                else -> "Set up in sandbox"
            },
        ) {
            if (sandboxBusy) return@ActionText
            val parts = sandboxRepo.split('/')
            if (sandboxToken.isBlank() || parts.size != 2 || parts.any { it.isBlank() }) {
                sandboxStatus = "Enter a GitHub token and the sandbox repository as owner/repo first."
                return@ActionText
            }
            sandboxBusy = true
            sandboxStatus = "Setting up ${e.name}…"
            scope.launch {
                val outcome = runCatching {
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        AzpLlm.packageFile(extensionsDir, hostAppId, e.packageId)?.readBytes()
                    } ?: error("the package file is gone")
                    val inputs = e.keyInput?.let { k -> keys[e.packageId]?.takeIf { it.isNotBlank() }?.let { mapOf(k.id to it) } }.orEmpty()
                    AzpLlmSandbox.install(
                        AzpLlmSandbox.GitHub(sandboxToken), parts[0], parts[1], bytes, inputs,
                    ) { _, message -> sandboxStatus = "${e.name}: $message" }
                }
                sandboxBusy = false
                outcome.fold(
                    onSuccess = { r ->
                        onSandboxInstalled(e.packageId, r.install.toJson())
                        val setup = r.setup
                        sandboxStatus = if (setup == null || setup.completed) {
                            "${e.name} is set up in ${sandboxRepo}."
                        } else {
                            "${e.name} was committed, but its setup run failed: ${setup.message}"
                        }
                    },
                    onFailure = { sandboxStatus = "Setting up ${e.name} failed: ${it.message}" },
                )
            }
        }
    }
    sandboxStatus?.let { Text(it, color = Neutral400, fontSize = 11.sp) }

    val ready = runner.filter { sandboxInstalls.containsKey(it.packageId) }
    if (ready.isNotEmpty() || textId.isNotBlank()) {
        Text("Background text jobs", color = Neutral500, fontSize = 10.sp)
        ProviderRow("Assistant brain", "Use the assistant brain for them, as before.", selected = textId.isBlank()) { onTextId("") }
        ready.forEach { e ->
            ProviderRow(e.name, "Runs in ${sandboxRepo.ifBlank { "the sandbox" }}.", selected = textId == e.packageId) { onTextId(e.packageId) }
        }
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(
        label, color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable { onClick() },
    )
}
