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
 * `frei0r=<name>:<params>`, this single path covers both the FFmpeg-filter and Frei0r ecosystems.
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
        grabber.start()

        val width = grabber.imageWidth.coerceAtLeast(2)
        val height = grabber.imageHeight.coerceAtLeast(2)
        val fps = grabber.frameRate.takeIf { it > 1.0 } ?: 30.0
        val hasAudio = grabber.audioChannels > 0

        val filter = FFmpegFrameFilter(filterGraph, width, height)
        filter.pixelFormat = avutil.AV_PIX_FMT_BGR24
        filter.frameRate = fps
        filter.start()

        val recorder = FFmpegFrameRecorder(out, width, height, if (hasAudio) grabber.audioChannels else 0)
        recorder.videoCodec = avcodec.AV_CODEC_ID_H264
        recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
        recorder.frameRate = fps
        recorder.videoBitrate = 8_000_000
        if (hasAudio) {
            recorder.audioCodec = avcodec.AV_CODEC_ID_AAC
            recorder.sampleRate = grabber.sampleRate
            recorder.audioChannels = grabber.audioChannels
            recorder.audioBitrate = 192_000
        }
        recorder.start()

        try {
            while (true) {
                val frame = grabber.grab() ?: break
                when {
                    frame.image != null -> {
                        filter.push(frame)
                        while (true) {
                            val filtered = filter.pull() ?: break
                            recorder.record(filtered)
                        }
                    }
                    hasAudio && frame.samples != null -> recorder.record(frame)
                }
            }
            // Flush any frames still buffered inside the filter graph (EOF via a null push).
            runCatching {
                filter.push(null)
                while (true) {
                    val filtered = filter.pull() ?: break
                    recorder.record(filtered)
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { filter.stop() }
            runCatching { filter.release() }
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }

        check(out.isFile && out.length() > 0) { "FFmpeg filter produced no output." }
        return out
    }

    private fun uriToFile(uri: String): File? = runCatching {
        when {
            uri.startsWith("file:") -> File(URI(uri))
            uri.startsWith("/") -> File(uri)
            else -> null
        }
    }.getOrNull()
}
