package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.media.MediaPreview
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.Keyframe
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.theme.Neutral300
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral600
import com.hereliesaz.guillotine.ui.theme.Neutral700
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral850
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Neutral950
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White
import kotlin.math.roundToInt

private val HEADER_WIDTH = 56.dp
private val RULER_HEIGHT = 24.dp

/**
 * Full timeline panel: scrollable multi-track lanes with playhead. The editing
 * tools (select/split/zoom/etc.) and the AI prompt live in the shared
 * [EditorToolStrip] so they are available in both the compact and wide layouts.
 */
@Composable
fun TimelinePanel(
    vm: EditorViewModel,
    state: EditorUiState,
    onImportToTrack: (String) -> Unit,
    onCreateOnTrack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(Neutral900)) {
        TimelineLanes(vm, state, onImportToTrack, onCreateOnTrack, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun TimelineLanes(
    vm: EditorViewModel,
    state: EditorUiState,
    onImportToTrack: (String) -> Unit,
    onCreateOnTrack: (String) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val pps = state.pixelsPerSecond
    val scroll = rememberScrollState()

    fun msToDp(ms: Long) = with(density) { (ms / 1000f * pps).toDp() }
    val totalMs = state.document.totalDurationMs
    val contentWidth = msToDp(totalMs) + 400.dp

    // Pinch-to-zoom (touch) + Ctrl+scroll zoom (mouse/trackpad). These read the LIVE
    // pixels-per-second from the view model (not the captured `state`, which would be
    // stale inside the one-shot pointerInput) so the zoom actually accumulates — pinching
    // in/out changes how much of the timeline (how many frames) is on screen.
    val zoomModifier = Modifier
        .pointerInput(Unit) {
            // Anisotropic pinch: horizontal finger spread changes width (pixels/second),
            // vertical spread changes track height. Tracked per-axis from the two pointers.
            awaitPointerEventScope {
                while (true) {
                    // Initial pass: claim two-finger pinch before the nested scroll/clip
                    // children can consume the drag (that's why vertical zoom didn't work).
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pts = event.changes.filter { it.pressed }
                    if (pts.size >= 2) {
                        val a = pts[0]
                        val b = pts[1]
                        val curH = kotlin.math.abs(a.position.x - b.position.x)
                        val curV = kotlin.math.abs(a.position.y - b.position.y)
                        val prevH = kotlin.math.abs(a.previousPosition.x - b.previousPosition.x)
                        val prevV = kotlin.math.abs(a.previousPosition.y - b.previousPosition.y)
                        var acted = false
                        if (prevH > 1f && curH > 1f && curH != prevH) {
                            vm.setZoom(vm.uiState.value.pixelsPerSecond * (curH / prevH)); acted = true
                        }
                        if (prevV > 1f && curV > 1f && curV != prevV) {
                            vm.scaleTrackHeight(curV / prevV); acted = true
                        }
                        if (acted) pts.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) vm.setZoom(vm.uiState.value.pixelsPerSecond * if (dy > 0) 0.9f else 1.1f)
                    }
                }
            }
        }

    // Vertical scroll shared by the header column and the lanes, so they move together
    // while the ruler stays frozen at the top.
    val vScroll = rememberScrollState()
    Row(modifier.fillMaxSize().then(zoomModifier)) {
        // Track-header column: ruler spacer fixed, header list scrolls with the lanes.
        Column(Modifier.width(HEADER_WIDTH).fillMaxHeight().background(Neutral900)) {
            Box(Modifier.height(RULER_HEIGHT).fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                state.document.videoTracks.forEach { TrackHeader(vm, state, it, ClipType.VIDEO, onImportToTrack, onCreateOnTrack) }
                state.document.audioTracks.forEach { TrackHeader(vm, state, it, ClipType.AUDIO, onImportToTrack, onCreateOnTrack) }
            }
        }
        // Horizontally-scrollable content; ruler fixed at top, lanes scroll vertically.
        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(scroll)
                .width(contentWidth)
                // Tap anywhere on the timeline surface (ruler, gaps, below the tracks) to
                // move the playhead there. Clips sit on top and handle their own taps.
                .pointerInput(pps) {
                    detectTapGestures { offset ->
                        vm.clearSelection()
                        vm.seekTo((offset.x / pps * 1000f).toLong())
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                Ruler(totalMs, pps, contentWidth)
                Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                    state.document.videoTracks.forEach { trackId ->
                        Lane(vm, state, trackId, pps) { msToDp(it) }
                    }
                    state.document.audioTracks.forEach { trackId ->
                        Lane(vm, state, trackId, pps) { msToDp(it) }
                    }
                }
            }
            // Playhead overlay spanning the visible lanes.
            Box(
                Modifier
                    .offset(x = msToDp(state.currentTimeMs))
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Red500),
            )
        }
    }
}

/**
 * Track identifier + whole-track controls. Tapping the header opens a popup with mute /
 * disable, a volume (audio/video) or opacity (video/text) slider, and add-clip
 * (import/create) actions for that track.
 */
@Composable
private fun TrackHeader(
    vm: EditorViewModel,
    state: EditorUiState,
    trackId: String,
    type: ClipType,
    onImport: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val ts = state.document.trackSettingsFor(trackId)
    Box(
        Modifier
            .height(state.trackHeight(trackId).dp)
            .fillMaxWidth()
            .background(Neutral900)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                trackId,
                color = if (ts.disabled) Neutral600 else Neutral400,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Row {
                if (ts.muted && type != ClipType.TEXT) {
                    Icon(Icons.Filled.VolumeOff, "Muted", tint = Red500, modifier = Modifier.size(11.dp))
                }
                if (ts.disabled) {
                    Icon(Icons.Filled.VisibilityOff, "Disabled", tint = Red500, modifier = Modifier.size(11.dp))
                }
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(220.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Track $trackId", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)

                if (type != ClipType.TEXT) {
                    TrackToggle("Mute", ts.muted) { vm.toggleTrackMuted(trackId) }
                }
                TrackToggle(if (type == ClipType.AUDIO) "Disable track" else "Hide track", ts.disabled) {
                    vm.toggleTrackDisabled(trackId)
                }

                if (type == ClipType.AUDIO || type == ClipType.VIDEO) {
                    TrackSlider("Volume", ts.volume, 0f..2f) { vm.setTrackVolume(trackId, it) }
                }
                if (type == ClipType.VIDEO || type == ClipType.TEXT) {
                    TrackSlider("Opacity", ts.opacity, 0f..1f) { vm.setTrackOpacity(trackId, it) }
                }

                HorizontalDivider(color = Neutral800, modifier = Modifier.padding(vertical = 6.dp))
                TrackAction("Import clip…") { open = false; onImport(trackId) }
                TrackAction(if (type == ClipType.TEXT) "Add text clip" else "Create clip…") {
                    open = false; onCreate(trackId)
                }
            }
        }
    }
}

@Composable
private fun TrackToggle(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Neutral300, fontSize = 12.sp)
        Text(if (on) "ON" else "OFF", color = if (on) Red500 else Neutral500, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TrackSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Neutral400, fontSize = 11.sp)
            Text("%.2f".format(value), color = Neutral500, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun TrackAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = White,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

@Composable
private fun Ruler(totalMs: Long, pps: Float, contentWidth: androidx.compose.ui.unit.Dp) {
    val tickColor = Neutral700
    Canvas(
        Modifier
            .width(contentWidth)
            .height(RULER_HEIGHT)
            .background(Neutral950),
    ) {
        val totalSec = (totalMs / 1000f) + 4f
        var s = 0
        while (s <= totalSec) {
            val x = s * pps
            drawLine(tickColor, Offset(x, size.height * 0.4f), Offset(x, size.height), strokeWidth = 1f)
            s++
        }
    }
}

@Composable
private fun Lane(
    vm: EditorViewModel,
    state: EditorUiState,
    trackId: String,
    pps: Float,
    msToDp: (Long) -> androidx.compose.ui.unit.Dp,
) {
    val clips = state.document.clips.filter { it.trackId == trackId }
    // No tap handler here: taps on empty lane area fall through to the timeline surface
    // handler (in TimelineLanes), which seeks the playhead and clears the selection.
    Box(
        Modifier
            .fillMaxWidth()
            .height(state.trackHeight(trackId).dp)
            .background(Neutral850)
            .border(0.5.dp, Neutral800),
    ) {
        clips.forEach { clip ->
            ClipView(vm, state, clip, pps, msToDp)
        }
    }
}

@Composable
private fun ClipView(
    vm: EditorViewModel,
    state: EditorUiState,
    clip: TimelineClip,
    pps: Float,
    msToDp: (Long) -> androidx.compose.ui.unit.Dp,
) {
    val selected = clip.id in state.selectedClipIds
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val media = state.document.mediaFor(clip)
    val selectedKf = clip.keyframes.firstOrNull { it.id == state.selectedKeyframeId }
    var dragPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var dragPy by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimStartPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimEndPx by remember(clip.id) { mutableFloatStateOf(0f) }
    val baseLeftPx = with(density) { msToDp(clip.startTimeMs).toPx() }
    val trackHeightPx = with(density) { state.trackHeight(clip.trackId).dp.toPx() }
    val sameTypeTracks = when (clip.type) {
        // Text clips live on video tracks, like any overlay/image clip.
        ClipType.VIDEO, ClipType.TEXT -> state.document.videoTracks
        ClipType.AUDIO -> state.document.audioTracks
    }

    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset((baseLeftPx + dragPx).roundToInt(), dragPy.roundToInt()) }
            .padding(vertical = 6.dp)
            .width(msToDp(clip.durationMs))
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Red500.copy(alpha = 0.22f) else Neutral800)
            .border(1.dp, if (selected) Red500 else Neutral700, RoundedCornerShape(4.dp))
            // Tap: select, or split when split tool is active. Long-press: range-select
            // from the current selection to this clip (across tracks, all clips between).
            .pointerInput(clip.id, state.tool, pps, clip.keyframes) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.selectRangeTo(clip.id)
                    },
                    onTap = onTap@{ offset ->
                        // Tap a keyframe diamond: select it + toggle its ease.
                        val h = size.height.toFloat()
                        val hitKf = clip.keyframes
                            .minByOrNull { (keyframePos(it, pps, h) - offset).getDistance() }
                            ?.takeIf { (keyframePos(it, pps, h) - offset).getDistance() < 24f }
                        if (hitKf != null) {
                            vm.tapKeyframe(clip.id, hitKf.id)
                            return@onTap
                        }
                        val tappedMs = clip.startTimeMs + (offset.x / pps * 1000f).toLong()
                        when (state.tool) {
                            EditorTool.SPLIT -> vm.splitClip(clip.id, tappedMs)
                            EditorTool.KEYFRAME -> {
                                // Keyframe tool: drop a keyframe at the tapped point.
                                vm.seekTo(tappedMs)
                                vm.selectClip(clip.id)
                                vm.addKeyframe(clip.id, KeyframeProperty.OPACITY)
                            }
                            else -> {
                                // Move the playhead to the tapped point, and select the clip.
                                vm.selectKeyframe(null)
                                vm.seekTo(tappedMs)
                                vm.selectClip(clip.id)
                            }
                        }
                    },
                )
            }
            // Drag to move: horizontally on the timeline, vertically across same-type tracks.
            // Disabled while a keyframe of this clip is selected (drag then edits the ease).
            .pointerInput(clip.id, state.tool, pps, sameTypeTracks, state.selectedKeyframeId) {
                if (state.tool == EditorTool.SELECT && selectedKf == null) {
                    detectDragGestures(
                        onDragStart = { dragPx = 0f; dragPy = 0f },
                        onDragEnd = {
                            val deltaMs = (dragPx / pps * 1000f).toLong()
                            val shift = if (trackHeightPx > 0f) (dragPy / trackHeightPx).roundToInt() else 0
                            // Group-aware: moves the whole group together when clip is grouped.
                            vm.moveClipBy(clip.id, shift, deltaMs)
                            dragPx = 0f; dragPy = 0f
                        },
                        onDragCancel = { dragPx = 0f; dragPy = 0f },
                        onDrag = { change, drag -> change.consume(); dragPx += drag.x; dragPy += drag.y },
                    )
                }
            }
            // With a keyframe selected, dragging adjusts its nearest bezier ease handle.
            .pointerInput(clip.id, state.selectedKeyframeId, pps) {
                val sel = clip.keyframes.firstOrNull { it.id == state.selectedKeyframeId } ?: return@pointerInput
                val next = nextKeyframe(clip, sel) ?: return@pointerInput
                val h = size.height.toFloat()
                var which = 1
                detectDragGestures(
                    onDragStart = { start ->
                        val a = keyframePos(sel, pps, h)
                        val b = keyframePos(next, pps, h)
                        val h1 = Offset(a.x + sel.easing.x1 * (b.x - a.x), a.y + sel.easing.y1 * (b.y - a.y))
                        val h2 = Offset(a.x + sel.easing.x2 * (b.x - a.x), a.y + sel.easing.y2 * (b.y - a.y))
                        which = if ((start - h1).getDistance() <= (start - h2).getDistance()) 1 else 2
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val a = keyframePos(sel, pps, h)
                        val b = keyframePos(next, pps, h)
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val nx = if (dx != 0f) ((change.position.x - a.x) / dx).coerceIn(0f, 1f) else 0f
                        val ny = if (dy != 0f) ((change.position.y - a.y) / dy).coerceIn(-0.5f, 1.5f)
                        else (if (which == 1) sel.easing.y1 else sel.easing.y2)
                        vm.updateKeyframe(clip.id, sel.id) { kf ->
                            kf.copy(
                                easing = if (which == 1) kf.easing.copy(x1 = nx, y1 = ny)
                                else kf.easing.copy(x2 = nx, y2 = ny),
                            )
                        }
                    },
                )
            },
    ) {
        // On-device preview behind everything: thumbnail for video/image, waveform for audio.
        media?.let { m ->
            if (clip.type == ClipType.AUDIO) ClipWaveform(m.uri)
            else ClipThumbnail(m.uri, m.kind, clip.trimStartMs)
        }
        // Text clips show their caption text.
        if (clip.type == ClipType.TEXT) {
            Text(
                clip.text.ifBlank { "Text" },
                color = White,
                fontSize = 9.sp,
                maxLines = 2,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 4.dp),
            )
        }
        // Label.
        Text(
            text = clip.id.take(4),
            color = White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        // Edit (keep/remove) overlays — positioned relative to the clip's trimmed window.
        if (clip.edits.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val clipStart = clip.trimStartMs
                clip.edits.forEach { edit ->
                    val relStart = (edit.startMs - clipStart).coerceAtLeast(0)
                    val relEnd = (edit.endMs - clipStart).coerceAtMost(clip.durationMs)
                    if (relEnd > relStart) {
                        val x = relStart / 1000f * pps
                        val w = (relEnd - relStart) / 1000f * pps
                        drawRect(
                            color = if (edit.action == EditAction.KEEP) Neutral500.copy(alpha = 0.35f)
                            else Red500.copy(alpha = 0.4f),
                            topLeft = Offset(x, size.height * 0.5f),
                            size = androidx.compose.ui.geometry.Size(w, size.height * 0.5f),
                        )
                    }
                }
            }
        }
        // Keyframe envelopes: each property's keyframes plotted by value (higher on the
        // clip = higher value, e.g. more opacity), connected into a curve.
        if (clip.keyframes.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                clip.keyframes.groupBy { it.property }.forEach { (prop, kfs) ->
                    val lo = 0f
                    val hi = when (prop) {
                        KeyframeProperty.OPACITY -> 1f
                        KeyframeProperty.SCALE -> 3f
                        KeyframeProperty.VOLUME -> 2f
                    }
                    val color = when (prop) {
                        KeyframeProperty.OPACITY -> White
                        KeyframeProperty.SCALE -> Red500
                        KeyframeProperty.VOLUME -> Neutral400
                    }
                    val sorted = kfs.sortedBy { it.timeMs }
                    fun ptOf(kf: com.hereliesaz.guillotine.model.Keyframe): Offset {
                        val x = kf.timeMs / 1000f * pps
                        val norm = ((kf.value - lo) / (hi - lo)).coerceIn(0f, 1f)
                        return Offset(x, size.height * (1f - norm))
                    }
                    for (i in 0 until sorted.size - 1) {
                        drawLine(color.copy(alpha = 0.6f), ptOf(sorted[i]), ptOf(sorted[i + 1]), strokeWidth = 1.5f)
                    }
                    sorted.forEach { kf ->
                        val c = ptOf(kf)
                        val r = 4f
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
                        }
                        drawPath(path, color)
                    }
                }
                // Bezier ease handles for the selected keyframe (drawn over the envelope).
                if (selectedKf != null) {
                    val next = nextKeyframe(clip, selectedKf)
                    if (next != null) {
                        val a = keyframePos(selectedKf, pps, size.height)
                        val b = keyframePos(next, pps, size.height)
                        val h1 = Offset(a.x + selectedKf.easing.x1 * (b.x - a.x), a.y + selectedKf.easing.y1 * (b.y - a.y))
                        val h2 = Offset(a.x + selectedKf.easing.x2 * (b.x - a.x), a.y + selectedKf.easing.y2 * (b.y - a.y))
                        drawLine(Red500, a, h1, strokeWidth = 1.5f)
                        drawLine(Red500, b, h2, strokeWidth = 1.5f)
                        drawCircle(White, radius = 5f, center = a)
                        drawCircle(Red500, radius = 6f, center = h1)
                        drawCircle(Red500, radius = 6f, center = h2)
                    }
                }
            }
        }

        // Trim handles — drag clip edges to adjust in/out points (select tool, when selected).
        if (selected && state.tool == EditorTool.SELECT) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(10.dp)
                    .fillMaxHeight()
                    .background(Red500)
                    .pointerInput(clip.id, pps) {
                        detectDragGestures(
                            onDragStart = { trimStartPx = 0f },
                            onDragEnd = { vm.trimClipStart(clip.id, (trimStartPx / pps * 1000f).toLong()); trimStartPx = 0f },
                            onDragCancel = { trimStartPx = 0f },
                            onDrag = { change, drag -> change.consume(); trimStartPx += drag.x },
                        )
                    },
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(10.dp)
                    .fillMaxHeight()
                    .background(Red500)
                    .pointerInput(clip.id, pps) {
                        detectDragGestures(
                            onDragStart = { trimEndPx = 0f },
                            onDragEnd = { vm.trimClipEnd(clip.id, (trimEndPx / pps * 1000f).toLong()); trimEndPx = 0f },
                            onDragCancel = { trimEndPx = 0f },
                            onDrag = { change, drag -> change.consume(); trimEndPx += drag.x },
                        )
                    },
            )
        }
    }
}

/** Canvas position of a keyframe: x by time, y by value (higher value = higher on the clip). */
private fun keyframePos(kf: Keyframe, pps: Float, heightPx: Float): Offset {
    val hi = when (kf.property) {
        KeyframeProperty.OPACITY -> 1f
        KeyframeProperty.SCALE -> 3f
        KeyframeProperty.VOLUME -> 2f
    }
    val norm = (kf.value / hi).coerceIn(0f, 1f)
    return Offset(kf.timeMs / 1000f * pps, heightPx * (1f - norm))
}

/** The next keyframe of the same property (its easing segment runs from [kf] to this). */
private fun nextKeyframe(clip: TimelineClip, kf: Keyframe): Keyframe? {
    val sameProp = clip.keyframes.filter { it.property == kf.property }.sortedBy { it.timeMs }
    return sameProp.getOrNull(sameProp.indexOf(kf) + 1)
}

/** Video/image clip background: a downscaled, dimmed thumbnail (loaded on-device, async). */
@Composable
private fun ClipThumbnail(uri: String, kind: com.hereliesaz.guillotine.model.MediaKind, atMs: Long) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, uri, atMs) {
        value = MediaPreview.thumbnail(context, uri, kind, atMs)
    }
    thumb?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
            alpha = 0.55f,
        )
    }
}

/** Audio clip background: a coarse amplitude waveform (decoded on-device, async). */
@Composable
private fun ClipWaveform(uri: String) {
    val context = LocalContext.current
    val wave by produceState<FloatArray?>(null, uri) { value = MediaPreview.waveform(context, uri) }
    val w = wave ?: return
    Canvas(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
        val mid = size.height / 2f
        val bw = size.width / w.size
        w.forEachIndexed { i, peak ->
            val h = (peak * size.height * 0.9f).coerceAtLeast(1f)
            val x = i * bw + bw / 2f
            drawLine(
                color = Neutral500,
                start = Offset(x, mid - h / 2f),
                end = Offset(x, mid + h / 2f),
                strokeWidth = (bw * 0.8f).coerceAtLeast(1f),
            )
        }
    }
}
