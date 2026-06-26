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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.ImageGen
import com.hereliesaz.guillotine.ai.ModelCatalog
import com.hereliesaz.guillotine.ai.meta
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
    var speechModelPath by remember { mutableStateOf(current.speechModelPath) }
    var agentModelPath by remember { mutableStateOf(current.agentModelPath) }
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

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("AI Analyzer", "Image Gen", "Transcription", "Advanced")

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
                    Text("Analyzer — free on-device, or bring your own key", color = Neutral400, fontSize = 12.sp)

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

                    // Assistant brain: the command bar uses the selected provider's key when set,
                    // else this on-device LLM. Lets the AI drive the editor fully offline.
                    Text("AI assistant — on-device model (optional)", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = agentModelPath,
                        onValueChange = { agentModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("On-device LLM model path (.task)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text(
                        "Point to a Gemma/Hammer/Llama .task model to run the assistant offline with no key; " +
                            "otherwise it uses the selected provider's key above.",
                        color = Neutral500, fontSize = 10.sp,
                    )
                    Text(
                        "Download an on-device model  ↗",
                        color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickableText {
                            uriHandler.openUri("https://huggingface.co/litert-community")
                        },
                    )
                }
                1 -> { // Image Gen
                    Text("Image generation — Leonardo.ai (optional)", color = Neutral400, fontSize = 12.sp)
                    KeyField("Leonardo API key", leonardoKey) { leonardoKey = it }
                    Text("Default model", color = Neutral500, fontSize = 10.sp)
                    LeonardoModelDropdown(leonardoKey, leonardoModel) { leonardoModel = it }
                    Text("Leave the key blank to generate with free Pollinations.ai.", color = Neutral500, fontSize = 10.sp)
                    Text(
                        "Get a Leonardo API key  ↗",
                        color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickableText { uriHandler.openUri("https://app.leonardo.ai/api-access") },
                    )
                }
                2 -> { // Transcription
                    Text("Transcription", color = Neutral400, fontSize = 12.sp)
                    OutlinedTextField(
                        value = speechModelPath,
                        onValueChange = { speechModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("On-device speech model path (Vosk)", color = Neutral500, fontSize = 12.sp) },
                        textStyle = TextStyle(color = White, fontSize = 12.sp),
                        singleLine = true,
                    )
                    Text("Set a Vosk model folder for offline transcription; blank uses OpenAI Whisper.", color = Neutral500, fontSize = 10.sp)
                    Text(
                        "Download a Vosk model  ↗",
                        color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickableText { uriHandler.openUri("https://alphacephei.com/vosk/models") },
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
                    onSave(
                        AiSettings(
                            provider = provider,
                            keys = keys,
                            models = models,
                            leonardoKey = leonardoKey.trim(),
                            leonardoModel = leonardoModel,
                            speechModelPath = speechModelPath.trim(),
                            agentModelPath = agentModelPath.trim(),
                        ),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Color.Black),
            ) { Text("Save", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                    LoadingIndicator()
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
                    Text("Duration: ${"%.1f".format(totalDurationMs / 1000f)}s → Movies/Guillotine", color = Neutral500, fontSize = 11.sp)
                    errorMessage?.let { Text(it, color = Red500, fontSize = 11.sp) }
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
    var model by remember { mutableStateOf(leonardoModel.ifBlank { ImageGen.LeonardoDefaultModel }) }
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
    var live by remember(apiKey) { mutableStateOf<List<ImageGen.LeonardoModel>?>(null) }
    var loading by remember(apiKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val models = live?.takeIf { it.isNotEmpty() } ?: ImageGen.LeonardoModels
    val name = models.firstOrNull { it.id == selectedId }?.name
        ?: ImageGen.LeonardoModels.firstOrNull { it.id == selectedId }?.name ?: "Select a model"
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
