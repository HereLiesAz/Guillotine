@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.media.LiveAudioProcessor
import com.hereliesaz.guillotine.media.SubjectSegmenter
import com.hereliesaz.guillotine.media.VideoEffects
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.PreviewGeometry
import com.hereliesaz.guillotine.model.TimelineMath
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral950
import com.hereliesaz.guillotine.ui.theme.White
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SCRUB_SEEK_TOLERANCE_MS = 60L
private const val PLAY_DRIFT_TOLERANCE_MS = 300L

/**
 * The video preview surface. It is slaved to the editor's timeline clock
 * (`state.currentTimeMs`).
 *
 * The picture is composited like a real NLE: **one video layer per video track**
 * (see [VideoTrackLayer]), stacked bottom-to-top so `videoTracks[0]` is on top.
 * Each layer crossfades its own overlapping clips and, for background-removed
 * clips, renders an on-device matte cutout so lower tracks show through.
 *
 * Audio is separate: video layers are muted (picture only), and there is **one audio player per
 * audio track** (see [AudioTrackLayer]). Multiple audio tracks (music + voiceover + effects) mix
 * through Android's audio layer instead of only playing the topmost one, and a video's own sound
 * still plays via its linked shadow audio clip. Within a single audio track only one clip is
 * played at a time (audio doesn't crossfade here), so preview audio still can't double.
 */
@Composable
fun PreviewPlayer(
    state: EditorUiState,
    modifier: Modifier = Modifier,
    cropMode: Boolean = false,
    /** Draw platform safe-zone guides (caption/UI areas) over vertical/square projects. */
    showSafeZones: Boolean = false,
    onCropTransform: (zoom: Float, panXFrac: Float, panYFrac: Float, rotationDelta: Float) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    val now = state.currentTimeMs
    // Disabled/hidden tracks drop out entirely.
    val clips = state.document.clips.filterNot { it.trackId in state.document.disabledTrackIds }

    // Audio wiring is per-track (see AudioTrackLayer): each audio track owns its own ExoPlayer
    // and the tracks mix through Android's audio layer, so parallel audio (music + voiceover, etc.)
    // all plays. Video layers are muted (picture only) so preview audio only comes from here.
    val activeText = TimelineMath.activeClips(clips, ClipType.TEXT, now)
    val anyActiveVideo = TimelineMath.activeClips(clips, ClipType.VIDEO, now).isNotEmpty()

    // ---- surface ----
    val aspectMod = when (state.document.settings.aspectRatio) {
        AspectRatio.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
        AspectRatio.RATIO_9_16 -> Modifier.aspectRatio(9f / 16f)
        AspectRatio.RATIO_1_1 -> Modifier.aspectRatio(1f)
        AspectRatio.ORIGINAL -> Modifier.fillMaxSize()
    }

    val cropModifier = if (cropMode) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, rotation ->
                val w = previewSize.width.coerceAtLeast(1)
                val h = previewSize.height.coerceAtLeast(1)
                onCropTransform(zoom, pan.x / w, pan.y / h, rotation)
            }
        }
    } else {
        Modifier
    }

    // ---- persistent preview viewport (zoom + pan) ----
    // Default 1x = fit. A popup slider sets the zoom. Rotating/moving/resizing the PREVIEW ITSELF via
    // a gesture on the canvas is deliberately not offered — a gesture there is reserved for the
    // selected video layer's own crop/transform (cropModifier below), never the viewport's framing, so
    // the two can't be confused for one another. Loaded from / saved to PanelLayoutPrefs, so where the
    // user set the zoom persists across sessions.
    val savedView = remember { PanelLayoutPrefs.loadPreview(context) }
    var zoom by remember { mutableStateOf(savedView.zoom) }
    var panX by remember { mutableStateOf(savedView.panX) }
    var panY by remember { mutableStateOf(savedView.panY) }
    var showZoom by remember { mutableStateOf(false) }

    // Keep pan within bounds: a frame scaled by `zoom` can slide at most half its overflow each way, so
    // at 1x (fit) the pan is pinned to centre. Runs after any zoom or size change.
    fun clampPan() {
        val maxX = (previewSize.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (previewSize.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }
    LaunchedEffect(zoom, previewSize) { clampPan() }
    // Debounce disk writes: zoom/pan change rapidly during a drag; persist ~0.4s after they settle.
    LaunchedEffect(zoom, panX, panY) {
        delay(400)
        PanelLayoutPrefs.savePreview(context, zoom, panX, panY)
    }

    val gestureModifier = if (cropMode) cropModifier else Modifier

    Box(
        modifier = modifier
            .background(Neutral950)
            .onSizeChanged { previewSize = it }
            .clipToBounds()
            .then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
      // The composited frame — video (TextureView), captions, and guides — lives in this inner box,
      // which the zoom/pan graphicsLayer scales and translates as one.
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
        // Project-level crop, applied to the video layers only — the same order export uses, where
        // geometry() is a per-clip video effect and captions are overlays composited onto the result.
        // Without this the editor showed the whole source while the file came out cropped.
        // Suppressed in crop mode, where the user is dragging the crop itself and needs to see what
        // they are cutting away.
        val projectCrop = if (cropMode) null else PreviewGeometry.forCrop(state.document.settings.crop)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (projectCrop == null) {
                        Modifier
                    } else {
                        Modifier.clipToBounds().graphicsLayer {
                            scaleX = projectCrop.scaleX
                            scaleY = projectCrop.scaleY
                            translationX = projectCrop.translationXFraction * size.width
                            translationY = projectCrop.translationYFraction * size.height
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
        // One video layer per video track, stacked bottom-to-top: reverse the track order so
        // videoTracks[0] (top of the panel) is rendered LAST and ends up on top. Each layer owns
        // its own players (released when its track leaves composition), so deleting a track is clean.
        state.document.videoTracks.asReversed().forEach { trackId ->
            key(trackId) {
                VideoTrackLayer(
                    trackId = trackId,
                    clips = clips,
                    trackSettings = state.document.trackSettingsFor(trackId),
                    mediaFor = state.document::mediaFor,
                    now = now,
                    isPlaying = state.isPlaying,
                    playbackRate = state.playbackRate,
                    maxVideoDim = state.previewQuality.maxDimension,
                    aspectMod = aspectMod,
                    projectFps = state.document.settings.fps,
                )
            }
        }
        } // end project-crop layer (video only; captions overlay the cropped frame, as in export)
        // One ExoPlayer per audio track so multiple audio tracks (music + voiceover, etc.) mix
        // instead of only playing the topmost one. Each layer releases its player when its track
        // leaves composition, so deleting a track is clean.
        state.document.audioTracks.forEach { trackId ->
            key(trackId) {
                AudioTrackLayer(
                    trackId = trackId,
                    clips = clips,
                    trackSettings = state.document.trackSettingsFor(trackId),
                    mediaFor = state.document::mediaFor,
                    now = now,
                    isPlaying = state.isPlaying,
                    playbackRate = state.playbackRate,
                )
            }
        }
        // Caption/text overlay — each text clip positioned/scaled by its crop transform
        // (offset from center as a fraction of the frame), rendered on top of every video layer.
        // Keyframed properties are evaluated at the current playhead for animated captions.
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
                fontFamily = t.font.fontFamily(),
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
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // Platform safe-zone guides: for vertical/square projects, show where TikTok/Reels/Shorts UI
        // (captions bottom, action icons right) covers the frame, so titles/subjects stay inside.
        val aspect = state.document.settings.aspectRatio
        if (showSafeZones && (aspect == AspectRatio.RATIO_9_16 || aspect == AspectRatio.RATIO_1_1)) {
            androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width; val h = size.height
                // Unsafe insets (fractions): top small, bottom (captions/CTA), right (action rail).
                val top = h * 0.06f
                val bottom = h * (if (aspect == AspectRatio.RATIO_9_16) 0.20f else 0.10f)
                val right = w * (if (aspect == AspectRatio.RATIO_9_16) 0.12f else 0.06f)
                val left = w * 0.04f
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                )
                drawRect(
                    color = Color(0xFFE53935).copy(alpha = 0.7f),
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(w - left - right, h - top - bottom),
                    style = stroke,
                )
            }
        }
      } // end inner zoomed frame

        // Zoom control — fixed size, OUTSIDE the zoomed layer so it never scales with the preview.
        PreviewZoomControl(
            zoom = zoom,
            expanded = showZoom,
            onExpandedChange = { showZoom = it },
            onZoomChange = { z ->
                zoom = z.coerceIn(1f, PanelLayoutPrefs.MAX_ZOOM)
                if (zoom <= 1f) { panX = 0f; panY = 0f } else clampPan()
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
    }
}

/**
 * Preview-zoom affordance: a magnifier button that opens a popup with a slider (1x–[PanelLayoutPrefs.MAX_ZOOM]).
 * Fixed-size and rendered outside the zoomed layer, so it never scales with the preview. "Fit" resets to 1x.
 */
@Composable
private fun PreviewZoomControl(
    zoom: Float,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        IconButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                Icons.Filled.ZoomIn,
                contentDescription = "Zoom preview",
                tint = if (zoom > 1f) White else Neutral500,
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    color = Neutral950,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(220.dp).padding(top = 44.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Zoom  ${"%.1f".format(zoom)}x", color = White, fontSize = 12.sp)
                        Slider(
                            value = zoom.coerceIn(1f, PanelLayoutPrefs.MAX_ZOOM),
                            onValueChange = onZoomChange,
                            valueRange = 1f..PanelLayoutPrefs.MAX_ZOOM,
                        )
                        Row {
                            TextButton(onClick = { onZoomChange(1f) }) {
                                Text("Fit", color = White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One composited video layer for a single video track. Owns two muted, picture-only
 * ExoPlayers so it can crossfade its own overlapping clips (outgoing + incoming).
 * A background-removed clip is rendered as an on-device matte cutout instead of a raw
 * player surface, so the tracks below show through its transparent areas.
 *
 * The players are created here and released in [DisposableEffect] when this track leaves
 * composition (e.g. the track is deleted), so there is no manual player pool to manage.
 */
@Composable
private fun VideoTrackLayer(
    trackId: String,
    clips: List<TimelineClip>,
    trackSettings: com.hereliesaz.guillotine.model.TrackSettings,
    mediaFor: (TimelineClip) -> MediaItem?,
    now: Long,
    isPlaying: Boolean,
    playbackRate: Float,
    maxVideoDim: Int,
    aspectMod: Modifier,
    projectFps: Int,
) {
    val context = LocalContext.current
    val gainA = remember { LiveAudioProcessor() }
    val gainB = remember { LiveAudioProcessor() }
    val playerA = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(com.hereliesaz.guillotine.media.previewRenderersFactory(context, gainA))
            .build()
    }
    val playerB = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(com.hereliesaz.guillotine.media.previewRenderersFactory(context, gainB))
            .build()
    }
    DisposableEffect(Unit) {
        onDispose {
            playerA.release()
            playerB.release()
        }
    }
    // Preview-quality cap: constrain the players' target video resolution (longest edge). Lower
    // quality trades clarity for smoother playback; FULL (Int.MAX_VALUE) leaves the source untouched.
    LaunchedEffect(maxVideoDim) {
        val params = playerA.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(maxVideoDim, maxVideoDim)
            .build()
        playerA.trackSelectionParameters = params
        playerB.trackSelectionParameters = params
    }

    // This track's active clips, earliest first. Two overlapping = a crossfade region
    // (outgoing fades out, incoming fades in across the overlap); >2 is degenerate, take the first two.
    val active = TimelineMath.activeClips(clips, ClipType.VIDEO, now)
        .filter { it.trackId == trackId }
        .sortedBy { it.startTimeMs }
    val outgoing = active.getOrNull(0)
    val incoming = active.getOrNull(1)
    // Crossfade progress 0..1 across the overlap [incoming.start, outgoing.end); null when not crossfading.
    val xfade = if (outgoing != null && incoming != null) {
        val span = (outgoing.endTimeMs - incoming.startTimeMs).coerceAtLeast(1)
        ((now - incoming.startTimeMs).toFloat() / span).coerceIn(0f, 1f)
    } else {
        null
    }

    // A bg-removed clip is drawn as a cutout (no player surface), so don't keep a decoder running for it.
    val playClipA = outgoing?.takeIf { !it.filters.removeBackground }
    val playClipB = incoming?.takeIf { !it.filters.removeBackground }
    wireVideoPlayer(playerA, gainA, playClipA, playClipA?.let(mediaFor), now, isPlaying, playbackRate, projectFps)
    wireVideoPlayer(playerB, gainB, playClipB, playClipB?.let(mediaFor), now, isPlaying, playbackRate, projectFps)

    // On-device matte cutouts for background-removed clips, recomputed per ~150 ms bucket
    // (crisp when paused, frame-coarse while playing). Null unless the clip removes its background.
    val bucket = now / 150L
    val cutoutA by produceState<ImageBitmap?>(null, outgoing?.id, outgoing?.filters?.removeBackground, bucket) {
        value = cutoutFor(context, outgoing, mediaFor, now)
    }
    val cutoutB by produceState<ImageBitmap?>(null, incoming?.id, incoming?.filters?.removeBackground, bucket) {
        value = cutoutFor(context, incoming, mediaFor, now)
    }
    // On-device face-blur overlays (transparent + blurred face patches) for clips that anonymize faces.
    val faceBlurA by produceState<ImageBitmap?>(null, outgoing?.id, outgoing?.filters?.blurFaces, bucket) {
        value = faceBlurFor(context, outgoing, mediaFor, now)
    }
    val faceBlurB by produceState<ImageBitmap?>(null, incoming?.id, incoming?.filters?.blurFaces, bucket) {
        value = faceBlurFor(context, incoming, mediaFor, now)
    }

    val trackOpacity = trackSettings.opacity
    // Outgoing fades out as xfade 0 -> 1; a lone clip stays fully opaque.
    val opacityA = outgoing?.let {
        TimelineMath.valueAt(it, KeyframeProperty.OPACITY, now - it.startTimeMs, 1f)
    }?.times(trackOpacity)?.times(1f - (xfade ?: 0f)) ?: 0f
    // Incoming fades IN over the same overlap.
    val opacityB = incoming?.let {
        TimelineMath.valueAt(it, KeyframeProperty.OPACITY, now - it.startTimeMs, 1f)
    }?.times(trackOpacity)?.times(xfade ?: 0f) ?: 0f

    VideoSlot(outgoing, playerA, cutoutA, faceBlurA, opacityA, now, aspectMod, transparent = false)
    VideoSlot(incoming, playerB, cutoutB, faceBlurB, opacityB, now, aspectMod, transparent = true)
}

/** Compute a face-blur overlay for [clip], or null when the clip doesn't anonymize faces. */
private suspend fun faceBlurFor(
    context: android.content.Context,
    clip: TimelineClip?,
    mediaFor: (TimelineClip) -> MediaItem?,
    now: Long,
): ImageBitmap? {
    if (clip == null || !clip.filters.blurFaces) return null
    val media = mediaFor(clip) ?: return null
    val src = TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0)
    return com.hereliesaz.guillotine.media.FaceBlurrer.blurOverlay(context, media.uri, media.kind, src)?.asImageBitmap()
}

/** Compute a background-removal cutout for [clip], or null when the clip doesn't remove its background. */
private suspend fun cutoutFor(
    context: android.content.Context,
    clip: TimelineClip?,
    mediaFor: (TimelineClip) -> MediaItem?,
    now: Long,
): ImageBitmap? {
    if (clip == null || !clip.filters.removeBackground) return null
    val media = mediaFor(clip) ?: return null
    val src = TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0)
    return SubjectSegmenter.cutout(context, media.uri, media.kind, src)?.asImageBitmap()
}

/**
 * Render one clip's picture: a background-removed clip becomes a matte [cutout] [Image]
 * (transparent where the subject isn't), otherwise the [player]'s [PlayerView]. Keyframed
 * scale × crop transform and [alpha] are applied via a shared graphics layer.
 */
@Composable
private fun VideoSlot(
    clip: TimelineClip?,
    player: ExoPlayer,
    cutout: ImageBitmap?,
    faceBlur: ImageBitmap?,
    alpha: Float,
    now: Long,
    aspectMod: Modifier,
    transparent: Boolean,
) {
    if (clip == null) return
    val mod = aspectMod
        // Clip BEFORE the transform below: a graphicsLayer scale/rotate doesn't clip its own content
        // by default, so a crop-tool zoom/pan/rotation on this clip could paint past the frame's own
        // rectangle — into the letterbox, over the zoom-control button, past where the picture is
        // supposed to end. The frame's bounding box must stay the topmost visual limit for every
        // layer; clipping at the pre-transform (aspectMod-sized) bounds enforces that regardless of
        // how far the transform below pushes the content.
        .clipToBounds()
        .graphicsLayer {
            this.alpha = alpha.coerceIn(0f, 1f)
            // Keyframe-aware crop/placement (absolute; the clip's static value is the default).
            val rel = now - clip.startTimeMs
            val s = TimelineMath.valueAt(clip, KeyframeProperty.SCALE, rel, clip.scale).coerceAtLeast(0f)
            scaleX = s
            scaleY = s
            rotationZ = TimelineMath.valueAt(clip, KeyframeProperty.ROTATION, rel, clip.rotation)
            translationX = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_X, rel, clip.offsetX) * size.width
            translationY = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_Y, rel, clip.offsetY) * size.height
        }
    // Picture + optional face-blur overlay share the same transformed box so blurred patches track
    // the video. Fit is used for both so the overlay (frame-sized) aligns with the fitted picture.
    Box(modifier = mod, contentAlignment = Alignment.Center) {
        if (clip.filters.removeBackground) {
            cutout?.let { cb ->
                Image(bitmap = cb, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        } else {
            AndroidView(
                // Inflated from XML so the surface is a TextureView (transformable by the zoom/pan
                // graphicsLayer, and genuinely transparent for the crossfade overlay slot).
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx)
                        .inflate(com.hereliesaz.guillotine.R.layout.preview_player_view, null) as PlayerView).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(
                            if (transparent) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK,
                        )
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (clip.filters.blurFaces) {
            faceBlur?.let { fb ->
                Image(bitmap = fb, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Wire a muted, picture-only ExoPlayer to [clip] (or clear it when [clip]/[media] is null). */
@Composable
private fun wireVideoPlayer(
    player: ExoPlayer,
    gain: LiveAudioProcessor,
    clip: TimelineClip?,
    media: MediaItem?,
    now: Long,
    isPlaying: Boolean,
    playbackRate: Float,
    projectFps: Int,
) {
    LaunchedEffect(media?.id) {
        if (media == null || clip == null) {
            player.stop()
            player.clearMediaItems()
        } else {
            player.setMediaItem(buildExoItem(media.uri, media.kind, clip.durationMs))
            player.prepare()
            player.seekTo(TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0))
        }
    }
    LaunchedEffect(clip?.id, clip?.filters, clip?.keyframes, projectFps) {
        // Color filters; keyframe-aware so a keyframed color animates per frame (startMs = -trimStart
        // maps the picture player's source-time position to clip-relative keyframe time). A frameStep
        // decimation drops frames to fps/step live (same length, choppy look), matching the exporter.
        if (clip != null) runCatching {
            player.setVideoEffects(
                VideoEffects.colorEffects(clip, -clip.trimStartMs) +
                    VideoEffects.frameDrop(clip.filters.frameStep, projectFps.toFloat()),
            )
        }
    }
    // Picture-only and NEVER outputs its own audio — the clip's sound plays through the audio player.
    LaunchedEffect(Unit) { player.volume = 0f; gain.gain = 0f }
    LaunchedEffect(playbackRate) { player.setPlaybackSpeed(playbackRate) }
    LaunchedEffect(isPlaying, media?.id) {
        player.playWhenReady = isPlaying && media != null
    }
    syncPosition(player, clip, now, isPlaying)
}

/** Build a Media3 item; images become timed image items so one path handles all kinds. */
/**
 * One audio track's playback layer: owns a single ExoPlayer + LiveAudioProcessor for this track,
 * plays whichever of the track's clips is active (if any) through the gain/pan/normalize pipeline,
 * and releases the player when the track leaves composition (parity with [VideoTrackLayer]). The
 * multi-audio path is just several of these composed in parallel — Android's audio layer mixes
 * them so music + voiceover + effects all play together instead of only the topmost track.
 */
@Composable
private fun AudioTrackLayer(
    trackId: String,
    clips: List<TimelineClip>,
    trackSettings: com.hereliesaz.guillotine.model.TrackSettings,
    mediaFor: (TimelineClip) -> MediaItem?,
    now: Long,
    isPlaying: Boolean,
    playbackRate: Float,
) {
    val context = LocalContext.current
    val gain = remember { LiveAudioProcessor() }
    val player = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(com.hereliesaz.guillotine.media.previewRenderersFactory(context, gain))
            .build()
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // The one active clip on THIS track. If multiple overlap (rare for audio; we don't crossfade
    // audio here), pick the earliest starter — deterministic and mirrors the single-clip path.
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

    // Peak-normalize gain (async; reuse the cached waveform decoder), matching the export. 1 = off.
    val norm by produceState(1f, media?.id, active?.filters?.normalize) {
        value = if (active?.filters?.normalize == true && media != null) {
            com.hereliesaz.guillotine.media.MediaPreview.waveform(context, media.uri)
                ?.let { com.hereliesaz.guillotine.media.MediaPreview.normalizeGain(it) } ?: 1f
        } else {
            1f
        }
    }

    LaunchedEffect(media?.id) {
        val clip = active
        if (media == null || clip == null) {
            player.stop()
            player.clearMediaItems()
        } else {
            player.setMediaItem(buildExoItem(media.uri, media.kind, clip.durationMs))
            player.prepare()
            player.seekTo(TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0))
        }
    }
    LaunchedEffect(volume, norm, pan) {
        player.volume = 1f
        gain.gain = (volume * norm).coerceAtLeast(0f)
        gain.pan = pan.coerceIn(-1f, 1f)
    }
    LaunchedEffect(playbackRate) { player.setPlaybackSpeed(playbackRate) }
    LaunchedEffect(isPlaying, media?.id) {
        player.playWhenReady = isPlaying && media != null
    }
    syncPosition(player, active, now, isPlaying)
}

private fun buildExoItem(uri: String, kind: MediaKind, durationMs: Long): ExoMediaItem {
    val builder = ExoMediaItem.Builder().setUri(Uri.parse(uri))
    if (kind == MediaKind.IMAGE) {
        builder.setImageDurationMs(if (durationMs > 0) durationMs else 5_000L)
    }
    return builder.build()
}

/**
 * Keeps an ExoPlayer's position aligned with the timeline. While paused, every
 * playhead change scrubs the player; while playing, drift is corrected lazily so
 * we don't seek every frame.
 */
@Composable
private fun syncPosition(player: ExoPlayer, clip: TimelineClip?, now: Long, isPlaying: Boolean) {
    val current by rememberUpdatedState(now)
    // Scrub when paused.
    LaunchedEffect(now, isPlaying, clip?.id) {
        if (clip != null && !isPlaying) {
            // previewSourceTimeMs, not sourceTimeMs: an AI-edit REMOVE is cut from the export, so the
            // preview must skip it too rather than showing footage the rendered file won't contain.
            val src = TimelineMath.previewSourceTimeMs(clip, current).coerceAtLeast(0)
            if (abs(player.currentPosition - src) > SCRUB_SEEK_TOLERANCE_MS) player.seekTo(src)
        }
    }
    // Correct drift while playing.
    LaunchedEffect(isPlaying, clip?.id) {
        if (clip != null && isPlaying) {
            while (isActive) {
                val src = TimelineMath.previewSourceTimeMs(clip, current).coerceAtLeast(0)
                if (abs(player.currentPosition - src) > PLAY_DRIFT_TOLERANCE_MS) player.seekTo(src)
                // 400ms was fine for correcting drift, but a REMOVE has to be left promptly or the
                // preview plays a chunk of cut footage before catching up. Poll faster when the clip
                // has edits at all; unedited clips keep the cheaper cadence.
                delay(if (clip.edits.any { it.action == com.hereliesaz.guillotine.model.EditAction.REMOVE }) 100 else 400)
            }
        }
    }
}
