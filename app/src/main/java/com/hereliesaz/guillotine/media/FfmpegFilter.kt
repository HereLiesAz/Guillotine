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
        try {
            return execute(ffmpegPath, listOf("-i", input.absolutePath, "-vf", filterGraph, "-c:a", "copy"), outDir)
        } finally {
            cleanupTemp(input, outDir)
        }
    }

    /**
     * Combine two clips with a **GL-style transition** via FFmpeg's `xfade` video filter (plus `acrossfade`
     * for audio), producing one new clip. [transition] is any xfade type (`fade`, `wipeleft`, `slideup`,
     * `circleopen`, `dissolve`, `pixelize`, `radial`, …). [offsetSec] is when the transition starts in the
     * timeline of clip A (typically A's duration − [durationSec]). On-device; requires an ffmpeg binary.
     */
    /** One clip's source segment: media [uri], [startSec] into it, and [durSec] long (its timeline trim). */
    data class Segment(val uri: String, val startSec: Float, val durSec: Float)

    fun xfade(
        context: Context,
        from: Segment,
        to: Segment,
        transition: String,
        durationSec: Float,
        ffmpegPath: String,
        outDir: File,
    ): Baked {
        require(ffmpegPath.isNotBlank()) {
            "No ffmpeg set. Point Settings → AI Analyzer → FFmpeg filters at an ffmpeg executable."
        }
        require(File(ffmpegPath).let { it.isFile || ffmpegPath == "ffmpeg" }) { "ffmpeg not found at: $ffmpegPath" }
        // The transition must fit inside BOTH clips (offset = A − dur stays valid; B isn't shorter than dur).
        val maxDur = maxOf(0.1f, minOf(from.durSec, to.durSec) - 0.05f)
        val dur = durationSec.coerceIn(0.05f, maxDur)
        val offset = (from.durSec - dur).coerceAtLeast(0f)

        outDir.mkdirs()
        var a: File? = null
        var b: File? = null
        try {
            a = localInput(context, from.uri, outDir)
            b = localInput(context, to.uri, outDir)
            // Referencing [0:a]/[1:a] for a clip with no audio makes ffmpeg fail at filtergraph parse
            // (not just mapping), so probe each and build the audio branch to match.
            val hasA = probe(a).second
            val hasB = probe(b).second
            val d = "%.3f".format(dur)
            val off = "%.3f".format(offset)
            val filter = buildString {
                append("[0:v][1:v]xfade=transition=$transition:duration=$d:offset=$off[v]")
                when {
                    hasA && hasB -> append(";[0:a][1:a]acrossfade=d=$d[a]")
                    hasA -> append(";[0:a]anull[a]")
                    // Only B has audio → delay it to the transition point so it stays in sync.
                    hasB -> append(";[1:a]adelay=delays=${(offset * 1000).toInt()}:all=1[a]")
                }
            }
            return execute(
                ffmpegPath,
                buildList {
                    add("-ss"); add("%.3f".format(from.startSec)); add("-t"); add("%.3f".format(from.durSec))
                    add("-i"); add(a.absolutePath)
                    add("-ss"); add("%.3f".format(to.startSec)); add("-t"); add("%.3f".format(to.durSec))
                    add("-i"); add(b.absolutePath)
                    add("-filter_complex"); add(filter)
                    add("-map"); add("[v]")
                    if (hasA || hasB) { add("-map"); add("[a]") }
                },
                outDir,
            )
        } finally {
            a?.let { cleanupTemp(it, outDir) }
            b?.let { cleanupTemp(it, outDir) }
        }
    }

    /** Run ffmpeg with [args] between `-y` and the generated output path; probe + return the result. */
    private fun execute(ffmpegPath: String, args: List<String>, outDir: File): Baked {
        val out = File(outDir, "ff_${System.nanoTime()}.mp4")
        val cmd = buildList { add(ffmpegPath); add("-y"); addAll(args); add(out.absolutePath) }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
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
    }

    private fun cleanupTemp(file: File, outDir: File) {
        if (file.parentFile == outDir && file.name.startsWith("ff_in_")) file.delete()
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
