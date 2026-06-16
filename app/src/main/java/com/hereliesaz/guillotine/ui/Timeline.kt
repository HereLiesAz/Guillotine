package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/**
 * Full timeline panel: scrollable multi-track lanes with playhead. The editing
 * tools (select/split/zoom/etc.) and the AI prompt live in the shared
 * [EditorToolStrip] so they are available in both the compact and wide layouts.
 */
@Composable
fun TimelinePanel(vm: EditorViewModel, state: EditorUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Neutral900)) {
        TimelineLanes(vm, state, modifier = Modifier.fillMaxSize())
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

    // Pinch-to-zoom (touch) + Ctrl+scroll zoom (mouse/trackpad). These read the LIVE
    // pixels-per-second from the view model (not the captured `state`, which would be
    // stale inside the one-shot pointerInput) so the zoom actually accumulates — pinching
    // in/out changes how much of the timeline (how many frames) is on screen.
    val zoomModifier = Modifier
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom != 1f) vm.setZoom(vm.uiState.value.pixelsPerSecond * zoom)
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

    Row(modifier.fillMaxSize().then(zoomModifier)) {
        // Fixed track-header column.
        Column(Modifier.width(HEADER_WIDTH).fillMaxHeight().background(Neutral900)) {
            Box(Modifier.height(RULER_HEIGHT).fillMaxWidth())
            state.document.textTracks.forEach { TrackHeader(it) }
            state.document.videoTracks.forEach { TrackHeader(it) }
            state.document.audioTracks.forEach { TrackHeader(it) }
        }
        // Scrollable content.
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
                state.document.textTracks.forEach { trackId ->
                    Lane(vm, state, trackId, pps) { msToDp(it) }
                }
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
    // No tap handler here: taps on empty lane area fall through to the timeline surface
    // handler (in TimelineLanes), which seeks the playhead and clears the selection.
    Box(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
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
    var dragPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var dragPy by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimStartPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimEndPx by remember(clip.id) { mutableFloatStateOf(0f) }
    val baseLeftPx = with(density) { msToDp(clip.startTimeMs).toPx() }
    val trackHeightPx = with(density) { TRACK_HEIGHT.toPx() }
    val sameTypeTracks = when (clip.type) {
        ClipType.VIDEO -> state.document.videoTracks
        ClipType.AUDIO -> state.document.audioTracks
        ClipType.TEXT -> state.document.textTracks
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
            .pointerInput(clip.id, state.tool, pps) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.selectRangeTo(clip.id)
                    },
                    onTap = { offset ->
                        val relMs = (offset.x / pps * 1000f).toLong()
                        if (state.tool == EditorTool.SPLIT) {
                            vm.splitClip(clip.id, clip.startTimeMs + relMs)
                        } else {
                            // Move the playhead to the tapped point, and select the clip.
                            vm.seekTo(clip.startTimeMs + relMs)
                            vm.selectClip(clip.id)
                        }
                    },
                )
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
