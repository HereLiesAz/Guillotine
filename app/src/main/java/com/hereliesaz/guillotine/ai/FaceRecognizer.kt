package com.hereliesaz.guillotine.ai

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * On-device face recognizer via a raw TFLite [Interpreter] (e.g. MobileFaceNet). Turns a detected face
 * crop into an L2-normalized embedding for cosine identity matching ("is this the same person?"). Kept
 * separate from the general MediaPipe ImageEmbedder because face `.tflite` models ship without MediaPipe
 * metadata and need their own preprocessing (square resize + (x−127.5)/128). Uses only the core
 * `org.tensorflow:tensorflow-lite` runtime. On-device only — pixels never leave the device.
 */
class FaceRecognizer(modelPath: String) : Closeable {

    private val interpreter: Interpreter? = runCatching {
        File(modelPath).takeIf { it.isFile }?.let {
            Interpreter(it, Interpreter.Options().apply { setNumThreads(2) })
        }
    }.getOrNull()

    val available: Boolean get() = interpreter != null

    // Read the model's own geometry so different face models drop in without code changes
    // (MobileFaceNet 112→192, FaceNet 160→128, …). Defaults match MobileFaceNet.
    private val inputSize: Int = interpreter?.getInputTensor(0)?.shape()?.getOrNull(1)?.takeIf { it > 0 } ?: 112
    private val outputDim: Int = interpreter?.getOutputTensor(0)?.shape()?.lastOrNull()?.takeIf { it > 0 } ?: 192

    /**
     * L2-normalized face embedding of [face] (a detected face crop); null if the model isn't available
     * or inference fails. The crop is square-resized to the model's input size — a plain detected-face
     * box works; 5-point alignment would improve accuracy (a future enhancement).
     */
    fun embed(face: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        return runCatching {
            val scaled = if (face.width != inputSize || face.height != inputSize) {
                Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
            } else {
                face
            }
            val buf = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
            val px = IntArray(inputSize * inputSize)
            scaled.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
            if (scaled !== face) scaled.recycle()
            for (p in px) {
                // MobileFaceNet / ArcFace convention: (x − 127.5) / 128 → [−1, 1], RGB order.
                buf.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
                buf.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
                buf.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
            buf.rewind()
            val out = Array(1) { FloatArray(outputDim) }
            itp.run(buf, out)
            l2normalize(out[0])
        }.getOrNull()
    }

    /** In-place L2 normalization so cosine similarity is a plain dot product. */
    private fun l2normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += (x * x).toDouble()
        val n = sqrt(s).toFloat()
        if (n > 1e-6f) for (i in v.indices) v[i] = v[i] / n
        return v
    }

    override fun close() {
        runCatching { interpreter?.close() }
    }
}
