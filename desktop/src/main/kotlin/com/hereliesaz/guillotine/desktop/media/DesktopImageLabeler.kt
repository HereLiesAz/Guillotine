package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.TensorInfo
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.exp

/**
 * On-device image labeling for the desktop (JVM) build via a user-supplied ONNX classifier — the
 * desktop analogue of Android's ML Kit image labeler (which is built in and needs no model path).
 * Feeds a video frame through a standard ImageNet-style classifier and returns the top predicted
 * labels. Powers `search_clips` ("find all clips with a dog"); pure JVM, nothing leaves the machine.
 *
 * The classifier is any single-input / single-output ONNX image model with an NCHW float input
 * (`[1, 3, H, W]`) and a `[1, numClasses]` logit (or probability) output — e.g. a torchvision-exported
 * MobileNet / ResNet / EfficientNet. Preprocessing follows the torchvision ImageNet convention (RGB,
 * scaled to `[0,1]`, then per-channel mean/std normalized), which matches the vast majority of such
 * exports.
 *
 * **Labels:** the model must be accompanied by a plain-text labels file (one class name per line, the
 * line index being the class index). We look, in order, for `<model-basename>.txt`, then `labels.txt`,
 * then `imagenet_classes.txt` in the model's directory. Without one we can't turn class indices into
 * searchable words, so [labels] throws a clear, actionable error.
 */
object DesktopImageLabeler {

    /** One predicted label and its confidence in `[0,1]`. */
    data class Label(val text: String, val confidence: Float)

    // torchvision ImageNet normalization (the de-facto standard for exported classifiers).
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    // Parsed labels cached per model path (the file sits next to the model and rarely changes).
    private val labelCache = HashMap<String, List<String>>()

    /**
     * Top-[topK] labels for [image] using the ONNX classifier at [modelPath]. Throws with a clear
     * message if the model can't be loaded or has no accompanying labels file.
     */
    @Synchronized
    fun labels(modelPath: String, image: BufferedImage, topK: Int = 5): List<Label> {
        val names = labelsFor(modelPath)
        val session = DesktopOnnx.session(modelPath)

        // Infer the expected input side length from the model's declared input shape; fall back to 224
        // (the ImageNet default) when the dimension is dynamic (-1) or unavailable.
        val (h, w) = inputSize(session)
        val input = preprocess(image, w, h)
        val logits = DesktopOnnx.runSingle(session, input, longArrayOf(1, 3, h.toLong(), w.toLong()))

        val probs = softmax(logits)
        return probs.indices
            .sortedByDescending { probs[it] }
            .take(topK)
            .map { idx ->
                val name = names.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "class $idx"
                Label(name, probs[idx])
            }
    }

    /** Resize to [w]×[h], convert to NCHW float, and apply ImageNet mean/std normalization (RGB). */
    private fun preprocess(src: BufferedImage, w: Int, h: Int): FloatArray {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g2d = scaled.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.drawImage(src, 0, 0, w, h, null)
        } finally {
            g2d.dispose()
        }
        val plane = w * h
        // Bulk-read all pixels once (far cheaper than per-pixel getRGB), then split into planes.
        val rgb = IntArray(plane)
        scaled.getRGB(0, 0, w, h, rgb, 0, w)
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val px = rgb[i]
            val r = ((px ushr 16) and 0xFF) / 255f
            val g = ((px ushr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            out[i] = (r - MEAN[0]) / STD[0]                    // R plane
            out[plane + i] = (g - MEAN[1]) / STD[1]            // G plane
            out[2 * plane + i] = (b - MEAN[2]) / STD[2]        // B plane
        }
        return out
    }

    /** Read the NCHW spatial dims from the session's first input; default to 224 for dynamic dims. */
    private fun inputSize(session: ai.onnxruntime.OrtSession): Pair<Int, Int> {
        val info = runCatching {
            (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape
        }.getOrNull()
        // Expect [N, C, H, W]; a positive value is a fixed dim, -1 (or anything <2) is dynamic.
        val h = info?.getOrNull(2)?.toInt()?.takeIf { it >= 2 } ?: 224
        val w = info?.getOrNull(3)?.toInt()?.takeIf { it >= 2 } ?: 224
        return h to w
    }

    /** Numerically stable softmax; safe to apply to raw logits or already-normalized probabilities. */
    private fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return logits
        val max = logits.maxOrNull() ?: 0f
        var sum = 0.0
        val exps = DoubleArray(logits.size) { exp((logits[it] - max).toDouble()).also { e -> sum += e } }
        return FloatArray(logits.size) { (exps[it] / sum).toFloat() }
    }

    private fun labelsFor(modelPath: String): List<String> {
        labelCache[modelPath]?.let { return it }
        val model = File(modelPath)
        val dir = model.parentFile ?: File(".")
        val base = model.nameWithoutExtension
        val candidates = listOf(
            File(dir, "$base.txt"),
            File(dir, "labels.txt"),
            File(dir, "imagenet_classes.txt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException(
                "No labels file for the classifier. Place a text file (one class name per line) next to " +
                    "the model — named \"$base.txt\", \"labels.txt\", or \"imagenet_classes.txt\".",
            )
        val names = file.readLines().map { it.trim() }
        labelCache[modelPath] = names
        return names
    }
}
