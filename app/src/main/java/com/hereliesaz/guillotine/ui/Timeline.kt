package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral700
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral850
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Neutral950
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White
import kotlin.math.roundToInt

private val TRACK_HEIGHT = 64.dp
private val HEADER_WIDTH = 56.dp
private val RULER_HEIGHT = 24.dp

/** Full timeline panel: toolbar + scrollable multi-track lanes with playhead. */
@Composable
fun TimelinePanel(vm: EditorViewModel, state: EditorUiState, onOpenAi: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Neutral900)) {
        TimelineToolbar(vm, state, onOpenAi)
        TimelineLanes(vm, state, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TimelineToolbar(vm: EditorViewModel, state: EditorUiState, onOpenAi: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Neutral950)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "V ${state.document.videoTracks.size}  A ${state.document.audioTracks.size}",
            color = Neutral500, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        Box(Modifier.weight(1f))
        ToolbarButton("AI", tint = Red500, onClick = onOpenAi)
        IconToolButton(Icons.Filled.Add, "Add track") {
            val t = state.selectedClips.singleOrNull()?.type ?: ClipType.VIDEO
            vm.addTrack(t)
        }
        // Expressive M3 toggle buttons for the active tool.
        ToggleButton(
            checked = state.tool == EditorTool.SELECT,
            onCheckedChange = { if (it) vm.setTool(EditorTool.SELECT) },
            modifier = Modifier.padding(horizontal = 2.dp),
        ) { Icon(Icons.Filled.NearMe, contentDescription = "Select", modifier = Modifier.size(18.dp)) }
        ToggleButton(
            checked = state.tool == EditorTool.SPLIT,
            onCheckedChange = { if (it) vm.setTool(EditorTool.SPLIT) },
            modifier = Modifier.padding(horizontal = 2.dp),
        ) { Icon(Icons.Filled.ContentCut, contentDescription = "Split", modifier = Modifier.size(18.dp)) }
        IconToolButton(Icons.Filled.Delete, "Delete", enabled = state.selectedClipIds.isNotEmpty()) {
            vm.deleteSelected()
        }
    }
}

@Composable
private fun TimelineLanes(vm: EditorViewModel, state: EditorUiState, modifier: Modifier) {
    val density = LocalDensity.current
    val pps = state.pixelsPerSecond
    val scroll = rememberScrollState()

    fun msToDp(ms: Long) = with(density) { (ms / 1000f * pps).toDp() }
    val totalMs = state.document.totalDurationMs
    val contentWidth = msToDp(totalMs) + 400.dp

    // Pinch-to-zoom (touch) + Ctrl+scroll zoom (mouse/trackpad).
    val zoomModifier = Modifier
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom != 1f) vm.setZoom(state.pixelsPerSecond * zoom)
            }
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) vm.setZoom(state.pixelsPerSecond * if (dy > 0) 0.9f else 1.1f)
                    }
                }
            }
        }

    Row(modifier.fillMaxSize().then(zoomModifier)) {
        // Fixed track-header column.
        Column(Modifier.width(HEADER_WIDTH).fillMaxHeight().background(Neutral900)) {
            Box(Modifier.height(RULER_HEIGHT).fillMaxWidth())
            state.document.videoTracks.forEach { TrackHeader(it) }
            state.document.audioTracks.forEach { TrackHeader(it) }
        }
        // Scrollable content.
        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(scroll)
                .width(contentWidth),
        ) {
            Column(Modifier.fillMaxSize()) {
                Ruler(totalMs, pps, contentWidth)
                state.document.videoTracks.forEach { trackId ->
                    Lane(vm, state, trackId, pps) { msToDp(it) }
                }
                state.document.audioTracks.forEach { trackId ->
                    Lane(vm, state, trackId, pps) { msToDp(it) }
                }
            }
            // Playhead overlay spanning the lanes.
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

@Composable
private fun TrackHeader(trackId: String) {
    Box(
        Modifier.height(TRACK_HEIGHT).fillMaxWidth().background(Neutral900),
        contentAlignment = Alignment.Center,
    ) {
        Text(trackId, color = Neutral500, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
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
    Box(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .background(Neutral850)
            .border(0.5.dp, Neutral800)
            .pointerInput(pps) {
                detectTapGestures { offset ->
                    // Tap empty lane: seek + clear selection.
                    vm.clearSelection()
                    vm.seekTo((offset.x / pps * 1000f).toLong())
                }
            },
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
    var dragPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var dragPy by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimStartPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimEndPx by remember(clip.id) { mutableFloatStateOf(0f) }
    val baseLeftPx = with(density) { msToDp(clip.startTimeMs).toPx() }
    val trackHeightPx = with(density) { TRACK_HEIGHT.toPx() }
    val sameTypeTracks = if (clip.type == ClipType.VIDEO) state.document.videoTracks else state.document.audioTracks

    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset((baseLeftPx + dragPx).roundToInt(), dragPy.roundToInt()) }
            .padding(vertical = 6.dp)
            .width(msToDp(clip.durationMs))
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Red500.copy(alpha = 0.22f) else Neutral800)
            .border(1.dp, if (selected) Red500 else Neutral700, RoundedCornerShape(4.dp))
            // Tap: select, or split when split tool is active.
            .pointerInput(clip.id, state.tool, pps) {
                detectTapGestures { offset ->
                    if (state.tool == EditorTool.SPLIT) {
                        val relMs = (offset.x / pps * 1000f).toLong()
                        vm.splitClip(clip.id, clip.startTimeMs + relMs)
                    } else {
                        vm.selectClip(clip.id)
                    }
                }
            }
            // Drag to move: horizontally on the timeline, vertically across same-type tracks.
            .pointerInput(clip.id, state.tool, pps, sameTypeTracks) {
                if (state.tool == EditorTool.SELECT) {
                    detectDragGestures(
                        onDragStart = { dragPx = 0f; dragPy = 0f },
                        onDragEnd = {
                            val deltaMs = (dragPx / pps * 1000f).toLong()
                            val curIndex = sameTypeTracks.indexOf(clip.trackId)
                            val shift = if (trackHeightPx > 0f) (dragPy / trackHeightPx).roundToInt() else 0
                            val targetIndex = (curIndex + shift).coerceIn(0, (sameTypeTracks.size - 1).coerceAtLeast(0))
                            val targetTrack = sameTypeTracks.getOrElse(targetIndex) { clip.trackId }
                            vm.moveClip(clip.id, targetTrack, clip.startTimeMs + deltaMs)
                            dragPx = 0f; dragPy = 0f
                        },
                        onDragCancel = { dragPx = 0f; dragPy = 0f },
                        onDrag = { change, drag -> change.consume(); dragPx += drag.x; dragPy += drag.y },
                    )
                }
            },
    ) {
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
        // Keyframe diamonds.
        if (clip.keyframes.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                clip.keyframes.forEach { kf ->
                    val x = kf.timeMs / 1000f * pps
                    val y = size.height - 6f
                    val r = 4f
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close()
                    }
                    drawPath(path, White)
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
