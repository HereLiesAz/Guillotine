package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * On-device subject segmentation for the desktop (JVM) build via a user-supplied ONNX model — the
 * desktop analogue of Android's ML Kit selfie segmenter. Produces a per-pixel alpha matte (subject
 * opaque, background transparent) so a `removeBackground` clip composites over the track beneath it.
 * Used by the render path (`replace_background`). Pure JVM; nothing leaves the machine.
 *
 * The model is any single-input ONNX segmenter with an NCHW float input (`[1, 3, H, W]`) and a mask
 * output — `[1, 1, h, w]` / `[1, h, w]` (single-channel foreground probability) or `[1, 2, h, w]`
 * (background/foreground logits). Input is scaled to `[0,1]` (the common selfie-seg convention). Any
 * failure returns the image unchanged, so a bad model never crashes the render — it just doesn't matte.
 */
object DesktopSegmenter {

    /** ARGB copy of [img] whose alpha is the subject mask from the model at [modelPath]; [img] on failure. */
    fun matte(img: BufferedImage, modelPath: String): BufferedImage =
        runCatching { matteInternal(img, modelPath) }.getOrDefault(img)

    /**
     * Portrait bokeh: keep the segmented subject sharp and blur the background. Blurs [img], then
     * composites the matted (sharp) subject back on top. Returns [img] unchanged on any failure.
     */
    fun portraitBlur(img: BufferedImage, modelPath: String): BufferedImage = runCatching {
        val subject = matteInternal(img, modelPath) // ARGB, subject opaque / background transparent
        val w = img.width
        val h = img.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(smoothBlur(img), 0, 0, w, h, null) // blurred background
        g.drawImage(subject, 0, 0, null)               // sharp subject over it
        g.dispose()
        out
    }.getOrDefault(img)

    /** Cheap smooth blur: downscale hard, then upscale bilinear. */
    private fun smoothBlur(img: BufferedImage): BufferedImage {
        val w = img.width
        val h = img.height
        val sw = (w / 12).coerceAtLeast(1)
        val sh = (h / 12).coerceAtLeast(1)
        val small = BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, sw, sh, null)
            dispose()
        }
        val big = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        big.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(small, 0, 0, w, h, null)
            dispose()
        }
        return big
    }

    private fun matteInternal(img: BufferedImage, modelPath: String): BufferedImage {
        val session = DesktopOnnx.session(modelPath)
        val inShape = runCatching { (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape }.getOrNull()
        val ih = inShape?.getOrNull(2)?.toInt()?.takeIf { it >= 2 } ?: 256
        val iw = inShape?.getOrNull(3)?.toInt()?.takeIf { it >= 2 } ?: 256
        val input = preprocess(img, iw, ih)
        val inputName = session.inputNames.first()

        OnnxTensor.createTensor(DesktopOnnx.environment, FloatBuffer.wrap(input), longArrayOf(1, 3, ih.toLong(), iw.toLong()))
            .use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val first = result.iterator().asSequence().firstOrNull()?.value as? OnnxTensor ?: return img
                    val shape = (first.info as? TensorInfo)?.shape ?: return img
                    val fb = first.floatBuffer
                    val flat = FloatArray(fb.remaining()).also { fb.get(it) }
                    // NCHW: channels, then spatial. length-3 = [1,h,w]; length-4 = [1,C,h,w].
                    val c = if (shape.size >= 4) shape[1].toInt() else 1
                    val mh = shape[shape.size - 2].toInt()
                    val mw = shape[shape.size - 1].toInt()
                    if (mh < 1 || mw < 1 || flat.size < c * mh * mw) return img

                    val w = img.width
                    val h = img.height
                    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                    val src = IntArray(w * h)
                    img.getRGB(0, 0, w, h, src, 0, w)
                    val dst = IntArray(w * h)
                    for (y in 0 until h) {
                        val my = (y * mh / h).coerceIn(0, mh - 1)
                        for (x in 0 until w) {
                            val mx = (x * mw / w).coerceIn(0, mw - 1)
                            val p = my * mw + mx
                            val prob = if (c >= 2) {
                                val bg = flat[p]
                                val fg = flat[mh * mw + p]
                                1f / (1f + exp(-(fg - bg)))
                            } else {
                                val v = flat[p]
                                if (v in 0f..1f) v else 1f / (1f + exp(-v)) // sigmoid if it's a logit
                            }
                            val a = (prob.coerceIn(0f, 1f) * 255f).toInt()
                            dst[y * w + x] = (a shl 24) or (src[y * w + x] and 0x00FFFFFF)
                        }
                    }
                    out.setRGB(0, 0, w, h, dst, 0, w)
                    return out
                }
            }
    }

    /** Resize to [w]×[h], NCHW float, scaled to `[0,1]` (RGB) — the common selfie-seg preprocessing. */
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
            out[i] = ((px ushr 16) and 0xFF) / 255f
            out[plane + i] = ((px ushr 8) and 0xFF) / 255f
            out[2 * plane + i] = (px and 0xFF) / 255f
        }
        return out
    }
}
