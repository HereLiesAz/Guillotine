package com.hereliesaz.guillotine.ai.tflite

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generic on-device image→image TFLite runtime (raw LiteRT `Interpreter`). Powers the on-device image
 * effects — super-resolution, style transfer, depth — where the model takes a single `[1,H,W,3]` image
 * and returns a single `[1,H2,W2,C]` image (C=3 for RGB output, C=1 for a depth/gray map).
 *
 * It reads the model's own input size and dtype, so different models drop in without code changes. It
 * assumes the common float `[0,1]` (or uint8 `[0,255]`) convention; a model using a different range may
 * need per-model tuning. Uses only the core `org.tensorflow:tensorflow-lite` runtime (no
 * tensorflow-lite-support, which collides with MediaPipe's bundled TFLite at manifest-merge). Input and
 * output are packed/read as raw NHWC [ByteBuffer]s by hand. On-device only — pixels never leave the device.
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

            val scaled = if (input.width != w || input.height != h) {
                Bitmap.createScaledBitmap(input, w, h, true)
            } else input
            val inBuf = bitmapToBuffer(scaled, h, w, inFloat)

            val outTensor = itp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outFloat = outTensor.dataType() == DataType.FLOAT32
            val outBytesPer = if (outFloat) 4 else 1
            val outCount = outShape.fold(1) { acc, d -> acc * d }
            val outBuf = ByteBuffer.allocateDirect(outCount * outBytesPer).order(ByteOrder.nativeOrder())

            itp.run(inBuf, outBuf)
            outBuf.rewind()
            bufferToBitmap(outBuf, outShape, outFloat)
        }.getOrNull()
    }

    /** Pack an [h]×[w] bitmap into an NHWC buffer: float32 `[0,1]` or uint8 `[0,255]`. */
    private fun bitmapToBuffer(bmp: Bitmap, h: Int, w: Int, isFloat: Boolean): ByteBuffer {
        val bytesPer = if (isFloat) 4 else 1
        val buf = ByteBuffer.allocateDirect(w * h * 3 * bytesPer).order(ByteOrder.nativeOrder())
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (isFloat) {
                buf.putFloat(r / 255f); buf.putFloat(g / 255f); buf.putFloat(b / 255f)
            } else {
                buf.put(r.toByte()); buf.put(g.toByte()); buf.put(b.toByte())
            }
        }
        buf.rewind()
        return buf
    }

    /** Read an NHWC output [buf] back into an ARGB bitmap; float outputs are assumed `[0,1]`. */
    private fun bufferToBitmap(buf: ByteBuffer, shape: IntArray, isFloat: Boolean): Bitmap {
        val h = shape.getOrElse(shape.size - 3) { 0 }
        val w = shape.getOrElse(shape.size - 2) { 0 }
        val c = shape.getOrElse(shape.size - 1) { 3 }
        require(h > 0 && w > 0) { "Model output has no image dimensions." }
        fun next(): Int {
            var v = if (isFloat) buf.float * 255f else (buf.get().toInt() and 0xFF).toFloat()
            return v.toInt().coerceIn(0, 255)
        }
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = next()
            val g = if (c >= 3) next() else r
            val b = if (c >= 3) next() else r
            if (c > 3) repeat(c - 3) { if (isFloat) buf.float else buf.get() } // skip alpha/extra channels
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        runCatching { interpreter?.close() }
    }
}
