@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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

    Box(
        modifier = modifier
            .background(Neutral950)
            .onSizeChanged { previewSize = it }
            .then(cropModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!anyActiveVideo) {
            Text("No video at ${"%.2f".format(now / 1000f)}s", color = Neutral500, fontSize = 12.sp)
        }
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
                )
            }
        }
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
    wireVideoPlayer(playerA, gainA, playClipA, playClipA?.let(mediaFor), now, isPlaying, playbackRate)
    wireVideoPlayer(playerB, gainB, playClipB, playClipB?.let(mediaFor), now, isPlaying, playbackRate)

    // On-device matte cutouts for background-removed clips, recomputed per ~150 ms bucket
    // (crisp when paused, frame-coarse while playing). Null unless the clip removes its background.
    val bucket = now / 150L
    val cutoutA by produceState<ImageBitmap?>(null, outgoing?.id, outgoing?.filters?.removeBackground, bucket) {
        value = cutoutFor(context, outgoing, mediaFor, now)
    }
    val cutoutB by produceState<ImageBitmap?>(null, incoming?.id, incoming?.filters?.removeBackground, bucket) {
        value = cutoutFor(context, incoming, mediaFor, now)
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

    VideoSlot(outgoing, playerA, cutoutA, opacityA, now, aspectMod, transparent = false)
    VideoSlot(incoming, playerB, cutoutB, opacityB, now, aspectMod, transparent = true)
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
    alpha: Float,
    now: Long,
    aspectMod: Modifier,
    transparent: Boolean,
) {
    if (clip == null) return
    val mod = aspectMod
        .wrapContentSize()
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
    if (clip.filters.removeBackground) {
        cutout?.let { cb ->
            Image(bitmap = cb, contentDescription = null, contentScale = ContentScale.Fit, modifier = mod)
        }
    } else {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(
                        if (transparent) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK,
                    )
                    this.player = player
                }
            },
            modifier = mod,
        )
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
    LaunchedEffect(clip?.id, clip?.filters, clip?.keyframes) {
        // Color filters; keyframe-aware so a keyframed color animates per frame (startMs = -trimStart
        // maps the picture player's source-time position to clip-relative keyframe time).
        if (clip != null) runCatching { player.setVideoEffects(VideoEffects.colorEffects(clip, -clip.trimStartMs)) }
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
            val src = TimelineMath.sourceTimeMs(clip, current).coerceAtLeast(0)
            if (abs(player.currentPosition - src) > SCRUB_SEEK_TOLERANCE_MS) player.seekTo(src)
        }
    }
    // Correct drift while playing.
    LaunchedEffect(isPlaying, clip?.id) {
        if (clip != null && isPlaying) {
            while (isActive) {
                val src = TimelineMath.sourceTimeMs(clip, current).coerceAtLeast(0)
                if (abs(player.currentPosition - src) > PLAY_DRIFT_TOLERANCE_MS) player.seekTo(src)
                delay(400)
            }
        }
    }
}
