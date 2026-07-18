package com.hereliesaz.guillotine.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure-Kotlin GLSL-ES → SkSL translation. The Skia compile/render itself lives on desktop
 * (needs Skia natives) and isn't exercised here; these assertions pin the string-level transforms the
 * translator is responsible for.
 */
class GlslToSkslTest {

    private fun translate(glsl: String) = GlslToSksl.translate(GlslShader.parse(glsl))

    private val rawInvert = """
        void main() {
            vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
            gl_FragColor = vec4(1.0 - c.rgb, c.a);
        }
    """.trimIndent()

    @Test fun raw_fragment_translates_entry_point_and_types() {
        val sksl = translate(rawInvert)
        // SkSL entry point + return, not GLSL's void main / gl_FragColor.
        assertTrue("has SkSL main", sksl.contains("half4 main(float2 fragCoord)"))
        assertTrue("returns colour", sksl.contains("return half4(_fragColor);"))
        assertFalse("no gl_FragColor left", sksl.contains("gl_FragColor"))
        assertFalse("no void main left", Regex("\\bvoid\\s+main\\b").containsMatchIn(sksl))
    }

    @Test fun vec_types_become_float_types() {
        val sksl = translate(rawInvert)
        assertTrue("vec4 -> float4", sksl.contains("float4"))
        assertFalse("no vec4 keyword", Regex("\\bvec4\\b").containsMatchIn(sksl))
        assertFalse("no vec3 keyword", Regex("\\bvec3\\b").containsMatchIn(sksl))
    }

    @Test fun sampler_becomes_child_shader_eval() {
        val sksl = translate(rawInvert)
        assertTrue("sampler declared as child shader", sksl.contains("uniform shader uTexSampler;"))
        // texture2D(uTexSampler, uv) -> uTexSampler.eval(uv * RENDERSIZE)
        assertTrue("eval in pixel space", sksl.contains("uTexSampler.eval("))
        assertTrue("scales to pixels", sksl.contains("* RENDERSIZE"))
        assertFalse("no texture2D call left", sksl.contains("texture2D("))
    }

    @Test fun standard_uniforms_declared() {
        val sksl = translate(rawInvert)
        assertTrue(sksl.contains("uniform float2 RENDERSIZE;"))
        assertTrue(sksl.contains("uniform float TIME;"))
    }

    @Test fun isf_scalar_input_becomes_float_uniform() {
        val isf = """
            /*{
              "ISFVSN": "2.0",
              "INPUTS": [
                { "NAME": "inputImage", "TYPE": "image" },
                { "NAME": "amount", "TYPE": "float", "DEFAULT": 0.5, "MIN": 0.0, "MAX": 1.0 }
              ]
            }*/
            void main() {
                vec4 c = IMG_THIS_PIXEL(inputImage);
                gl_FragColor = vec4(c.rgb * amount, c.a);
            }
        """.trimIndent()
        val sksl = translate(isf)
        assertTrue("scalar input carried as float uniform", sksl.contains("uniform float amount;"))
        assertTrue("ISF sampler rewritten", sksl.contains("uTexSampler.eval("))
        assertFalse("no ISF builtin left", sksl.contains("IMG_THIS_PIXEL"))
        assertFalse("no vec4 keyword", Regex("\\bvec4\\b").containsMatchIn(sksl))
    }

    @Test fun nested_parens_in_sample_coord_are_balanced() {
        val glsl = """
            void main() {
                gl_FragColor = texture2D(uTexSampler, clamp(vTexSamplingCoord, vec2(0.0), vec2(1.0)));
            }
        """.trimIndent()
        val sksl = translate(glsl)
        // The whole clamp(...) expression must survive as the eval coordinate.
        assertTrue("coord expr preserved", sksl.contains("uTexSampler.eval((clamp(vTexSamplingCoord, float2(0.0), float2(1.0))) * RENDERSIZE)"))
    }
}
