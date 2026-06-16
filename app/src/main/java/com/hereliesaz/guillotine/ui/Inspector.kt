package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White

/** The left/side inspector. Shows batch, single-clip, or global settings. */
@Composable
fun Inspector(
    vm: EditorViewModel,
    state: EditorUiState,
    onAnalyze: () -> Unit,
    onTranscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedClips
    Column(
        modifier
            .background(Neutral900)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            selected.size > 1 -> BatchSection(vm, state, onAnalyze)
            selected.size == 1 -> ClipSection(vm, state, onAnalyze, onTranscribe)
            else -> EmptyHint()
        }
    }
}

@Composable
private fun BatchSection(vm: EditorViewModel, state: EditorUiState, onAnalyze: () -> Unit) {
    SectionTitle("Batch (${state.selectedClips.size} clips)")
    PromptField(
        value = state.selectedClips.firstOrNull()?.prompt ?: "",
        onChange = { vm.setPromptForSelected(it) },
    )
    AnalyzeButton(state, onAnalyze)
    state.error?.let { ErrorBox(it) }
}

@Composable
private fun ClipSection(vm: EditorViewModel, state: EditorUiState, onAnalyze: () -> Unit, onTranscribe: () -> Unit) {
    val clip = state.selectedClips.first()

    // Text/caption clips just edit their text.
    if (clip.type == ClipType.TEXT) {
        SectionTitle("Text")
        OutlinedTextField(
            value = clip.text,
            onValueChange = { vm.setClipText(clip.id, it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Caption text…", color = Neutral500, fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
            minLines = 2,
        )
        return
    }

    SectionTitle(if (clip.type == ClipType.VIDEO) "Video clip" else "Audio clip")
    Text(clip.id, color = Neutral500, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    PromptField(value = clip.prompt, onChange = { vm.setPromptForSelected(it) })
    AnalyzeButton(state, onAnalyze)
    state.error?.let { ErrorBox(it) }

    // Whisper → generates grouped caption/text clips on the track above this clip.
    Button(
        onClick = onTranscribe,
        enabled = !state.isProcessing,
        colors = ButtonDefaults.buttonColors(containerColor = Neutral800),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Transcribe → captions", color = White, fontSize = 12.sp)
    }

    // After analysis the clip carries keep/remove ranges (clip-cutting, applied on export).
    // Offer segmentation too: split those ranges into discrete, rearrangeable clips.
    if (clip.edits.isNotEmpty()) {
        Button(
            onClick = { vm.segmentSelectedClip() },
            colors = ButtonDefaults.buttonColors(containerColor = Neutral800),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Split into ${clip.edits.size} clips", color = White, fontSize = 12.sp)
        }
    }

    if (clip.type == ClipType.VIDEO) {
        Divider()
        SectionTitle("Filters")
        val f = clip.filters
        FilterSlider(vm, "Brightness", f.brightness, 0f..2f) { v, ff -> ff.copy(brightness = v) }
        FilterSlider(vm, "Contrast", f.contrast, 0f..2f) { v, ff -> ff.copy(contrast = v) }
        FilterSlider(vm, "Saturation", f.saturation, 0f..2f) { v, ff -> ff.copy(saturation = v) }
        FilterSlider(vm, "Sepia", f.sepia, 0f..100f, "%") { v, ff -> ff.copy(sepia = v) }
        FilterSlider(vm, "Hue", f.hueRotate, 0f..360f, "°") { v, ff -> ff.copy(hueRotate = v) }
        FilterSlider(vm, "Invert", f.invert, 0f..100f, "%") { v, ff -> ff.copy(invert = v) }
        FilterSlider(vm, "Grayscale", f.grayscale, 0f..100f, "%") { v, ff -> ff.copy(grayscale = v) }
        FilterSlider(vm, "Blur", f.blur, 0f..20f, "px") { v, ff -> ff.copy(blur = v) }
        PresetRow(vm)
    }

    Divider()
    SectionTitle("Audio")
    val f = clip.filters
    FilterSlider(vm, "Volume", f.volume, 0f..2f) { v, ff -> ff.copy(volume = v) }
    FilterSlider(vm, "Pan", f.pan, -1f..1f) { v, ff -> ff.copy(pan = v) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = f.normalize, onCheckedChange = { c -> vm.updateSelectedFilters { it.copy(normalize = c) } })
        Text("Normalize audio", color = Neutral400, fontSize = 12.sp)
    }

    Divider()
    KeyframesSection(vm, state)
}

@Composable
private fun EmptyHint() {
    SectionTitle("Nothing selected")
    Text(
        "Select a clip to edit it. Project-wide options (aspect ratio, quality) live in the " +
            "▸ menu under “Project settings”.",
        color = Neutral500, fontSize = 12.sp,
    )
}

@Composable
private fun KeyframesSection(vm: EditorViewModel, state: EditorUiState) {
    val clip = state.selectedClips.firstOrNull() ?: return
    var property by remember { mutableStateOf(KeyframeProperty.OPACITY) }
    SectionTitle("Keyframes")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyframeProperty.values().forEach { p ->
            Chip(label = p.name.lowercase().replaceFirstChar { it.uppercase() }, selected = property == p) { property = p }
        }
    }
    Button(
        onClick = { vm.addKeyframe(clip.id, property) },
        colors = ButtonDefaults.buttonColors(containerColor = Neutral800),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = White, modifier = Modifier.size(14.dp))
        Text("  Add keyframe", color = White, fontSize = 12.sp)
    }

    val range = when (property) {
        KeyframeProperty.OPACITY -> 0f..1f
        KeyframeProperty.SCALE -> 0f..3f
        KeyframeProperty.VOLUME -> 0f..2f
    }
    clip.keyframes.filter { it.property == property }.sortedBy { it.timeMs }.forEach { kf ->
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .border(1.dp, Neutral800, RoundedCornerShape(4.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("t=${"%.2f".format(kf.timeMs / 1000f)}s", color = Neutral400, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Icon(
                    Icons.Filled.Close, contentDescription = "Remove keyframe", tint = Neutral500,
                    modifier = Modifier.size(16.dp).clickable { vm.removeKeyframe(clip.id, kf.id) },
                )
            }
            Slider(
                value = kf.value.coerceIn(range.start, range.endInclusive),
                onValueChange = { v -> vm.updateKeyframe(clip.id, kf.id) { it.copy(value = v) } },
                valueRange = range,
            )
            Text("Easing", color = Neutral500, fontSize = 10.sp)
            CurveEditor(value = kf.easing, onChange = { e -> vm.updateKeyframe(clip.id, kf.id) { it.copy(easing = e) } })
        }
    }
}

// ---- small building blocks ----

@Composable private fun SectionTitle(text: String) =
    Text(text, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)

@Composable private fun Divider() =
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        androidx.compose.material3.HorizontalDivider(color = Neutral800)
    }

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("e.g. 'Cut out silences'", color = Neutral500, fontSize = 12.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
        minLines = 2,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnalyzeButton(state: EditorUiState, onAnalyze: () -> Unit) {
    Button(
        onClick = onAnalyze,
        enabled = !state.isProcessing,
        colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Color.Black),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.isProcessing) {
            LoadingIndicator(modifier = Modifier.size(18.dp))
            Text("  Processing…", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        } else {
            Text("Generate edits", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    Text(
        message, color = Red500, fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Red500.copy(alpha = 0.1f))
            .border(1.dp, Red500.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .padding(8.dp),
    )
}

@Composable
private fun FilterSlider(
    vm: EditorViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    apply: (Float, ClipFilters) -> ClipFilters,
) {
    LabeledSlider(label, value, range, suffix) { v -> vm.updateSelectedFilters { apply(v, it) } }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Neutral500, fontSize = 10.sp)
            Text("${"%.2f".format(value)}$suffix", color = Neutral400, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun PresetRow(vm: EditorViewModel) {
    Text("Presets", color = Red500, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Vintage", false) { vm.updateSelectedFilters { it.copy(sepia = 80f, contrast = 1.2f, brightness = 0.9f, blur = 1f, grayscale = 20f) } }
        Chip("Noir", false) { vm.updateSelectedFilters { it.copy(grayscale = 100f, contrast = 1.4f, brightness = 1.1f) } }
        Chip("Reset", false) { vm.updateSelectedFilters { ClipFilters() } }
    }
}

