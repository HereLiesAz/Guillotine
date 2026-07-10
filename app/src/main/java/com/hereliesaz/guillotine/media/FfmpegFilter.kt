package com.hereliesaz.guillotine.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bakes a standard **FFmpeg `-vf` filtergraph** onto a clip's video using an external `ffmpeg`
 * executable, producing a new file. Because FFmpeg builds expose **Frei0r** plugins through the
 * `frei0r=<name>:<params>` filter, this single path covers both the FFmpeg-filter and Frei0r
 * ecosystems. This is a bake-to-new-clip step (FFmpeg can't drive live GL preview).
 *
 * Everything runs **on-device** — the ffmpeg process reads/writes local files only, nothing leaves the
 * device. Requires the user to point [ffmpegPath] at an ffmpeg binary (desktop-first; on Android a
 * bundled/downloaded ARM binary). Blank/invalid path → the caller relays a clear error.
 */
object FfmpegFilter {

    /** Result of a bake: the output file, its measured duration (ms), and whether it carries audio. */
    data class Baked(val file: File, val durationMs: Long, val hasAudio: Boolean)

    private const val TIMEOUT_MINUTES = 10L

    /**
     * Run `ffmpeg -y -i <input> -vf "<filterGraph>" -c:a copy <out.mp4>`. [inputUri] may be a
     * `content://` (copied to a temp file first) or a `file://`/absolute path. Throws with ffmpeg's
     * stderr tail on failure.
     */
    fun apply(context: Context, inputUri: String, filterGraph: String, ffmpegPath: String, outDir: File): Baked {
        require(ffmpegPath.isNotBlank()) {
            "No ffmpeg set. Point Settings → AI Analyzer → FFmpeg filters at an ffmpeg executable."
        }
        require(File(ffmpegPath).let { it.isFile || ffmpegPath == "ffmpeg" }) {
            "ffmpeg not found at: $ffmpegPath"
        }
        require(filterGraph.isNotBlank()) { "Provide an FFmpeg -vf filtergraph." }

        outDir.mkdirs()
        val input = localInput(context, inputUri, outDir)
        val out = File(outDir, "ff_${System.nanoTime()}.mp4")
        try {
            val process = ProcessBuilder(
                ffmpegPath, "-y", "-i", input.absolutePath,
                "-vf", filterGraph, "-c:a", "copy", out.absolutePath,
            ).redirectErrorStream(true).start()

            val log = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("ffmpeg timed out after $TIMEOUT_MINUTES min.")
            }
            check(process.exitValue() == 0 && out.isFile && out.length() > 0) {
                "ffmpeg failed: ${log.takeLast(400).trim()}"
            }
            val (durationMs, hasAudio) = probe(out)
            return Baked(out, durationMs, hasAudio)
        } finally {
            if (input.parentFile == outDir && input.name.startsWith("ff_in_")) input.delete()
        }
    }

    /** ffmpeg needs a real path; copy a content:// source to a temp file, else use the file path. */
    private fun localInput(context: Context, uri: String, outDir: File): File {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return File(requireNotNull(parsed.path))
        if (parsed.scheme == null || parsed.scheme == "") return File(uri)
        val tmp = File(outDir, "ff_in_${System.nanoTime()}")
        context.contentResolver.openInputStream(parsed)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Couldn't read the clip's media.")
        return tmp
    }

    private fun probe(file: File): Pair<Long, Boolean> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            dur to hasAudio
        } finally {
            r.release()
        }
    }.getOrDefault(0L to false)
}
