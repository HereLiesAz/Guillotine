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
        // Render into a sibling `.part` file and only rename it over the target once the encoder has
        // finished cleanly: a cancelled or failed export no longer leaves a truncated .mp4 behind, and
        // re-exporting doesn't destroy the previous good file until the new one is complete.
        val partFile = File(outputDir, "${safeName}.mp4.part")
        // H.264 / yuv420p needs even dimensions (chroma is subsampled 2x2); round odd sizes down.
        val outW = evenDimension(config.width)
        val outH = evenDimension(config.height)
        @Suppress("NAME_SHADOWING")
        val config = config.copy(width = outW, height = outH)

        val totalDurationMs = document.totalDurationMs
        if (totalDurationMs <= 0) error("Nothing to export")

        val frameDurationMs = (1000.0 / config.fps)
        val totalFrames = (totalDurationMs / frameDurationMs).toLong() + 1
        val converter = Java2DFrameConverter()

        val clips = document.clips.filterNot { it.trackId in document.disabledTrackIds }
        val hasAudio = clips.any {
            it.type == ClipType.AUDIO || (it.type == ClipType.VIDEO && document.mediaFor(it)?.hasAudio == true)
        }

        partFile.delete()
        val recorder = FFmpegFrameRecorder(partFile, config.width, config.height)
        recorder.format = "mp4" // the .part extension would otherwise hide the container from FFmpeg
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

        var completed = false
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

                // Composite tracks bottom-to-top. TEXT clips are "just a clip on a video track", so they
                // composite at their track's layer with the same pairing/crossfade rules as the preview's
                // VideoTrackLayer (only same-type pairs crossfade; a caption over a video on one track
                // shows both at full opacity). Text used to be drawn over everything after all tracks,
                // so a caption on a lower track was hidden in preview but visible in the export.
                document.videoTracks.asReversed().forEach { trackId ->
                    val trackClips = (TimelineMath.activeClips(clips, ClipType.VIDEO, timeMs) +
                        TimelineMath.activeClips(clips, ClipType.TEXT, timeMs))
                        .filter { it.trackId == trackId }
                        .sortedBy { it.startTimeMs }

                    val outgoing = trackClips.getOrNull(0)
                    val incoming = trackClips.getOrNull(1)
                    val xfade = if (outgoing != null && incoming != null && outgoing.type == incoming.type) {
                        val span = (outgoing.endTimeMs - incoming.startTimeMs).coerceAtLeast(1)
                        ((timeMs - incoming.startTimeMs).toFloat() / span).coerceIn(0f, 1f)
                    } else null
                    val differentTypeOverlap = outgoing != null && incoming != null && outgoing.type != incoming.type

                    val trackSettings = document.trackSettingsFor(trackId)
                    fun draw(clip: TimelineClip, mix: Float) {
                        val opacity = TimelineMath.valueAt(clip, KeyframeProperty.OPACITY, timeMs - clip.startTimeMs, 1f) *
                            trackSettings.opacity * mix
                        if (clip.type == ClipType.TEXT) {
                            if (opacity > 0f) drawTextClip(g, clip, timeMs, config, opacity)
                        } else {
                            renderClipToCanvas(g, clip, document, timeMs, config.width, config.height, opacity, grabberCache, converter)
                        }
                    }
                    outgoing?.let { draw(it, if (differentTypeOverlap) 1f else 1f - (xfade ?: 0f)) }
                    incoming?.let { draw(it, if (differentTypeOverlap) 1f else (xfade ?: 0f)) }
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
                val ctx = coroutineContext
                exportAudio(recorder, document, clips, config, totalDurationMs) { ctx.ensureActive() }
            }
            completed = true
        } finally {
            grabberCache.values.forEach {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            // stop() flushes the encoder and writes the moov atom; a failure there means the file is bad.
            val closed = runCatching { recorder.stop() }
            runCatching { recorder.release() }
            if (completed && closed.isFailure) {
                partFile.delete()
                throw closed.exceptionOrNull()!!
            }
            if (!completed) partFile.delete()
        }
        try {
            java.nio.file.Files.move(
                partFile.toPath(), outputFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(partFile.toPath(), outputFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }

        onProgress(1f, totalDurationMs)
        outputFile
    }

    /** Draw caption clip [t] centred on its (keyframed) offset, word-wrapped, at [opacity]. */
    private fun drawTextClip(g: java.awt.Graphics2D, t: TimelineClip, timeMs: Long, config: ExportConfig, opacity: Float) {
        val relMs = (timeMs - t.startTimeMs).coerceIn(0, t.durationMs)
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
        // Wrap long captions to ~90% of the frame width instead of running off-screen,
        // then draw the lines as a vertically-centered block around the anchor.
        val lines = wrapToWidth(t.text, fm, (config.width * 0.9f).toInt())
        val blockTop = cy - (lineH * lines.size) / 2
        lines.forEachIndexed { i, line ->
            val lw = fm.stringWidth(line)
            val lx = cx - lw / 2
            val baseline = blockTop + i * lineH + fm.ascent
            // Transparent glyphs only — no baked-in background (matches the app export and
            // both previews). A background, if wanted, is a separate shape-layer clip
            // stacked underneath. Solid white — the composite above already applies clip
            // opacity once; encoding alpha here too would fade the text quadratically.
            g.color = Color.WHITE
            g.drawString(line, lx, baseline)
        }
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1f)
    }

    /** Largest even value <= [v] (minimum 2): H.264 4:2:0 can't encode odd widths/heights. */
    internal fun evenDimension(v: Int): Int = (v and 1.inv()).coerceAtLeast(2)

    /** Seconds of audio mixed per pass — bounds the mix buffer instead of allocating the whole timeline. */
    private const val MIX_CHUNK_SECONDS = 5

    /**
     * Mix every audible clip into the recorder, streaming: the timeline is processed in
     * [MIX_CHUNK_SECONDS] chunks with one open decoder per clip carried across chunks, so memory is
     * O(chunk) instead of two float arrays for the whole timeline (~1.3 GB for an hour at 44.1 kHz).
     *
     * Each clip's audio follows its speed ([ClipFilters.speed] and SPEED keyframes) the same way the
     * picture does ([TimelineMath.sourceTimeMs]): source position advances by the clip's speed per
     * output sample (linear-interpolated resample — a varispeed, so pitch follows speed), matching the
     * preview's [DesktopAudioPlayer.rate]. [checkCancelled] runs once per chunk.
     */
    private fun exportAudio(
        recorder: FFmpegFrameRecorder,
        document: Document,
        clips: List<TimelineClip>,
        config: ExportConfig,
        totalDurationMs: Long,
        checkCancelled: () -> Unit,
    ) {
        // Mix standalone + shadow audio clips as before, PLUS a video clip's own embedded audio when it
        // has no linked shadow clip standing in for it — otherwise a video's sound was dropped from the
        // export. (A shadowed video's audio still comes through its shadow, unchanged.)
        val shadowedVideoIds = clips.mapNotNull { if (it.type == ClipType.AUDIO) it.linkedClipId else null }.toHashSet()
        val audioClips = clips.filter { c ->
            c.type == ClipType.AUDIO ||
                (c.type == ClipType.VIDEO && document.mediaFor(c)?.hasAudio == true && c.id !in shadowedVideoIds)
        }.filterNot { document.trackSettingsFor(it.trackId).muted }
        if (audioClips.isEmpty()) return

        val sr = config.sampleRate
        val totalSamples = totalDurationMs * sr / 1000L
        val chunk = sr * MIX_CHUNK_SECONDS
        val mixL = FloatArray(chunk)
        val mixR = FloatArray(chunk)
        val readers = HashMap<String, ClipAudioMixer>()
        val failed = HashSet<String>()
        val out = ShortBuffer.allocate(chunk * 2)
        try {
            var chunkStart = 0L
            while (chunkStart < totalSamples) {
                checkCancelled()
                val chunkEnd = minOf(chunkStart + chunk, totalSamples)
                java.util.Arrays.fill(mixL, 0f)
                java.util.Arrays.fill(mixR, 0f)
                for (clip in audioClips) {
                    val cs = clip.startTimeMs * sr / 1000L
                    val ce = minOf(clip.endTimeMs * sr / 1000L, totalSamples)
                    if (ce <= chunkStart) {
                        readers.remove(clip.id)?.close() // finished: free its decoder now
                        continue
                    }
                    if (cs >= chunkEnd || clip.id in failed) continue
                    val mixer = readers[clip.id] ?: run {
                        val m = ClipAudioMixer.open(clip, document, sr)
                        if (m == null) { failed += clip.id; return@run null }
                        readers[clip.id] = m
                        m
                    } ?: continue
                    mixer.mixInto(mixL, mixR, chunkStart, maxOf(cs, chunkStart), minOf(ce, chunkEnd))
                }
                val n = (chunkEnd - chunkStart).toInt()
                out.clear()
                for (i in 0 until n) {
                    out.put((mixL[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                    out.put((mixR[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
                out.flip()
                val frame = org.bytedeco.javacv.Frame()
                frame.sampleRate = sr
                frame.audioChannels = 2
                frame.samples = arrayOf(out)
                recorder.record(frame)
                chunkStart = chunkEnd
            }
        } finally {
            readers.values.forEach { it.close() }
        }
    }

    /**
     * One clip's contribution to the export mix: an open decoder (packed S16 stereo at the mix rate)
     * read forward only, plus the clip's gains, keyframes and speed curve. [srcPos] is the read head in
     * source samples relative to [TimelineClip.trimStartMs].
     */
    private class ClipAudioMixer(
        private val clip: TimelineClip,
        private val grabber: FFmpegFrameGrabber,
        private val sr: Int,
        private val trackVolume: Float,
        private val normGain: Float,
        private val speedKfs: List<com.hereliesaz.guillotine.model.Keyframe>,
        private val baseSpeed: Float,
    ) {
        private val volKfs = clip.keyframes.filter { it.property == KeyframeProperty.VOLUME }.sortedBy { it.timeMs }
        private val panKfs = clip.keyframes.filter { it.property == KeyframeProperty.PAN }.sortedBy { it.timeMs }
        private val hasRemoves = clip.edits.any { it.action == com.hereliesaz.guillotine.model.EditAction.REMOVE }
        private val clipStartSample = clip.startTimeMs * sr / 1000L

        // Decoded-sample window: source sample index [winBase, winBase + winLen).
        private var winL = FloatArray(sr)
        private var winR = FloatArray(sr)
        private var winBase = 0L
        private var winLen = 0
        private var eof = false
        private var srcPos = 0.0

        /** Make sure source sample [idx] is in the window (decoding forward); false at end of stream. */
        private fun ensure(idx: Long): Boolean {
            while (idx >= winBase + winLen) {
                if (eof) return false
                // Drop samples behind the read head before appending, keeping one for interpolation.
                val keepFrom = (minOf(idx, winBase + winLen) - 1).coerceAtLeast(winBase)
                val drop = (keepFrom - winBase).toInt()
                if (drop > 0) {
                    System.arraycopy(winL, drop, winL, 0, winLen - drop)
                    System.arraycopy(winR, drop, winR, 0, winLen - drop)
                    winLen -= drop
                    winBase += drop
                }
                val frame = grabber.grabSamples()
                if (frame == null) { eof = true; return false }
                val buf = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                buf.rewind()
                val n = buf.remaining() / 2
                if (winLen + n > winL.size) {
                    val cap = maxOf(winL.size * 2, winLen + n)
                    winL = winL.copyOf(cap)
                    winR = winR.copyOf(cap)
                }
                for (i in 0 until n) {
                    winL[winLen + i] = buf.get().toFloat() / 32768f
                    winR[winLen + i] = buf.get().toFloat() / 32768f
                }
                winLen += n
            }
            return true
        }

        /** Add this clip's samples for timeline samples [from, to) into the chunk starting at [chunkStart]. */
        fun mixInto(mixL: FloatArray, mixR: FloatArray, chunkStart: Long, from: Long, to: Long) {
            var lastMs = Long.MIN_VALUE
            var speed = baseSpeed
            var lg = 0f
            var rg = 0f
            for (s in from until to) {
                val relMs = (s - clipStartSample) * 1000L / sr
                if (relMs != lastMs) {
                    // Per-millisecond: speed (same curve TimelineMath.speedAt reads) and gains.
                    lastMs = relMs
                    speed = if (speedKfs.isEmpty()) baseSpeed else TimelineMath.interpolateSorted(speedKfs, relMs, baseSpeed)
                    val v = TimelineMath.interpolateSorted(volKfs, relMs, clip.filters.volume) * trackVolume
                    val p = TimelineMath.interpolateSorted(panKfs, relMs, clip.filters.pan)
                    lg = v * (if (p > 0f) 1f - p else 1f) * normGain
                    rg = v * (if (p < 0f) 1f + p else 1f) * normGain
                }
                val i0 = srcPos.toLong()
                if (!ensure(i0 + 1)) {
                    if (!ensure(i0)) return // source exhausted: the rest is silence
                }
                val frac = (srcPos - i0).toFloat()
                val a = (i0 - winBase).toInt()
                val b = if (a + 1 < winLen) a + 1 else a
                val l = winL[a] + (winL[b] - winL[a]) * frac
                val r = winR[a] + (winR[b] - winR[a]) * frac
                // Drop samples inside a removed source range (read them, but mix silence) — mirrors
                // the video path, which draws nothing for a removed frame.
                val removed = hasRemoves &&
                    TimelineMath.isRemoved(clip, clip.trimStartMs + (srcPos * 1000.0 / sr).toLong())
                if (!removed) {
                    val k = (s - chunkStart).toInt()
                    mixL[k] += l * lg
                    mixR[k] += r * rg
                }
                srcPos += speed
            }
        }

        fun close() {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }

        companion object {
            /** Open [clip]'s source for mixing (null if it has no local media). Throws if the decoder fails. */
            fun open(clip: TimelineClip, document: Document, sr: Int): ClipAudioMixer? {
                val media = document.mediaFor(clip) ?: return null
                val file = DesktopExporter.uriToFile(media.uri) ?: return null
                // The clip's own speed curve — the same one the preview audio and TimelineMath.sourceTimeMs use.
                val speedSource = clip
                val speedKfs = speedSource.keyframes.filter { it.property == KeyframeProperty.SPEED }.sortedBy { it.timeMs }
                val normGain = if (clip.filters.normalize) {
                    runCatching {
                        kotlinx.coroutines.runBlocking { DesktopMediaDecoder.waveform(media.uri) }
                            ?.let { DesktopMediaDecoder.normalizeGain(it) }
                    }.getOrNull() ?: 1f
                } else 1f
                val grabber = FFmpegFrameGrabber(file)
                grabber.sampleRate = sr
                grabber.audioChannels = 2
                grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
                try {
                    grabber.start()
                } catch (e: Exception) {
                    runCatching { grabber.release() }
                    throw e
                }
                grabber.timestamp = clip.trimStartMs * 1000L
                return ClipAudioMixer(
                    clip, grabber, sr, document.trackSettingsFor(clip.trackId).volume, normGain,
                    speedKfs, speedSource.filters.speed.coerceIn(0.1f, 10f),
                )
            }
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

        // Stable layer baseline: source aspect, project height. Canvas WIDTH is deliberately absent
        // from this calculation, so changing the project aspect only changes what part of the layer
        // the canvas sees. User-authored clip.scale is the only event-level size transform.
        val baseScale = canvasH.toDouble() / img.height.coerceAtLeast(1)
        val drawW = img.width * baseScale * scale
        val drawH = img.height * baseScale * scale

        val transform = AffineTransform()
        transform.translate(cx, cy)
        transform.rotate(Math.toRadians(rotation.toDouble()))
        transform.translate(-drawW / 2, -drawH / 2)
        transform.scale(baseScale * scale, baseScale * scale)

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

    internal fun uriToFile(uri: String): File? = runCatching {
        when {
            uri.startsWith("file:") -> File(URI(uri))
            uri.startsWith("/") -> File(uri)
            else -> null
        }
    }.getOrNull()

    // Reused across the per-frame export hot loop to avoid re-allocating on every line/frame.
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
