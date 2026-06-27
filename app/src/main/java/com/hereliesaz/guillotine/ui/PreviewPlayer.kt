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
 * Audio is separate and unified: a single muted picture path plus one [audioPlayer]
 * that sources ALL audio (including a video clip's own sound via its linked audio
 * clip) through the gain/pan/normalize pipeline — so preview audio can never double.
 */
@Composable
fun PreviewPlayer(
    state: EditorUiState,
    modifier: Modifier = Modifier,
    cropMode: Boolean = false,
    onCropTransform: (zoom: Float, panXFrac: Float, panYFrac: Float, rotationDelta: Float) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // Live gain+pan processor so preview can boost (normalize) and pan, which ExoPlayer.volume can't.
    val audioGain = remember { LiveAudioProcessor() }
    val audioPlayer = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(com.hereliesaz.guillotine.media.previewRenderersFactory(context, audioGain))
            .build()
    }
    DisposableEffect(Unit) {
        onDispose { audioPlayer.release() }
    }

    val now = state.currentTimeMs
    // Disabled/hidden tracks drop out entirely.
    val clips = state.document.clips.filterNot { it.trackId in state.document.disabledTrackIds }

    // The preview's video layers are muted (picture only), so ALL preview audio is played here —
    // including a video clip's own sound, via its linked audio clip — through the gain/pan/normalize
    // pipeline. One audio source, so preview audio can never double.
    val activeAudio = TimelineMath.topActiveClip(
        clips, ClipType.AUDIO, now, state.document.audioTracks,
    )
    val activeText = TimelineMath.activeClips(clips, ClipType.TEXT, now)
    val anyActiveVideo = TimelineMath.activeClips(clips, ClipType.VIDEO, now).isNotEmpty()
    val audioTrack = activeAudio?.let { state.document.trackSettingsFor(it.trackId) }
    val audioMedia = activeAudio?.let { state.document.mediaFor(it) }

    val audioVolume = if (audioTrack?.muted == true) 0f else (activeAudio?.let {
        TimelineMath.valueAt(it, KeyframeProperty.VOLUME, now - it.startTimeMs, it.filters.volume)
    } ?: 0f) * (audioTrack?.volume ?: 1f)

    // Peak-normalize gain (async; reuse the cached waveform decoder), matching the export. 1 = off.
    val audioNorm by produceState(1f, audioMedia?.id, activeAudio?.filters?.normalize) {
        value = if (activeAudio?.filters?.normalize == true && audioMedia != null) {
            com.hereliesaz.guillotine.media.MediaPreview.waveform(context, audioMedia.uri)
                ?.let { com.hereliesaz.guillotine.media.MediaPreview.normalizeGain(it) } ?: 1f
        } else {
            1f
        }
    }

    // ---- audio player wiring ----
    LaunchedEffect(audioMedia?.id) {
        val clip = activeAudio
        if (audioMedia == null || clip == null) {
            audioPlayer.stop()
            audioPlayer.clearMediaItems()
        } else {
            audioPlayer.setMediaItem(buildExoItem(audioMedia.uri, audioMedia.kind, clip.durationMs))
            audioPlayer.prepare()
            audioPlayer.seekTo(TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0))
        }
    }
    LaunchedEffect(audioVolume, audioNorm, activeAudio?.filters?.pan) {
        audioPlayer.volume = 1f
        audioGain.gain = (audioVolume * audioNorm).coerceAtLeast(0f)
        audioGain.pan = (activeAudio?.filters?.pan ?: 0f).coerceIn(-1f, 1f)
    }
    LaunchedEffect(state.playbackRate) { audioPlayer.setPlaybackSpeed(state.playbackRate) }
    LaunchedEffect(state.isPlaying, audioMedia?.id) {
        audioPlayer.playWhenReady = state.isPlaying && audioMedia != null
    }
    syncPosition(audioPlayer, activeAudio, now, state.isPlaying)

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
                    aspectMod = aspectMod,
                )
            }
        }
        // Caption/text overlay — each text clip positioned/scaled by its crop transform
        // (offset from center as a fraction of the frame), rendered on top of every video layer.
        activeText.forEach { t ->
            Text(
                t.text,
                color = White.copy(alpha = state.document.trackSettingsFor(t.trackId).opacity.coerceIn(0f, 1f)),
                fontSize = 14.sp,
                fontFamily = t.font.fontFamily(),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            (t.offsetX * previewSize.width).roundToInt(),
                            (t.offsetY * previewSize.height).roundToInt(),
                        )
                    }
                    .graphicsLayer { scaleX = t.scale; scaleY = t.scale; rotationZ = t.rotation }
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
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
            // Keyframed scale × crop-tool base scale; crop offset translates it.
            val s = (TimelineMath.valueAt(clip, KeyframeProperty.SCALE, now - clip.startTimeMs, 1f) *
                clip.scale).coerceAtLeast(0f)
            scaleX = s
            scaleY = s
            rotationZ = clip.rotation
            translationX = clip.offsetX * size.width
            translationY = clip.offsetY * size.height
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
    LaunchedEffect(clip?.id, clip?.filters) {
        if (clip != null) runCatching { player.setVideoEffects(VideoEffects.build(clip.filters)) }
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
