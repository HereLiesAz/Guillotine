package com.hereliesaz.guillotine.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * On-device 2-stem music source separation (Deezer Spleeter, run via ONNX Runtime). Splits already-
 * decoded stereo PCM into **vocals** and **accompaniment** WAVs. Pipeline (verified against sherpa-onnx's
 * Spleeter implementation): resample to 44.1 kHz → STFT (4096-pt Hann, hop 1024, no center-pad; keep the
 * first 1024 of 2049 bins) → two ONNX models estimate each stem's magnitude → power-ratio soft mask
 * applied to the original complex STFT (phase preserved) → iSTFT overlap-add. On-device only.
 *
 * Pure JVM + raw `ai.onnxruntime` + the shared [Fft] — no platform dependency. Each platform decodes the
 * clip's audio its own way (Android via `MediaCodec`, desktop via JavaCV) and passes the stereo samples
 * in; the DSP + inference run here.
 *
 * Heavy: the model input is `[2, num_splits, 512, 1024]` float32, so a multi-minute song allocates
 * hundreds of MB. Best on a capable machine and moderate clip lengths.
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
     * Separate already-decoded stereo PCM ([leftIn]/[rightIn], normalized [-1,1] at [srcSampleRate] Hz)
     * into vocals + accompaniment using the two Spleeter ONNX models in [modelDir] (`vocals*.onnx` and
     * `accompaniment*.onnx`), writing stereo WAVs under [outDir]. Returns their paths, or null if the
     * models are missing or there isn't enough audio.
     */
    fun separate(
        leftIn: FloatArray,
        rightIn: FloatArray,
        srcSampleRate: Int,
        modelDir: String,
        outDir: File,
    ): Stems? {
        val dir = File(modelDir)
        val onnx = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.toList() ?: emptyList()
        val vocalsModel = onnx.firstOrNull { it.name.contains("vocals") } ?: return null
        val accModel = onnx.firstOrNull { it.name.contains("accompaniment") || it.name.contains("accomp") } ?: return null

        // The pipeline is fixed at 44.1 kHz; resample the incoming PCM to match.
        val left = resample(leftIn, srcSampleRate, SR)
        val right = resample(rightIn, srcSampleRate, SR)
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
        // A per-run token so repeated separations into the same dir never collide (identityHashCode is
        // not unique — a GC address reuse could overwrite a prior result). Both stems share the token.
        val token = java.util.UUID.randomUUID().toString().substring(0, 8)
        val vWav = "${stamp}_vocals_${token}.wav"
        val aWav = "${stamp}_accompaniment_${token}.wav"
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
