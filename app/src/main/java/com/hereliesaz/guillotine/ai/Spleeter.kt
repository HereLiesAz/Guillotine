package com.hereliesaz.guillotine.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * On-device 2-stem music source separation (Deezer Spleeter, run via ONNX Runtime). Splits a clip's
 * stereo audio into **vocals** and **accompaniment** WAVs. Pipeline (verified against sherpa-onnx's
 * Spleeter implementation): STFT (44.1 kHz, 4096-pt Hann, hop 1024, no center-pad; keep the first 1024
 * of 2049 bins) → two ONNX models estimate each stem's magnitude → power-ratio soft mask applied to the
 * original complex STFT (phase preserved) → iSTFT overlap-add. On-device only.
 *
 * Heavy: the model input is `[2, num_splits, 512, 1024]` float32, so a multi-minute song allocates
 * hundreds of MB. Best on a capable device and moderate clip lengths.
 */
object Spleeter {

    private const val SR = 44_100
    private const val NFFT = 4096
    private const val HOP = 1024
    private const val T = 512      // frames per model segment
    private const val F = 1024     // freq bins the model uses
    private const val BINS = NFFT / 2 + 1 // 2049

    data class Stems(val vocalsWav: String, val accompanimentWav: String, val durationMs: Long)

    /**
     * Separate [uri]'s audio into vocals + accompaniment using the two Spleeter ONNX models in
     * [modelDir] (`vocals*.onnx` and `accompaniment*.onnx`), writing WAVs under [outDir]. Returns their
     * paths, or null if there's no usable audio.
     */
    fun separate(context: Context, uri: Uri, modelDir: String, outDir: File): Stems? {
        val dir = File(modelDir)
        val onnx = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.toList() ?: emptyList()
        val vocalsModel = onnx.firstOrNull { it.name.contains("vocals") } ?: return null
        val accModel = onnx.firstOrNull { it.name.contains("accompaniment") || it.name.contains("accomp") } ?: return null

        val (left, right) = decodeStereo44k(context, uri) ?: return null
        val n = minOf(left.size, right.size)
        if (n < NFFT) return null

        // Forward STFT per channel → magnitude + complex (kept for phase during reconstruction).
        val window = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / NFFT) } // periodic Hann
        val chans = arrayOf(left, right)
        val numFrames = (n - NFFT) / HOP + 1
        val splits = (numFrames + T - 1) / T
        val paddedFrames = splits * T
        // magnitude[c][frame*F + bin], complexRe/Im[c][frame*BINS + bin]
        val mag = Array(2) { FloatArray(paddedFrames * F) }
        val re = Array(2) { FloatArray(numFrames * BINS) }
        val im = Array(2) { FloatArray(numFrames * BINS) }
        val fr = DoubleArray(NFFT); val fi = DoubleArray(NFFT)
        for (c in 0..1) {
            val sig = chans[c]
            for (f in 0 until numFrames) {
                val base = f * HOP
                for (i in 0 until NFFT) { fr[i] = sig[base + i] * window[i]; fi[i] = 0.0 }
                Fft.transform(fr, fi, inverse = false)
                val magOff = f * F; val cOff = f * BINS
                for (k in 0 until BINS) {
                    re[c][cOff + k] = fr[k].toFloat(); im[c][cOff + k] = fi[k].toFloat()
                    if (k < F) mag[c][magOff + k] = sqrt(fr[k] * fr[k] + fi[k] * fi[k]).toFloat()
                }
            }
        }

        // Build model input x[2, splits, 512, 1024] and run both models.
        val x = FloatArray(2 * splits * T * F)
        for (c in 0..1) for (frame in 0 until paddedFrames) {
            if (frame >= numFrames) continue
            val src = frame * F
            val dst = (c.toLong() * splits * T * F + frame.toLong() * F).toInt()
            System.arraycopy(mag[c], src, x, dst, F)
        }
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(2, splits.toLong(), T.toLong(), F.toLong())
        val vY = runModel(env, vocalsModel.absolutePath, x, shape)
        val aY = runModel(env, accModel.absolutePath, x, shape)

        // Ratio mask + iSTFT per channel per stem.
        val vocalsL = istftMasked(vY, re[0], im[0], numFrames, 0, splits, aY, window, n)
        val vocalsR = istftMasked(vY, re[1], im[1], numFrames, 1, splits, aY, window, n)
        val accL = istftMasked(aY, re[0], im[0], numFrames, 0, splits, vY, window, n)
        val accR = istftMasked(aY, re[1], im[1], numFrames, 1, splits, vY, window, n)

        outDir.mkdirs()
        val stamp = File(outDir, "stem").absolutePath
        val vWav = "${stamp}_vocals_${System.identityHashCode(vocalsL)}.wav"
        val aWav = "${stamp}_accompaniment_${System.identityHashCode(accL)}.wav"
        writeStereoWav(File(vWav), vocalsL, vocalsR)
        writeStereoWav(File(aWav), accL, accR)
        return Stems(vWav, aWav, n * 1000L / SR)
    }

    /** Run a Spleeter ONNX model on input [x] of [shape], returning its flat `[2,splits,512,1024]` output. */
    private fun runModel(env: OrtEnvironment, modelPath: String, x: FloatArray, shape: LongArray): FloatArray {
        env.createSession(modelPath, OrtSession.SessionOptions()).use { session ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { input ->
                session.run(mapOf(session.inputNames.first() to input)).use { result ->
                    val out = result[0] as OnnxTensor
                    val fb = out.floatBuffer
                    return FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
    }

    /**
     * Reconstruct one channel of a stem: build the power-ratio soft mask from this stem's [thisMag] and
     * the [otherMag] stem, apply it to the original complex STFT ([re]/[im], preserving phase), and
     * overlap-add iSTFT back to [outLen] samples. [ch] selects the channel plane in the model tensors.
     */
    private fun istftMasked(
        thisMag: FloatArray, re: FloatArray, im: FloatArray, numFrames: Int, ch: Int, splits: Int,
        otherMag: FloatArray, window: DoubleArray, outLen: Int,
    ): FloatArray {
        val out = DoubleArray(outLen)
        val norm = DoubleArray(outLen)
        val sr = DoubleArray(NFFT); val si = DoubleArray(NFFT)
        val eps = 1e-10
        for (f in 0 until numFrames) {
            val tIdx = f % T; val split = f / T
            val tBase = ((ch.toLong() * splits + split) * T + tIdx) * F
            val cOff = f * BINS
            // Build full 4096 complex spectrum: bins 0..F-1 masked, F..2048 zeroed, mirror for the rest.
            for (k in 0 until BINS) {
                var mr = 0.0; var mi = 0.0
                if (k < F) {
                    val v = thisMag[(tBase + k).toInt()].toDouble()
                    val o = otherMag[(tBase + k).toInt()].toDouble()
                    val mask = (v * v + eps / 2) / (v * v + o * o + eps)
                    mr = mask * re[cOff + k]; mi = mask * im[cOff + k]
                }
                sr[k] = mr; si[k] = mi
                if (k in 1 until BINS - 1) { sr[NFFT - k] = mr; si[NFFT - k] = -mi } // Hermitian mirror
            }
            Fft.transform(sr, si, inverse = true)
            val base = f * HOP
            for (i in 0 until NFFT) {
                val pos = base + i
                if (pos >= outLen) break
                val w = window[i]
                out[pos] += (sr[i] / NFFT) * w   // iFFT is unscaled → /N; weighted overlap-add
                norm[pos] += w * w
            }
        }
        val result = FloatArray(outLen)
        for (i in 0 until outLen) {
            result[i] = (if (norm[i] > 1e-8) out[i] / norm[i] else 0.0).toFloat().coerceIn(-1f, 1f)
        }
        return result
    }

    /** Decode [uri]'s first audio track to stereo float PCM resampled to 44.1 kHz. Null if no audio. */
    private fun decodeStereo44k(context: Context, uri: Uri): Pair<FloatArray, FloatArray>? {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(context, uri, null) } catch (_: Exception) {
            runCatching { extractor.release() }; return null
        }
        var track = -1; var format: MediaFormat? = null
        for (t in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(t)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = t; format = f; break }
        }
        if (track < 0 || format == null) { extractor.release(); return null }
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        extractor.selectTrack(track)
        val left = ArrayList<Float>(1 shl 20); val right = ArrayList<Float>(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false; var ch = 0; var l = 0f; var r = 0f
        var codec: MediaCodec? = null
        try {
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(format, null, null, 0); dec.start()
            while (!outputDone) {
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) { dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = dec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        val sh = ob.asShortBuffer()
                        while (sh.hasRemaining()) {
                            val s = sh.get() / 32768f
                            if (channels == 1) { left.add(s); right.add(s) }
                            else { when (ch) { 0 -> l = s; 1 -> r = s }; ch++; if (ch >= channels) { left.add(l); right.add(r); ch = 0 } }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
        }
        if (left.isEmpty()) return null
        return resample(left.toFloatArray(), srcRate, SR) to resample(right.toFloatArray(), srcRate, SR)
    }

    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || src.isEmpty()) return src
        val ratio = to.toDouble() / from
        val out = FloatArray((src.size * ratio).toInt().coerceAtLeast(1))
        for (i in out.indices) {
            val p = i / ratio; val i0 = p.toInt(); val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val fr = (p - i0).toFloat()
            out[i] = src[i0] * (1 - fr) + src[i1] * fr
        }
        return out
    }

    private fun writeStereoWav(file: File, left: FloatArray, right: FloatArray) {
        val frames = minOf(left.size, right.size)
        val dataSize = frames * 4 // 2 ch * 16-bit
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII)); header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16); header.putShort(1); header.putShort(2)
            header.putInt(SR); header.putInt(SR * 4); header.putShort(4); header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(dataSize)
            raf.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                body.putShort((left[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                body.putShort((right[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
            }
            raf.write(body.array())
        }
    }
}
