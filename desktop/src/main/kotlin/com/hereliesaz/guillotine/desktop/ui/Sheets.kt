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
fun SettingsScreen(current: AiSettings, onSave: (AiSettings) -> Unit, onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf(current.provider) }
    var keys by remember { mutableStateOf(current.keys) }
    var models by remember { mutableStateOf(current.models) }
    var leonardoKey by remember { mutableStateOf(current.leonardoKey) }
    var leonardoModel by remember { mutableStateOf(current.leonardoModel) }
    var speechModelPath by remember { mutableStateOf(current.speechModelPath) }
    var agentModelPath by remember { mutableStateOf(current.agentModelPath) }
    var labelModelPath by remember { mutableStateOf(current.labelModelPath) }
    var audioEventModelPath by remember { mutableStateOf(current.audioEventModelPath) }
    var faceDetectModelPath by remember { mutableStateOf(current.faceDetectModelPath) }
    var idEmbedModelPath by remember { mutableStateOf(current.idEmbedModelPath) }
    var segModelPath by remember { mutableStateOf(current.segModelPath) }
    var frameAnalysisCacheSize by remember { mutableIntStateOf(current.frameAnalysisCacheSize) }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    // Assemble settings from the current editable state — shared by Save and the .azp installer
    // (which folds newly-routed model paths into the visible fields first).
    fun buildSettings(): AiSettings = AiSettings(
        provider = provider,
        keys = keys,
        models = models,
        leonardoKey = leonardoKey.trim(),
        leonardoModel = leonardoModel,
        speechModelPath = speechModelPath.trim(),
        agentModelPath = agentModelPath.trim(),
        labelModelPath = labelModelPath.trim(),
        audioEventModelPath = audioEventModelPath.trim(),
        faceDetectModelPath = faceDetectModelPath.trim(),
        idEmbedModelPath = idEmbedModelPath.trim(),
        segModelPath = segModelPath.trim(),
        frameAnalysisCacheSize = frameAnalysisCacheSize,
    )

    // --- Install an AI model from an azphalt .azp package ---------------------------------------
    var azpBusy by remember { mutableStateOf(false) }
    var azpStatus by remember { mutableStateOf<String?>(null) }
    var azpUntrusted by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }

    fun applyInstalled(result: com.hereliesaz.guillotine.azphalt.AzpModelInstall.Result) {
        val slot = com.hereliesaz.guillotine.azphalt.AzpModelInstaller.ModelSlot
        result.installed.forEach { inst ->
            when (inst.slot) {
                slot.SPEECH_TO_TEXT -> speechModelPath = inst.path
                slot.IMAGE_LABELING -> labelModelPath = inst.path
                slot.AUDIO_EVENT -> audioEventModelPath = inst.path
                slot.FACE_DETECTION -> faceDetectModelPath = inst.path
                slot.IMAGE_EMBEDDING -> idEmbedModelPath = inst.path
                slot.SUBJECT_SEGMENTATION -> segModelPath = inst.path
                else -> Unit // FACE_EMBEDDING has no desktop field yet
            }
        }
        onSave(buildSettings())
    }

    fun installAzp(bytes: ByteArray, allowUntrusted: Boolean) {
        scope.launch {
            azpBusy = true
            azpStatus = "Reading package…"
            try {
                val dir = java.io.File(com.hereliesaz.guillotine.desktop.platform.DesktopStorage.dataDir, "azp-models")
                val result = withContext(Dispatchers.IO) {
                    com.hereliesaz.guillotine.azphalt.AzpModelInstall.install(bytes, emptySet(), dir, allowUntrusted) { p ->
                        val pct = p.bytesTotal?.takeIf { it > 0 }?.let { p.bytesDone * 100 / it }
                        azpStatus = when (p.phase) {
                            com.hereliesaz.guillotine.azphalt.AzpModelInstall.Phase.DOWNLOADING ->
                                "Downloading ${p.model.filename}${pct?.let { " — $it%" } ?: ""}…"
                            com.hereliesaz.guillotine.azphalt.AzpModelInstall.Phase.VERIFYING -> "Verifying ${p.model.filename}…"
                            com.hereliesaz.guillotine.azphalt.AzpModelInstall.Phase.WRITING -> "Writing ${p.model.filename}…"
                        }
                    }
                }
                applyInstalled(result)
                val routed = result.installed.count { it.slot != null }
                azpStatus = "Installed ${result.installed.size} model(s) from ${result.packageId}" +
                    (if (result.trust.trusted) " (trusted)" else " (unsigned)") +
                    if (routed < result.installed.size) " — ${result.installed.size - routed} need manual wiring." else "."
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

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("AI Analyzer", "Image Gen", "Transcription", "Advanced")

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

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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

                    Text("Footage search (on-device)", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = labelModelPath,
                        onValueChange = { labelModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Image-classifier model (.onnx)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text(
                        "Point at an ONNX ImageNet classifier (e.g. MobileNet/ResNet) to enable on-device " +
                            "footage search (\"find clips with a dog\"). Put a labels file — one class per " +
                            "line — next to it, named \"<model>.txt\", \"labels.txt\", or " +
                            "\"imagenet_classes.txt\". Blank = footage search off. Other on-device vision " +
                            "tools use a cloud provider above.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Audio highlights (on-device)", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = audioEventModelPath,
                        onValueChange = { audioEventModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("YAMNet audio-event model (.onnx)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text(
                        "Point at a YAMNet ONNX export to enable on-device highlight detection " +
                            "(\"find the best moments\" — applause, cheering, laughter, music). Blank = off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Face detection (on-device)", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = faceDetectModelPath,
                        onValueChange = { faceDetectModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Face-detector model (.onnx, UltraFace-style)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text(
                        "Point at an UltraFace-style ONNX face detector to enable on-device " +
                            "auto-reframe (punch in and pan to follow the main face) and face blur. Blank = off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Concept matching (on-device)", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = idEmbedModelPath,
                        onValueChange = { idEmbedModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Image-embedding model (.onnx)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text(
                        "Point at an ONNX image embedder (a classifier with its head removed → a feature " +
                            "vector) to teach and find things (add_reference / analyze_clip_with_concept). Blank = off.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Background removal (on-device)", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = segModelPath,
                        onValueChange = { segModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Subject-segmentation model (.onnx)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text(
                        "Point at an ONNX subject segmenter (selfie-seg / U2Net / MODNet) to matte the " +
                            "subject for replace_background (applied on export). Blank = off.",
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
                    Text("Transcription", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = speechModelPath,
                        onValueChange = { speechModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Vosk model folder path", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text("Set a Vosk model folder for offline transcription; blank uses OpenAI Whisper.", color = Neutral500, fontSize = 10.sp)
                    Text(
                        "Download a Vosk model  ↗",
                        color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { uriHandler.openUri("https://alphacephei.com/vosk/models") },
                    )
                }
                3 -> {
                    Text("MCP server", color = Neutral400, fontSize = 12.sp)
                    Text(
                        "The MCP server runs on port 7865. External AI tools can connect using " +
                            "the bearer token configured in the desktop key store.",
                        color = Neutral500, fontSize = 10.sp,
                    )

                    Text("Install AI model (.azp)", color = Neutral400, fontSize = 12.sp)
                    Text(
                        if (azpBusy) "Installing…" else "Install",
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
    isExporting: Boolean,
    progress: Float,
    doneMessage: String?,
    errorMessage: String?,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("guillotine_export") }
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
                    Text("Output: H.264 + AAC in MP4, 1920x1080 @ 30fps", color = Neutral500, fontSize = 11.sp)
                    errorMessage?.let { Text(it, color = Red500, fontSize = 11.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cancel", color = Neutral400, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp).clickable(onClick = onDismiss))
                        Button(
                            onClick = { onStart(name) },
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
