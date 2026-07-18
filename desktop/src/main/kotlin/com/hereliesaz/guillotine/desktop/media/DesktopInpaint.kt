package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.awt.image.BufferedImage
import java.nio.FloatBuffer

/**
 * On-device generative object removal on the desktop: build a mask of the salient subject with the
 * segmentation model, then fill it with a **LaMa-style inpainting** `.onnx` model — no cloud call, in
 * contrast to Android's Leonardo-backed path (desktop keeps the on-device invariant). Removes the
 * segmented subject from a single frame and returns the inpainted image.
 *
 * The inpaint model is any two-input ONNX taking an image (`[1,3,H,W]`, `[0,1]`) and a mask
 * (`[1,1,H,W]`, 1 = region to fill) and returning `[1,3,H,W]`. Inputs are matched by channel count (and
 * by a "mask" name hint). Any failure returns null and the caller reports it honestly.
 */
object DesktopInpaint {

    /** Remove the segmented subject of [img] using [segModelPath] for the mask and [inpaintModelPath] to fill. */
    fun removeSubject(img: BufferedImage, segModelPath: String, inpaintModelPath: String): BufferedImage? = runCatching {
        val matte = DesktopSegmenter.matte(img, segModelPath) // ARGB: subject alpha
        val session = DesktopOnnx.session(inpaintModelPath)

        // Identify the image vs mask inputs by declared channel count / name.
        var imageName = session.inputNames.first()
        var maskName = session.inputNames.firstOrNull { it != imageName } ?: imageName
        var h = 512
        var w = 512
        for (name in session.inputNames) {
            val shape = runCatching { (session.inputInfo[name]?.info as? TensorInfo)?.shape }.getOrNull() ?: continue
            val c = shape.getOrNull(1) ?: -1
            if (name.lowercase().contains("mask") || c == 1L) maskName = name
            if (c == 3L) {
                imageName = name
                shape.getOrNull(2)?.toInt()?.takeIf { it >= 2 }?.let { h = it }
                shape.getOrNull(3)?.toInt()?.takeIf { it >= 2 }?.let { w = it }
            }
        }
        if (h < 2 || w < 2) { h = img.height.coerceAtMost(1024); w = img.width.coerceAtMost(1024) }

        val imgChw = imageChw(img, w, h)
        val maskChw = maskFromAlpha(matte, w, h)
        val env = DesktopOnnx.environment
        OnnxTensor.createTensor(env, FloatBuffer.wrap(imgChw), longArrayOf(1, 3, h.toLong(), w.toLong())).use { imgT ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(maskChw), longArrayOf(1, 1, h.toLong(), w.toLong())).use { maskT ->
                val inputs = if (maskName == imageName) mapOf(imageName to imgT) else mapOf(imageName to imgT, maskName to maskT)
                session.run(inputs).use { result ->
                    val out = result.iterator().asSequence().firstOrNull()?.value as? OnnxTensor ?: return null
                    val shape = (out.info as? TensorInfo)?.shape ?: return null
                    val fb = out.floatBuffer
                    val flat = FloatArray(fb.remaining()).also { fb.get(it) }
                    decodeRgb(flat, shape)
                }
            }
        }
    }.getOrNull()

    private fun imageChw(img: BufferedImage, w: Int, h: Int): FloatArray {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply { drawImage(img, 0, 0, w, h, null); dispose() }
        val out = FloatArray(3 * w * h)
        val plane = w * h
        val pixels = IntArray(plane)
        scaled.getRGB(0, 0, w, h, pixels, 0, w)
        for (i in 0 until plane) {
            val rgb = pixels[i]
            out[i] = ((rgb ushr 16) and 0xFF) / 255f
            out[plane + i] = ((rgb ushr 8) and 0xFF) / 255f
            out[2 * plane + i] = (rgb and 0xFF) / 255f
        }
        return out
    }

    /** Mask plane [1,1,H,W]: 1 where the subject alpha is high (the region to erase). */
    private fun maskFromAlpha(matte: BufferedImage, w: Int, h: Int): FloatArray {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply { drawImage(matte, 0, 0, w, h, null); dispose() }
        val out = FloatArray(w * h)
        val pixels = IntArray(w * h)
        scaled.getRGB(0, 0, w, h, pixels, 0, w)
        for (i in 0 until w * h) {
            val a = (pixels[i] ushr 24) and 0xFF
            out[i] = if (a > 128) 1f else 0f
        }
        return out
    }

    private fun decodeRgb(flat: FloatArray, shape: LongArray): BufferedImage? {
        val dims = shape.dropWhile { it == 1L && shape.size > 3 }.map { it.toInt() }
        val (c, h, w) = when (dims.size) {
            4 -> Triple(dims[1], dims[2], dims[3])
            3 -> Triple(dims[0], dims[1], dims[2])
            else -> return null
        }
        if (c != 3 || h < 2 || w < 2 || flat.size < 3 * h * w) return null
        var max = -Float.MAX_VALUE
        for (v in flat) if (v > max) max = v
        val scale = if (max > 2f) 1f else 255f // [0,255] already, else [0,1]
        val plane = h * w
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val idx = y * w + x
            val r = (flat[idx] * scale).toInt().coerceIn(0, 255)
            val gg = (flat[plane + idx] * scale).toInt().coerceIn(0, 255)
            val b = (flat[2 * plane + idx] * scale).toInt().coerceIn(0, 255)
            out.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (gg shl 8) or b)
        }
        return out
    }
}
