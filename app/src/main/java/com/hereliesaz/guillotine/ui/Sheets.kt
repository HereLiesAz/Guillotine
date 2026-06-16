package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.GlobalSettings
import com.hereliesaz.guillotine.model.Quality
import com.hereliesaz.guillotine.ui.theme.Neutral400
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
fun SettingsSheet(current: AiSettings, onSave: (AiSettings) -> Unit, onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf(current.provider) }
    var geminiKey by remember { mutableStateOf(current.geminiKey) }
    var openaiKey by remember { mutableStateOf(current.openaiKey) }
    var anthropicKey by remember { mutableStateOf(current.anthropicKey) }
    Dialog(onDismissRequest = onDismiss) {
        SheetCard {
            Text("Settings", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Analyzer (bring your own key)", color = Neutral400, fontSize = 12.sp)
            ProviderRow("Local (free, on-device — cuts silences)", provider == AiProviderType.LOCAL) { provider = AiProviderType.LOCAL }
            ProviderRow("Gemini (video-native)", provider == AiProviderType.GEMINI) { provider = AiProviderType.GEMINI }
            ProviderRow("OpenAI (gpt-4o frames / Whisper)", provider == AiProviderType.OPENAI) { provider = AiProviderType.OPENAI }
            ProviderRow("Anthropic (Claude, video frames only)", provider == AiProviderType.ANTHROPIC) { provider = AiProviderType.ANTHROPIC }
            when (provider) {
                AiProviderType.GEMINI -> KeyField("Gemini API key", geminiKey) { geminiKey = it }
                AiProviderType.OPENAI -> KeyField("OpenAI API key", openaiKey) { openaiKey = it }
                AiProviderType.ANTHROPIC -> KeyField("Anthropic API key", anthropicKey) { anthropicKey = it }
                AiProviderType.LOCAL -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { onSave(AiSettings(provider, geminiKey, openaiKey, anthropicKey)) },
                    colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Color.Black),
                ) { Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
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
    Text("Stored only on this device.", color = Neutral500, fontSize = 10.sp)
}

@Composable
private fun ProviderRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = Neutral400, fontSize = 12.sp)
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

@Composable
fun GenerateSheet(onGenerate: (url: String, name: String) -> Unit, onDismiss: () -> Unit) {
    var prompt by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        SheetCard {
            Text("Generate image (free)", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Pollinations.ai — no key required.", color = Neutral500, fontSize = 11.sp)
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Describe the image…", color = Neutral500, fontSize = 12.sp) },
                textStyle = TextStyle(color = White, fontSize = 12.sp),
                minLines = 2,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", color = Neutral400, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp).clickableText(onDismiss))
                Button(
                    enabled = prompt.isNotBlank(),
                    onClick = {
                        val encoded = java.net.URLEncoder.encode(prompt.trim(), "UTF-8")
                        val url = "https://image.pollinations.ai/prompt/$encoded?width=1280&height=720&nologo=true"
                        onGenerate(url, "Generated: ${prompt.take(20)}")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red500),
                ) { Text("Generate", fontSize = 12.sp, color = White) }
            }
        }
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
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AspectRatio.values().forEach { ar ->
                    SettingChip(ar.label(), current.aspectRatio == ar) { onChange(current.copy(aspectRatio = ar)) }
                }
            }

            Text("Quality", color = Neutral400, fontSize = 12.sp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
