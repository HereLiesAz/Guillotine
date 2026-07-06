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

    /** Decoded output geometry: image [h]×[w] with [c] channels, laid out channel-first if [nchw]. */
    private data class Dims(val h: Int, val w: Int, val c: Int, val nchw: Boolean)

    /**
     * Work out the image geometry + memory layout of an output tensor. Real models vary: RGB super-res
     * emits NCHW `[1,3,H,W]`; MiDaS depth emits `[1,H,W]` (single channel, no channel dim); classic
     * models emit NHWC `[1,H,W,3]`. The channel dim is the one that's 1/3/4.
     */
    private fun decodeShape(s: IntArray): Dims = when (s.size) {
        4 -> {
            val d1 = s[1]; val d2 = s[2]; val d3 = s[3]
            when {
                d3 == 1 || d3 == 3 || d3 == 4 -> Dims(d1, d2, d3, false) // NHWC
                d1 == 1 || d1 == 3 || d1 == 4 -> Dims(d2, d3, d1, true)  // NCHW
                else -> Dims(d1, d2, d3, false)
            }
        }
        3 -> when {
            s[0] == 1 -> Dims(s[1], s[2], 1, false)                       // [1,H,W] depth map
            s[2] == 1 || s[2] == 3 || s[2] == 4 -> Dims(s[0], s[1], s[2], false) // [H,W,C]
            else -> Dims(s[1], s[2], 1, false)
        }
        2 -> Dims(s[0], s[1], 1, false)
        else -> Dims(0, 0, 0, false)
    }

    /**
     * Read the output [buf] back into an ARGB bitmap, honouring NHWC/NCHW layout. RGB float outputs are
     * assumed `[0,1]` (×255); a single-channel float map (depth) is min–max normalized to `[0,255]` so it
     * renders as a visible greyscale depth image regardless of the model's raw value range.
     */
    private fun bufferToBitmap(buf: ByteBuffer, shape: IntArray, isFloat: Boolean): Bitmap {
        val (h, w, c, nchw) = decodeShape(shape)
        require(h > 0 && w > 0) { "Model output has no image dimensions." }
        val n = h * w * c
        val vals = FloatArray(n) { if (isFloat) buf.float else (buf.get().toInt() and 0xFF).toFloat() }

        // Scaling: RGB [0,1]→[0,255]; single-channel float depth → min–max stretch; uint8 already 0–255.
        val depthMap = isFloat && c == 1
        var lo = 0f; var span = 1f
        if (depthMap) {
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (v in vals) { if (v < mn) mn = v; if (v > mx) mx = v }
            lo = mn; span = (mx - mn).takeIf { it > 1e-6f } ?: 1f
        }
        fun sample(y: Int, x: Int, ch: Int): Int {
            val idx = if (nchw) ch * h * w + y * w + x else (y * w + x) * c + ch
            val raw = vals[idx]
            val v = when {
                depthMap -> (raw - lo) / span * 255f
                isFloat -> raw * 255f
                else -> raw
            }
            return v.toInt().coerceIn(0, 255)
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val r = sample(y, x, 0)
            val g = if (c >= 3) sample(y, x, 1) else r
            val b = if (c >= 3) sample(y, x, 2) else r
            out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        runCatching { interpreter?.close() }
    }
}
