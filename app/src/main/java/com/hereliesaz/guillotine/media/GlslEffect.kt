@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.media

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.hereliesaz.guillotine.media.GlslShader.ShaderUniform
import com.hereliesaz.guillotine.media.GlslShader.UniformType

/**
 * A single-input custom GLSL color effect for Media3 — the engine behind on-device ISF / raw-fragment
 * shader filters. Runs the parsed [program]'s fragment shader over each frame in BOTH live preview
 * (`ExoPlayer.setVideoEffects`) and export (`Transformer`), so the look matches. Structure mirrors
 * Media3's own `PeriodicVignetteShaderProgram` demo (the canonical `BaseGlShaderProgram`). On-device.
 *
 * Two-input effects (gl-transitions) are out of scope — Media3's per-clip effect is 1-in/1-out; see
 * docs/ECOSYSTEM.md for the compositor path.
 */
class GlslEffect(private val program: GlslShader.Program) : GlEffect {
    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram =
        GlslShaderProgram(program, useHdr)
}

private const val VERTEX_SHADER =
    "#version 100\n" +
        "attribute vec4 aFramePosition;\n" +
        "varying vec2 vTexSamplingCoord;\n" +
        "void main() {\n" +
        "  gl_Position = aFramePosition;\n" +
        "  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);\n" +
        "}\n"

/** The 1-in/1-out shader program: compiles [program], sets its default uniforms, and draws a fullscreen quad. */
private class GlslShaderProgram(
    program: GlslShader.Program,
    useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val uniforms: List<ShaderUniform> = program.uniforms
    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, program.fragmentShader)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var width = 1
    private var height = 1

    init {
        // Fullscreen quad (4 verts, homogeneous coords), matching the copy vertex shader.
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        // Author-declared uniforms keep their default values across frames. A uniform the compiler
        // optimized away isn't active, so setting it throws — guard each so one dead uniform can't kill
        // the whole effect.
        uniforms.forEach { u -> runCatching { apply(u) } }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight) // 1:1 filter
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            // RENDERSIZE / TIME are injected by GlslShader; set them if the shader actually uses them.
            runCatching { glProgram.setFloatsUniform("RENDERSIZE", floatArrayOf(width.toFloat(), height.toFloat())) }
            runCatching { glProgram.setFloatUniform("TIME", presentationTimeUs / 1_000_000f) }
            glProgram.bindAttributesAndUniforms()
            // Output framebuffer is already bound & cleared by BaseGlShaderProgram.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun apply(u: ShaderUniform) = when (u.type) {
        UniformType.FLOAT -> glProgram.setFloatUniform(u.name, u.values[0])
        UniformType.INT, UniformType.BOOL -> glProgram.setIntUniform(u.name, u.values[0].toInt())
        UniformType.VEC2, UniformType.VEC3, UniformType.VEC4 -> glProgram.setFloatsUniform(u.name, u.values)
    }

    override fun release() {
        super.release()
        runCatching { glProgram.delete() }
    }
}
