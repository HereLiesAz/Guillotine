package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.media.SubjectSegmenter
import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TextFont
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White

/**
 * Context-sensitive per-clip tool buttons, shown inline in the editor tool strip
 * (this replaces the old side Inspector panel). Each button opens a small popup
 * holding the detailed controls for the selected clip. Nothing shows unless exactly
 * one clip is selected.
 */
@Composable
fun ClipToolButtons(
    vm: EditorViewModel,
    state: EditorUiState,
    onTranscribe: () -> Unit,
) {
    val sel = state.selectedClips
    if (sel.isEmpty()) return
    // Pick a representative clip per type, so a grouped video+audio pair shows both the
    // picture tools and the audio tool without needing to ungroup.
    val video = sel.firstOrNull { it.type == ClipType.VIDEO }
    val text = sel.firstOrNull { it.type == ClipType.TEXT }
    // Audio editing targets an independent audio clip; if the only audio is a video's linked
    // shadow, route to the video clip (which actually carries that sound).
    val audioTarget = sel.firstOrNull { it.type == ClipType.AUDIO && it.linkedClipId == null } ?: video
    val processable = video ?: sel.firstOrNull { it.type == ClipType.AUDIO }

    if (text != null) TextToolButton(vm, text)
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

// ---- individual tool buttons + their popups ----

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
        Text("Size & placement: use the crop tool on the preview.", color = Neutral500, fontSize = 10.sp)
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
            CutoutPreview(media.uri, media.kind, clip.trimStartMs)
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

@Composable
private fun TranscribeToolButton(state: EditorUiState, onTranscribe: () -> Unit) {
    IconToolButton(Icons.Filled.Subtitles, "Transcribe → captions", enabled = !state.isProcessing) { onTranscribe() }
}

@Composable
private fun SplitToolButton(vm: EditorViewModel, clip: TimelineClip) {
    IconToolButton(Icons.Filled.CallSplit, "Split into ${clip.edits.size} clips") { vm.segmentClip(clip.id) }
}

// ---- shared popup shell + small building blocks ----

/** A small floating panel anchored under the tool strip, with a title bar and a close affordance. */
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
private fun CutoutPreview(uri: String, kind: com.hereliesaz.guillotine.model.MediaKind, atMs: Long) {
    val context = LocalContext.current
    val cut by produceState<ImageBitmap?>(null, uri, atMs) {
        value = SubjectSegmenter.cutout(context, uri, kind, atMs)?.asImageBitmap()
    }
    val bitmap = cut
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Foreground cutout",
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text("Generating cutout…", color = Neutral500, fontSize = 11.sp)
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
                // Keyframeable settings get a diamond: record the current value at the playhead.
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
