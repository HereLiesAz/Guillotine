@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.hereliesaz.guillotine.media.SubjectSegmenter
import com.hereliesaz.guillotine.media.VideoEffects
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
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
 * (`state.currentTimeMs`): it picks the active video/audio clips, applies the
 * shared [VideoEffects], animates keyframed opacity/scale, and corrects drift
 * against two ExoPlayer instances (one for picture+its audio, one for separate
 * audio clips — so there is never double audio from a single source).
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
    // Live gain+pan processors so preview can boost (normalize) and pan, which ExoPlayer.volume can't.
    val videoGain = remember { com.hereliesaz.guillotine.media.LiveAudioProcessor() }
    val audioGain = remember { com.hereliesaz.guillotine.media.LiveAudioProcessor() }
    val videoPlayer = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(com.hereliesaz.guillotine.media.previewRenderersFactory(context, videoGain))
            .build()
    }
    val audioPlayer = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(com.hereliesaz.guillotine.media.previewRenderersFactory(context, audioGain))
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            videoPlayer.release()
            audioPlayer.release()
        }
    }

    val now = state.currentTimeMs
    // Disabled/hidden tracks drop out entirely.
    val clips = state.document.clips.filterNot { it.trackId in state.document.disabledTrackIds }
    // Layer-aware compositing: the player shows the topmost non-removed (background) video;
    // a background-removed clip above it is overlaid as a matted cutout so the background
    // shows through. With no background, the removed clip just plays normally.
    val activeVideoClips = TimelineMath.activeClips(clips, ClipType.VIDEO, now)
        .sortedBy { state.document.videoTracks.indexOf(it.trackId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    val foregroundClip = activeVideoClips.firstOrNull { it.filters.removeBackground }
    val backgroundClip = activeVideoClips.firstOrNull { !it.filters.removeBackground }
    val activeVideo = backgroundClip ?: foregroundClip
    val overlayClip = if (backgroundClip != null && foregroundClip != null) foregroundClip else null
    // Exclude linked shadow clips: their sound is the video's own audio, already played by the
    // video player — playing them here too would double it.
    val activeAudio = TimelineMath.topActiveClip(
        clips.filter { it.linkedClipId == null }, ClipType.AUDIO, now, state.document.audioTracks,
    )
    val activeText = TimelineMath.activeClips(clips, ClipType.TEXT, now)
    val videoTrack = activeVideo?.let { state.document.trackSettingsFor(it.trackId) }
    val audioTrack = activeAudio?.let { state.document.trackSettingsFor(it.trackId) }
    val videoMedia = activeVideo?.let { state.document.mediaFor(it) }
    val audioMedia = activeAudio?.let { state.document.mediaFor(it) }

    // Keyframed view-layer values for the active video clip, scaled by whole-track settings.
    val opacity = (activeVideo?.let {
        TimelineMath.valueAt(it, KeyframeProperty.OPACITY, now - it.startTimeMs, 1f)
    } ?: 1f) * (videoTrack?.opacity ?: 1f)
    val scale = activeVideo?.let {
        TimelineMath.valueAt(it, KeyframeProperty.SCALE, now - it.startTimeMs, 1f)
    } ?: 1f
    val videoVolume = if (videoTrack?.muted == true) 0f else (activeVideo?.let {
        TimelineMath.valueAt(it, KeyframeProperty.VOLUME, now - it.startTimeMs, it.filters.volume)
    } ?: 0f) * (videoTrack?.volume ?: 1f)
    val audioVolume = if (audioTrack?.muted == true) 0f else (activeAudio?.let {
        TimelineMath.valueAt(it, KeyframeProperty.VOLUME, now - it.startTimeMs, it.filters.volume)
    } ?: 0f) * (audioTrack?.volume ?: 1f)

    // Peak-normalize gains (async; reuse the cached waveform decoder), matching the export. 1 = off.
    val videoNorm by produceState(1f, videoMedia?.id, activeVideo?.filters?.normalize) {
        value = if (activeVideo?.filters?.normalize == true && videoMedia != null) {
            com.hereliesaz.guillotine.media.MediaPreview.waveform(context, videoMedia.uri)
                ?.let { com.hereliesaz.guillotine.media.MediaPreview.normalizeGain(it) } ?: 1f
        } else {
            1f
        }
    }
    val audioNorm by produceState(1f, audioMedia?.id, activeAudio?.filters?.normalize) {
        value = if (activeAudio?.filters?.normalize == true && audioMedia != null) {
            com.hereliesaz.guillotine.media.MediaPreview.waveform(context, audioMedia.uri)
                ?.let { com.hereliesaz.guillotine.media.MediaPreview.normalizeGain(it) } ?: 1f
        } else {
            1f
        }
    }

    // ---- video player wiring ----
    LaunchedEffect(videoMedia?.id) {
        val clip = activeVideo
        if (videoMedia == null || clip == null) {
            videoPlayer.stop()
            videoPlayer.clearMediaItems()
        } else {
            videoPlayer.setMediaItem(buildExoItem(videoMedia.uri, videoMedia.kind, clip.durationMs))
            videoPlayer.prepare()
            videoPlayer.seekTo(TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0))
        }
    }
    LaunchedEffect(activeVideo?.id, activeVideo?.filters) {
        if (activeVideo != null) {
            runCatching { videoPlayer.setVideoEffects(VideoEffects.build(activeVideo.filters)) }
        }
    }
    // Gain (incl. normalize boost) + pan go through the processor; keep player.volume at unity.
    LaunchedEffect(videoVolume, videoNorm, activeVideo?.filters?.pan) {
        videoPlayer.volume = 1f
        videoGain.gain = (videoVolume * videoNorm).coerceAtLeast(0f)
        videoGain.pan = (activeVideo?.filters?.pan ?: 0f).coerceIn(-1f, 1f)
    }
    LaunchedEffect(state.playbackRate) { videoPlayer.setPlaybackSpeed(state.playbackRate) }
    LaunchedEffect(state.isPlaying, videoMedia?.id) {
        videoPlayer.playWhenReady = state.isPlaying && videoMedia != null
    }
    syncPosition(videoPlayer, activeVideo, now, state.isPlaying)

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
        if (videoMedia == null) {
            Text("No video at ${"%.2f".format(now / 1000f)}s", color = Neutral500, fontSize = 12.sp)
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.BLACK)
                        player = videoPlayer
                    }
                },
                modifier = aspectMod
                    .wrapContentSize()
                    .graphicsLayer {
                        alpha = opacity.coerceIn(0f, 1f)
                        // Keyframed scale × crop-tool base scale; crop offset translates it.
                        val s = (scale * (activeVideo?.scale ?: 1f)).coerceAtLeast(0f)
                        scaleX = s
                        scaleY = s
                        rotationZ = activeVideo?.rotation ?: 0f
                        translationX = (activeVideo?.offsetX ?: 0f) * size.width
                        translationY = (activeVideo?.offsetY ?: 0f) * size.height
                    },
            )
        }
        // Background-removed foreground composited over the background video. The matte is
        // computed on-device per ~150 ms bucket (crisp when paused, frame-coarse while playing).
        val fgMedia = overlayClip?.let { state.document.mediaFor(it) }
        if (overlayClip != null && fgMedia != null) {
            val bucket = now / 150L
            val cutout by produceState<ImageBitmap?>(null, overlayClip.id, bucket) {
                val src = TimelineMath.sourceTimeMs(overlayClip, now).coerceAtLeast(0)
                value = SubjectSegmenter.cutout(context, fgMedia.uri, fgMedia.kind, src)?.asImageBitmap()
            }
            cutout?.let { cb ->
                Image(
                    bitmap = cb,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = aspectMod
                        .wrapContentSize()
                        .graphicsLayer {
                            val s = overlayClip.scale.coerceAtLeast(0f)
                            scaleX = s
                            scaleY = s
                            rotationZ = overlayClip.rotation
                            translationX = overlayClip.offsetX * size.width
                            translationY = overlayClip.offsetY * size.height
                        },
                )
            }
        }
        // Caption/text overlay — each text clip positioned/scaled by its crop transform
        // (offset from center as a fraction of the frame), rendered on top of the video.
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
