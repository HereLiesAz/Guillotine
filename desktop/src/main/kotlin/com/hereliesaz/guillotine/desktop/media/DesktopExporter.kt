package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
import com.hereliesaz.guillotine.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.ShortBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

object DesktopExporter {

    data class ExportConfig(
        val name: String = "guillotine_export",
        val width: Int = 1920,
        val height: Int = 1080,
        val fps: Double = 30.0,
        val videoBitrate: Int = 8_000_000,
        val audioBitrate: Int = 192_000,
        val sampleRate: Int = 44100,
    )

    suspend fun export(
        document: Document,
        config: ExportConfig = ExportConfig(),
        onProgress: (Float, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val safeName = config.name.replace(Regex("[/\\\\]"), "_").replace("..", "_").ifBlank { "export" }
        val outputDir = File(System.getProperty("user.home"), "Videos/Guillotine")
        outputDir.mkdirs()
        val outputFile = File(outputDir, "${safeName}.mp4")

        val totalDurationMs = document.totalDurationMs
        if (totalDurationMs <= 0) error("Nothing to export")

        val frameDurationMs = (1000.0 / config.fps)
        val totalFrames = (totalDurationMs / frameDurationMs).toLong() + 1
        val converter = Java2DFrameConverter()

        val clips = document.clips.filterNot { it.trackId in document.disabledTrackIds }
        val hasAudio = clips.any { it.type == ClipType.AUDIO }

        val recorder = FFmpegFrameRecorder(outputFile, config.width, config.height)
        recorder.videoCodec = avcodec.AV_CODEC_ID_H264
        recorder.videoBitrate = config.videoBitrate
        recorder.frameRate = config.fps
        recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
        if (hasAudio) {
            recorder.audioCodec = avcodec.AV_CODEC_ID_AAC
            recorder.audioBitrate = config.audioBitrate
            recorder.sampleRate = config.sampleRate
            recorder.audioChannels = 2
        }
        recorder.start()

        try {
            // Video frames
            for (frameIdx in 0 until totalFrames) {
                ensureActive()
                val timeMs = (frameIdx * frameDurationMs).toLong()
                val canvas = BufferedImage(config.width, config.height, BufferedImage.TYPE_INT_ARGB)
                val g = canvas.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color.BLACK
                g.fillRect(0, 0, config.width, config.height)

                // Composite video tracks bottom-to-top
                document.videoTracks.asReversed().forEach { trackId ->
                    val trackClips = TimelineMath.activeClips(clips, ClipType.VIDEO, timeMs)
                        .filter { it.trackId == trackId }
                        .sortedBy { it.startTimeMs }

                    val outgoing = trackClips.getOrNull(0)
                    val incoming = trackClips.getOrNull(1)
                    val xfade = if (outgoing != null && incoming != null) {
                        val span = (outgoing.endTimeMs - incoming.startTimeMs).coerceAtLeast(1)
                        ((timeMs - incoming.startTimeMs).toFloat() / span).coerceIn(0f, 1f)
                    } else null

                    val trackSettings = document.trackSettingsFor(trackId)
                    outgoing?.let { clip ->
                        val opacity = TimelineMath.valueAt(clip, KeyframeProperty.OPACITY, timeMs - clip.startTimeMs, 1f) *
                            trackSettings.opacity * (1f - (xfade ?: 0f))
                        renderClipToCanvas(g, clip, document, timeMs, config.width, config.height, opacity)
                    }
                    incoming?.let { clip ->
                        val opacity = TimelineMath.valueAt(clip, KeyframeProperty.OPACITY, timeMs - clip.startTimeMs, 1f) *
                            trackSettings.opacity * (xfade ?: 0f)
                        renderClipToCanvas(g, clip, document, timeMs, config.width, config.height, opacity)
                    }
                }

                // Text overlays
                TimelineMath.activeClips(clips, ClipType.TEXT, timeMs).forEach { t ->
                    val relMs = (timeMs - t.startTimeMs).coerceIn(0, t.durationMs)
                    val trackOpacity = document.trackSettingsFor(t.trackId).opacity
                    val opacity = TimelineMath.valueAt(t, KeyframeProperty.OPACITY, relMs, 1f) * trackOpacity
                    val ox = TimelineMath.valueAt(t, KeyframeProperty.OFFSET_X, relMs, t.offsetX)
                    val oy = TimelineMath.valueAt(t, KeyframeProperty.OFFSET_Y, relMs, t.offsetY)
                    val scale = TimelineMath.valueAt(t, KeyframeProperty.SCALE, relMs, t.scale)

                    g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, opacity.coerceIn(0f, 1f))
                    // Honor the clip's typeface (Font row / text presets). AWT has no cursive logical
                    // font, so cursive falls back to serif — the closest logical family.
                    val family = when (t.font) {
                        com.hereliesaz.guillotine.model.TextFont.SANS -> java.awt.Font.SANS_SERIF
                        com.hereliesaz.guillotine.model.TextFont.SERIF -> java.awt.Font.SERIF
                        com.hereliesaz.guillotine.model.TextFont.MONO -> java.awt.Font.MONOSPACED
                        com.hereliesaz.guillotine.model.TextFont.CURSIVE -> java.awt.Font.SERIF
                    }
                    val font = java.awt.Font(family, java.awt.Font.PLAIN, 1).deriveFont(14f * scale)
                    g.font = font
                    val cx = config.width / 2 + (ox * config.width).roundToInt()
                    val cy = config.height / 2 + (oy * config.height).roundToInt()
                    val fm = g.fontMetrics
                    val tw = fm.stringWidth(t.text)
                    val tx = cx - tw / 2
                    val baseline = cy + fm.ascent / 2
                    // Dark scrim behind the glyphs so captions stay legible over bright footage —
                    // matches the app export (CaptionOverlay) and both previews. Clip opacity is
                    // applied by the composite above. ~55% black.
                    val pad = (6 * scale).roundToInt().coerceAtLeast(0) // scale padding with the text
                    g.color = Color(0, 0, 0, 140)
                    g.fillRect(tx - pad, baseline - fm.ascent - pad, tw + pad * 2, fm.ascent + fm.descent + pad * 2)
                    // Solid white — the composite (set above) already applies clip opacity once;
                    // encoding alpha here too would fade the text quadratically vs. the scrim.
                    g.color = Color.WHITE
                    g.drawString(t.text, tx, baseline)
                    g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1f)
                }

                g.dispose()

                // Convert ARGB to BGR for encoding
                val bgrImage = BufferedImage(config.width, config.height, BufferedImage.TYPE_3BYTE_BGR)
                val bg = bgrImage.createGraphics()
                bg.drawImage(canvas, 0, 0, null)
                bg.dispose()

                recorder.record(converter.convert(bgrImage))
                onProgress(frameIdx.toFloat() / totalFrames, timeMs)
            }

            // Audio: mix all audio tracks together
            if (hasAudio) {
                exportAudio(recorder, document, clips, config, totalDurationMs)
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        onProgress(1f, totalDurationMs)
        outputFile
    }

    private fun exportAudio(
        recorder: FFmpegFrameRecorder,
        document: Document,
        clips: List<TimelineClip>,
        config: ExportConfig,
        totalDurationMs: Long,
    ) {
        val audioClips = clips.filter { it.type == ClipType.AUDIO }
        if (audioClips.isEmpty()) return

        val totalSamples = (totalDurationMs * config.sampleRate / 1000L).toInt()
        val mixL = FloatArray(totalSamples)
        val mixR = FloatArray(totalSamples)

        for (clip in audioClips) {
            val media = document.mediaFor(clip) ?: continue
            val trackSettings = document.trackSettingsFor(clip.trackId)
            if (trackSettings.muted) continue

            val file = uriToFile(media.uri) ?: continue
            val volume = clip.filters.volume * trackSettings.volume
            val pan = clip.filters.pan
            val leftGain = volume * if (pan > 0f) 1f - pan else 1f
            val rightGain = volume * if (pan < 0f) 1f + pan else 1f

            // Normalize gain
            val normGain = if (clip.filters.normalize) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        DesktopMediaDecoder.waveform(media.uri)
                    }?.let { DesktopMediaDecoder.normalizeGain(it) }
                }.getOrNull() ?: 1f
            } else 1f

            val grabber = FFmpegFrameGrabber(file)
            grabber.sampleRate = config.sampleRate
            grabber.audioChannels = 2
            grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
            grabber.start()
            try {
                grabber.timestamp = clip.trimStartMs * 1000L
                val startSample = (clip.startTimeMs * config.sampleRate / 1000L).toInt()
                val endSample = (clip.endTimeMs * config.sampleRate / 1000L).toInt().coerceAtMost(totalSamples)
                var sampleIdx = startSample

                while (sampleIdx < endSample) {
                    val frame = grabber.grabSamples() ?: break
                    val samples = frame.samples ?: continue
                    if (samples.isEmpty()) continue
                    val buf = samples[0] as? ShortBuffer ?: continue
                    buf.rewind()
                    while (buf.remaining() >= 2 && sampleIdx < endSample) {
                        val l = buf.get().toFloat() / 32768f
                        val r = buf.get().toFloat() / 32768f
                        mixL[sampleIdx] += l * leftGain * normGain
                        mixR[sampleIdx] += r * rightGain * normGain
                        sampleIdx++
                    }
                }
            } finally {
                grabber.stop()
                grabber.release()
            }
        }

        // Write mixed audio in chunks
        val chunkSize = config.sampleRate / 10
        val buf = ShortBuffer.allocate(chunkSize * 2)
        var offset = 0
        while (offset < totalSamples) {
            val end = (offset + chunkSize).coerceAtMost(totalSamples)
            buf.clear()
            for (i in offset until end) {
                buf.put((mixL[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                buf.put((mixR[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            buf.flip()
            val frame = org.bytedeco.javacv.Frame()
            frame.sampleRate = config.sampleRate
            frame.audioChannels = 2
            frame.samples = arrayOf(buf)
            recorder.record(frame)
            offset = end
        }
    }

    private fun renderClipToCanvas(
        g: java.awt.Graphics2D,
        clip: TimelineClip,
        document: Document,
        timeMs: Long,
        canvasW: Int,
        canvasH: Int,
        opacity: Float,
    ) {
        if (opacity <= 0f) return
        val media = document.mediaFor(clip) ?: return
        val file = uriToFile(media.uri) ?: return
        val sourceMs = TimelineMath.sourceTimeMs(clip, timeMs).coerceAtLeast(0)
        val relMs = timeMs - clip.startTimeMs

        val img = when (media.kind) {
            MediaKind.IMAGE -> runCatching { ImageIO.read(file) }.getOrNull()
            MediaKind.VIDEO, MediaKind.AUDIO -> runCatching {
                val grabber = FFmpegFrameGrabber(file)
                grabber.start()
                try {
                    grabber.timestamp = sourceMs * 1000L
                    val converter = Java2DFrameConverter()
                    var frame = grabber.grabImage() ?: return
                    var attempts = 0
                    while (frame.image == null && attempts++ < 5) {
                        frame = grabber.grabImage() ?: return
                    }
                    converter.convert(frame)
                } finally {
                    grabber.stop()
                    grabber.release()
                }
            }.getOrNull()
        } ?: return

        // Apply color effects
        val f = clip.filters
        val brightness = TimelineMath.valueAt(clip, KeyframeProperty.BRIGHTNESS, relMs, f.brightness)
        val contrast = TimelineMath.valueAt(clip, KeyframeProperty.CONTRAST, relMs, f.contrast)
        val saturation = TimelineMath.valueAt(clip, KeyframeProperty.SATURATION, relMs, f.saturation)
        val hue = TimelineMath.valueAt(clip, KeyframeProperty.HUE, relMs, f.hueRotate)
        val sepia = TimelineMath.valueAt(clip, KeyframeProperty.SEPIA, relMs, f.sepia)
        if (!DesktopColorMatrix.isIdentity(brightness, contrast, saturation, hue, sepia)) {
            val matrix = DesktopColorMatrix.buildMatrix(brightness, contrast, saturation, hue, sepia)
            DesktopColorMatrix.applyToImage(img, matrix)
        }
        // 3D `.cube` LUT grade, applied after the color matrix (matches Android's order). The LUT is
        // parsed once and cached by path, so it isn't re-parsed for every exported frame.
        if (f.lutPath.isNotBlank()) {
            DesktopLutCache.get(f.lutPath)?.let { DesktopColorMatrix.applyLut(img, it) }
        }

        // Subject segmentation (needs a seg model), applied after colour/LUT:
        //  • removeBackground → matte the subject to alpha so the track beneath shows through
        //  • bokeh → keep the subject sharp and blur the background
        val segModel = DesktopRenderConfig.segModelPath
        val drawImg = when {
            f.removeBackground && segModel.isNotBlank() -> DesktopSegmenter.matte(img, segModel)
            f.bokeh && segModel.isNotBlank() -> DesktopSegmenter.portraitBlur(img, segModel)
            else -> img
        }

        // Keyframed transforms
        val scale = TimelineMath.valueAt(clip, KeyframeProperty.SCALE, relMs, clip.scale).coerceAtLeast(0f)
        val rotation = TimelineMath.valueAt(clip, KeyframeProperty.ROTATION, relMs, clip.rotation)
        val ox = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_X, relMs, clip.offsetX)
        val oy = TimelineMath.valueAt(clip, KeyframeProperty.OFFSET_Y, relMs, clip.offsetY)

        val prevComposite = g.composite
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, opacity.coerceIn(0f, 1f))

        val prevTransform = g.transform
        val cx = canvasW / 2.0 + ox * canvasW
        val cy = canvasH / 2.0 + oy * canvasH

        // Fit-inside scaling
        val fitScale = minOf(canvasW.toDouble() / img.width, canvasH.toDouble() / img.height)
        val drawW = img.width * fitScale * scale
        val drawH = img.height * fitScale * scale

        val transform = AffineTransform()
        transform.translate(cx, cy)
        transform.rotate(Math.toRadians(rotation.toDouble()))
        transform.translate(-drawW / 2, -drawH / 2)
        transform.scale(fitScale * scale, fitScale * scale)

        g.transform = transform
        g.drawImage(drawImg, 0, 0, null)
        g.transform = prevTransform
        g.composite = prevComposite
    }

    private fun uriToFile(uri: String): File? = runCatching {
        when {
            uri.startsWith("file:") -> File(URI(uri))
            uri.startsWith("/") -> File(uri)
            else -> null
        }
    }.getOrNull()
}
