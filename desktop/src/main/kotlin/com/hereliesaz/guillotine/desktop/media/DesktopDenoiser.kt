package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.ai.StreamingStft

/**
 * On-device speech noise reduction for desktop: the GTCRN model (`gtcrn_simple.onnx`, the same file
 * Android runs through sherpa-onnx) driven directly through ONNX Runtime, since sherpa-onnx has no clean
 * JVM distribution.
 *
 * GTCRN is a streaming model: each call takes one STFT frame (`mix`, `[1, 257, 1, 2]` = 257 bins ×
 * real/imag) plus three recurrent caches and returns the enhanced frame and the updated caches. The
 * framing (512-point FFT, hop 256, sqrt-Hann) comes from the model's own metadata and lives in
 * [StreamingStft]. Input is 16 kHz mono; so is the output. Nothing leaves the machine.
 */
object DesktopDenoiser {

    const val SAMPLE_RATE = 16_000
    private const val BINS = 257

    // Cache shapes from the model metadata (conv_cache_shape / tra_cache_shape / inter_cache_shape).
    private val CONV_SHAPE = longArrayOf(2, 1, 16, 16, 33)
    private val TRA_SHAPE = longArrayOf(2, 3, 1, 1, 16)
    private val INTER_SHAPE = longArrayOf(2, 1, 33, 16)
    private val MIX_SHAPE = longArrayOf(1, BINS.toLong(), 1, 2)

    private fun size(shape: LongArray) = shape.fold(1L) { a, b -> a * b }.toInt()

    /** Denoise [pcm] (16 kHz mono, [-1, 1]) with the GTCRN model at [modelPath]. Same length out. */
    fun denoise(modelPath: String, pcm: FloatArray): FloatArray {
        val session = DesktopOnnx.session(modelPath)
        var conv = FloatArray(size(CONV_SHAPE))
        var tra = FloatArray(size(TRA_SHAPE))
        var inter = FloatArray(size(INTER_SHAPE))
        val mix = FloatArray(BINS * 2)
        return StreamingStft.run(pcm) { re, im ->
            for (k in 0 until BINS) {
                mix[2 * k] = re[k]
                mix[2 * k + 1] = im[k]
            }
            val tensors = listOf(
                "mix" to DesktopOnnx.floatTensor(mix, MIX_SHAPE),
                "conv_cache" to DesktopOnnx.floatTensor(conv, CONV_SHAPE),
                "tra_cache" to DesktopOnnx.floatTensor(tra, TRA_SHAPE),
                "inter_cache" to DesktopOnnx.floatTensor(inter, INTER_SHAPE),
            )
            try {
                DesktopOnnx.run(session, tensors.toMap()).use { result ->
                    fun out(name: String): FloatArray {
                        val t = result.get(name).orElseThrow { IllegalStateException("GTCRN model has no output $name") }
                            as ai.onnxruntime.OnnxTensor
                        val buf = t.floatBuffer
                        return FloatArray(buf.remaining()).also { buf.get(it) }
                    }
                    val enh = out("enh")
                    conv = out("conv_cache_out")
                    tra = out("tra_cache_out")
                    inter = out("inter_cache_out")
                    FloatArray(BINS) { enh[2 * it] } to FloatArray(BINS) { enh[2 * it + 1] }
                }
            } finally {
                tensors.forEach { it.second.close() }
            }
        }
    }

    /** Write mono 16-bit PCM WAV at [SAMPLE_RATE]. */
    fun writeWav(file: java.io.File, samples: FloatArray) {
        val dataSize = samples.size * 2
        val buf = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII)).put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        file.parentFile?.mkdirs()
        file.writeBytes(buf.array())
    }
}
