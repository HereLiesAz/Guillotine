package com.hereliesaz.guillotine.desktop.media

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import java.net.URI

/**
 * Bakes a standard **FFmpeg `-vf` filtergraph** onto a clip's video **in-process** via JavaCV's bundled
 * FFmpeg (an [FFmpegFrameFilter]), producing a new file — the desktop analogue of the Android tool that
 * shells an external `ffmpeg` binary. Because FFmpeg builds expose **Frei0r** plugins through
 * `frei0r=<name>:<params>` — though callers from outside the app (MCP) are restricted to
 * [com.hereliesaz.guillotine.mcp.FfmpegFilterGuard]'s allowlist, which excludes frei0r and every
 * file-reading/writing filter.
 * Video frames are pushed through the filter; any audio is copied through untouched. On-device only —
 * nothing leaves the machine.
 */
object DesktopFfmpegFilter {

    /**
     * Run [filterGraph] (an FFmpeg `-vf`-style single-input video filtergraph) over [inputUri]'s video,
     * writing a new `.mp4` in [outDir] and returning it. Throws on failure.
     */
    fun apply(inputUri: String, filterGraph: String, outDir: File): File {
        require(filterGraph.isNotBlank()) { "Provide an FFmpeg -vf filtergraph." }
        val input = uriToFile(inputUri) ?: error("Couldn't read the clip's media: $inputUri")
        outDir.mkdirs()
        val out = File(outDir, "ff_${System.nanoTime()}.mp4")

        val grabber = FFmpegFrameGrabber(input)
        grabber.pixelFormat = avutil.AV_PIX_FMT_BGR24
        try {
            grabber.start()
        } catch (e: Exception) {
            runCatching { grabber.release() }
            throw e
        }

        val width = grabber.imageWidth.coerceAtLeast(2)
        val height = grabber.imageHeight.coerceAtLeast(2)
        val fps = grabber.frameRate.takeIf { it > 1.0 } ?: 30.0
        val hasAudio = grabber.audioChannels > 0

        val filter = FFmpegFrameFilter(filterGraph, width, height)
        filter.pixelFormat = avutil.AV_PIX_FMT_BGR24
        filter.frameRate = fps
        try {
            filter.start() // throws on a malformed graph
        } catch (e: Exception) {
            runCatching { filter.release() }
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            throw e
        }

        // The recorder is created on the first FILTERED frame, sized to that frame: a graph with
        // scale/crop/transpose/pad changes the output size, and recording it at the input size made
        // FFmpeg rescale it straight back (or distort it). H.264 4:2:0 needs even dimensions.
        var recorder: FFmpegFrameRecorder? = null
        // Audio that arrives before the first video frame is held (cloned — the grabber reuses its
        // Frame buffers) until the recorder exists.
        val pendingAudio = ArrayList<org.bytedeco.javacv.Frame>()

        fun recorderFor(filtered: org.bytedeco.javacv.Frame): FFmpegFrameRecorder {
            recorder?.let { return it }
            val outW = (filtered.imageWidth and 1.inv()).coerceAtLeast(2)
            val outH = (filtered.imageHeight and 1.inv()).coerceAtLeast(2)
            val r = FFmpegFrameRecorder(out, outW, outH, if (hasAudio) grabber.audioChannels else 0)
            r.videoCodec = avcodec.AV_CODEC_ID_H264
            r.pixelFormat = avutil.AV_PIX_FMT_YUV420P
            r.frameRate = fps
            r.videoBitrate = 8_000_000
            if (hasAudio) {
                r.audioCodec = avcodec.AV_CODEC_ID_AAC
                r.sampleRate = grabber.sampleRate
                r.audioChannels = grabber.audioChannels
                r.audioBitrate = 192_000
            }
            r.start()
            recorder = r
            pendingAudio.forEach { r.record(it) }
            pendingAudio.clear()
            return r
        }

        var ok = false
        try {
            while (true) {
                val frame = grabber.grab() ?: break
                when {
                    frame.image != null -> {
                        filter.push(frame)
                        while (true) {
                            val filtered = filter.pull() ?: break
                            recorderFor(filtered).record(filtered)
                        }
                    }
                    hasAudio && frame.samples != null -> {
                        val r = recorder
                        if (r != null) r.record(frame) else if (pendingAudio.size < MAX_PENDING_AUDIO) pendingAudio += frame.clone()
                    }
                }
            }
            // Flush any frames still buffered inside the filter graph (EOF via a null push).
            runCatching {
                filter.push(null)
                while (true) {
                    val filtered = filter.pull() ?: break
                    recorderFor(filtered).record(filtered)
                }
            }
            checkNotNull(recorder) { "The FFmpeg filter produced no video frames." }.stop()
            ok = true
        } finally {
            recorder?.let { r ->
                if (!ok) runCatching { r.stop() }
                runCatching { r.release() }
            }
            pendingAudio.forEach { runCatching { it.close() } }
            runCatching { filter.stop() }
            runCatching { filter.release() }
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            if (!ok) out.delete() // never leave a truncated result behind
        }

        check(out.isFile && out.length() > 0) { "FFmpeg filter produced no output." }
        return out
    }

    /** Cap on audio frames buffered before the first filtered video frame (~a few seconds). */
    private const val MAX_PENDING_AUDIO = 512

    private fun uriToFile(uri: String): File? = runCatching {
        when {
            uri.startsWith("file:") -> File(URI(uri))
            uri.startsWith("/") -> File(uri)
            else -> null
        }
    }.getOrNull()
}
