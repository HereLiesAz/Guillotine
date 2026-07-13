package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.TensorInfo
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.sqrt

/**
 * On-device image embedding for the desktop (JVM) build via a user-supplied ONNX model — the desktop
 * analogue of Android's MediaPipe `ImageEmbedder`. Turns a frame into an L2-normalized feature vector
 * so two frames can be compared by cosine similarity ("is this the same thing I pointed out?"). Powers
 * the learned-concept tools (`add_reference` / `analyze_clip_with_concept`). Pure JVM; on-device only.
 *
 * The model is any single-input / single-output ONNX image embedder with an NCHW float input
 * (`[1, 3, H, W]`) and a `[1, D]` embedding output (e.g. a torchvision-exported MobileNet/ResNet with
 * its classifier head removed). Preprocessing follows the torchvision ImageNet convention, matching
 * [DesktopImageLabeler].
 */
object DesktopImageEmbedder {

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** L2-normalized embedding of [image] using the ONNX model at [modelPath]. Throws if unloadable. */
    fun embed(modelPath: String, image: BufferedImage): FloatArray {
        val session = DesktopOnnx.session(modelPath)
        val (h, w) = inputSize(session)
        val input = preprocess(image, w, h)
        val out = DesktopOnnx.runSingle(session, input, longArrayOf(1, 3, h.toLong(), w.toLong()))
        return l2Normalize(out)
    }

    /** Cosine similarity of two L2-normalized vectors (a plain dot product); 0 if lengths differ. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm <= 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun inputSize(session: ai.onnxruntime.OrtSession): Pair<Int, Int> {
        val shape = runCatching {
            (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape
        }.getOrNull()
        val h = shape?.getOrNull(2)?.toInt()?.takeIf { it >= 2 } ?: 224
        val w = shape?.getOrNull(3)?.toInt()?.takeIf { it >= 2 } ?: 224
        return h to w
    }

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
            out[i] = (((px ushr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = (((px ushr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((px and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }
}
