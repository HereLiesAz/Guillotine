package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer

/**
 * On-device face detection for the desktop (JVM) build via a user-supplied ONNX model — the desktop
 * analogue of Android's built-in ML Kit face detector. Targets the common **UltraFace / RFB** export
 * (Ultra-Light-Fast-Generic-Face-Detector): input `[1, 3, H, W]` with `(pixel - 127) / 128`
 * normalization (RGB), and TWO outputs — confidences `[1, N, 2]` and **already-decoded** boxes
 * `[1, N, 4]` in normalized `[0,1]` corner coords (the model bakes in the prior-box decoding, so no
 * anchor math is needed here). Used by `auto_reframe` to follow the main face. Pure JVM; nothing
 * leaves the machine.
 */
object DesktopFaceDetector {

    /** One detected face box in normalized `[0,1]` coords, with its confidence. */
    data class Face(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float) {
        val area: Float get() = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
        val centerX: Float get() = ((x1 + x2) / 2f)
    }

    /**
     * Normalized center-X (`0..1`) of the **largest** face in [image] using the ONNX detector at
     * [modelPath], or `null` when no detection clears [minScore]. Throws if the model can't be loaded.
     */
    fun largestFaceCenterX(modelPath: String, image: BufferedImage, minScore: Float = 0.6f): Float? {
        val face = detect(modelPath, image, minScore).maxByOrNull { it.area } ?: return null
        return face.centerX.coerceIn(0f, 1f)
    }

    /** All faces scoring ≥ [minScore]. Boxes are in normalized `[0,1]` corner coords. */
    fun detect(modelPath: String, image: BufferedImage, minScore: Float = 0.6f): List<Face> {
        val session = DesktopOnnx.session(modelPath)
        val inShape = runCatching {
            (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape
        }.getOrNull()
        val h = inShape?.getOrNull(2)?.toInt()?.takeIf { it >= 2 } ?: 240
        val w = inShape?.getOrNull(3)?.toInt()?.takeIf { it >= 2 } ?: 320
        val input = preprocess(image, w, h)
        val inputName = session.inputNames.first()

        OnnxTensor.createTensor(DesktopOnnx.environment, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong()))
            .use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    // Disambiguate the two outputs by their trailing dim: 2 → confidences, 4 → boxes.
                    var scores: FloatArray? = null
                    var boxes: FloatArray? = null
                    for (i in 0 until result.size()) {
                        val ot = result[i] as? OnnxTensor ?: continue
                        val last = (ot.info as? TensorInfo)?.shape?.lastOrNull()?.toInt() ?: continue
                        val fb = ot.floatBuffer
                        val arr = FloatArray(fb.remaining()).also { fb.get(it) }
                        if (last == 2) scores = arr else if (last == 4) boxes = arr
                    }
                    val sc = scores ?: return emptyList()
                    val bx = boxes ?: return emptyList()
                    val n = minOf(sc.size / 2, bx.size / 4)
                    val faces = ArrayList<Face>()
                    for (i in 0 until n) {
                        val conf = sc[i * 2 + 1] // index 0 = background, index 1 = face
                        if (conf >= minScore) {
                            faces += Face(bx[i * 4], bx[i * 4 + 1], bx[i * 4 + 2], bx[i * 4 + 3], conf)
                        }
                    }
                    return faces
                }
            }
    }

    /** Resize to [w]×[h], NCHW float, `(v-127)/128` normalization (RGB) — the UltraFace preprocessing. */
    private fun preprocess(src: BufferedImage, w: Int, h: Int): FloatArray {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        val plane = w * h
        val rgb = IntArray(plane)
        scaled.getRGB(0, 0, w, h, rgb, 0, w)
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val px = rgb[i]
            out[i] = (((px ushr 16) and 0xFF) - 127f) / 128f            // R plane
            out[plane + i] = (((px ushr 8) and 0xFF) - 127f) / 128f      // G plane
            out[2 * plane + i] = ((px and 0xFF) - 127f) / 128f           // B plane
        }
        return out
    }
}
