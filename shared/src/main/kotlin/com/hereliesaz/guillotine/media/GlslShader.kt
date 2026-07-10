package com.hereliesaz.guillotine.media

import org.json.JSONObject

/**
 * Parses on-device GLSL shader effects into a Media3-ready fragment shader + uniform list, so a clip
 * can carry a custom GL color effect that renders identically in preview and export. Two input formats:
 *
 *  - **ISF** ([Interactive Shader Format](https://isf.video)) — the JSON-in-a-leading-block-comment header
 *    convention used by VDMX/Resolume. Only the **single-pass, single-image** filter subset is supported; multi-pass,
 *    persistent/feedback buffers, and audio/extra-image inputs are rejected with a clear message (Media3's
 *    per-clip effect is 1-in/1-out). ISF built-ins (`IMG_THIS_PIXEL`, `IMG_NORM_PIXEL`, `isf_FragNormCoord`,
 *    `RENDERSIZE`, `TIME`) are rewritten to Media3's conventions.
 *  - **Raw fragment** — a plain GLSL fragment `main()` that already samples `uTexSampler` at
 *    `vTexSamplingCoord` (for authors who target Guillotine/Media3 directly).
 *
 * Pure Kotlin (no Android), shared by `:app` and `:desktop`. On-device only.
 *
 * Note: true two-input **gl-transitions** (which sample both a "from" and "to" frame) are NOT expressible
 * as a single-input effect — see docs/ECOSYSTEM.md for the compositor path.
 */
object GlslShader {

    enum class UniformType { FLOAT, VEC2, VEC3, VEC4, INT, BOOL }

    /** One shader uniform with its default value (floats; INT/BOOL use [values]`[0]` as 0/1/int) plus,
     *  for scalar inputs, the ISF `MIN`/`MAX` range (for adjustment UI / agent context). */
    data class ShaderUniform(
        val name: String,
        val type: UniformType,
        val values: FloatArray,
        val min: Float = 0f,
        val max: Float = 1f,
    ) {
        override fun equals(other: Any?) =
            other is ShaderUniform && name == other.name && type == other.type && values.contentEquals(other.values)
        override fun hashCode() = 31 * (31 * name.hashCode() + type.hashCode()) + values.contentHashCode()
    }

    /** A Media3-ready fragment shader (samples `uTexSampler`/`vTexSamplingCoord`) + its [uniforms]. */
    data class Program(val fragmentShader: String, val uniforms: List<ShaderUniform>)

    /** Media3 conventions the generated shader targets (see BaseGlShaderProgram / GlProgram). */
    private const val HEADER =
        "#version 100\n" +
            "precision highp float;\n" +
            "uniform sampler2D uTexSampler;\n" +
            "varying vec2 vTexSamplingCoord;\n" +
            "uniform vec2 RENDERSIZE;\n" +
            "uniform float TIME;\n"

    /** Parse either ISF (has a JSON block-comment header) or a raw fragment shader. */
    fun parse(text: String): Program =
        if (isIsf(text)) parseIsf(text) else parseRaw(text)

    private fun isIsf(text: String): Boolean {
        val i = text.indexOf("/*")
        if (i < 0) return false
        // ISF headers open with `/*{` (optionally whitespace) — a JSON object.
        val after = text.substring(i + 2).trimStart()
        return after.startsWith("{")
    }

    // ---- ISF -----------------------------------------------------------------

    fun parseIsf(text: String): Program {
        val start = text.indexOf("/*")
        val end = text.indexOf("*/", start + 2)
        require(start >= 0 && end > start) { "Not an ISF shader (missing /*{ … }*/ header)." }
        val header = JSONObject(text.substring(start + 2, end).trim())

        header.optJSONArray("PASSES")?.let {
            require(it.length() <= 1) { "Multi-pass ISF shaders aren't supported (needs ${it.length()} passes)." }
        }
        require(!header.has("IMPORTED") || header.getJSONObject("IMPORTED").length() == 0) {
            "ISF shaders with imported images aren't supported."
        }

        val uniforms = ArrayList<ShaderUniform>()
        val decls = StringBuilder()
        var imageInput = "inputImage"
        var imageCount = 0

        header.optJSONArray("INPUTS")?.let { inputs ->
            for (i in 0 until inputs.length()) {
                val inp = inputs.getJSONObject(i)
                val name = inp.getString("NAME")
                when (inp.optString("TYPE")) {
                    "image" -> { imageCount++; imageInput = name }
                    "audio", "audioFFT" -> throw IllegalArgumentException("Audio ISF inputs aren't supported.")
                    "float", "long" -> {
                        val isInt = inp.optString("TYPE") == "long"
                        val d = inp.optDouble("DEFAULT", 0.0).toFloat()
                        val min = inp.optDouble("MIN", 0.0).toFloat()
                        val max = inp.optDouble("MAX", 1.0).toFloat()
                        uniforms += ShaderUniform(
                            name, if (isInt) UniformType.INT else UniformType.FLOAT, floatArrayOf(d), min, max,
                        )
                        decls.append(if (isInt) "uniform int $name;\n" else "uniform float $name;\n")
                    }
                    "bool", "event" -> {
                        val d = if (inp.optBoolean("DEFAULT", false)) 1f else 0f
                        uniforms += ShaderUniform(name, UniformType.BOOL, floatArrayOf(d))
                        decls.append("uniform bool $name;\n")
                    }
                    "color" -> {
                        uniforms += ShaderUniform(name, UniformType.VEC4, readVec(inp.opt("DEFAULT"), 4, 1f))
                        decls.append("uniform vec4 $name;\n")
                    }
                    "point2D" -> {
                        uniforms += ShaderUniform(name, UniformType.VEC2, readVec(inp.opt("DEFAULT"), 2, 0f))
                        decls.append("uniform vec2 $name;\n")
                    }
                    else -> Unit // unknown input type — ignore (shader may not reference it)
                }
            }
        }
        require(imageCount <= 1) { "Multi-image ISF shaders aren't supported (needs $imageCount images)." }

        val body = rewriteIsfBuiltins(stripVersion(text.substring(end + 2)), imageInput)
        return Program(HEADER + decls + body, uniforms)
    }

    /** Drop any `#version …` directive — we prepend our own `#version 100` header. */
    private fun stripVersion(src: String): String =
        src.lineSequence().filterNot { it.trimStart().startsWith("#version") }.joinToString("\n")

    /** Rewrite ISF built-in image samplers / coords to Media3's `uTexSampler` + `vTexSamplingCoord`. */
    private fun rewriteIsfBuiltins(body: String, image: String): String {
        var s = body
        // Image sampling — order matters (THIS_ before generic).
        s = s.replace(Regex("IMG_THIS_PIXEL\\s*\\(\\s*$image\\s*\\)"), "texture2D(uTexSampler, vTexSamplingCoord)")
        s = s.replace(Regex("IMG_THIS_NORM_PIXEL\\s*\\(\\s*$image\\s*\\)"), "texture2D(uTexSampler, vTexSamplingCoord)")
        s = s.replace(Regex("IMG_NORM_PIXEL\\s*\\(\\s*$image\\s*,"), "texture2D(uTexSampler,")
        // IMG_PIXEL takes PIXEL coords → normalize by /RENDERSIZE. Only the common `gl_FragCoord.xy`
        // argument is rewritten (as one balanced expression); other pixel-coord forms are left for the
        // author to write in normalized space (a partial regex rewrite would unbalance parentheses).
        s = s.replace(
            Regex("IMG_PIXEL\\s*\\(\\s*$image\\s*,\\s*gl_FragCoord\\.xy\\s*\\)"),
            "texture2D(uTexSampler, gl_FragCoord.xy / RENDERSIZE)",
        )
        s = s.replace(Regex("IMG_SIZE\\s*\\(\\s*$image\\s*\\)"), "RENDERSIZE")
        s = s.replace("isf_FragNormCoord", "vTexSamplingCoord")
        return s
    }

    // ---- raw fragment --------------------------------------------------------

    private fun parseRaw(text: String): Program {
        // A raw fragment is expected to be a `main()` sampling `uTexSampler`/`vTexSamplingCoord`. Always
        // prepend our header, stripping any lines that would redeclare its symbols (or a conflicting
        // #version/precision) so the result compiles whether the author included a preamble or not.
        val body = text.lineSequence().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("#version") ||
                t.startsWith("precision ") ||
                HEADER_DECLS.any { it.containsMatchIn(t) }
        }.joinToString("\n")
        return Program(HEADER + body, emptyList())
    }

    private val HEADER_DECLS = listOf(
        Regex("uniform\\s+sampler2D\\s+uTexSampler"),
        Regex("varying\\s+vec2\\s+vTexSamplingCoord"),
        Regex("uniform\\s+vec2\\s+RENDERSIZE"),
        Regex("uniform\\s+float\\s+TIME"),
    )

    private fun readVec(default: Any?, size: Int, fill: Float): FloatArray {
        val out = FloatArray(size) { fill }
        if (default is org.json.JSONArray) {
            for (i in 0 until minOf(size, default.length())) out[i] = default.optDouble(i, out[i].toDouble()).toFloat()
        }
        return out
    }
}
