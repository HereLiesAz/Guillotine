@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.media.VideoEffects
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral950
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

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
fun PreviewPlayer(state: EditorUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val videoPlayer = remember { ExoPlayer.Builder(context).build() }
    val audioPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
        onDispose {
            videoPlayer.release()
            audioPlayer.release()
        }
    }

    val clips = state.document.clips
    val now = state.currentTimeMs
    val activeVideo = TimelineMath.activeClip(clips, ClipType.VIDEO, now)
    val activeAudio = TimelineMath.activeClip(clips, ClipType.AUDIO, now)
    val videoMedia = activeVideo?.let { state.document.mediaFor(it) }
    val audioMedia = activeAudio?.let { state.document.mediaFor(it) }

    // Keyframed view-layer values for the active video clip.
    val opacity = activeVideo?.let {
        TimelineMath.valueAt(it, KeyframeProperty.OPACITY, now - it.startTimeMs, 1f)
    } ?: 1f
    val scale = activeVideo?.let {
        TimelineMath.valueAt(it, KeyframeProperty.SCALE, now - it.startTimeMs, 1f)
    } ?: 1f
    val videoVolume = activeVideo?.let {
        TimelineMath.valueAt(it, KeyframeProperty.VOLUME, now - it.startTimeMs, it.filters.volume)
    } ?: 0f
    val audioVolume = activeAudio?.let {
        TimelineMath.valueAt(it, KeyframeProperty.VOLUME, now - it.startTimeMs, it.filters.volume)
    } ?: 0f

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
    LaunchedEffect(videoVolume) { videoPlayer.volume = videoVolume.coerceIn(0f, 2f) }
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
    LaunchedEffect(audioVolume) { audioPlayer.volume = audioVolume.coerceIn(0f, 2f) }
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

    Box(modifier = modifier.background(Neutral950), contentAlignment = Alignment.Center) {
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
                        scaleX = scale.coerceAtLeast(0f)
                        scaleY = scale.coerceAtLeast(0f)
                    },
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
