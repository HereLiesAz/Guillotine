package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.awt.image.BufferedImage
import java.nio.FloatBuffer

/**
 * Runs a generic ON-DEVICE image model (`.onnx`) over a frame and returns the result as a
 * [BufferedImage] — the desktop equivalent of Android's TFLite `apply_image_effect`. Handles the common
 * single-image effects that ship as ONNX exports:
 *  - **superres** (Real-ESRGAN): 3-channel output larger than the input.
 *  - **style** / **lowlight** (fast-style-transfer, MIRNet): 3-channel output the same size.
 *  - **depth** (MiDaS): 1-channel output normalized to a grayscale map.
 *
 * The pipeline is deliberately model-agnostic: input is NCHW float in `[0,1]` (RGB planes), the input
 * spatial size follows the model when it's fixed and the frame otherwise, and the output is decoded
 * from whatever NCHW shape comes back (3-channel → colour, 1-channel → grayscale) with the value range
 * auto-detected (`[0,1]`, `[0,255]`, or `[-1,1]`). Anything it can't read returns null, and the caller
 * surfaces an honest error rather than a fake result.
 */
object DesktopImageEffect {

    /** Run [modelPath] on [img]; null if the model produced nothing usable. */
    fun run(img: BufferedImage, modelPath: String): BufferedImage? = runCatching {
        val session = DesktopOnnx.session(modelPath)
        val inInfo = runCatching { (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape }.getOrNull()
        // Use the model's fixed input size when it declares one (>= 2), else the frame's own size,
        // capped so an unbounded model can't blow up memory.
        val ih = inInfo?.getOrNull(2)?.toInt()?.takeIf { it >= 2 } ?: img.height.coerceAtMost(1024)
        val iw = inInfo?.getOrNull(3)?.toInt()?.takeIf { it >= 2 } ?: img.width.coerceAtMost(1024)
        val chw = preprocess(img, iw, ih)
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(DesktopOnnx.environment, FloatBuffer.wrap(chw), longArrayOf(1, 3, ih.toLong(), iw.toLong()))
            .use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result.iterator().asSequence().firstOrNull()?.value as? OnnxTensor ?: return null
                    val shape = (out.info as? TensorInfo)?.shape ?: return null
                    val fb = out.floatBuffer
                    val flat = FloatArray(fb.remaining()).also { fb.get(it) }
                    decode(flat, shape)
                }
            }
    }.getOrNull()

    /** NCHW float [0,1], RGB plane order (R plane, then G, then B). Mirrors DesktopSegmenter. */
    private fun preprocess(img: BufferedImage, w: Int, h: Int): FloatArray {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        val out = FloatArray(3 * w * h)
        val plane = w * h
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = scaled.getRGB(x, y)
                out[i] = ((rgb ushr 16) and 0xFF) / 255f
                out[plane + i] = ((rgb ushr 8) and 0xFF) / 255f
                out[2 * plane + i] = (rgb and 0xFF) / 255f
                i++
            }
        }
        return out
    }

    /**
     * Decode an NCHW output tensor to an image. Supports `[1,3,H,W]`/`[3,H,W]` (colour),
     * `[1,1,H,W]`/`[1,H,W]` (grayscale). Value range is auto-detected and normalized to bytes.
     */
    private fun decode(flat: FloatArray, shape: LongArray): BufferedImage? {
        // Collapse a leading batch dim of 1.
        val dims = shape.dropWhile { it == 1L && shape.size > 3 }.map { it.toInt() }
        val (c, h, w) = when (dims.size) {
            4 -> Triple(dims[1], dims[2], dims[3])
            3 -> Triple(dims[0], dims[1], dims[2])
            2 -> Triple(1, dims[0], dims[1]) // [H,W] single channel
            else -> return null
        }
        if (h < 2 || w < 2 || (c != 1 && c != 3)) return null
        if (flat.size < c * h * w) return null

        // Auto-detect value range.
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in flat) { if (v < min) min = v; if (v > max) max = v }
        val toByte: (Float) -> Int = when {
            max > 2f -> { v -> v.toInt().coerceIn(0, 255) }               // already [0,255]
            min < -0.01f -> { v -> (((v + 1f) * 0.5f) * 255f).toInt().coerceIn(0, 255) } // [-1,1]
            else -> { v -> (v * 255f).toInt().coerceIn(0, 255) }           // [0,1]
        }
        // For depth (single channel) rescale the actual min..max to the full range for a readable map.
        val depthScale = if (c == 1 && max > min) 255f / (max - min) else 0f

        val outImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val plane = h * w
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val argb = if (c == 3) {
                    val r = toByte(flat[idx])
                    val gg = toByte(flat[plane + idx])
                    val b = toByte(flat[2 * plane + idx])
                    (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
                } else {
                    val v = if (depthScale > 0f) ((flat[idx] - min) * depthScale).toInt().coerceIn(0, 255) else toByte(flat[idx])
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                outImg.setRGB(x, y, argb)
            }
        }
        return outImg
    }
}
