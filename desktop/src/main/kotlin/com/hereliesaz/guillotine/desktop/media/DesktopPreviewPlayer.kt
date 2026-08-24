package com.hereliesaz.guillotine.desktop.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.hereliesaz.guillotine.desktop.ui.HeldModifiers
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.desktop.ui.PanelLayoutPrefs
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral950
import com.hereliesaz.guillotine.desktop.ui.theme.Red500
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.FxLayer
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SCRUB_SEEK_TOLERANCE_MS = 60L
private const val PLAY_DRIFT_POLL_MS = 400L

@Composable
fun DesktopPreviewPlayer(
    state: EditorUiState,
    modifier: Modifier = Modifier,
    /** True while the Crop tool is selected — enables the mouse transform gesture below on the
     *  selected clip. Mirrors the Android app's `cropMode`. */
    cropMode: Boolean = false,
    /** Mouse equivalent of Android's pinch/pan/rotate `detectTransformGestures`: called with the
     *  same (zoom, panXFrac, panYFrac, rotationDelta) shape [EditorViewModel.transformSelectedClip]
     *  expects, so the caller can wire it to that one shared function unchanged. */
    onCropTransform: (zoom: Float, panXFrac: Float, panYFrac: Float, rotationDelta: Float) -> Unit = { _, _, _, _ -> },
    /** See [FullscreenPreviewOverlay] — the caller owns fullscreen layout/state; this composable only
     *  exposes the corner toggle affordance (and, when in fullscreen, its "exit" variant). */
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // Persistent preview viewport (zoom + pan), loaded from / saved to PanelLayoutPrefs — same
    // behavior and persistence as the Android app's equivalent.
    val savedView = remember { PanelLayoutPrefs.loadPreview() }
    var zoom by remember { mutableStateOf(savedView.zoom) }
    var panX by remember { mutableStateOf(savedView.panX) }
    var panY by remember { mutableStateOf(savedView.panY) }
    var showZoom by remember { mutableStateOf(false) }
    var zoomPaneSize by remember { mutableStateOf(IntSize.Zero) }
    // Hand tool: drag to pan the zoomed-in viewport. Off by default so a plain drag elsewhere on the
    // preview isn't misread as panning.
    var panMode by remember { mutableStateOf(false) }
    fun clampPan() {
        val maxX = (zoomPaneSize.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (zoomPaneSize.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }
    LaunchedEffect(zoom, zoomPaneSize) { clampPan() }
    // Debounce disk writes: zoom/pan change rapidly during a drag; persist ~0.4s after they settle.
    LaunchedEffect(zoom, panX, panY) {
        delay(400)
        PanelLayoutPrefs.savePreview(zoom, panX, panY)
    }
    fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, PanelLayoutPrefs.MAX_ZOOM)
        if (zoom <= 1f) { panX = 0f; panY = 0f } else clampPan()
    }
    val panModifier = if (panMode) {
        Modifier.pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                panX += drag.x
                panY += drag.y
                clampPan()
            }
        }
    } else {
        Modifier
    }

    // Crop/transform tool — the mouse equivalent of Android's pinch-drag-rotate on the preview.
    // No pinch on a mouse, so: plain drag pans the clip (matching Android's single-finger pan),
    // Shift-held drag rotates instead (checked once at drag-start, the same "snapshot the held
    // modifier when the gesture begins" pattern the timeline's clip drag already uses for its own
    // Ctrl/Alt/Shift-gated edits), and the scroll wheel zooms.
    val cropModifier = if (cropMode) {
        Modifier
            .pointerInput(Unit) {
                var rotateMode = false
                detectDragGestures(
                    onDragStart = { rotateMode = HeldModifiers.shift.value },
                    onDrag = { change, drag ->
                        change.consume()
                        if (rotateMode) {
                            onCropTransform(1f, 0f, 0f, drag.x * 0.3f)
                        } else {
                            val w = zoomPaneSize.width.coerceAtLeast(1)
                            val h = zoomPaneSize.height.coerceAtLeast(1)
                            onCropTransform(1f, drag.x / w, drag.y / h, 0f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) {
                                event.changes.forEach { it.consume() }
                                onCropTransform(if (dy > 0) 0.95f else 1.05f, 0f, 0f, 0f)
                            }
                        }
                    }
                }
            }
    } else {
        Modifier
    }
    val gestureModifier = if (cropMode) cropModifier else panModifier

    val now = state.currentTimeMs
    val clips = state.document.clips.filterNot { it.trackId in state.effectivePreviewDisabledTrackIds }
    val activeText = TimelineMath.activeClips(clips, ClipType.TEXT, now)
    val activeVideoClips = TimelineMath.activeClips(clips, ClipType.VIDEO, now)
    val anyActiveVideo = activeVideoClips.isNotEmpty()
    // Which clip the crop tool's drag/scroll gestures above actually target — mirrors
    // EditorViewModel.cropTargetClipId's own resolution exactly, so the outline drawn below always
    // matches what transformSelectedClip will act on. Used to outline that one clip's frame while
    // the tool is active, so it's visually unambiguous that only this clip — not the preview
    // viewport — is what the gesture moves.
    val cropTargetClipId = if (cropMode) {
        (state.selectedClips.firstOrNull { it.type != ClipType.AUDIO } ?: state.selectedClips.firstOrNull())?.id
    } else {
        null
    }

    val aspectMod = when (state.document.settings.aspectRatio) {
        AspectRatio.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
        AspectRatio.RATIO_9_16 -> Modifier.aspectRatio(9f / 16f)
        AspectRatio.RATIO_1_1 -> Modifier.aspectRatio(1f)
        AspectRatio.ORIGINAL -> Modifier.fillMaxSize()
    }

    // Global crop (x/y/w/h in % of the frame): scale the crop sub-rectangle up to fill the frame, so
    // the preview matches the export's crop semantics (DesktopExporter takes the same sub-rect and
    // scales it to the output). Applied to the whole framed content — video AND text — via a single
    // graphicsLayer, then clipped, so captions crop and scale in lockstep with the footage (WYSIWYG).
    // Suppressed in crop mode, matching Android: the user is dragging the crop itself and needs to
    // see what they are cutting away, not a preview already cropped by the project-level rectangle.
    val crop = state.document.settings.crop
    val cropped = !cropMode && (crop.x != 0f || crop.y != 0f || crop.w != 100f || crop.h != 100f)
    val sx = if (crop.w > 0f) 100f / crop.w else 1f
    val sy = if (crop.h > 0f) 100f / crop.h else 1f

    Box(
        modifier = modifier.background(Neutral950).onSizeChanged { zoomPaneSize = it }.then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
      // The composited frame — video, captions, and audio layers — lives in this inner box, which the
      // zoom/pan graphicsLayer scales and translates as one. Controls (below) sit outside it so they
      // never scale with the preview.
      Box(
          modifier = Modifier
              .fillMaxSize()
              .graphicsLayer {
                  scaleX = zoom
                  scaleY = zoom
                  translationX = panX
                  translationY = panY
              },
          contentAlignment = Alignment.Center,
      ) {
        if (!anyActiveVideo) {
            Text("No video at ${"%.2f".format(now / 1000f)}s", color = Neutral500, fontSize = 12.sp)
        }
        state.document.audioTracks.forEach { trackId ->
            key(trackId) {
                AudioTrackLayer(
                    trackId = trackId,
                    clips = clips,
                    trackSettings = state.document.trackSettingsFor(trackId),
                    mediaFor = state.document::mediaFor,
                    now = now,
                    isPlaying = state.isPlaying,
                )
            }
        }
        // The framed content box: carries the aspect ratio (the export frame), measures previewSize
        // (so text offsets are relative to the frame, matching export), and applies the crop transform.
        Box(
            modifier = aspectMod
                .onSizeChanged { previewSize = it }
                .then(
                    if (cropped) Modifier.graphicsLayer {
                        clip = true
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = sx
                        scaleY = sy
                        translationX = -(crop.x / 100f) * size.width * sx
                        translationY = -(crop.y / 100f) * size.height * sy
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
        state.document.videoTracks.asReversed().forEach { trackId ->
            key(trackId) {
                VideoTrackLayer(
                    trackId = trackId,
                    clips = clips,
                    trackSettings = state.document.trackSettingsFor(trackId),
                    mediaFor = state.document::mediaFor,
                    now = now,
                    isPlaying = state.isPlaying,
                    frameDurationMs = state.document.settings.frameDurationMs,
                    cropTargetClipId = cropTargetClipId,
                )
            }
        }
        activeText.forEach { t ->
            val relMs = (now - t.startTimeMs).coerceIn(0, t.durationMs)
            val scale = TimelineMath.valueAt(t, KeyframeProperty.SCALE, relMs, t.scale)
            val rotation = TimelineMath.valueAt(t, KeyframeProperty.ROTATION, relMs, t.rotation)
            val ox = TimelineMath.valueAt(t, KeyframeProperty.OFFSET_X, relMs, t.offsetX)
            val oy = TimelineMath.valueAt(t, KeyframeProperty.OFFSET_Y, relMs, t.offsetY)
            val opacity = TimelineMath.valueAt(t, KeyframeProperty.OPACITY, relMs, 1f)
            val trackOpacity = state.document.trackSettingsFor(t.trackId).opacity.coerceIn(0f, 1f)
            Text(
                t.text,
                color = White.copy(alpha = (opacity * trackOpacity).coerceIn(0f, 1f)),
                fontSize = 14.sp,
                fontFamily = when (t.font) {
                    com.hereliesaz.guillotine.model.TextFont.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
                    com.hereliesaz.guillotine.model.TextFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
                    com.hereliesaz.guillotine.model.TextFont.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
                    com.hereliesaz.guillotine.model.TextFont.CURSIVE -> androidx.compose.ui.text.font.FontFamily.Cursive
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            (ox * previewSize.width).roundToInt(),
                            (oy * previewSize.height).roundToInt(),
                        )
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation }
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .then(
                        if (t.id == cropTargetClipId) {
                            Modifier.border(1.dp, Red500)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        }
        // Persistent frame boundary — the actual output canvas rectangle. Drawn LAST (front of every
        // video/caption layer above) so it stays visible however opaque or overflowing the content
        // beneath it is, instead of being paintable-over by a clip that fills to the frame edge.
        // Immovable: no per-clip graphicsLayer transform, just aspectMod's fixed sizing, so it never
        // scales/pans/rotates with a clip's crop — only with the shared preview zoom (this whole box
        // already sits inside that graphicsLayer). Deliberately outside the project-crop transform
        // above (which only wraps the video tracks), so it stays the true full frame even when a
        // project-level crop changes what's currently visible — matching Android's PreviewPlayer.
        // Also covers the empty-project case: VideoSlot bails out entirely when its clip is null, so
        // without a boundary independent of clip state the frame vanished completely with no clips.
        Box(modifier = aspectMod.border(1.dp, Neutral500))
        // Each active video clip's own true rectangle, drawn after (in front of) the frame boundary
        // above — visible exactly where a clip's content is cut off by the frame. The crop tool's
        // actual target gets the richer interactive CropWireframe (drag-to-resize handles); every
        // other active clip gets a plain outline. Both share the clip's own scale/rotate/pan (minus
        // rotation for the interactive one — see CropWireframe's own doc) so they track the clip
        // exactly; a plain resting clip's outline just coincides with the frame.
        activeVideoClips.forEach { clip ->
            key(clip.id) {
                val relMs = now - clip.startTimeMs
                val s = TimelineMath.valueAt(clip, KeyframeProperty.SCALE, relMs, clip.scale).coerceAtLeast(0f)
                val rotationDeg = TimelineMath.valueAt(clip, KeyframeProperty.ROTATION, relMs, clip.rotation)
                val offXFrac = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_X, relMs, clip.offsetX)
                val offYFrac = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_Y, relMs, clip.offsetY)
                Box(modifier = aspectMod, contentAlignment = Alignment.Center) {
                    if (clip.id == cropTargetClipId) {
                        CropWireframe(
                            scale = s, offsetXFrac = offXFrac, offsetYFrac = offYFrac,
                            onCropTransform = onCropTransform,
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = s
                                    scaleY = s
                                    rotationZ = rotationDeg
                                    translationX = offXFrac * size.width
                                    translationY = offYFrac * size.height
                                }
                                .border(1.dp, Red500),
                        )
                    }
                }
            }
        }
      } // end zoomed frame

        // Zoom + fullscreen controls — fixed size, OUTSIDE the zoomed layer so they never scale with
        // the preview.
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = { setZoom(zoom - PanelLayoutPrefs.ZOOM_STEP) }, enabled = zoom > 1f) {
                androidx.compose.material3.Icon(
                    Icons.Filled.ZoomOut,
                    contentDescription = "Zoom out",
                    tint = if (zoom > 1f) White else Neutral500,
                )
            }
            androidx.compose.material3.IconButton(onClick = { setZoom(zoom + PanelLayoutPrefs.ZOOM_STEP) }, enabled = zoom < PanelLayoutPrefs.MAX_ZOOM) {
                androidx.compose.material3.Icon(
                    Icons.Filled.ZoomIn,
                    contentDescription = "Zoom in",
                    tint = if (zoom < PanelLayoutPrefs.MAX_ZOOM) White else Neutral500,
                )
            }
            // Hand tool: drag the zoomed-in preview to pan it.
            androidx.compose.material3.IconButton(onClick = { panMode = !panMode }) {
                androidx.compose.material3.Icon(
                    Icons.Filled.PanTool,
                    contentDescription = if (panMode) "Hand tool (on)" else "Hand tool",
                    tint = if (panMode) Red500 else Neutral500,
                )
            }
            Box {
                androidx.compose.material3.IconButton(onClick = { showZoom = !showZoom }) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.ZoomIn,
                        contentDescription = "Zoom preview",
                        tint = if (zoom > 1f) White else Neutral500,
                    )
                }
                if (showZoom) {
                    androidx.compose.ui.window.Popup(
                        alignment = Alignment.TopEnd,
                        onDismissRequest = { showZoom = false },
                        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                    ) {
                        androidx.compose.material3.Surface(
                            color = Neutral950,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            modifier = Modifier.width(220.dp).padding(top = 44.dp),
                        ) {
                            androidx.compose.foundation.layout.Column(Modifier.padding(12.dp)) {
                                Text("Zoom  ${"%.1f".format(zoom)}x", color = White, fontSize = 12.sp)
                                androidx.compose.material3.Slider(
                                    value = zoom.coerceIn(1f, PanelLayoutPrefs.MAX_ZOOM),
                                    onValueChange = ::setZoom,
                                    valueRange = 1f..PanelLayoutPrefs.MAX_ZOOM,
                                )
                                androidx.compose.material3.TextButton(onClick = { setZoom(1f) }) {
                                    Text("Fit", color = White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (onToggleFullscreen != null) {
                androidx.compose.material3.IconButton(onClick = onToggleFullscreen) {
                    androidx.compose.material3.Icon(
                        if (isFullscreen) {
                            Icons.Filled.FullscreenExit
                        } else {
                            Icons.Filled.Fullscreen
                        },
                        contentDescription = if (isFullscreen) "Exit full screen" else "Full screen",
                        tint = White,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoTrackLayer(
    trackId: String,
    clips: List<TimelineClip>,
    trackSettings: com.hereliesaz.guillotine.model.TrackSettings,
    mediaFor: (TimelineClip) -> MediaItem?,
    now: Long,
    isPlaying: Boolean,
    frameDurationMs: Double,
    cropTargetClipId: String? = null,
) {
    val active = TimelineMath.activeClips(clips, ClipType.VIDEO, now)
        .filter { it.trackId == trackId }
        .sortedBy { it.startTimeMs }
    val outgoing = active.getOrNull(0)
    val incoming = active.getOrNull(1)

    val xfade = if (outgoing != null && incoming != null) {
        val span = (outgoing.endTimeMs - incoming.startTimeMs).coerceAtLeast(1)
        ((now - incoming.startTimeMs).toFloat() / span).coerceIn(0f, 1f)
    } else {
        null
    }

    val trackOpacity = trackSettings.opacity
    val opacityA = outgoing?.let {
        TimelineMath.valueAt(it, KeyframeProperty.OPACITY, now - it.startTimeMs, 1f)
    }?.times(trackOpacity)?.times(1f - (xfade ?: 0f)) ?: 0f
    val opacityB = incoming?.let {
        TimelineMath.valueAt(it, KeyframeProperty.OPACITY, now - it.startTimeMs, 1f)
    }?.times(trackOpacity)?.times(xfade ?: 0f) ?: 0f

    VideoSlot(outgoing, mediaFor, opacityA, now, isPlaying, frameDurationMs, cropTargetClipId)
    VideoSlot(incoming, mediaFor, opacityB, now, isPlaying, frameDurationMs, cropTargetClipId)
}

@Composable
private fun VideoSlot(
    clip: TimelineClip?,
    mediaFor: (TimelineClip) -> MediaItem?,
    alpha: Float,
    now: Long,
    isPlaying: Boolean,
    frameDurationMs: Double,
    cropTargetClipId: String? = null,
) {
    if (clip == null || alpha <= 0f) return
    val media = mediaFor(clip) ?: return

    // Frame decimation (frameStep): hold the same grabbed frame across `frameStep` output frames by
    // snapping the source time to the project's kept-frame grid. No-op when frameStep <= 1.
    val sourceMs = TimelineMath.decimateSourceMs(
        clip,
        TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0),
        frameDurationMs,
    )
    var frame by remember(clip.id, media.id) { mutableStateOf<ImageBitmap?>(null) }
    var lastDecodedMs by remember(clip.id, media.id) { mutableStateOf(-1L) }

    val currentSourceMs by rememberUpdatedState(sourceMs)
    val currentClip by rememberUpdatedState(clip)

    // One reused decoder per video/audio source, seeked instead of reopened per frame — same reasoning
    // as the export path. Held for the slot's lifetime and released when it leaves the composition.
    val grabber = remember(clip.id, media.id) {
        if (media.kind != MediaKind.IMAGE) {
            uriToFile(media.uri)?.let { f -> runCatching { PreviewGrabber(f) }.getOrNull() }
        } else null
    }
    DisposableEffect(grabber) {
        onDispose { grabber?.release() }
    }

    // Scrub: decode when playhead moves past the tolerance threshold. Paused, so subject segmentation
    // (background removal / bokeh) is applied here — it's too heavy to run at playback frame rate.
    LaunchedEffect(clip.id, media.id, isPlaying) {
        if (isPlaying) return@LaunchedEffect
        // Decode loop for scrubbing: watches sourceMs changes via rememberUpdatedState
        while (isActive) {
            val ms = currentSourceMs
            if (abs(ms - lastDecodedMs) > SCRUB_SEEK_TOLERANCE_MS || lastDecodedMs < 0) {
                val bmp = withContext(Dispatchers.IO) {
                    decodeFrame(media, ms, currentClip, grabber, applySeg = true)
                }
                frame = bmp
                lastDecodedMs = ms
            }
            delay(16)
        }
    }

    // Playback: decode at ~30fps. Segmentation is skipped here (applySeg=false) so ONNX matting doesn't
    // stall the frame rate; the segmented look reappears the moment playback pauses.
    LaunchedEffect(clip.id, media.id, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            val ms = currentSourceMs
            val bmp = withContext(Dispatchers.IO) {
                decodeFrame(media, ms, currentClip, grabber, applySeg = false)
            }
            frame = bmp
            lastDecodedMs = ms
            delay(33)
        }
    }

    val relMs = now - clip.startTimeMs
    val s = TimelineMath.valueAt(clip, KeyframeProperty.SCALE, relMs, clip.scale).coerceAtLeast(0f)
    val rotationDeg = TimelineMath.valueAt(clip, KeyframeProperty.ROTATION, relMs, clip.rotation)
    val offXFrac = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_X, relMs, clip.offsetX)
    val offYFrac = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_Y, relMs, clip.offsetY)

    // Clip to the frame BEFORE the transform below, for every clip except the one the crop tool is
    // actively editing. A graphicsLayer scale/rotate doesn't clip its own content by default, so
    // without this a transformed clip paints past the frame's own rectangle — into the letterbox,
    // over the zoom controls — which for a resting (non-target) clip reads as "the preview moved"
    // rather than "the clip overflowed," so it stays clipped there (matching Android's VideoSlot,
    // which has always clipped, and export, which never shows what's outside the frame). But the
    // clip actively being cropped is deliberately exempted: the whole point of dragging/scaling it
    // is to decide what ends up inside the frame vs. cut away, and that decision needs the
    // overflowing part visible, not hidden — [DesktopPreviewPlayer]'s top-level outline pass (see
    // there) marks where the frame boundary actually is so "in frame" vs. "will be cropped" stays
    // legible while it paints past that line.
    val isCropTarget = clip.id == cropTargetClipId
    val mod = Modifier
        .fillMaxSize()
        .then(if (isCropTarget) Modifier else Modifier.clipToBounds())
        .graphicsLayer {
            this.alpha = alpha.coerceIn(0f, 1f)
            scaleX = s
            scaleY = s
            rotationZ = rotationDeg
            translationX = offXFrac * size.width
            translationY = offYFrac * size.height
        }

    frame?.let { bmp ->
        Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = mod)
    }
}

/**
 * The crop tool's resize affordance: an outline at the selected clip's true (possibly off-frame)
 * corners, with a small handle at each corner. Dragging a handle scales the clip from its centre —
 * the model has one uniform `scale`, not independent width/height, so there's no meaningful
 * per-edge resize, only "grow/shrink toward or away from this corner's direction". Deliberately not
 * rotation-aware (handles sit at the axis-aligned scaled+panned rectangle, ignoring the clip's own
 * rotation) — combining a rotated wireframe with correct drag-to-resize math is a lot of added
 * complexity for a case (resizing while heavily rotated) most edits won't hit; rotate and resize are
 * still each fully usable, just not simultaneously precise.
 */
@Composable
private fun CropWireframe(
    scale: Float,
    offsetXFrac: Float,
    offsetYFrac: Float,
    onCropTransform: (zoom: Float, panXFrac: Float, panYFrac: Float, rotationDelta: Float) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetXFrac * size.width
                translationY = offsetYFrac * size.height
            }
            .border(1.dp, Red500),
    ) {
        val corners = listOf(
            Alignment.TopStart to Pair(-1f, -1f),
            Alignment.TopEnd to Pair(1f, -1f),
            Alignment.BottomStart to Pair(-1f, 1f),
            Alignment.BottomEnd to Pair(1f, 1f),
        )
        corners.forEach { (alignment, sign) ->
            val (signX, signY) = sign
            // Counter-scale so the handle stays a constant on-screen size regardless of how zoomed
            // in/out the clip is — otherwise a 6x-scaled clip's handle would render at 6x size, and a
            // 0.1x one would shrink to under a pixel.
            val handleSize = (HANDLE_SIZE_DP / scale.coerceAtLeast(0.05f)).dp
            Box(
                Modifier
                    .align(alignment)
                    .size(handleSize)
                    .background(Red500)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            // Radial component of the drag along this corner's outward direction:
                            // dragging away from centre grows the clip, dragging toward it shrinks.
                            val radial = drag.x * signX + drag.y * signY
                            onCropTransform(1f + radial / RESIZE_SENSITIVITY, 0f, 0f, 0f)
                        }
                    },
            )
        }
    }
}

private const val HANDLE_SIZE_DP = 10f
private const val RESIZE_SENSITIVITY = 200f

private fun decodeFrame(
    media: MediaItem,
    sourceMs: Long,
    clip: TimelineClip,
    grabber: PreviewGrabber?,
    applySeg: Boolean,
): ImageBitmap? {
    val file = uriToFile(media.uri) ?: return null

    if (media.kind == MediaKind.IMAGE) {
        return runCatching {
            val img = javax.imageio.ImageIO.read(file) ?: return null
            val out = applyColorEffects(img, clip, sourceMs, applySeg)
            out.toComposeImageBitmap()
        }.getOrNull()
    }

    val img = grabber?.frameAt(sourceMs) ?: return null
    return runCatching {
        val out = applyColorEffects(img, clip, sourceMs, applySeg)
        out.toComposeImageBitmap()
    }.getOrNull()
}

/** Applies colour matrix + LUT in place, then (when [applySeg]) subject segmentation, returning the
 *  image to draw. Segmentation may return a new bitmap, so the caller uses the returned reference. */
private fun applyColorEffects(
    img: BufferedImage,
    clip: TimelineClip,
    sourceMs: Long,
    applySeg: Boolean = false,
): BufferedImage {
    val f = clip.filters
    val relMs = sourceMs - clip.trimStartMs
    val brightness = TimelineMath.valueAt(clip, KeyframeProperty.BRIGHTNESS, relMs, f.brightness)
    val contrast = TimelineMath.valueAt(clip, KeyframeProperty.CONTRAST, relMs, f.contrast)
    val saturation = TimelineMath.valueAt(clip, KeyframeProperty.SATURATION, relMs, f.saturation)
    val hue = TimelineMath.valueAt(clip, KeyframeProperty.HUE, relMs, f.hueRotate)
    val sepia = TimelineMath.valueAt(clip, KeyframeProperty.SEPIA, relMs, f.sepia)
    if (!DesktopColorMatrix.isIdentity(brightness, contrast, saturation, hue, sepia, f.grayscale, f.invert)) {
        val matrix = DesktopColorMatrix.buildMatrix(brightness, contrast, saturation, hue, sepia, f.grayscale, f.invert)
        DesktopColorMatrix.applyToImage(img, matrix)
    }
    if (f.blur > 0f) DesktopColorMatrix.blur(img, f.blur)
    // The clip's FX chain (LUTs/shaders), in chain order — see ClipFilters.effectiveFxChain for the
    // legacy single-slot fallback that keeps an already-saved project rendering unchanged. LUT layers
    // apply first (matching the pre-chain LUT-then-shader order); parsed/cached by path so nothing is
    // re-parsed per frame.
    val chain = f.effectiveFxChain.filter { it.enabled }
    for (layer in chain) {
        if (layer.kind != FxLayer.KIND_LUT) continue
        DesktopLutCache.get(layer.path)?.let { DesktopColorMatrix.applyLut(img, it) }
    }

    // Subject segmentation, matching the export path's order (after colour/LUT, before shaders). Only
    // run when the caller opted in (paused), since ONNX matting is far too slow for live playback:
    //  • removeBackground → matte the subject to alpha so lower tracks / the letterbox show through
    //  • bokeh → keep the subject sharp and blur the background
    val segged = if (applySeg) {
        val segModel = DesktopRenderConfig.segModelPath
        if (segModel.isNotBlank()) {
            when {
                f.removeBackground -> runCatching { DesktopSegmenter.matte(img, segModel) }.getOrDefault(img)
                f.bokeh -> runCatching { DesktopSegmenter.portraitBlur(img, segModel) }.getOrDefault(img)
                else -> img
            }
        } else img
    } else img
    // Custom GLSL/ISF shader layers, applied last in chain order (matches the export path). Rendered
    // via Skia's CPU raster runtime effect; a no-op if a shader can't be compiled to SkSL.
    var out = segged
    for (layer in chain) {
        if (layer.kind != FxLayer.KIND_SHADER) continue
        out = DesktopShaderPass.apply(out, layer.path, layer.params, relMs.coerceAtLeast(0))
    }
    return out
}

/** A reused FFmpeg decoder for the preview. Seeks (instead of reopening) toward the requested source
 *  time, grabbing forward when the target is just ahead — the same cheap-seek strategy as the export
 *  path. Accessed from both the scrub and playback IO loops, so every method is synchronized. */
private class PreviewGrabber(file: File) {
    // Open via a helper (not `FFmpegFrameGrabber(file).apply { start() }` inline) so a `start()`
    // failure (corrupt/truncated file, unsupported codec) releases the native handle before the
    // exception propagates, instead of leaking the AVFormatContext.
    private val grabber = openGrabber(file)
    private val converter = Java2DFrameConverter()
    private var closed = false

    @Synchronized
    fun frameAt(sourceMs: Long): BufferedImage? {
        if (closed) return null
        return runCatching {
            val target = sourceMs * 1000L
            val current = grabber.timestamp
            if (current < 0 || target < current || target - current > 500_000L) {
                grabber.timestamp = target
            }
            var f = grabber.grabImage() ?: return@runCatching null
            var attempts = 0
            while (f.image == null && attempts++ < 5) {
                f = grabber.grabImage() ?: return@runCatching null
            }
            converter.convert(f)
        }.getOrNull()
    }

    @Synchronized
    fun release() {
        if (closed) return
        closed = true
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }
}

/**
 * Open and start an [FFmpegFrameGrabber] on [file], releasing its native handle first if `start()`
 * throws instead of leaking the AVFormatContext.
 */
private fun openGrabber(file: File): FFmpegFrameGrabber {
    val grabber = FFmpegFrameGrabber(file)
    try {
        grabber.start()
    } catch (e: Exception) {
        runCatching { grabber.release() }
        throw e
    }
    return grabber
}

@Composable
private fun AudioTrackLayer(
    trackId: String,
    clips: List<TimelineClip>,
    trackSettings: com.hereliesaz.guillotine.model.TrackSettings,
    mediaFor: (TimelineClip) -> MediaItem?,
    now: Long,
    isPlaying: Boolean,
) {
    val player = remember { DesktopAudioPlayer() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val active = clips
        .filter { it.type == ClipType.AUDIO && it.trackId == trackId }
        .filter { now >= it.startTimeMs && now < it.endTimeMs }
        .minByOrNull { it.startTimeMs }
    val media = active?.let(mediaFor)

    val volume = if (trackSettings.muted) 0f else (active?.let {
        TimelineMath.valueAt(it, KeyframeProperty.VOLUME, now - it.startTimeMs, it.filters.volume)
    } ?: 0f) * trackSettings.volume
    val pan = active?.let {
        TimelineMath.valueAt(it, KeyframeProperty.PAN, now - it.startTimeMs, it.filters.pan)
    } ?: 0f

    val norm by produceState(1f, media?.id, active?.filters?.normalize) {
        value = if (active?.filters?.normalize == true && media != null) {
            DesktopMediaDecoder.waveform(media.uri)
                ?.let { DesktopMediaDecoder.normalizeGain(it) } ?: 1f
        } else {
            1f
        }
    }

    LaunchedEffect(volume, norm, pan) {
        player.gain = (volume * norm).coerceAtLeast(0f)
        player.pan = pan.coerceIn(-1f, 1f)
    }

    var lastMediaId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(media?.id, isPlaying) {
        if (media == null || active == null || !isPlaying) {
            player.stop()
            lastMediaId = null
            return@LaunchedEffect
        }
        val file = uriToFile(media.uri) ?: return@LaunchedEffect
        val src = TimelineMath.sourceTimeMs(active, now).coerceAtLeast(0)
        if (media.id != lastMediaId) {
            player.start(file, src, 1f)
            lastMediaId = media.id
        }
    }

    // This coroutine is launched once per (isPlaying, active clip) pair and then keeps running
    // across every later recomposition without being relaunched — so reading `now`/`active`
    // directly here would close over whatever value they happened to have at launch time, frozen
    // for the rest of the clip's playback. That was the bug: every ~400ms this recomputed `src`
    // from that stale, unchanging `now`, so once the live position drifted past it by 300ms the
    // poll kept seeking the player back to nearly the same point — audio looping a small window
    // while video (whose decode loops already read `rememberUpdatedState` values, see VideoSlot's
    // `currentSourceMs`/`currentClip`) kept advancing normally. `rememberUpdatedState` gives this
    // loop the live values on every poll instead.
    val currentNow by rememberUpdatedState(now)
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(isPlaying, active?.id) {
        if (active != null && isPlaying) {
            while (isActive) {
                delay(PLAY_DRIFT_POLL_MS)
                val clip = currentActive ?: continue
                val src = TimelineMath.sourceTimeMs(clip, currentNow).coerceAtLeast(0)
                if (abs(player.positionMs - src) > 300L) {
                    player.seek(src)
                }
            }
        }
    }
}

private fun uriToFile(uri: String): File? = runCatching {
    when {
        uri.startsWith("file:") -> File(URI(uri))
        uri.startsWith("/") -> File(uri)
        else -> null
    }
}.getOrNull()
