package com.hereliesaz.guillotine.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * A registry of named, one-call video effects. Each entry is a plain-English editing command backed by a
 * standard FFmpeg `-vf` filtergraph, so the AI (and any external MCP client) can invoke e.g. `sharpen` or
 * `film_grain` directly instead of hand-authoring a graph through `apply_ffmpeg_filter`.
 *
 * Both platforms share this catalog: each emits [toolDefinitions] alongside its hand-written tools and,
 * for a matching call, routes [graphFor] through its own `apply_ffmpeg_filter` bake. Every graph here is
 * single-input and **duration-preserving** — audio is copied through the bake unchanged, so an effect must
 * never change the clip's length (no `setpts`/`trim`/`reverse`). Filters are kept to standard, widely
 * available ones; a graph that a particular ffmpeg build lacks simply surfaces its error to the user.
 */
object VideoFilterCatalog {

    /** A tunable parameter of an effect. All params are optional; [default] is used when omitted. */
    data class Param(val name: String, val desc: String, val default: Double, val integer: Boolean = false)

    data class Spec(
        val name: String,
        val description: String,
        val params: List<Param>,
        val summary: String,
        val build: (JSONObject) -> String,
    )

    private fun JSONObject.d(p: Param): Double = if (has(p.name)) optDouble(p.name, p.default) else p.default
    private fun JSONObject.i(p: Param): Int = if (has(p.name)) optInt(p.name, p.default.toInt()) else p.default.toInt()

    // ---- reusable parameters -------------------------------------------------
    private val pFrames = Param("frames", "How many frames to blend — more = longer trail.", 3.0, integer = true)
    private val pSharpen = Param("amount", "Sharpening strength (higher = crisper).", 1.5)
    private val pLens = Param("amount", "Correction strength; higher fixes stronger barrel/fisheye bulge.", 0.2)
    private val pGrain = Param("strength", "Grain intensity, 0..100.", 20.0, integer = true)
    private val pVhs = Param("strength", "Channel-shift/noise intensity in px.", 5.0, integer = true)
    private val pCa = Param("amount", "Colour-channel shift in px.", 4.0, integer = true)
    private val pPixel = Param("size", "Pixel block size in px (bigger = blockier).", 16.0, integer = true)
    private val pGlow = Param("strength", "Glow opacity, 0..1.", 0.5)
    private val pGrid = Param("size", "Grid cell size in px.", 64.0, integer = true)

    val specs: List<Spec> = listOf(
        Spec(
            "motion_trail",
            "Blend consecutive frames into a motion trail / echo / ghosting smear ('motion trail', 'echo', " +
                "'ghosting', 'smear the movement'). Same length, just trailing motion.",
            listOf(pFrames), "Added a motion-trail (frame-blend) effect as a new clip.",
        ) { "tmix=frames=${it.i(pFrames).coerceIn(2, 128)}" },
        Spec(
            "sharpen",
            "Sharpen a soft or slightly out-of-focus clip ('sharpen', 'make it crisp/sharper', 'add detail').",
            listOf(pSharpen), "Sharpened the clip and added the result as a new clip.",
        ) { "unsharp=la=${it.d(pSharpen).coerceIn(0.0, 5.0)}" },
        Spec(
            "denoise_video",
            "Reduce video noise/grain (temporal + spatial) for cleaner footage ('denoise the video', " +
                "'clean up the grain/noise', 'reduce video noise'). Note: this is the PICTURE — use denoise_clip " +
                "for audio.",
            emptyList(), "Denoised the video and added the result as a new clip.",
        ) { "hqdn3d" },
        Spec(
            "deband",
            "Remove colour banding in gradients/skies ('fix the banding', 'smooth the gradient', 'deband').",
            emptyList(), "Removed colour banding and added the result as a new clip.",
        ) { "deband" },
        Spec(
            "deflicker",
            "Remove flicker / brightness pulsing between frames ('remove the flicker', 'stop the pulsing', " +
                "'deflicker').",
            emptyList(), "Removed flicker and added the result as a new clip.",
        ) { "deflicker" },
        Spec(
            "stabilize",
            "Stabilise shaky handheld footage in one pass ('stabilise', 'fix the shaky camera', 'smooth the " +
                "shake', 'steady the footage').",
            emptyList(), "Stabilised the footage and added the result as a new clip.",
        ) { "deshake" },
        Spec(
            "lens_correction",
            "Correct barrel/fisheye lens distortion ('fix the fisheye', 'remove lens distortion', 'defish', " +
                "'straighten the bulge').",
            listOf(pLens), "Corrected lens distortion and added the result as a new clip.",
        ) { val k = it.d(pLens).coerceIn(-1.0, 1.0); "lenscorrection=cx=0.5:cy=0.5:k1=${-k}:k2=${-k / 4}" },
        Spec(
            "mirror",
            "Mirror the clip horizontally, left-right ('mirror it', 'flip horizontally').",
            emptyList(), "Mirrored the clip horizontally and added the result as a new clip.",
        ) { "hflip" },
        Spec(
            "flip_vertical",
            "Flip the clip vertically, upside-down ('flip vertically', 'turn it upside down').",
            emptyList(), "Flipped the clip vertically and added the result as a new clip.",
        ) { "vflip" },
        Spec(
            "rotate_180",
            "Rotate the clip 180° ('rotate 180', 'turn it around').",
            emptyList(), "Rotated the clip 180° and added the result as a new clip.",
        ) { "hflip,vflip" },
        Spec(
            "film_grain",
            "Add film grain / analog noise ('film grain', 'grainy look', 'add noise', 'analog texture').",
            listOf(pGrain), "Added film grain and added the result as a new clip.",
        ) { "noise=alls=${it.i(pGrain).coerceIn(0, 100)}:allf=t+u" },
        Spec(
            "vignette",
            "Darken the edges with a soft vignette ('add a vignette', 'darken the corners/edges').",
            emptyList(), "Added a vignette and added the result as a new clip.",
        ) { "vignette" },
        Spec(
            "vhs",
            "Retro VHS / analog-tape look: colour-channel shift plus noise ('VHS', 'old tape look', " +
                "'analog glitch', '80s camcorder').",
            listOf(pVhs), "Applied a VHS look and added the result as a new clip.",
        ) { val s = it.i(pVhs).coerceIn(0, 100); "rgbashift=rh=$s:bh=${-s},noise=alls=12:allf=t" },
        Spec(
            "chromatic_aberration",
            "Split the colour channels for a chromatic-aberration / RGB fringe ('chroma shift', 'colour " +
                "fringing', 'chromatic aberration', 'glitch fringe').",
            listOf(pCa), "Added chromatic aberration and added the result as a new clip.",
        ) { val a = it.i(pCa).coerceIn(0, 100); "rgbashift=rh=$a:bh=${-a}" },
        Spec(
            "glow",
            "Dreamy soft glow / bloom over the highlights ('glow', 'bloom', 'dreamy soft look', 'ethereal').",
            listOf(pGlow), "Added a soft glow and added the result as a new clip.",
        ) { "split=2[a][b];[b]gblur=sigma=10[bl];[a][bl]blend=all_mode=screen:all_opacity=${it.d(pGlow).coerceIn(0.0, 1.0)}" },
        Spec(
            "old_film",
            "Aged-film look: vintage curve + grain + vignette ('old film', 'vintage movie', 'aged footage', " +
                "'silent-era look').",
            emptyList(), "Applied an old-film look and added the result as a new clip.",
        ) { "curves=preset=vintage,noise=alls=15:allf=t,vignette" },
        Spec(
            "edge_detect",
            "Outline the edges for a line-art / sketch look ('edge detect', 'outline', 'sketch effect', " +
                "'pencil lines').",
            emptyList(), "Applied edge detection and added the result as a new clip.",
        ) { "edgedetect" },
        Spec(
            "pixelate",
            "Pixelate / mosaic the whole frame ('pixelate', 'mosaic', '8-bit blocky look', 'censor-blocks').",
            listOf(pPixel), "Pixelated the clip and added the result as a new clip.",
            // Downscale-then-upscale with nearest-neighbour: works on every ffmpeg build (unlike the newer
            // `pixelize` filter). The second scale multiplies the already-downscaled iw/ih back up by n.
        ) { val n = it.i(pPixel).coerceIn(2, 256); "scale=iw/$n:ih/$n:flags=neighbor,scale=iw*$n:ih*$n:flags=neighbor" },
        Spec(
            "warm",
            "Warm the colour up, toward orange/gold ('warmer', 'warm tone', 'golden hour look').",
            emptyList(), "Warmed the colour and added the result as a new clip.",
        ) { "colorbalance=rm=0.12:bm=-0.12" },
        Spec(
            "cool",
            "Cool the colour down, toward blue ('cooler', 'cold/blue tone', 'icy look').",
            emptyList(), "Cooled the colour and added the result as a new clip.",
        ) { "colorbalance=rm=-0.12:bm=0.12" },
        Spec(
            "cinematic",
            "Teal-and-orange cinematic grade ('cinematic', 'teal and orange', 'blockbuster movie look').",
            emptyList(), "Applied a teal-and-orange cinematic grade and added the result as a new clip.",
        ) { "colorbalance=rs=-0.08:bs=0.08:rm=0.05:rh=0.08:bh=-0.05" },
        Spec(
            "increase_contrast",
            "Boost contrast with a punchy S-curve ('more contrast', 'punchier', 'stronger contrast').",
            emptyList(), "Increased contrast and added the result as a new clip.",
        ) { "curves=preset=increase_contrast" },
        Spec(
            "vintage",
            "Faded vintage colour curve ('vintage colours', 'faded/retro grade').",
            emptyList(), "Applied a vintage colour curve and added the result as a new clip.",
        ) { "curves=preset=vintage" },
        Spec(
            "cross_process",
            "Cross-process colour look with skewed cyan/green shadows ('cross process', 'lomo look').",
            emptyList(), "Applied a cross-process look and added the result as a new clip.",
        ) { "curves=preset=cross_process" },
        Spec(
            "darker",
            "Darken the overall exposure ('darker', 'lower the exposure', 'underexpose').",
            emptyList(), "Darkened the clip and added the result as a new clip.",
        ) { "curves=preset=darker" },
        Spec(
            "brighter",
            "Brighten the overall exposure ('brighter', 'lift the exposure', 'lighten it up').",
            emptyList(), "Brightened the clip and added the result as a new clip.",
        ) { "curves=preset=lighter" },
        Spec(
            "noir",
            "High-contrast black-and-white film-noir look ('noir', 'high-contrast black and white', " +
                "'dramatic monochrome').",
            emptyList(), "Applied a film-noir look and added the result as a new clip.",
        ) { "hue=s=0,curves=preset=strong_contrast" },
        Spec(
            "night_vision",
            "Green night-vision goggles look with noise ('night vision', 'green goggles', 'infrared green').",
            emptyList(), "Applied a night-vision look and added the result as a new clip.",
        ) { "lutrgb=r=0:b=0,noise=alls=10:allf=t" },
        Spec(
            "thermal",
            "False-colour thermal / infrared-camera look ('thermal camera', 'heat map', 'predator vision', " +
                "'infrared look').",
            emptyList(), "Applied a thermal false-colour look and added the result as a new clip.",
        ) { "format=gray,pseudocolor=preset=inferno" },
        Spec(
            "grid_overlay",
            "Overlay an alignment / rule-of-thirds grid ('add a grid', 'grid overlay', 'guides').",
            listOf(pGrid), "Overlaid a grid and added the result as a new clip.",
        ) { val n = it.i(pGrid).coerceIn(2, 4096); "drawgrid=width=$n:height=$n:thickness=1:color=white@0.4" },
    )

    val names: Set<String> by lazy { specs.mapTo(HashSet()) { it.name } }
    private val byName: Map<String, Spec> by lazy { specs.associateBy { it.name } }

    /** JSON-RPC tool definitions for every effect, ready to append to a surface's `tools/list`. */
    fun toolDefinitions(): JSONArray = JSONArray().apply {
        for (s in specs) {
            val properties = JSONObject()
            properties.put("clip_id", stringProp("The clip to apply the effect to."))
            for (p in s.params) {
                properties.put(p.name, if (p.integer) intProp(p.desc) else numberProp(p.desc))
            }
            val schema = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray().put("clip_id"))
            put(toolDefinition(s.name, s.description, schema))
        }
    }

    /** The FFmpeg `-vf` graph for [name], resolving [args] against the effect's parameter defaults. */
    fun graphFor(name: String, args: JSONObject): String =
        (byName[name] ?: throw IllegalArgumentException("Unknown video filter: $name")).build(args)

    /** A human-readable summary for [name]'s result (shown in the activity log). */
    fun summaryFor(name: String): String =
        byName[name]?.summary ?: "Applied a video effect and added the result as a new clip."
}
