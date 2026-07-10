package com.hereliesaz.guillotine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the ISF / raw-fragment shader parser (the pure-Kotlin half of the GLSL effect path). */
class GlslShaderTest {

    private val simpleIsf = """
        /*{
          "ISFVSN": "2.0",
          "DESCRIPTION": "Saturation",
          "INPUTS": [
            { "NAME": "inputImage", "TYPE": "image" },
            { "NAME": "saturation", "TYPE": "float", "DEFAULT": 1.5, "MIN": 0.0, "MAX": 2.0 }
          ]
        }*/
        void main() {
          vec4 c = IMG_THIS_PIXEL(inputImage);
          float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
          gl_FragColor = vec4(mix(vec3(luma), c.rgb, saturation), c.a);
        }
    """.trimIndent()

    @Test fun parses_isf_header_and_extracts_float_input() {
        val p = GlslShader.parse(simpleIsf)
        val sat = p.uniforms.single { it.name == "saturation" }
        assertEquals(GlslShader.UniformType.FLOAT, sat.type)
        assertEquals(1.5f, sat.values[0], 1e-6f)
        // Declared in the assembled shader.
        assertTrue(p.fragmentShader.contains("uniform float saturation;"))
    }

    @Test fun rewrites_isf_image_sampler_to_media3_convention() {
        val p = GlslShader.parse(simpleIsf)
        // ISF's IMG_THIS_PIXEL(inputImage) becomes a Media3 texture2D(uTexSampler, vTexSamplingCoord).
        assertTrue(p.fragmentShader.contains("texture2D(uTexSampler, vTexSamplingCoord)"))
        assertFalse(p.fragmentShader.contains("IMG_THIS_PIXEL"))
        // The image input is NOT a settable uniform.
        assertTrue(p.uniforms.none { it.name == "inputImage" })
    }

    @Test fun maps_isf_input_types() {
        val isf = """
            /*{
              "INPUTS": [
                { "NAME": "img", "TYPE": "image" },
                { "NAME": "flag", "TYPE": "bool", "DEFAULT": true },
                { "NAME": "count", "TYPE": "long", "DEFAULT": 3 },
                { "NAME": "tint", "TYPE": "color", "DEFAULT": [0.1, 0.2, 0.3, 1.0] },
                { "NAME": "center", "TYPE": "point2D", "DEFAULT": [0.5, 0.5] }
              ]
            }*/
            void main() { gl_FragColor = IMG_THIS_PIXEL(img); }
        """.trimIndent()
        val p = GlslShader.parse(isf)
        assertEquals(GlslShader.UniformType.BOOL, p.uniforms.single { it.name == "flag" }.type)
        assertEquals(GlslShader.UniformType.INT, p.uniforms.single { it.name == "count" }.type)
        assertEquals(GlslShader.UniformType.VEC4, p.uniforms.single { it.name == "tint" }.type)
        assertEquals(GlslShader.UniformType.VEC2, p.uniforms.single { it.name == "center" }.type)
        assertEquals(0.2f, p.uniforms.single { it.name == "tint" }.values[1], 1e-6f)
    }

    @Test fun rejects_multipass_and_audio() {
        val multipass = """
            /*{ "PASSES": [ {"TARGET":"a"}, {"TARGET":"b"} ] }*/
            void main() { gl_FragColor = vec4(0.0); }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { GlslShader.parse(multipass) }

        val audio = """
            /*{ "INPUTS": [ {"NAME":"a","TYPE":"audio"} ] }*/
            void main() { gl_FragColor = vec4(0.0); }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { GlslShader.parse(audio) }
    }

    @Test fun raw_fragment_gets_media3_header_prepended() {
        val raw = "void main() { gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord); }"
        val p = GlslShader.parse(raw)
        assertTrue(p.fragmentShader.contains("uniform sampler2D uTexSampler;"))
        assertTrue(p.uniforms.isEmpty())
    }

    @Test fun strips_conflicting_version_when_header_is_prepended() {
        // No uTexSampler → we prepend the Media3 header, so any body #version must be stripped to avoid
        // a duplicate-directive compile error.
        val raw = "#version 300 es\nvoid main() { gl_FragColor = vec4(1.0); }"
        val p = GlslShader.parse(raw)
        assertEquals(1, Regex("#version").findAll(p.fragmentShader).count())
        assertTrue(p.fragmentShader.trimStart().startsWith("#version 100"))
    }
}
