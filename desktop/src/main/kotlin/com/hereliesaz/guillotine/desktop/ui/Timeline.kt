package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.editor.EditorTool
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.Keyframe
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral300
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral600
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral700
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral850
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral900
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral950
import com.hereliesaz.guillotine.desktop.ui.theme.Red500
import com.hereliesaz.guillotine.desktop.ui.theme.White
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val HEADER_WIDTH = 56.dp
private val RULER_HEIGHT = 24.dp
/** Snap radius (px) when dragging a clip to the playhead / other clip edges / timeline start. */
private const val SNAP_PX = 20f
/** Weaker snap radius (px) for the timeline grid (increment) magnet. */
private const val SNAP_GRID_PX = 8f

/** Live drag offset shared across a clip's group so every member moves together DURING the drag. */
private data class GroupDrag(val ids: Set<String>, val dx: Float, val dy: Float)

/** Which axis a two-finger pinch is currently locked to. Null until first significant motion. */
private enum class ZoomAxis { HORIZONTAL, VERTICAL }

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
    // Live, group-aware drag offset: while a clip is dragged, every clip in its group reads this and
    // moves together (snapped) — so the whole group tracks the cursor, not just the grabbed clip.
    var groupDrag by remember { mutableStateOf<GroupDrag?>(null) }
    // Visible timeline width in px, updated whenever the horizontally-scrolled BoxWithConstraints
    // (re)measures. Read from the pps-change effect to clamp the playhead into view.
    var viewportWidthPx by remember { mutableIntStateOf(0) }

    // Zoom-around-playhead: any pps change (pinch, Ctrl+scroll, TopBar buttons, addMedia fit-all)
    // solves for a scroll that KEEPS THE PLAYHEAD AT THE SAME ON-SCREEN X. Zoom is precision — the
    // frame under the user's fingers must not slide out from under them.
    //
    // We compute the playhead's pre-zoom viewport-X and preserve it: after the new pps takes effect,
    // scroll to (newPlayheadPx − anchorViewportX). If the playhead was already visible, its on-screen
    // position doesn't change at all. If it was scrolled off-screen we CLAMP the anchor into
    // [0, viewport] so the zoom always leaves the playhead at the nearest visible edge — the "stays
    // in the viewport" guarantee — rather than shifting it further off-screen with each zoom step.
    //
    // withFrameNanos before scroll.scrollTo lets the layout pass with the new pps commit first —
    // otherwise scroll.maxValue is stale from the previous width and the scroll clamps wrong on
    // zoom-in. roundToInt (not toInt) so a target of e.g. −0.9 doesn't truncate to 0.
    var lastZoomedPps by remember { mutableFloatStateOf(pps) }
    LaunchedEffect(pps) {
        if (pps == lastZoomedPps) return@LaunchedEffect
        val playheadMs = vm.uiState.value.currentTimeMs
        val oldPlayheadPx = playheadMs / 1000f * lastZoomedPps
        val vp = viewportWidthPx
        val rawViewportX = oldPlayheadPx - scroll.value
        val anchorViewportX =
            if (vp > 0) rawViewportX.coerceIn(0f, vp.toFloat()) else rawViewportX
        lastZoomedPps = pps
        androidx.compose.runtime.withFrameNanos {}
        val newPlayheadPx = playheadMs / 1000f * pps
        val target = (newPlayheadPx - anchorViewportX).roundToInt().coerceAtLeast(0)
        scroll.scrollTo(target)
    }

    fun msToDp(ms: Long) = with(density) { (ms / 1000f * pps).toDp() }
    val totalMs = state.document.totalDurationMs
    val contentWidth = msToDp(totalMs) + 400.dp

    // Pinch-to-zoom (touch) + Ctrl+scroll zoom (mouse/trackpad). These read the LIVE
    // pixels-per-second from the view model (not the captured `state`, which would be
    // stale inside the one-shot pointerInput) so the zoom actually accumulates — pinching
    // in/out changes how much of the timeline (how many frames) is on screen.
    val zoomModifier = Modifier
        .pointerInput(Unit) {
            // Single-axis pinch: one gesture changes EITHER width (pixels/second) OR track
            // height, never both. Picking the axis PER EVENT (as we used to) meant a
            // slightly-diagonal pinch flipped axes mid-gesture and both wound up scaled.
            // Now we lock the axis on the first significant motion and keep it locked
            // until fewer than two fingers are down (gesture end).
            awaitPointerEventScope {
                var lockedAxis: ZoomAxis? = null
                val axisLockThresholdPx = 8f
                while (true) {
                    // Initial pass: claim two-finger pinch before the nested scroll/clip
                    // children can consume the drag (that's why vertical zoom didn't work).
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pts = event.changes.filter { it.pressed }
                    if (pts.size < 2) {
                        // Gesture ended (release or lifted below two fingers) — unlock so
                        // the next pinch is free to pick its own axis.
                        lockedAxis = null
                        continue
                    }
                    val a = pts[0]
                    val b = pts[1]
                    val curH = kotlin.math.abs(a.position.x - b.position.x)
                    val curV = kotlin.math.abs(a.position.y - b.position.y)
                    val prevH = kotlin.math.abs(a.previousPosition.x - b.previousPosition.x)
                    val prevV = kotlin.math.abs(a.previousPosition.y - b.previousPosition.y)
                    val dH = kotlin.math.abs(curH - prevH)
                    val dV = kotlin.math.abs(curV - prevV)
                    if (lockedAxis == null) {
                        // First significant motion decides — need at least axisLockThresholdPx
                        // of change so a stationary two-finger touch doesn't lock on noise.
                        lockedAxis = when {
                            dH >= axisLockThresholdPx && dH > dV -> ZoomAxis.HORIZONTAL
                            dV >= axisLockThresholdPx && dV > dH -> ZoomAxis.VERTICAL
                            else -> null
                        }
                    }
                    var acted = false
                    when (lockedAxis) {
                        ZoomAxis.HORIZONTAL -> {
                            if (prevH > 1f && curH > 1f && curH != prevH) {
                                vm.setZoom(vm.uiState.value.pixelsPerSecond * (curH / prevH))
                                acted = true
                            }
                        }
                        ZoomAxis.VERTICAL -> {
                            if (prevV > 1f && curV > 1f && curV != prevV) {
                                vm.scaleTrackHeight(curV / prevV)
                                acted = true
                            }
                        }
                        null -> {}
                    }
                    if (acted) pts.forEach { it.consume() }
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
        // Wrapped in BoxWithConstraints so the surface always fills the viewport width — even
        // with no clips (contentWidth would otherwise be ~400dp), so tap-to-seek and the pinch
        // gestures cover the whole visible timeline regardless of whether any clip is present.
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val surfaceWidth = maxOf(contentWidth, maxWidth)
        // Report the visible lanes width so the view model can cap zoom-out at "whole project
        // fits in 2/3 of the timeline".
        val viewportPx = with(density) { maxWidth.toPx() }
        androidx.compose.runtime.LaunchedEffect(viewportPx) {
            vm.setTimelineViewportPx(viewportPx)
            viewportWidthPx = viewportPx.roundToInt()
        }
        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(scroll)
                .width(surfaceWidth)
                // Tap anywhere on the timeline surface (ruler, gaps, below the tracks) to
                // move the playhead there. Clips sit on top and handle their own taps.
                .pointerInput(pps) {
                    detectTapGestures { offset ->
                        vm.clearSelection()
                        vm.seekTo((offset.x / pps * 1000f).toLong())
                    }
                }
                // Playhead drag: grab the red line anywhere along its height (not just from
                // the ruler strip). Gate by proximity: if the initial down is within ±16 dp of
                // the playhead's surface X, we own this pointer and seek as it drags. Otherwise
                // we bail out immediately, so clip taps/long-presses under the playhead still
                // reach their own detectors (Compose hit-testing wouldn't propagate through a
                // covering sibling Box, so we handle the drag from the parent surface instead).
                //
                // startMs + (x − downX) rather than accumulating dragAmount keeps the math local
                // to positions the pointer scope already gives us — no per-event delta lookups.
                .pointerInput(pps, state.currentTimeMs) {
                    val hitRadiusPx = 16.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val playheadPx = state.currentTimeMs / 1000f * pps
                        if (kotlin.math.abs(down.position.x - playheadPx) > hitRadiusPx) {
                            return@awaitEachGesture
                        }
                        val startMs = vm.uiState.value.currentTimeMs
                        val downX = down.position.x
                        val seekTo: (Float) -> Unit = { x ->
                            val newMs = (startMs + ((x - downX) / pps * 1000f).toLong())
                                .coerceAtLeast(0L)
                            vm.seekTo(newMs)
                        }
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume(); seekTo(change.position.x)
                        } ?: return@awaitEachGesture
                        drag(slop.id) { change ->
                            change.consume(); seekTo(change.position.x)
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                Ruler(vm, totalMs, pps, contentWidth)
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(vScroll)
                        // Report the lanes viewport height in dp so vm.fitAllToViewport() can
                        // divide it across the tracks. Reported on every measure — cheap.
                        .onSizeChanged { size ->
                            vm.setTimelineLanesHeightDp(with(density) { size.height.toDp().value })
                        },
                ) {
                    state.document.videoTracks.forEach { trackId ->
                        Lane(vm, state, trackId, pps, { msToDp(it) }, groupDrag) { groupDrag = it }
                    }
                    state.document.audioTracks.forEach { trackId ->
                        Lane(vm, state, trackId, pps, { msToDp(it) }, groupDrag) { groupDrag = it }
                    }
                }
            }
            // Playhead overlay spanning the visible lanes. Drag is captured on the parent
            // surface (see .pointerInput above) so the hit region isn't a covering sibling —
            // Compose wouldn't propagate through it to underlying clips.
            Box(
                Modifier
                    .offset(x = msToDp(state.currentTimeMs))
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Red500),
            )
            // Marquee (range-select) overlay: only in MARQUEE mode. Dragging draws a rectangle over a
            // time range and selects every clip it touches on release. It captures the drag (so the
            // timeline doesn't scroll), and its local x == content x, so x/pps maps straight to ms.
            if (state.tool == EditorTool.MARQUEE) {
                var startX by remember { mutableStateOf<Float?>(null) }
                var curX by remember { mutableFloatStateOf(0f) }
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(pps) {
                            detectDragGestures(
                                onDragStart = { off -> startX = off.x; curX = off.x },
                                onDrag = { change, _ -> change.consume(); curX = change.position.x },
                                onDragEnd = {
                                    startX?.let { s ->
                                        vm.selectClipsInRange(
                                            (s / pps * 1000f).toLong(),
                                            (curX / pps * 1000f).toLong(),
                                        )
                                    }
                                    startX = null
                                },
                                onDragCancel = { startX = null },
                            )
                        },
                ) {
                    startX?.let { s ->
                        Box(
                            Modifier
                                .offset(x = with(density) { kotlin.math.min(s, curX).toDp() })
                                .width(with(density) { kotlin.math.abs(curX - s).toDp() })
                                .fillMaxHeight()
                                .background(Red500.copy(alpha = 0.18f))
                                .border(1.dp, Red500),
                        )
                    }
                }
            }
        }
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
                // A + icon button that adds a new track of this head's kind (video tracks carry
                // text/image clips too).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconToolButton(
                        Icons.Filled.Add,
                        if (type == ClipType.AUDIO) "New audio track" else "New video track",
                    ) { open = false; vm.addTrack(type) }
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
private fun Ruler(vm: EditorViewModel, totalMs: Long, pps: Float, contentWidth: androidx.compose.ui.unit.Dp) {
    val fps = vm.uiState.value.document.settings.fps
    val majorColor = Neutral500
    val minorColor = Neutral700
    Canvas(
        Modifier
            .width(contentWidth)
            .height(RULER_HEIGHT)
            .background(Neutral950)
            .pointerInput(pps) {
                detectTapGestures { off ->
                    vm.seekTo((off.x / pps * 1000f).toLong().coerceAtLeast(0))
                }
            }
            .pointerInput(pps) {
                detectDragGestures(
                    onDragStart = { off ->
                        vm.seekTo((off.x / pps * 1000f).toLong().coerceAtLeast(0))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        vm.seekTo((change.position.x / pps * 1000f).toLong().coerceAtLeast(0))
                    },
                )
            },
    ) {
        val endMs = totalMs + 4000L
        val grid = gridIncrementMs(pps, fps)
        val secondMs = 1000L
        var ms = 0L
        while (ms <= endMs) {
            val x = ms / 1000f * pps
            val isSecond = ms % secondMs == 0L
            if (isSecond) {
                drawLine(majorColor, Offset(x, size.height * 0.2f), Offset(x, size.height), strokeWidth = 1.5f)
            } else {
                drawLine(minorColor, Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 1f)
            }
            ms += grid
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
    groupDrag: GroupDrag?,
    onGroupDrag: (GroupDrag?) -> Unit,
) {
    val clips = state.document.clips.filter { it.trackId == trackId }
    // While a drag is in flight, lift the lane containing the grabbed clip(s) above sibling lanes:
    // the dragged clip is offset in Y by its live drag delta, which pushes its rendered pixels
    // outside this lane's box — and the next lane down (drawn later in the tracks Column) would
    // otherwise over-paint that overflow, so a video clip dragged toward the audio row reads as
    // "the video went behind the audio track". zIndex lifts THIS lane's draw pass above siblings.
    val dragging = groupDrag != null && clips.any { it.id in groupDrag.ids }
    // No tap handler here: taps on empty lane area fall through to the timeline surface
    // handler (in TimelineLanes), which seeks the playhead and clears the selection.
    Box(
        Modifier
            .fillMaxWidth()
            .height(state.trackHeight(trackId).dp)
            .zIndex(if (dragging) 1f else 0f)
            .background(Neutral850)
            .border(0.5.dp, Neutral800),
    ) {
        clips.forEach { clip ->
            ClipView(vm, state, clip, pps, msToDp, groupDrag, onGroupDrag)
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
    groupDrag: GroupDrag?,
    onGroupDrag: (GroupDrag?) -> Unit,
) {
    val selected = clip.id in state.selectedClipIds
    val density = LocalDensity.current
    val media = state.document.mediaFor(clip)
    val selectedKf = clip.keyframes.firstOrNull { it.id == state.selectedKeyframeId }
    // Raw accumulated drag of THIS clip (when it's the grabbed one); the live visual offset comes from
    // the shared, snapped groupDrag so every group member moves together.
    var dragPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var dragPy by remember(clip.id) { mutableFloatStateOf(0f) }
    // Auto-create-track-on-drag: which edge the clip is currently held past (-1 = above the top track,
    // +1 = below the bottom track, 0 = within range), and a latch so the create fires once and the
    // drag-end move is skipped (the auto-create already moved the clip).
    var holdEdge by remember(clip.id) { mutableIntStateOf(0) }
    var autoTrackConsumed by remember(clip.id) { mutableStateOf(false) }
    var trimStartPx by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimEndPx by remember(clip.id) { mutableFloatStateOf(0f) }
    // -1 = trimming the left edge, +1 = the right edge, 0 = not trimming (long-press near an edge).
    var trimEdge by remember(clip.id) { mutableIntStateOf(0) }
    val edgeThresholdPx = with(density) { 24.dp.toPx() }
    // Live edge-trim preview: shift/resize the clip's box while dragging an edge (commit on release).
    val leftTrimPx = if (trimEdge < 0) trimStartPx else 0f
    val rightTrimPx = if (trimEdge > 0) trimEndPx else 0f
    // This clip's live move offset: the shared group drag if it's part of the active drag, else none.
    val moveDrag = groupDrag?.takeIf { clip.id in it.ids }
    val moveDx = moveDrag?.dx ?: 0f
    val moveDy = moveDrag?.dy ?: 0f
    val baseLeftPx = with(density) { msToDp(clip.startTimeMs).toPx() }
    val trackHeightPx = with(density) { state.trackHeight(clip.trackId).dp.toPx() }
    val sameTypeTracks = when (clip.type) {
        // Text clips live on video tracks, like any overlay/image clip.
        ClipType.VIDEO, ClipType.TEXT -> state.document.videoTracks
        ClipType.AUDIO -> state.document.audioTracks
    }

    // Held past the top/bottom edge for ~1s → create a new track of this clip's type there and drop the
    // clip onto it. Keyed on holdEdge, so moving back into range before the second elapses cancels it.
    LaunchedEffect(holdEdge, clip.id) {
        if (holdEdge != 0 && !autoTrackConsumed) {
            delay(1000)
            autoTrackConsumed = true
            val deltaMs = snappedDeltaMs(state, clip, (dragPx / pps * 1000f).toLong(), pps)
            vm.addEdgeTrackAndMoveClip(clip.id, atTop = holdEdge < 0, deltaMs = deltaMs)
            onGroupDrag(null)
        }
    }

    Box(
        Modifier
            // Live preview folds an in-progress edge trim into the box geometry: the left edge shifts the
            // offset and shrinks the width; the right edge changes the width. Committed on drag end.
            .offset { androidx.compose.ui.unit.IntOffset((baseLeftPx + moveDx + leftTrimPx).roundToInt(), moveDy.roundToInt()) }
            .padding(vertical = 6.dp)
            .width(with(density) { (msToDp(clip.durationMs).toPx() - leftTrimPx + rightTrimPx).coerceAtLeast(1f).toDp() })
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Red500.copy(alpha = 0.22f) else Neutral800)
            .border(1.dp, if (selected) Red500 else Neutral700, RoundedCornerShape(4.dp))
            // Tap: select, or split when split tool is active. Long-press: range-select
            // from the current selection to this clip (across tracks, all clips between).
            .pointerInput(clip.id, state.tool, pps, clip.keyframes) {
                detectTapGestures(
                    onLongPress = { offset ->
                        // Near an edge, a long-press starts an edge-trim drag (handled below) — only the
                        // middle of the clip range-selects.
                        if (offset.x > edgeThresholdPx && offset.x < size.width - edgeThresholdPx) {
                            vm.selectRangeTo(clip.id)
                        }
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
                    val ids = groupIdsOf(state, clip)
                    detectDragGestures(
                        onDragStart = { dragPx = 0f; dragPy = 0f; holdEdge = 0; autoTrackConsumed = false; onGroupDrag(GroupDrag(ids, 0f, 0f)) },
                        onDragEnd = {
                            holdEdge = 0
                            // If the 1s hold already created a track + moved the clip, don't move again.
                            if (!autoTrackConsumed) {
                                // Commit the same snapped delta the live preview showed. Group-aware:
                                // moveClipBy moves the whole group together.
                                val deltaMs = snappedDeltaMs(state, clip, (dragPx / pps * 1000f).toLong(), pps)
                                val shift = if (trackHeightPx > 0f) (dragPy / trackHeightPx).roundToInt() else 0
                                vm.moveClipBy(clip.id, shift, deltaMs)
                            }
                            onGroupDrag(null)
                        },
                        onDragCancel = { holdEdge = 0; onGroupDrag(null) },
                        onDrag = { change, drag ->
                            change.consume(); dragPx += drag.x; dragPy += drag.y
                            // Live + snapped: the whole group jumps to the magnet as any edge nears it.
                            onGroupDrag(GroupDrag(ids, snappedDragPx(state, clip, dragPx, pps), dragPy))
                            // Track whether the clip is currently dragged past the first/last lane of its
                            // type — holding there for ~1s (see LaunchedEffect) spawns a new track.
                            val shift = if (trackHeightPx > 0f) (dragPy / trackHeightPx).roundToInt() else 0
                            val targetIdx = sameTypeTracks.indexOf(clip.trackId) + shift
                            holdEdge = when {
                                autoTrackConsumed -> 0
                                targetIdx < 0 -> -1
                                targetIdx > sameTypeTracks.lastIndex -> 1
                                else -> 0
                            }
                        },
                    )
                }
            }
            // Long-press near an edge, then drag, to trim that in/out point (Vegas-style). A previously
            // split/trimmed clip re-extends by dragging its edge outward — trimClipStart/trimClipEnd
            // bound it to the source media. The grabbed edge previews live and commits on release.
            .pointerInput(clip.id, pps, state.tool) {
                if (state.tool != EditorTool.SELECT) return@pointerInput
                val edgePx = 24.dp.toPx()
                detectDragGesturesAfterLongPress(
                    onDragStart = { down ->
                        trimEdge = when {
                            down.x <= edgePx -> -1
                            down.x >= size.width - edgePx -> 1
                            else -> 0
                        }
                        trimStartPx = 0f; trimEndPx = 0f
                    },
                    onDrag = { change, drag ->
                        if (trimEdge != 0) {
                            change.consume()
                            if (trimEdge < 0) trimStartPx += drag.x else trimEndPx += drag.x
                        }
                    },
                    onDragEnd = {
                        when {
                            trimEdge < 0 -> vm.trimClipStart(clip.id, (trimStartPx / pps * 1000f).toLong())
                            trimEdge > 0 -> vm.trimClipEnd(clip.id, (trimEndPx / pps * 1000f).toLong())
                        }
                        trimEdge = 0; trimStartPx = 0f; trimEndPx = 0f
                    },
                    onDragCancel = { trimEdge = 0; trimStartPx = 0f; trimEndPx = 0f },
                )
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
                    val lo = prop.uiRange.start
                    val hi = prop.uiRange.endInclusive
                    val color = keyframeColor(prop)
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

        // Edge affordance: a subtle handle on each edge when selected (long-press + drag to trim),
        // brightening on the edge currently being trimmed.
        if (selected && state.tool == EditorTool.SELECT) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(Red500.copy(alpha = if (trimEdge < 0) 1f else 0.45f)),
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(Red500.copy(alpha = if (trimEdge > 0) 1f else 0.45f)),
            )
        }
    }
}

/** Ids that move together: the clip's group, plus all selected clips (and their groups) when the clip is selected. */
private fun groupIdsOf(state: EditorUiState, clip: TimelineClip): Set<String> {
    val clipGroupId = clip.groupId
    val group = if (clipGroupId != null) {
        val set = LinkedHashSet<String>()
        for (c in state.document.clips) {
            if (c.groupId == clipGroupId) set.add(c.id)
        }
        set
    } else {
        setOf(clip.id)
    }
    if (clip.id !in state.selectedClipIds) return group
    val allIds = LinkedHashSet(group)
    allIds.addAll(state.selectedClipIds)
    val groupIds = LinkedHashSet<String>()
    for (c in state.document.clips) {
        if (c.id in allIds) {
            val gId = c.groupId
            if (gId != null) groupIds.add(gId)
        }
    }
    if (groupIds.isNotEmpty()) {
        for (c in state.document.clips) {
            val gId = c.groupId
            if (gId != null && gId in groupIds) allIds.add(c.id)
        }
    }
    return allIds
}

/**
 * A "nice" grid increment (ms) whose on-screen spacing is at least ~44px at the current zoom.
 * The smallest possible unit is one frame at the project's [fps]; the list grows by
 * frame-count multiples, then whole seconds and beyond so every snap point lands on a
 * frame boundary.
 */
private fun gridIncrementMs(pps: Float, fps: Int = 30): Long {
    val frameMs = 1000.0 / fps
    val frameCounts = listOf(1, 2, 5, 10, 15) + listOf(fps / 2, fps, fps * 2, fps * 5,
        fps * 10, fps * 30, fps * 60).filter { it > 15 }
    val nice = frameCounts.distinct().sorted().map { n -> Math.round(n * frameMs) }
    return nice.firstOrNull { it / 1000f * pps >= 44f } ?: nice.last()
}

/**
 * Vegas-style snap for a moving clip/group: try to land ANY moving edge (every group member's start
 * AND end) on a magnet — timeline start (0), the playhead, or ANY non-moving clip's start/end (any
 * track) — within the strong radius; else snap a moving edge to the timeline grid within the weaker
 * radius; else free. Returns the (floor-clamped) delta to apply to the whole group. Soft: dragging
 * past a magnet keeps going (so you can overlap into a crossfade).
 */
private fun snappedDeltaMs(state: EditorUiState, clip: TimelineClip, rawDeltaMs: Long, pps: Float): Long {
    val fps = state.document.settings.fps
    val movingIds = groupIdsOf(state, clip)
    val moving = state.document.clips.filter { it.id in movingIds }
    val floorDelta = -(moving.minOfOrNull { it.startTimeMs } ?: 0L) // earliest member stays >= 0

    val strong = (SNAP_PX / pps * 1000f).toLong().coerceAtLeast(1L)
    val edges = sortedSetOf(0L, state.currentTimeMs)
    state.document.clips.forEach { c -> if (c.id !in movingIds) { edges.add(c.startTimeMs); edges.add(c.endTimeMs) } }

    var best = rawDeltaMs
    var bestDist = Long.MAX_VALUE
    fun consider(adjust: Long) {
        val d = kotlin.math.abs(adjust - rawDeltaMs)
        if (d < bestDist) { best = adjust; bestDist = d }
    }
    // Strong: any moving edge to any clip edge / playhead / start.
    moving.forEach { m ->
        val s = m.startTimeMs + rawDeltaMs
        val e = m.endTimeMs + rawDeltaMs
        edges.forEach { t ->
            if (kotlin.math.abs(t - s) <= strong) consider(rawDeltaMs + (t - s))
            if (kotlin.math.abs(t - e) <= strong) consider(rawDeltaMs + (t - e))
        }
    }
    if (bestDist == Long.MAX_VALUE) {
        // Weak: any moving edge to the nearest grid line.
        val grid = gridIncrementMs(pps, fps)
        val weak = (SNAP_GRID_PX / pps * 1000f).toLong().coerceAtLeast(1L)
        moving.forEach { m ->
            listOf(m.startTimeMs + rawDeltaMs, m.endTimeMs + rawDeltaMs).forEach { pos ->
                val nearest = Math.round(pos.toDouble() / grid) * grid
                if (kotlin.math.abs(nearest - pos) <= weak) consider(rawDeltaMs + (nearest - pos))
            }
        }
    }
    return best.coerceAtLeast(floorDelta)
}

/** The snapped horizontal offset in px for a live drag of [rawPx] (drives the live group preview). */
private fun snappedDragPx(state: EditorUiState, clip: TimelineClip, rawPx: Float, pps: Float): Float =
    snappedDeltaMs(state, clip, (rawPx / pps * 1000f).toLong(), pps) / 1000f * pps

/** Display color for each keyframe property's envelope. */
private fun keyframeColor(prop: KeyframeProperty): Color = when (prop) {
    KeyframeProperty.OPACITY -> White
    KeyframeProperty.SCALE, KeyframeProperty.ROTATION,
    KeyframeProperty.OFFSET_X, KeyframeProperty.OFFSET_Y -> Red500
    KeyframeProperty.VOLUME, KeyframeProperty.PAN -> Neutral400
    KeyframeProperty.BRIGHTNESS, KeyframeProperty.CONTRAST, KeyframeProperty.SATURATION,
    KeyframeProperty.HUE, KeyframeProperty.SEPIA -> Neutral500
}

/** Canvas position of a keyframe: x by time, y by value (higher value = higher on the clip). */
private fun keyframePos(kf: Keyframe, pps: Float, heightPx: Float): Offset {
    val lo = kf.property.uiRange.start
    val hi = kf.property.uiRange.endInclusive
    val norm = ((kf.value - lo) / (hi - lo)).coerceIn(0f, 1f)
    return Offset(kf.timeMs / 1000f * pps, heightPx * (1f - norm))
}

/** The next keyframe of the same property (its easing segment runs from [kf] to this). */
private fun nextKeyframe(clip: TimelineClip, kf: Keyframe): Keyframe? {
    val sameProp = clip.keyframes.filter { it.property == kf.property }.sortedBy { it.timeMs }
    return sameProp.getOrNull(sameProp.indexOf(kf) + 1)
}

/** Video/image clip background: thumbnail extracted via FFmpegFrameGrabber. */
@Composable
private fun ClipThumbnail(uri: String, kind: com.hereliesaz.guillotine.model.MediaKind, atMs: Long) {
    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri, atMs) {
        value = com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder.thumbnail(uri, kind, atMs)
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Neutral800.copy(alpha = 0.55f))) {
        thumb?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Audio clip background: waveform decoded via FFmpegFrameGrabber and drawn as peak bars. */
@Composable
private fun ClipWaveform(uri: String) {
    val waveform by androidx.compose.runtime.produceState<com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder.Waveform?>(null, uri) {
        value = com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder.waveform(uri)
    }
    Canvas(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
        val wf = waveform ?: return@Canvas
        val buckets = wf.left.size
        if (buckets == 0) return@Canvas
        val barW = size.width / buckets
        val halfH = size.height / 2f
        val gain = com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder.normalizeGain(wf)
        for (i in 0 until buckets) {
            val lH = (wf.left[i] * gain).coerceAtMost(1f) * halfH
            val rH = (wf.right[i] * gain).coerceAtMost(1f) * halfH
            val x = i * barW
            // Left channel: top half (draws upward from center)
            drawRect(
                color = Neutral400,
                topLeft = androidx.compose.ui.geometry.Offset(x, halfH - lH),
                size = androidx.compose.ui.geometry.Size(barW.coerceAtLeast(1f), lH),
            )
            // Right channel: bottom half (draws downward from center)
            drawRect(
                color = Neutral500,
                topLeft = androidx.compose.ui.geometry.Offset(x, halfH),
                size = androidx.compose.ui.geometry.Size(barW.coerceAtLeast(1f), rH),
            )
        }
    }
}
