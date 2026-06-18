package com.hereliesaz.guillotine.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * On-device speech-to-text via Vosk (no key, no network). The user supplies a model
 * directory once (Settings → speech model path); audio is decoded to 16 kHz mono PCM and
 * fed to the recognizer, which emits word timings grouped into [TranscriptCue]s.
 *
 * Decoding + naive resampling happen in memory, so it suits clip-length audio. Needs the
 * model present on device; verify on a device.
 */
object VoskTranscriber {

    private const val TARGET_RATE = 16000

    fun transcribeBlocking(context: Context, modelPath: String, uri: String): List<TranscriptCue> {
        val mono = decodeMono16k(context, Uri.parse(uri))
            ?: throw IllegalStateException("Could not read audio for on-device transcription.")
        val model = Model(modelPath)
        return try {
            val rec = Recognizer(model, TARGET_RATE.toFloat())
            // Nested try/finally so the recognizer is closed even if a chunk throws — the
            // outer finally only closes the model.
            try {
                rec.setWords(true)
                val cues = mutableListOf<TranscriptCue>()
                var i = 0
                val chunk = 4000
                while (i < mono.size) {
                    val len = min(chunk, mono.size - i)
                    val buf = if (i == 0 && len == mono.size) mono else mono.copyOfRange(i, i + len)
                    if (rec.acceptWaveForm(buf, len)) parseResult(rec.result)?.let { cues += it }
                    i += len
                }
                parseResult(rec.finalResult)?.let { cues += it }
                cues
            } finally {
                rec.close()
            }
        } finally {
            model.close()
        }
    }

    suspend fun transcribe(context: Context, modelPath: String, uri: String): List<TranscriptCue> =
        withContext(Dispatchers.IO) { transcribeBlocking(context, modelPath, uri) }

    private fun parseResult(json: String): TranscriptCue? {
        val arr = JSONObject(json).optJSONArray("result") ?: return null
        if (arr.length() == 0) return null
        var start = Double.MAX_VALUE
        var end = 0.0
        val sb = StringBuilder()
        for (k in 0 until arr.length()) {
            val w = arr.getJSONObject(k)
            start = min(start, w.optDouble("start"))
            end = max(end, w.optDouble("end"))
            sb.append(w.optString("word")).append(' ')
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) return null
        return TranscriptCue((start * 1000).toLong(), (end * 1000).toLong(), text)
    }

    /** Decode the first audio track to 16 kHz mono 16-bit PCM (downmixed + linearly resampled). */
    private fun decodeMono16k(context: Context, uri: Uri): ShortArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var track = -1
            var format: MediaFormat? = null
            for (t in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(t)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = t; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val monoBytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)
                            val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIndex)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                var j = 0
                                val n = shorts.remaining()
                                while (j + channels <= n) {
                                    var sum = 0
                                    for (c in 0 until channels) sum += shorts.get(j + c).toInt()
                                    val mono = (sum / channels)
                                    monoBytes.write(mono and 0xFF)
                                    monoBytes.write((mono shr 8) and 0xFF)
                                    j += channels
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return resample(toShortArray(monoBytes.toByteArray()), srcRate, TARGET_RATE)
        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    private fun toShortArray(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    private fun resample(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (src.isEmpty() || srcRate == dstRate) return src
        val dstLen = (src.size.toLong() * dstRate / srcRate).toInt()
        val out = ShortArray(dstLen)
        val ratio = srcRate.toDouble() / dstRate
        for (i in out.indices) {
            val pos = i * ratio
            val lo = pos.toInt()
            val hi = min(lo + 1, src.size - 1)
            val frac = pos - lo
            out[i] = (src[lo] * (1 - frac) + src[hi] * frac).toInt().toShort()
        }
        return out
    }
}
