package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TextFont
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral900
import com.hereliesaz.guillotine.desktop.ui.theme.Red500
import com.hereliesaz.guillotine.desktop.ui.theme.White

/** How auto-captions are laid down: plain subtitle clips, or the animated word-pop style. */
enum class CaptionStyle { PLAIN, ANIMATED }

@Composable
fun ClipToolButtons(
    vm: EditorViewModel,
    state: EditorUiState,
    onTranscribe: (CaptionStyle) -> Unit,
) {
    val sel = state.selectedClips
    if (sel.isEmpty()) return
    val video = sel.firstOrNull { it.type == ClipType.VIDEO }
    val text = sel.firstOrNull { it.type == ClipType.TEXT }
    val audioTarget = sel.firstOrNull { it.type == ClipType.AUDIO && it.linkedClipId == null } ?: video
    val processable = video ?: sel.firstOrNull { it.type == ClipType.AUDIO }

    if (text != null) {
        TextToolButton(vm, text)
        KineticTypeToolButton(vm, text)
    }
    if (video != null) {
        BackgroundToolButton(vm, state, video)
        FiltersToolButton(vm, video)
    }
    if (audioTarget != null) AudioToolButton(vm, audioTarget)
    (video ?: processable ?: text)?.let { KeyframesToolButton(vm, it) }
    if (processable != null) {
        TranscribeToolButton(state, onTranscribe)
        if (processable.edits.isNotEmpty()) SplitToolButton(vm, processable)
    }
}

@Composable
private fun TextToolButton(vm: EditorViewModel, clip: TimelineClip) {
    var open by remember { mutableStateOf(false) }
    IconToolButton(Icons.Filled.TextFields, "Text & font", active = open) { open = !open }
    if (open) ToolPopup("Text", { open = false }) {
        OutlinedTextField(
            value = clip.text,
            onValueChange = { vm.setClipText(clip.id, it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Caption text…", color = Neutral500, fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
            minLines = 2,
        )
        Text("Font", color = Neutral400, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextFont.values().forEach { f ->
                Chip(label = f.label(), selected = clip.font == f) { vm.setClipFont(clip.id, f) }
            }
        }
        Text("Style", color = Neutral400, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.hereliesaz.guillotine.model.TEXT_STYLE_PRESETS.forEach { p ->
                // A preset gives a predictable look, so it also zeroes any prior pan/rotation —
                // otherwise a moved or rotated clip lands misaligned. The chip reads as active only
                // when the clip matches the preset exactly (including those zeroed fields).
                val selected = clip.font == p.font && clip.offsetX == 0f && clip.offsetY == p.offsetY &&
                    clip.scale == p.scale && clip.rotation == 0f
                Chip(label = p.label, selected = selected) {
                    vm.updateClip(clip.id) {
                        it.copy(font = p.font, offsetX = 0f, offsetY = p.offsetY, scale = p.scale, rotation = 0f)
                    }
                }
            }
        }
        Text("One-tap looks set font, size and placement — tweak further with the crop tool.", color = Neutral500, fontSize = 10.sp)
    }
}

/**
 * Kinetic-typography picker for a selected caption: lists installed motion `.azp` plugins and bakes the
 * chosen animation onto the caption (or clears it) via the shared
 * [com.hereliesaz.guillotine.ui.KineticTypographyPicker]. Hidden until a kinetic-typography plugin is
 * installed — the desktop half of the per-caption UI the az-motion feature was missing.
 */
@Composable
private fun KineticTypeToolButton(vm: EditorViewModel, clip: TimelineClip) {
    val motions = remember(clip.id) {
        com.hereliesaz.guillotine.ui.KineticTypographyPicker.listInstalled(
            java.io.File(com.hereliesaz.guillotine.desktop.platform.DesktopStorage.dataDir, "extensions"),
        )
    }
    if (motions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    IconToolButton(Icons.Filled.Animation, "Kinetic type", active = open) { open = !open }
    if (open) ToolPopup("Kinetic type", { open = false }) {
        Chip(label = "None", selected = false) {
            com.hereliesaz.guillotine.ui.KineticTypographyPicker.clear(vm, clip.id)
        }
        motions.forEach { m ->
            Chip(label = m.name, selected = false) {
                com.hereliesaz.guillotine.ui.KineticTypographyPicker.apply(vm, m, clip.id)
            }
        }
    }
}

@Composable
private fun BackgroundToolButton(vm: EditorViewModel, state: EditorUiState, clip: TimelineClip) {
    var open by remember { mutableStateOf(false) }
    IconToolButton(Icons.Filled.Layers, "Background removal", active = clip.filters.removeBackground) { open = !open }
    if (open) ToolPopup("Background", { open = false }) {
        val media = state.document.mediaFor(clip)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = clip.filters.removeBackground,
                onCheckedChange = { c -> vm.updateClipFilters(clip.id) { it.copy(removeBackground = c) } },
            )
            Text("Remove background (subject only)", color = Neutral400, fontSize = 12.sp)
        }
        if (clip.filters.removeBackground && media != null) {
            Text(
                "Background removal preview not available on desktop",
                color = Neutral500, fontSize = 11.sp,
            )
            Text(
                "On-device cutout. Put a clip on a lower track to composite behind it.",
                color = Neutral500, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun FiltersToolButton(vm: EditorViewModel, clip: TimelineClip) {
    var open by remember { mutableStateOf(false) }
    IconToolButton(Icons.Filled.Tune, "Filters", active = open) { open = !open }
    if (open) ToolPopup("Filters", { open = false }) {
        val f = clip.filters
        FilterSlider(vm, clip.id, "Brightness", f.brightness, 0f..2f, keyframe = KeyframeProperty.BRIGHTNESS) { v, ff -> ff.copy(brightness = v) }
        FilterSlider(vm, clip.id, "Contrast", f.contrast, 0f..2f, keyframe = KeyframeProperty.CONTRAST) { v, ff -> ff.copy(contrast = v) }
        FilterSlider(vm, clip.id, "Saturation", f.saturation, 0f..2f, keyframe = KeyframeProperty.SATURATION) { v, ff -> ff.copy(saturation = v) }
        FilterSlider(vm, clip.id, "Sepia", f.sepia, 0f..100f, "%", keyframe = KeyframeProperty.SEPIA) { v, ff -> ff.copy(sepia = v) }
        FilterSlider(vm, clip.id, "Hue", f.hueRotate, 0f..360f, "°", keyframe = KeyframeProperty.HUE) { v, ff -> ff.copy(hueRotate = v) }
        FilterSlider(vm, clip.id, "Invert", f.invert, 0f..100f, "%") { v, ff -> ff.copy(invert = v) }
        FilterSlider(vm, clip.id, "Grayscale", f.grayscale, 0f..100f, "%") { v, ff -> ff.copy(grayscale = v) }
        FilterSlider(vm, clip.id, "Blur", f.blur, 0f..20f, "px") { v, ff -> ff.copy(blur = v) }
        PresetRow(vm, clip.id)
    }
}

@Composable
private fun AudioToolButton(vm: EditorViewModel, clip: TimelineClip) {
    var open by remember { mutableStateOf(false) }
    IconToolButton(Icons.Filled.VolumeUp, "Audio", active = open) { open = !open }
    if (open) ToolPopup("Audio", { open = false }) {
        val f = clip.filters
        FilterSlider(vm, clip.id, "Volume", f.volume, 0f..2f, keyframe = KeyframeProperty.VOLUME) { v, ff -> ff.copy(volume = v) }
        FilterSlider(vm, clip.id, "Pan", f.pan, -1f..1f, keyframe = KeyframeProperty.PAN) { v, ff -> ff.copy(pan = v) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = f.normalize, onCheckedChange = { c -> vm.updateClipFilters(clip.id) { it.copy(normalize = c) } })
            Text("Normalize audio", color = Neutral400, fontSize = 12.sp)
        }
    }
}

@Composable
private fun KeyframesToolButton(vm: EditorViewModel, clip: TimelineClip) {
    var open by remember { mutableStateOf(false) }
    IconToolButton(Icons.Filled.Timeline, "Keyframes", active = open) { open = !open }
    if (open) ToolPopup("Keyframes", { open = false }) {
        var property by remember { mutableStateOf(KeyframeProperty.OPACITY) }
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

        val range = property.uiRange
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
}

/** One-tap auto-captions. On-device transcription (Vosk) → caption clips, in the chosen style. */
@Composable
private fun TranscribeToolButton(state: EditorUiState, onTranscribe: (CaptionStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconToolButton(Icons.Filled.Subtitles, "Auto-captions", enabled = !state.isProcessing) { open = !open }
    if (open) ToolPopup("Auto-captions", { open = false }) {
        Text("On-device speech-to-text — your audio never leaves the device.", color = Neutral500, fontSize = 10.sp)
        CaptionStyleRow("Captions", "Clean subtitle clips, timed to the speech") {
            onTranscribe(CaptionStyle.PLAIN); open = false
        }
        CaptionStyleRow("Animated", "Word-pop / karaoke style — each syllable grows as it's spoken") {
            onTranscribe(CaptionStyle.ANIMATED); open = false
        }
    }
}

@Composable
private fun CaptionStyleRow(label: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(Neutral800)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = Neutral400, fontSize = 10.sp)
    }
}

@Composable
private fun SplitToolButton(vm: EditorViewModel, clip: TimelineClip) {
    IconToolButton(Icons.Filled.CallSplit, "Split into ${clip.edits.size} clips") { vm.segmentClip(clip.id) }
}

@Composable
private fun ToolPopup(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            color = Neutral900,
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Neutral800),
            modifier = Modifier.width(300.dp),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close", tint = Neutral400,
                        modifier = Modifier.size(18.dp).clickable(onClick = onDismiss),
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun FilterSlider(
    vm: EditorViewModel,
    clipId: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    keyframe: KeyframeProperty? = null,
    apply: (Float, ClipFilters) -> ClipFilters,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Neutral500, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${"%.2f".format(value)}$suffix", color = Neutral400, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (keyframe != null) {
                    Icon(
                        Icons.Filled.Diamond,
                        contentDescription = "Keyframe $label at playhead",
                        tint = Red500,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(16.dp)
                            .clickable { vm.keyframeSettingAtPlayhead(clipId, keyframe) },
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { v -> vm.updateClipFilters(clipId) { apply(v, it) } },
            valueRange = range,
        )
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
private fun PresetRow(vm: EditorViewModel, clipId: String) {
    Text("Presets", color = Red500, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Vintage", false) { vm.updateClipFilters(clipId) { it.copy(sepia = 80f, contrast = 1.2f, brightness = 0.9f, blur = 1f, grayscale = 20f) } }
        Chip("Noir", false) { vm.updateClipFilters(clipId) { it.copy(grayscale = 100f, contrast = 1.4f, brightness = 1.1f) } }
        Chip("Reset", false) { vm.updateClipFilters(clipId) { ClipFilters() } }
    }
}
