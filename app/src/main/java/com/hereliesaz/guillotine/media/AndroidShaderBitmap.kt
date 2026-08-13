package com.hereliesaz.guillotine.media

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Applies an on-clip GLSL shader to a still [Bitmap] — used only for the store's live
 * effect-preview thumbnails (see `AzpStorePreviewRenderer`), never the real per-clip render path,
 * which stays GL/Media3-video-based ([VideoEffects]/[GlslEffect]) since it needs a GL texture at
 * video framerate, not a one-off still. Reuses the exact GLSL→SkSL translation the desktop's Skia
 * CPU-raster shader pass uses ([GlslToSksl]) — Android's [RuntimeShader] accepts the same SkSL
 * dialect (AGSL), so no separate translator is needed.
 *
 * Requires API 33 ([RuntimeShader] shipped in Android 13) — below that this returns null. That's a
 * real platform ceiling, not a shortcut: there is no still-Bitmap shader-render path on Android
 * before `RuntimeShader` existed, and Guillotine's minSdk (26) predates it by years.
 */
object AndroidShaderBitmap {

    fun apply(bitmap: Bitmap, glslSource: String, params: Map<String, Float>): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching { render(bitmap, glslSource, params) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun render(bitmap: Bitmap, glslSource: String, params: Map<String, Float>): Bitmap {
        val program = GlslShader.parse(glslSource)
        val sksl = GlslToSksl.translate(program)
        val shader = RuntimeShader(sksl)
        val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        // Uniforms set defensively: a shader that doesn't reference one had it dead-code-eliminated,
        // and setting a nonexistent uniform throws — one absent uniform mustn't sink the whole render.
        fun set(block: () -> Unit) { runCatching(block) }
        set { shader.setInputShader("uTexSampler", bitmapShader) }
        set { shader.setFloatUniform("RENDERSIZE", bitmap.width.toFloat(), bitmap.height.toFloat()) }
        set { shader.setFloatUniform("TIME", 0f) }
        for (u in program.uniforms) {
            when (u.type) {
                GlslShader.UniformType.FLOAT ->
                    set { shader.setFloatUniform(u.name, params[u.name] ?: u.values.getOrElse(0) { 0f }) }
                GlslShader.UniformType.INT, GlslShader.UniformType.BOOL ->
                    set { shader.setIntUniform(u.name, (params[u.name] ?: u.values.getOrElse(0) { 0f }).toInt()) }
                GlslShader.UniformType.VEC2 ->
                    set { shader.setFloatUniform(u.name, u.values[0], u.values[1]) }
                GlslShader.UniformType.VEC3 ->
                    set { shader.setFloatUniform(u.name, u.values[0], u.values[1], u.values[2]) }
                GlslShader.UniformType.VEC4 ->
                    set { shader.setFloatUniform(u.name, u.values[0], u.values[1], u.values[2], u.values[3]) }
            }
        }

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { this.shader = shader }
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
        return out
    }
}
