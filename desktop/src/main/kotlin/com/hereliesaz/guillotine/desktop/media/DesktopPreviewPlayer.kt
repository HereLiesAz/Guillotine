package com.hereliesaz.guillotine.desktop.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral950
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.editor.EditorUiState
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.ClipType
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
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    val now = state.currentTimeMs
    val clips = state.document.clips.filterNot { it.trackId in state.document.disabledTrackIds }
    val activeText = TimelineMath.activeClips(clips, ClipType.TEXT, now)
    val anyActiveVideo = TimelineMath.activeClips(clips, ClipType.VIDEO, now).isNotEmpty()

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
    val crop = state.document.settings.crop
    val cropped = crop.x != 0f || crop.y != 0f || crop.w != 100f || crop.h != 100f
    val sx = if (crop.w > 0f) 100f / crop.w else 1f
    val sy = if (crop.h > 0f) 100f / crop.h else 1f

    Box(
        modifier = modifier.background(Neutral950),
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
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
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

    VideoSlot(outgoing, mediaFor, opacityA, now, isPlaying)
    VideoSlot(incoming, mediaFor, opacityB, now, isPlaying)
}

@Composable
private fun VideoSlot(
    clip: TimelineClip?,
    mediaFor: (TimelineClip) -> MediaItem?,
    alpha: Float,
    now: Long,
    isPlaying: Boolean,
) {
    if (clip == null || alpha <= 0f) return
    val media = mediaFor(clip) ?: return

    val sourceMs = TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0)
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
    val mod = Modifier
        .fillMaxSize()
        .graphicsLayer {
            this.alpha = alpha.coerceIn(0f, 1f)
            val s = TimelineMath.valueAt(clip, KeyframeProperty.SCALE, relMs, clip.scale).coerceAtLeast(0f)
            scaleX = s
            scaleY = s
            rotationZ = TimelineMath.valueAt(clip, KeyframeProperty.ROTATION, relMs, clip.rotation)
            translationX = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_X, relMs, clip.offsetX) * size.width
            translationY = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_Y, relMs, clip.offsetY) * size.height
        }

    frame?.let { bmp ->
        Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = mod)
    }
}

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
    // 3D `.cube` LUT grade, applied after the color matrix (matches Android's order). Parsed once and
    // cached by path so the LUT isn't re-parsed per frame.
    if (f.lutPath.isNotBlank()) {
        DesktopLutCache.get(f.lutPath)?.let { DesktopColorMatrix.applyLut(img, it) }
    }

    // Subject segmentation, matching the export path's order (after colour/LUT). Only run when the
    // caller opted in (paused), since ONNX matting is far too slow for live playback:
    //  • removeBackground → matte the subject to alpha so lower tracks / the letterbox show through
    //  • bokeh → keep the subject sharp and blur the background
    if (applySeg) {
        val segModel = DesktopRenderConfig.segModelPath
        if (segModel.isNotBlank()) {
            return when {
                f.removeBackground -> runCatching { DesktopSegmenter.matte(img, segModel) }.getOrDefault(img)
                f.bokeh -> runCatching { DesktopSegmenter.portraitBlur(img, segModel) }.getOrDefault(img)
                else -> img
            }
        }
    }
    return img
}

/** A reused FFmpeg decoder for the preview. Seeks (instead of reopening) toward the requested source
 *  time, grabbing forward when the target is just ahead — the same cheap-seek strategy as the export
 *  path. Accessed from both the scrub and playback IO loops, so every method is synchronized. */
private class PreviewGrabber(file: File) {
    private val grabber = FFmpegFrameGrabber(file).apply { start() }
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
        runCatching { grabber.stop(); grabber.release() }
    }
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

    LaunchedEffect(isPlaying, active?.id) {
        if (active != null && isPlaying) {
            while (isActive) {
                delay(PLAY_DRIFT_POLL_MS)
                val src = TimelineMath.sourceTimeMs(active, now).coerceAtLeast(0)
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
