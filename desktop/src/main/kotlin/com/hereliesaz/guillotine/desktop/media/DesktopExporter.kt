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

    /**
     * @param region When non-null, "Render Loop Region Only" (Vegas J.4): the export is pre-clamped to
     * this `[start, end)` timeline window ([Document.clampedToRegion]) before anything else runs, so the
     * rest of this pipeline (frame/sample loops bounded by `document.totalDurationMs`) is unchanged — it
     * just sees a shorter document.
     */
    suspend fun export(
        document: Document,
        config: ExportConfig = ExportConfig(),
        onProgress: (Float, Long) -> Unit = { _, _ -> },
        region: LongRange? = null,
    ): File = withContext(Dispatchers.IO) {
        val document = if (region != null) document.clampedToRegion(region.first, region.last) else document
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
        val hasAudio = clips.any {
            it.type == ClipType.AUDIO || (it.type == ClipType.VIDEO && document.mediaFor(it)?.hasAudio == true)
        }

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

        // One decoder per source file, reused across every frame that samples it. Opening/starting a
        // fresh FFmpegFrameGrabber per frame (its header read + codec init) made export crawl; export is
        // forward-sequential, so seeking a live grabber each frame is far cheaper. Released in `finally`.
        val grabberCache = HashMap<String, FFmpegFrameGrabber>()

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
                        renderClipToCanvas(g, clip, document, timeMs, config.width, config.height, opacity, grabberCache, converter)
                    }
                    incoming?.let { clip ->
                        val opacity = TimelineMath.valueAt(clip, KeyframeProperty.OPACITY, timeMs - clip.startTimeMs, 1f) *
                            trackSettings.opacity * (xfade ?: 0f)
                        renderClipToCanvas(g, clip, document, timeMs, config.width, config.height, opacity, grabberCache, converter)
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
                    // Size the caption relative to the export canvas — a fixed 14px was near-illegible on
                    // a 1080p+ frame. Match the app export's ~64px-at-1080p so captions read at the same
                    // relative size on both platforms (then apply the clip's own scale).
                    val baseFontPx = config.height * (64f / 1080f)
                    val font = java.awt.Font(family, java.awt.Font.PLAIN, 1).deriveFont(baseFontPx * scale)
                    g.font = font
                    val cx = config.width / 2 + (ox * config.width).roundToInt()
                    val cy = config.height / 2 + (oy * config.height).roundToInt()
                    val fm = g.fontMetrics
                    val lineH = fm.ascent + fm.descent
                    val pad = (6 * scale).roundToInt().coerceAtLeast(0) // scale padding with the text
                    // Wrap long captions to ~90% of the frame width instead of running off-screen,
                    // then draw the lines as a vertically-centered block around the anchor.
                    val lines = wrapToWidth(t.text, fm, (config.width * 0.9f).toInt())
                    val blockTop = cy - (lineH * lines.size) / 2
                    lines.forEachIndexed { i, line ->
                        val lw = fm.stringWidth(line)
                        val lx = cx - lw / 2
                        val baseline = blockTop + i * lineH + fm.ascent
                        // Dark scrim behind each line so captions stay legible over bright footage —
                        // matches the app export and both previews. Clip opacity applied by the
                        // composite above. ~55% black.
                        g.color = scrimColor
                        g.fillRect(lx - pad, baseline - fm.ascent - pad, lw + pad * 2, lineH + pad * 2)
                        // Solid white — the composite already applies clip opacity once; encoding
                        // alpha here too would fade the text quadratically vs. the scrim.
                        g.color = Color.WHITE
                        g.drawString(line, lx, baseline)
                    }
                    g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1f)
                }

                g.dispose()

                // Global crop (x/y/w/h in % of the frame): take the crop sub-rectangle of the composited
                // frame and scale it back to fill the output, so the crop rectangle becomes the visible
                // frame (matches the model's crop semantics + the app's Media3 Crop). No-op at full frame.
                val crop = document.settings.crop
                val source: BufferedImage = if (crop.x != 0f || crop.y != 0f || crop.w != 100f || crop.h != 100f) {
                    val cx = (crop.x / 100f * config.width).roundToInt().coerceIn(0, config.width - 1)
                    val cy = (crop.y / 100f * config.height).roundToInt().coerceIn(0, config.height - 1)
                    val cw = (crop.w / 100f * config.width).roundToInt().coerceIn(1, config.width - cx)
                    val ch = (crop.h / 100f * config.height).roundToInt().coerceIn(1, config.height - cy)
                    val scaled = BufferedImage(config.width, config.height, BufferedImage.TYPE_INT_ARGB)
                    val sg = scaled.createGraphics()
                    sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    sg.drawImage(canvas.getSubimage(cx, cy, cw, ch), 0, 0, config.width, config.height, null)
                    sg.dispose()
                    scaled
                } else {
                    canvas
                }

                // Convert ARGB to BGR for encoding
                val bgrImage = BufferedImage(config.width, config.height, BufferedImage.TYPE_3BYTE_BGR)
                val bg = bgrImage.createGraphics()
                bg.drawImage(source, 0, 0, null)
                bg.dispose()

                recorder.record(converter.convert(bgrImage))
                onProgress(frameIdx.toFloat() / totalFrames, timeMs)
            }

            // Audio: mix all audio tracks together
            if (hasAudio) {
                exportAudio(recorder, document, clips, config, totalDurationMs)
            }
        } finally {
            grabberCache.values.forEach {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
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
        // Mix standalone + shadow audio clips as before, PLUS a video clip's own embedded audio when it
        // has no linked shadow clip standing in for it — otherwise a video's sound was dropped from the
        // export. (A shadowed video's audio still comes through its shadow, unchanged.)
        val shadowedVideoIds = clips.mapNotNull { if (it.type == ClipType.AUDIO) it.linkedClipId else null }.toHashSet()
        val audioClips = clips.filter { c ->
            c.type == ClipType.AUDIO ||
                (c.type == ClipType.VIDEO && document.mediaFor(c)?.hasAudio == true && c.id !in shadowedVideoIds)
        }
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
            // When VOLUME/PAN are keyframed, gains are interpolated per sample below instead of using the
            // static gains above — otherwise volume/pan automation exported flat on desktop.
            val volKfs = clip.keyframes.filter { it.property == KeyframeProperty.VOLUME }.sortedBy { it.timeMs }
            val panKfs = clip.keyframes.filter { it.property == KeyframeProperty.PAN }.sortedBy { it.timeMs }
            val animatedGain = volKfs.isNotEmpty() || panKfs.isNotEmpty()
            // Mirror the video path's REMOVE handling: samples whose source time falls in a removed
            // range are written as silence, so a marked-but-not-yet-applied cut is silent in the export
            // exactly where the picture goes black. The audio here reads source 1:1 (speed is ignored in
            // the mix), so source ms maps linearly from the sample offset.
            val hasRemoves = clip.edits.any { it.action == com.hereliesaz.guillotine.model.EditAction.REMOVE }

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
            try {
                grabber.start()
            } catch (e: Exception) {
                runCatching { grabber.release() }
                throw e
            }
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
                        val relMs = (sampleIdx - startSample) * 1000L / config.sampleRate
                        // Drop samples inside a removed source range (read them, but mix silence).
                        if (hasRemoves && TimelineMath.isRemoved(clip, clip.trimStartMs + relMs)) {
                            buf.get(); buf.get()
                            sampleIdx++
                            continue
                        }
                        var lg = leftGain
                        var rg = rightGain
                        if (animatedGain) {
                            val v = TimelineMath.interpolateSorted(volKfs, relMs, clip.filters.volume) * trackSettings.volume
                            val p = TimelineMath.interpolateSorted(panKfs, relMs, clip.filters.pan)
                            lg = v * if (p > 0f) 1f - p else 1f
                            rg = v * if (p < 0f) 1f + p else 1f
                        }
                        val l = buf.get().toFloat() / 32768f
                        val r = buf.get().toFloat() / 32768f
                        mixL[sampleIdx] += l * lg * normGain
                        mixR[sampleIdx] += r * rg * normGain
                        sampleIdx++
                    }
                }
            } finally {
                runCatching { grabber.stop() }
                runCatching { grabber.release() }
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
        grabbers: MutableMap<String, FFmpegFrameGrabber>,
        converter: Java2DFrameConverter,
    ) {
        if (opacity <= 0f) return
        val media = document.mediaFor(clip) ?: return
        val file = uriToFile(media.uri) ?: return
        // Frame decimation (frameStep): hold the same source frame across `frameStep` output frames by
        // snapping the source time to the project's kept-frame grid. Audio is mixed separately and stays
        // in sync (same duration). No-op when frameStep <= 1.
        val sourceMs = TimelineMath.decimateSourceMs(
            clip,
            TimelineMath.sourceTimeMs(clip, timeMs).coerceAtLeast(0),
            document.settings.frameDurationMs,
        )
        // Honor un-applied REMOVE marks: if this frame samples a removed source range, draw nothing so
        // the lower track / black shows through. The audio mixer gates on the same isRemoved check, so
        // video and audio stay in sync. (Committed cuts ripple via applyCuts and carry no marks; this
        // only affects clips exported while still carrying scissor marks — which desktop previously
        // played straight through.)
        if (TimelineMath.isRemoved(clip, sourceMs)) return
        val relMs = timeMs - clip.startTimeMs

        val img = when (media.kind) {
            MediaKind.IMAGE -> runCatching { ImageIO.read(file) }.getOrNull()
            MediaKind.VIDEO, MediaKind.AUDIO -> {
                // Reuse (or open once) a grabber for this file; seek instead of reopening per frame.
                val path = file.absolutePath
                val grabber = runCatching {
                    grabbers.getOrPut(path) { openExportGrabber(file) }
                }.getOrNull() ?: return
                runCatching {
                    // Only seek when not already positioned just before the target — a per-frame seek is
                    // expensive, and a forward export usually advances sequentially; grab the next frame
                    // instead when the target is within ~0.5s ahead of the current position.
                    val target = sourceMs * 1000L
                    val current = grabber.timestamp
                    if (current < 0 || target < current || target - current > 500_000L) {
                        grabber.timestamp = target
                    }
                    var frame = grabber.grabImage() ?: return@runCatching null
                    var attempts = 0
                    while (frame.image == null && attempts++ < 5) {
                        frame = grabber.grabImage() ?: return@runCatching null
                    }
                    converter.convert(frame)
                }.onFailure {
                    // Evict a broken grabber so later frames don't keep hitting the same failure.
                    grabbers.remove(path)
                    runCatching { grabber.stop() }
                    runCatching { grabber.release() }
                }.getOrNull()
            }
        } ?: return

        // Apply color effects
        val f = clip.filters
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
        // legacy single-slot fallback that keeps an already-saved project rendering unchanged. LUT
        // layers apply first (matches the pre-chain LUT-then-shader order); each is parsed once and
        // cached by path, so it isn't re-parsed for every exported frame.
        val fxChain = f.effectiveFxChain.filter { it.enabled }
        for (layer in fxChain) {
            if (layer.kind != com.hereliesaz.guillotine.model.FxLayer.KIND_LUT) continue
            DesktopLutCache.get(layer.path)?.let { DesktopColorMatrix.applyLut(img, it) }
        }

        // Subject segmentation (needs a seg model), applied after colour/LUT, before shaders:
        //  • removeBackground → matte the subject to alpha so the track beneath shows through
        //  • bokeh → keep the subject sharp and blur the background
        val segModel = DesktopRenderConfig.segModelPath
        val segImg = when {
            f.removeBackground && segModel.isNotBlank() -> DesktopSegmenter.matte(img, segModel)
            f.bokeh && segModel.isNotBlank() -> DesktopSegmenter.portraitBlur(img, segModel)
            else -> img
        }
        // Custom GLSL/ISF shader layers, applied last in chain order (matches the preview path).
        // Rendered through Skia's CPU raster runtime effect; a no-op if a shader can't be compiled to SkSL.
        var drawImg = segImg
        for (layer in fxChain) {
            if (layer.kind != com.hereliesaz.guillotine.model.FxLayer.KIND_SHADER) continue
            drawImg = DesktopShaderPass.apply(drawImg, layer.path, layer.params, relMs.coerceAtLeast(0))
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

    /**
     * Open and start an [FFmpegFrameGrabber] on [file] for the per-file grabber cache, releasing its
     * native handle first if `start()` throws (corrupt/truncated file, unsupported codec) instead of
     * leaking the AVFormatContext.
     */
    private fun openExportGrabber(file: File): FFmpegFrameGrabber {
        val grabber = FFmpegFrameGrabber(file)
        try {
            grabber.start()
        } catch (e: Exception) {
            runCatching { grabber.release() }
            throw e
        }
        return grabber
    }

    private fun uriToFile(uri: String): File? = runCatching {
        when {
            uri.startsWith("file:") -> File(URI(uri))
            uri.startsWith("/") -> File(uri)
            else -> null
        }
    }.getOrNull()

    // Reused across the per-frame export hot loop to avoid re-allocating on every line/frame.
    private val scrimColor = Color(0, 0, 0, 140)
    private val whitespaceRegex = Regex("\\s+")

    /** Greedy word-wrap: pack words into lines no wider than [maxW] px for [fm]. A single word wider
     *  than maxW gets its own line (may overflow — rare). Never returns an empty list. Runs per
     *  frame during export, so it fast-paths text that already fits and reuses one StringBuilder. */
    private fun wrapToWidth(text: String, fm: java.awt.FontMetrics, maxW: Int): List<String> {
        val trimmed = text.trim()
        if (fm.stringWidth(trimmed) <= maxW) return listOf(trimmed) // fast path: fits on one line
        val words = trimmed.split(whitespaceRegex).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        val cur = StringBuilder()
        for (w in words) {
            if (cur.isEmpty()) {
                cur.append(w)
            } else {
                val lenBefore = cur.length
                cur.append(' ').append(w)
                if (fm.stringWidth(cur.toString()) > maxW) {
                    cur.setLength(lenBefore)      // undo: this word starts a new line
                    lines.add(cur.toString())
                    cur.setLength(0)
                    cur.append(w)
                }
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }
}
