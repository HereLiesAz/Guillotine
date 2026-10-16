package com.hereliesaz.guillotine.mcp

/**
 * Allowlist check for FFmpeg filtergraphs that come from outside the app (an MCP client, the assistant
 * model) — `apply_ffmpeg_filter` and friends. A filtergraph is a small language with file I/O: `movie=`
 * reads any path, `drawtext=textfile=`, `curves=psfile=`, `lut3d`, `haldclut` and `sofalizer` read
 * files, `metadata=file=`, `psnr`/`ssim` `stats_file=` and `vidstabdetect=result=` WRITE them, and
 * `sendcmd` turns a file into filter commands. So instead of a denylist, a graph is accepted only when
 * every filter in it is in [ALLOWED_FILTERS] (a vetted set of pure pixel/sample processing filters) and
 * no option uses a file-ish key ([FORBIDDEN_OPTION_KEYS]).
 *
 * The parser follows FFmpeg's graph syntax closely enough to be conservative: chains split on `;`,
 * filters on `,`, `[label]` pads stripped, `name@instance` reduced to `name`, options split on `:`,
 * and `'…'` quoting / `\` escaping respected so a quoted comma can't hide a second filter. Anything it
 * can't make sense of is rejected.
 *
 * Usage: `FfmpegFilterGuard.check(graph)` returns null when the graph is allowed, else a human-readable
 * reason; [requireAllowed] throws [IllegalArgumentException] with that reason. [isValidXfadeTransition]
 * validates an `xfade` transition name (which is interpolated into a graph) against FFmpeg's list.
 */
object FfmpegFilterGuard {

    /** Pure processing filters with no file, device, network or command-channel options. */
    val ALLOWED_FILTERS: Set<String> = setOf(
        // geometry / timing-neutral transforms
        "scale", "scale2ref", "crop", "pad", "hflip", "vflip", "transpose", "rotate", "setsar", "setdar",
        "lenscorrection", "perspective", "fillborders", "zoompan",
        // colour / tone
        "eq", "hue", "curves", "colorbalance", "colorchannelmixer", "colorlevels", "colortemperature",
        "lutrgb", "lutyuv", "lut", "negate", "format", "pseudocolor", "colorize", "vibrance", "selectivecolor",
        "huesaturation", "monochrome", "grayworld", "exposure", "normalize", "histeq", "colorcontrast",
        "colorcorrect", "chromashift", "rgbashift",
        // blur / sharpen / noise
        "unsharp", "gblur", "boxblur", "avgblur", "smartblur", "sab", "dblur", "noise", "hqdn3d", "nlmeans",
        "atadenoise", "removegrain", "deband", "deflicker", "deshake", "cas", "median", "bilateral",
        // stylise
        "vignette", "edgedetect", "drawgrid", "drawbox", "drawtext", "tmix", "lagfun", "pixelize", "blend",
        "overlay", "elbg", "convolution", "sobel", "prewitt", "roberts", "erosion", "dilation", "deflate",
        "inflate", "geq", "chromakey", "colorkey", "colorhold", "despill", "minterpolate", "framestep",
        "fps", "tblend", "random", "shuffleframes", "split", "null", "nullsink", "copy", "xfade",
        "fade", "setpts", "trim", "reverse", "loop", "tpad", "select", "framerate", "interlace", "yadif",
        "bwdif", "w3fdif", "hstack", "vstack", "xstack", "alphamerge", "alphaextract", "premultiply",
        "unpremultiply", "colormatrix", "colorspace", "kerndeint", "showinfo",
        // audio
        "volume", "atempo", "afade", "acrossfade", "aformat", "aresample", "highpass", "lowpass", "bandpass",
        "bandreject", "equalizer", "bass", "treble", "afftdn", "anlmdn", "agate", "acompressor", "alimiter",
        "loudnorm", "dynaudnorm", "aecho", "chorus", "flanger", "aphaser", "vibrato", "tremolo", "asetrate",
        "areverse", "atrim", "asetpts", "apad", "amix", "amerge", "pan", "channelsplit", "silenceremove",
        "anull", "anullsink", "asplit", "adelay", "extrastereo", "stereotools", "crystalizer", "acopy",
        "deesser", "speechnorm", "aloop",
    )

    /**
     * Never allowed, whatever the allowlist says (defence in depth, and for clearer errors): filters
     * that read/write files, load code, or open command channels.
     */
    val FORBIDDEN_FILTERS: Set<String> = setOf(
        "movie", "amovie", "subtitles", "ass", "afir", "frei0r", "frei0r_src", "metadata", "ametadata",
        "psnr", "ssim", "vidstabdetect", "vidstabtransform", "sendcmd", "asendcmd", "lut3d", "haldclut",
        "sofalizer", "ladspa", "lv2", "zmq", "azmq", "signature", "ocr", "dnn_processing", "sr",
        "dnn_detect", "dnn_classify", "libvmaf", "coreimage", "coreimagesrc", "openclsrc", "program_opencl",
        "lensfun", "cover_rect", "find_rect", "lut1d", "aspectralstats", "abuffersink", "buffersink",
    )

    /** Option keys that name a file (or a file format) anywhere in FFmpeg's filter set. */
    val FORBIDDEN_OPTION_KEYS: Set<String> = setOf(
        "file", "filename", "textfile", "f", "result", "sofa", "psfile", "stats_file", "stats_log",
        "fontfile", "filename_prefix", "model", "log_path", "log_fmt", "commands", "impulse", "lut",
    )

    /**
     * Filters whose POSITIONAL options can reach a file option (e.g. `drawtext`'s 3rd positional is
     * `textfile`, `curves`' later positionals include `psfile`): for these, only named `key=value`
     * options are accepted, except `curves`' first positional (its preset).
     */
    private val NAMED_OPTIONS_ONLY: Map<String, Int> = mapOf("drawtext" to 0, "curves" to 1)

    /** FFmpeg's `xfade` transition names (the built-in set; `custom` needs expressions and is excluded). */
    val XFADE_TRANSITIONS: Set<String> = setOf(
        "fade", "wipeleft", "wiperight", "wipeup", "wipedown", "slideleft", "slideright", "slideup",
        "slidedown", "circlecrop", "rectcrop", "distance", "fadeblack", "fadewhite", "radial", "smoothleft",
        "smoothright", "smoothup", "smoothdown", "circleopen", "circleclose", "vertopen", "vertclose",
        "horzopen", "horzclose", "dissolve", "pixelize", "diagtl", "diagtr", "diagbl", "diagbr", "hlslice",
        "hrslice", "vuslice", "vdslice", "hblur", "fadegrays", "wipetl", "wipetr", "wipebl", "wipebr",
        "squeezeh", "squeezev", "zoomin", "fadefast", "fadeslow", "hlwind", "hrwind", "vuwind", "vdwind",
        "coverleft", "coverright", "coverup", "coverdown", "revealleft", "revealright", "revealup",
        "revealdown",
    )

    /** True when [name] is one of FFmpeg's built-in `xfade` transitions (exact, lowercase match). */
    fun isValidXfadeTransition(name: String): Boolean = name in XFADE_TRANSITIONS

    /** One parsed filter: its bare name (no `@instance`) and its options as (key or null, value). */
    data class ParsedFilter(val name: String, val options: List<Pair<String?, String>>)

    /** Null when [graph] is allowed; otherwise why it was refused. */
    fun check(graph: String): String? {
        if (graph.isBlank()) return "Empty filter graph."
        if (graph.length > MAX_GRAPH_CHARS) return "Filter graph is too long."
        val filters = try {
            parse(graph)
        } catch (e: IllegalArgumentException) {
            return "Couldn't parse the filter graph: ${e.message}"
        }
        if (filters.isEmpty()) return "Empty filter graph."
        for (f in filters) {
            if (f.name in FORBIDDEN_FILTERS) return "The \"${f.name}\" filter is not allowed (it can read or write files)."
            if (f.name !in ALLOWED_FILTERS) return "The \"${f.name}\" filter is not in the allowed set."
            val positionalLimit = NAMED_OPTIONS_ONLY[f.name]
            var positional = 0
            for ((key, _) in f.options) {
                if (key == null) {
                    positional++
                    if (positionalLimit != null && positional > positionalLimit) {
                        return "\"${f.name}\" options must be given as key=value."
                    }
                } else if (key.lowercase() in FORBIDDEN_OPTION_KEYS) {
                    return "The \"$key\" option of \"${f.name}\" is not allowed (it names a file)."
                }
            }
        }
        return null
    }

    fun isAllowed(graph: String): Boolean = check(graph) == null

    /** Throws [IllegalArgumentException] with the refusal reason when [graph] isn't allowed. */
    fun requireAllowed(graph: String) {
        check(graph)?.let { throw IllegalArgumentException(it) }
    }

    /** The bare filter names in [graph], in order (labels and `@instance` suffixes stripped). */
    fun filterNames(graph: String): List<String> = parse(graph).map { it.name }

    // ---- parsing ---------------------------------------------------------------

    private const val MAX_GRAPH_CHARS = 16_384
    private val NAME = Regex("^[a-z0-9_]+$")
    private val KEY = Regex("^[A-Za-z0-9_]+$")

    /** Parse [graph] into its filters. Throws [IllegalArgumentException] on malformed input. */
    fun parse(graph: String): List<ParsedFilter> =
        splitTopLevel(graph, setOf(';', ','))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::parseFilter)

    private fun parseFilter(segment: String): ParsedFilter {
        var s = stripLeadingLabels(segment.trim())
        s = stripTrailingLabels(s)
        if (s.isEmpty()) throw IllegalArgumentException("a filter is missing its name")
        val eq = indexOfTopLevel(s, '=')
        val rawName = (if (eq < 0) s else s.substring(0, eq)).trim()
        val name = rawName.substringBefore('@').lowercase()
        if (!NAME.matches(name)) throw IllegalArgumentException("bad filter name \"$rawName\"")
        val args = if (eq < 0) "" else s.substring(eq + 1)
        val options = if (args.isBlank()) emptyList() else splitTopLevel(args, setOf(':')).map { opt ->
            val e = indexOfTopLevel(opt, '=')
            if (e < 0) {
                null to opt
            } else {
                val key = opt.substring(0, e).trim()
                if (!KEY.matches(key)) throw IllegalArgumentException("bad option name \"$key\"")
                key to opt.substring(e + 1)
            }
        }
        return ParsedFilter(name, options)
    }

    private fun stripLeadingLabels(s: String): String {
        var t = s
        while (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) throw IllegalArgumentException("unterminated [label]")
            t = t.substring(end + 1).trimStart()
        }
        return t
    }

    private fun stripTrailingLabels(s: String): String {
        var t = s
        while (t.endsWith("]")) {
            val start = lastLabelStart(t)
            t = t.substring(0, start).trimEnd()
        }
        return t
    }

    /** Start of the trailing `[label]` in [s] (which ends with `]`), skipping quoted/escaped text. */
    private fun lastLabelStart(s: String): Int {
        var start = -1
        var i = 0
        var quoted = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && !quoted -> i++
                c == '\'' -> quoted = !quoted
                !quoted && c == '[' -> start = i
            }
            i++
        }
        if (start < 0 || quoted) throw IllegalArgumentException("unbalanced [label]")
        return start
    }

    /** Split on any of [seps] outside quotes, escapes and `[labels]`. */
    private fun splitTopLevel(s: String, seps: Set<Char>): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var bracket = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && !quoted -> {
                    cur.append(c)
                    if (i + 1 < s.length) cur.append(s[i + 1])
                    i++
                }
                c == '\'' -> { quoted = !quoted; cur.append(c) }
                !quoted && c == '[' -> { bracket = true; cur.append(c) }
                !quoted && c == ']' -> { bracket = false; cur.append(c) }
                !quoted && !bracket && c in seps -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        if (quoted) throw IllegalArgumentException("unterminated quote")
        if (bracket) throw IllegalArgumentException("unterminated [label]")
        out.add(cur.toString())
        return out
    }

    /** Index of the first [ch] in [s] outside quotes/escapes, or -1. */
    private fun indexOfTopLevel(s: String, ch: Char): Int {
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && !quoted -> i++
                c == '\'' -> quoted = !quoted
                !quoted && c == ch -> return i
            }
            i++
        }
        return -1
    }
}
