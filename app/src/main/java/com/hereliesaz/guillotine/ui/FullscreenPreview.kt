package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White

/**
 * Full-screen "cinema mode": the preview fills the whole screen and every other panel (timeline,
 * side tools, toolbar) is hidden — reached from [PreviewPlayer]'s corner Fullscreen button. Since
 * the full multi-track timeline is gone, a floating toolbar at the bottom carries the handful of
 * things you'd still want without leaving fullscreen: transport, split, undo/redo, and a single-lane
 * [ScrubBar] standing in for the timeline so the whole project is still scrubbable.
 */
@Composable
fun FullscreenPreviewOverlay(vm: EditorViewModel, state: EditorUiState, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PreviewPlayer(
            state,
            Modifier.fillMaxSize(),
            isFullscreen = true,
            onToggleFullscreen = onExit,
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // This Box is edge-to-edge with no ancestor inset padding (cinema mode hides the
                // normal systemBarsPadding()'d editor chrome), so the scrubber/transport toolbar must
                // clear the navigation bar (or a side-docked one in landscape) itself.
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ScrubBar(state, onSeek = vm::seekTo)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val total = state.document.totalDurationMs
                IconToolButton(Icons.Filled.SkipPrevious, "Start") { vm.seekTo(0) }
                IconToolButton(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/Pause",
                ) { vm.togglePlay() }
                IconToolButton(Icons.Filled.SkipNext, "End") { vm.seekTo(total) }
                IconToolButton(Icons.Filled.ContentCut, "Split at playhead") { vm.splitAtPlayhead() }
                IconToolButton(Icons.Filled.Undo, "Undo", enabled = state.canUndo) { vm.undo() }
                IconToolButton(Icons.Filled.Redo, "Redo", enabled = state.canRedo) { vm.redo() }
                IconToolButton(Icons.Filled.FullscreenExit, "Exit full screen", onClick = onExit)
            }
        }
    }
}

/**
 * A condensed, single-lane stand-in for the timeline: every clip across every track flattened onto
 * one bar (colored by type), with a playhead line. Tap or drag anywhere to scrub — the only timeline
 * interaction fullscreen mode offers, deliberately: this is a "keep watching, glance-scrub" control,
 * not a second copy of the real per-track timeline.
 */
@Composable
private fun ScrubBar(state: EditorUiState, onSeek: (Long) -> Unit) {
    val total = state.document.totalDurationMs.coerceAtLeast(1L)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Neutral900)
            .pointerInput(total) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.coerceAtLeast(1) * total).toLong().coerceIn(0, total))
                }
            }
            .pointerInput(total) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width.coerceAtLeast(1) * total).toLong().coerceIn(0, total))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        state.document.clips.forEach { c ->
            val x0 = (c.startTimeMs.toFloat() / total) * w
            val x1 = (c.endTimeMs.toFloat() / total) * w
            drawRect(
                color = scrubClipColor(c.type),
                topLeft = Offset(x0, 0f),
                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), h),
            )
        }
        val px = (state.currentTimeMs.toFloat() / total) * w
        drawLine(White, Offset(px, 0f), Offset(px, h), strokeWidth = 3f)
    }
}

private fun scrubClipColor(type: ClipType): Color = when (type) {
    ClipType.VIDEO -> Red500.copy(alpha = 0.6f)
    ClipType.AUDIO -> Neutral400.copy(alpha = 0.6f)
    ClipType.TEXT -> Neutral500.copy(alpha = 0.6f)
}
