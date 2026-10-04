package com.hereliesaz.guillotine.ai.safety

import java.io.File
import java.security.MessageDigest
import kotlin.math.exp

/**
 * The on-device check every AI-generated image or video frame passes before it reaches the timeline
 * ([ContentSafety] layer 3): Falconsai's ViT NSFW classifier (`normal` / `nsfw`), the int8 ONNX build
 * from `onnx-community/nsfw_image_detection-ONNX`, pinned to a commit and a SHA-256. 87 MB, so it is
 * downloaded once on first use instead of shipped in the app. The platforms run it through ONNX
 * Runtime; this object holds the parts both share (the file, preprocessing, the verdict).
 *
 * Fails closed: a caller that can't get or load the model refuses the generation.
 */
object NsfwModel {

    const val FILE_NAME = "nsfw-vit-int8.onnx"
    const val URL =
        "https://huggingface.co/onnx-community/nsfw_image_detection-ONNX/resolve/" +
            "1ceb3c7fe1e9f3f2507e6df577437f23a9149fd5/onnx/model_int8.onnx"
    const val SHA256 = "bdc941491cd09ae75c5f40b038a5cd64482fa4e2b5297747323c5cda31303920"
    const val BYTES = 87_333_629L

    const val INPUT = "pixel_values"
    const val SIZE = 224

    /** NSFW probability at or above which a result is refused. */
    const val THRESHOLD = 0.5f

    /** Thrown when generated content is refused (flagged, or the check couldn't run). */
    class FlaggedException(message: String) : IllegalStateException(message)

    private val lock = Any()

    /**
     * The verified model file in [dir], downloading it first if it's missing or doesn't match the
     * pinned hash. Blocking; call off the main thread. Throws [FlaggedException] when it can't be had,
     * so generation fails closed.
     */
    fun ensure(dir: File): File = synchronized(lock) {
        val file = File(dir, FILE_NAME)
        if (file.length() == BYTES && sha256(file) == SHA256) return file
        dir.mkdirs()
        val part = File(dir, "$FILE_NAME.part")
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(okhttp3.Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                part.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            if (sha256(part) != SHA256) throw java.io.IOException("checksum mismatch")
            file.delete()
            if (!part.renameTo(file)) { part.copyTo(file, overwrite = true); part.delete() }
            return file
        } catch (e: Exception) {
            part.delete()
            throw FlaggedException(
                "Couldn't download the content-safety check (${e.message}). AI generation needs it; " +
                    "try again when online.",
            )
        }
    }

    /**
     * Pixels → the model's `[1, 3, 224, 224]` input: [argb] is a [width]×[height] image (ARGB ints, as
     * Android's `Bitmap.getPixels` and AWT's `getRGB` return). Bilinear resize to 224², scale to
     * [0, 1], then normalise with mean 0.5 and std 0.5, matching the model's preprocessor config.
     */
    fun preprocess(argb: IntArray, width: Int, height: Int): FloatArray {
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        val sx = width.toFloat() / SIZE
        val sy = height.toFloat() / SIZE
        for (y in 0 until SIZE) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, height - 1); val wy = fy - y0
            for (x in 0 until SIZE) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, width - 1); val wx = fx - x0
                val p00 = argb[y0 * width + x0]; val p01 = argb[y0 * width + x1]
                val p10 = argb[y1 * width + x0]; val p11 = argb[y1 * width + x1]
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    fun ch(p: Int) = ((p shr shift) and 0xFF).toFloat()
                    val top = ch(p00) + (ch(p01) - ch(p00)) * wx
                    val bot = ch(p10) + (ch(p11) - ch(p10)) * wx
                    val v = (top + (bot - top) * wy) / 255f
                    out[c * plane + y * SIZE + x] = (v - 0.5f) / 0.5f
                }
            }
        }
        return out
    }

    /** NSFW probability from the model's two logits (`[normal, nsfw]`). */
    fun nsfwProbability(logits: FloatArray): Float {
        val m = maxOf(logits[0], logits[1])
        val a = exp((logits[0] - m).toDouble()); val b = exp((logits[1] - m).toDouble())
        return (b / (a + b)).toFloat()
    }

    /** Throws [FlaggedException] when any of [scores] reaches [THRESHOLD]. */
    fun requireClean(scores: List<Float>) {
        if (scores.isEmpty()) throw FlaggedException("The generated result couldn't be checked, so it was discarded.")
        if (scores.any { it >= THRESHOLD }) {
            throw FlaggedException("The generated result was flagged as sexual content and discarded.")
        }
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
