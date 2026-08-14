package com.hereliesaz.guillotine.media

/**
 * Best-effort translator from the GLSL-ES fragment shaders [GlslShader] emits (Media3 conventions:
 * `uTexSampler` / `vTexSamplingCoord` / `gl_FragColor` / `RENDERSIZE` / `TIME`) into **SkSL**, the
 * shading language Skia's [runtime effects](https://skia.org/docs/user/sksl/) accept. This lets the
 * desktop render the same on-clip shaders through Skia's CPU raster backend — no GL context needed —
 * so `apply_shader` is visible in the desktop preview and export, not just on Android.
 *
 * Pure Kotlin (no Skia/Android types) so the string translation is unit-testable on the JVM. It is a
 * *best-effort* transform of the common single-image filter subset: the desktop shader pass compiles
 * the result and, on any SkSL compile failure, falls back to leaving the frame untouched — so a shader
 * this translator can't fully handle simply no-ops rather than corrupting the picture.
 *
 * The main differences it bridges:
 *  - types: `vec2/3/4` → `float2/3/4`, `mat2/3/4` → `float2x2/3x3/4x4`, `ivec*`/`bvec*` likewise.
 *  - image input: `uniform sampler2D uTexSampler` → `uniform shader uTexSampler`; `texture2D(uTexSampler, uv)`
 *    (uv normalized) → `uTexSampler.eval(uv * RENDERSIZE)` (Skia's `eval` samples in pixels).
 *  - entry point: `void main()` writing `gl_FragColor` → `half4 main(float2 fragCoord)` returning the colour.
 *  - `gl_FragCoord.xy` → `fragCoord`; `vTexSamplingCoord` is re-derived from `fragCoord / RENDERSIZE`.
 */
object GlslToSksl {

    /** Translate a parsed [GlslShader.Program] to a SkSL runtime-effect source string. */
    fun translate(program: GlslShader.Program): String {
        val uniforms = buildString {
            append("uniform shader uTexSampler;\n")
            append("uniform float2 RENDERSIZE;\n")
            append("uniform float TIME;\n")
            for (u in program.uniforms) append(uniformDecl(u))
        }
        // Drop every declaration line we regenerate above (and GLSL-only preamble); keep helper
        // functions + main so we translate just the executable code.
        val code = program.fragmentShader.lineSequence().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("#version") ||
                t.startsWith("precision ") ||
                t.startsWith("uniform ") ||
                t.startsWith("varying ")
        }.joinToString("\n")
        return uniforms + "\n" + translateBody(code)
    }

    private fun uniformDecl(u: GlslShader.ShaderUniform): String = when (u.type) {
        GlslShader.UniformType.FLOAT -> "uniform float ${u.name};\n"
        // SkSL runtime effects don't allow bool uniforms; carry booleans as int (0/1). Shaders that use
        // the value as a GLSL `bool` won't compile as SkSL and fall back to a no-op, which is fine.
        GlslShader.UniformType.INT, GlslShader.UniformType.BOOL -> "uniform int ${u.name};\n"
        GlslShader.UniformType.VEC2 -> "uniform float2 ${u.name};\n"
        GlslShader.UniformType.VEC3 -> "uniform float3 ${u.name};\n"
        GlslShader.UniformType.VEC4 -> "uniform float4 ${u.name};\n"
    }

    private val typeMap = listOf(
        "vec2" to "float2", "vec3" to "float3", "vec4" to "float4",
        "ivec2" to "int2", "ivec3" to "int3", "ivec4" to "int4",
        "bvec2" to "bool2", "bvec3" to "bool3", "bvec4" to "bool4",
        "mat2" to "float2x2", "mat3" to "float3x3", "mat4" to "float4x4",
    )

    private fun translateBody(src: String): String {
        var s = src
        // Rewrite image sampling first (before type/keyword edits touch the argument expression).
        s = rewriteTextureCalls(s)
        // Type keywords → SkSL. Longer names first (ivec2 before vec2) so we don't half-match.
        for ((glsl, sksl) in typeMap.sortedByDescending { it.first.length }) {
            s = s.replace(Regex("\\b$glsl\\b"), sksl)
        }
        // Built-in coord: gl_FragCoord.xy is the pixel coordinate == SkSL's fragCoord.
        s = s.replace(Regex("\\bgl_FragCoord\\s*\\.\\s*xy\\b"), "fragCoord")
        s = s.replace(Regex("\\bgl_FragCoord\\b"), "float4(fragCoord, 0.0, 1.0)")
        // Output colour: carry gl_FragColor as a local we return at the end of main (handles `.rgb`
        // partial writes too).
        s = s.replace(Regex("\\bgl_FragColor\\b"), "_fragColor")
        return rewriteMain(s)
    }

    /**
     * Replace `texture2D(uTexSampler, EXPR)` / `texture(uTexSampler, EXPR)` with
     * `uTexSampler.eval((EXPR) * RENDERSIZE)`, scanning a balanced argument so nested parens in EXPR
     * survive. Only calls whose first argument is `uTexSampler` are touched.
     */
    private fun rewriteTextureCalls(src: String): String {
        val out = StringBuilder()
        var i = 0
        val fn = Regex("\\btexture2D\\s*\\(|\\btexture\\s*\\(")
        while (i < src.length) {
            val m = fn.find(src, i) ?: run { out.append(src.substring(i)); return out.toString() }
            out.append(src, i, m.range.first)
            // Position just after the '('.
            var j = m.range.last + 1
            // First argument up to the top-level comma.
            val firstArgEnd = topLevelComma(src, j)
            if (firstArgEnd < 0) { out.append(src.substring(m.range.first)); return out.toString() }
            val firstArg = src.substring(j, firstArgEnd).trim()
            val closeParen = matchParen(src, m.range.last) // index of the ')' closing this call
            if (closeParen < 0) { out.append(src.substring(m.range.first)); return out.toString() }
            if (firstArg == "uTexSampler") {
                val coord = src.substring(firstArgEnd + 1, closeParen).trim()
                out.append("uTexSampler.eval((").append(coord).append(") * RENDERSIZE)")
            } else {
                // Not our sampler — leave the call verbatim.
                out.append(src, m.range.first, closeParen + 1)
            }
            i = closeParen + 1
        }
        return out.toString()
    }

    /** Index of the top-level comma at/after [from], or -1. [from] is just inside the '('. */
    private fun topLevelComma(s: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> { if (depth == 0) return -1; depth-- }
                ',' -> if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** Given [openParenPos-1] is a '(' opener (we pass the index of '('), return the matching ')' index. */
    private fun matchParen(s: String, openParenPos: Int): Int {
        // openParenPos is the index of '(' (the last char of the fn match).
        var depth = 0
        var i = openParenPos
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /**
     * Convert the GLSL entry point `void main() { … }` into SkSL's `half4 main(float2 fragCoord) { … }`,
     * injecting the `vTexSamplingCoord`/`_fragColor` locals at the top and `return half4(_fragColor);`
     * before the function's closing brace.
     */
    private fun rewriteMain(src: String): String {
        val sig = Regex("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{")
        val m = sig.find(src) ?: return src // no recognizable main → let the SkSL compile fail → no-op
        val braceOpen = m.range.last // index of '{'
        val braceClose = matchBrace(src, braceOpen)
        if (braceClose < 0) return src
        val prelude =
            "half4 main(float2 fragCoord) {\n" +
                "    float2 vTexSamplingCoord = fragCoord / RENDERSIZE;\n" +
                // Default alpha to opaque (1.0), matching how GLSL fragment shaders that only ever
                // write `gl_FragColor.rgb` are normally interpreted — the alpha channel is simply
                // left untouched, not zeroed. Initializing to float4(0.0) here made every such
                // shader (a common, legitimate pattern) render fully transparent with no compile
                // error and no visible failure.
                "    float4 _fragColor = float4(0.0, 0.0, 0.0, 1.0);\n"
        return buildString {
            append(src, 0, m.range.first)
            append(prelude)
            append(src, braceOpen + 1, braceClose)
            append("\n    return half4(_fragColor);\n}")
            append(src, braceClose + 1, src.length)
        }
    }

    /** Match the '{' at [openPos] to its '}'. Returns the '}' index, or -1. */
    private fun matchBrace(s: String, openPos: Int): Int {
        var depth = 0
        var i = openPos
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }
}
