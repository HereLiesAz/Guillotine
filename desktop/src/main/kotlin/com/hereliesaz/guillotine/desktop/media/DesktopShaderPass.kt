package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.media.GlslShader
import com.hereliesaz.guillotine.media.GlslToSksl
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Renders a clip's on-clip GLSL/ISF shader ([com.hereliesaz.guillotine.model.ClipFilters.shaderPath])
 * on the desktop by translating it to SkSL ([GlslToSksl]) and running it through **Skia's runtime
 * effects on the CPU raster backend** — no OpenGL context, window, or display required, so it works in
 * headless export just as it does in the live preview. This closes the last desktop/Android render gap:
 * `apply_shader` is now visible on desktop, not only recorded.
 *
 * Everything is defensive: a shader that fails to parse, translate, or compile to SkSL — or that throws
 * while rendering — leaves the frame untouched (returns the input image). A shader is compiled once and
 * the [RuntimeEffect] cached by path, so the SkSL compile cost isn't paid per frame.
 */
object DesktopShaderPass {

    private class Compiled(val effect: RuntimeEffect, val uniforms: List<GlslShader.ShaderUniform>)

    // path -> compiled effect (Optional.empty caches a failed compile so we don't retry every frame).
    private val cache = ConcurrentHashMap<String, Optional<Compiled>>()

    /** True if [shaderPath] parses, translates, and compiles as SkSL — i.e. it will actually render. */
    fun canRender(shaderPath: String): Boolean =
        shaderPath.isNotBlank() && compiled(shaderPath) != null

    private fun compiled(path: String): Compiled? =
        cache.getOrPut(path) {
            Optional.ofNullable(
                runCatching {
                    val program = GlslShader.parse(java.io.File(path).readText())
                    val sksl = GlslToSksl.translate(program)
                    Compiled(RuntimeEffect.makeForShader(sksl), program.uniforms)
                }.getOrNull(),
            )
        }.orElse(null)

    /**
     * Apply the shader at [shaderPath] to [img], overriding scalar uniforms from [params] and feeding
     * `TIME` from [timeMs]. Returns a new image, or [img] unchanged on any failure.
     */
    fun apply(img: BufferedImage, shaderPath: String, params: Map<String, Float>, timeMs: Long): BufferedImage {
        if (shaderPath.isBlank()) return img
        val c = compiled(shaderPath) ?: return img
        return runCatching { render(img, c, params, timeMs) }.getOrDefault(img)
    }

    private fun render(img: BufferedImage, c: Compiled, params: Map<String, Float>, timeMs: Long): BufferedImage {
        val w = img.width
        val h = img.height
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        Image.makeFromEncoded(png).use { skImg ->
            skImg.makeShader().use { imgShader ->
                RuntimeShaderBuilder(c.effect).use { b ->
                    // Set uniforms defensively: a shader that doesn't reference RENDERSIZE/TIME/an input
                    // has it dead-code-eliminated, and setting a nonexistent uniform throws — swallow so
                    // one absent uniform doesn't sink the whole pass.
                    fun set(block: () -> Unit) { runCatching { block() } }
                    set { b.child("uTexSampler", imgShader) }
                    set { b.uniform("RENDERSIZE", w.toFloat(), h.toFloat()) }
                    set { b.uniform("TIME", timeMs / 1000f) }
                    for (u in c.uniforms) {
                        when (u.type) {
                            GlslShader.UniformType.FLOAT ->
                                set { b.uniform(u.name, params[u.name] ?: u.values.getOrElse(0) { 0f }) }
                            GlslShader.UniformType.INT, GlslShader.UniformType.BOOL ->
                                set { b.uniform(u.name, (params[u.name] ?: u.values.getOrElse(0) { 0f }).toInt()) }
                            GlslShader.UniformType.VEC2 ->
                                set { b.uniform(u.name, u.values[0], u.values[1]) }
                            GlslShader.UniformType.VEC3 ->
                                set { b.uniform(u.name, u.values[0], u.values[1], u.values[2]) }
                            GlslShader.UniformType.VEC4 ->
                                set { b.uniform(u.name, u.values[0], u.values[1], u.values[2], u.values[3]) }
                        }
                    }
                    b.makeShader(null).use { shader ->
                        Surface.makeRasterN32Premul(w, h).use { surface ->
                            Paint().use { paint ->
                                paint.shader = shader
                                surface.canvas.drawPaint(paint)
                            }
                            surface.makeImageSnapshot().use { snap ->
                                val data = snap.encodeToData() ?: return img
                                data.use { d ->
                                    return ImageIO.read(ByteArrayInputStream(d.bytes)) ?: img
                                }
                            }
                        }
                    }
                }
            }
        }
        return img
    }
}
