package com.hereliesaz.guillotine.ai.tflite

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.Closeable
import java.io.File

/**
 * Generic on-device image→image TFLite runtime (raw LiteRT `Interpreter`). Powers the on-device image
 * effects — super-resolution, style transfer, depth — where the model takes a single `[1,H,W,3]` image
 * and returns a single `[1,H2,W2,C]` image (C=3 for RGB output, C=1 for a depth/gray map).
 *
 * It reads the model's own input size and dtype, so different models drop in without code changes. It
 * assumes the common float `[0,1]` (or uint8 `[0,255]`) convention; a model using a different range may
 * need per-model tuning. On-device only — pixels never leave the device.
 */
class TfliteImageModel(modelPath: String) : Closeable {

    private val interpreter: Interpreter? = runCatching {
        Interpreter(File(modelPath), Interpreter.Options().apply { setNumThreads(4) })
    }.getOrNull()

    val available: Boolean get() = interpreter != null

    /** Run the model on [input]; returns the output image, or null on failure. */
    fun run(input: Bitmap): Bitmap? {
        val itp = interpreter ?: return null
        return runCatching {
            val inTensor = itp.getInputTensor(0)
            val inShape = inTensor.shape() // [1,H,W,3]
            val h = inShape.getOrElse(1) { 256 }
            val w = inShape.getOrElse(2) { 256 }
            val inFloat = inTensor.dataType() == DataType.FLOAT32

            var image = TensorImage(inTensor.dataType())
            image.load(input)
            image = ImageProcessor.Builder()
                .add(ResizeOp(h, w, ResizeOp.ResizeMethod.BILINEAR))
                .apply { if (inFloat) add(NormalizeOp(0f, 255f)) } // uint8 [0,255] → float [0,1]
                .build()
                .process(image)

            val outTensor = itp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outBuf = TensorBuffer.createFixedSize(outShape, outTensor.dataType())
            outBuf.buffer.rewind()
            itp.run(image.buffer, outBuf.buffer)
            outputToBitmap(outBuf, outShape, outTensor.dataType() == DataType.FLOAT32)
        }.getOrNull()
    }

    private fun outputToBitmap(buf: TensorBuffer, shape: IntArray, isFloat: Boolean): Bitmap {
        val h = shape.getOrElse(shape.size - 3) { 0 }
        val w = shape.getOrElse(shape.size - 2) { 0 }
        val c = shape.getOrElse(shape.size - 1) { 3 }
        require(h > 0 && w > 0) { "Model output has no image dimensions." }
        val floats = buf.floatArray // dequantized for uint8 buffers
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = (y * w + x) * c
                fun ch(o: Int): Int {
                    var v = floats.getOrElse(base + o) { 0f }
                    if (isFloat) v *= 255f // float outputs are assumed [0,1]
                    return v.toInt().coerceIn(0, 255)
                }
                val r = ch(0)
                val g = if (c >= 3) ch(1) else r
                val b = if (c >= 3) ch(2) else r
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        runCatching { interpreter?.close() }
    }
}
