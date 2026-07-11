package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.ai.TranscriptCue
import com.hereliesaz.guillotine.ai.WordCue
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.min

/**
 * On-device speech-to-text via Vosk (no key, no network) for the desktop (JVM) build — mirrors
 * `:app`'s [com.hereliesaz.guillotine.ai.VoskTranscriber]. The user supplies a model directory once
 * (Settings → Transcription → speech model path); 16 kHz mono PCM is fed to the recognizer, which
 * emits word timings grouped into [TranscriptCue]s (with per-word [WordCue]s for animated captions).
 *
 * Unlike Android (which decodes via MediaCodec), the audio is decoded up front by
 * [DesktopMediaDecoder.decodePcmMono] (FFmpeg resamples to 16 kHz mono) and passed in as normalized
 * floats, which this converts to the 16-bit PCM Vosk expects. Pure JVM; nothing leaves the machine.
 */
object DesktopVoskTranscriber {

    private const val TARGET_RATE = 16_000

    /**
     * Transcribe [samples16k] — normalized mono PCM in [-1, 1] that MUST already be at 16 kHz (decode
     * via `DesktopMediaDecoder.decodePcmMono(uri, 16_000)`) — using the Vosk model directory at
     * [modelPath]. Returns timed cues (possibly empty). Throws if the model can't be loaded.
     */
    fun transcribe(modelPath: String, samples16k: FloatArray): List<TranscriptCue> {
        // Vosk wants 16-bit signed PCM. Convert from normalized floats.
        val pcm = ShortArray(samples16k.size) { i ->
            (samples16k[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        // Model + Recognizer are native-backed AutoCloseables — .use frees them even if a chunk
        // throws. Reuse a single 4000-sample buffer instead of allocating per iteration.
        return Model(modelPath).use { model ->
            Recognizer(model, TARGET_RATE.toFloat()).use { rec ->
                rec.setWords(true)
                val cues = mutableListOf<TranscriptCue>()
                val chunk = 4000
                val buf = ShortArray(chunk)
                var i = 0
                while (i < pcm.size) {
                    val len = min(chunk, pcm.size - i)
                    pcm.copyInto(buf, 0, i, i + len)
                    if (rec.acceptWaveForm(buf, len)) parseResult(rec.result)?.let { cues += it }
                    i += len
                }
                parseResult(rec.finalResult)?.let { cues += it }
                cues
            }
        }
    }

    /** Parse one Vosk JSON result (`{"result":[{word,start,end},…]}`) into a [TranscriptCue]. */
    private fun parseResult(json: String): TranscriptCue? {
        val arr = JSONObject(json).optJSONArray("result") ?: return null
        if (arr.length() == 0) return null
        var start = Double.MAX_VALUE
        var end = 0.0
        val sb = StringBuilder()
        val wordCues = mutableListOf<WordCue>()
        for (k in 0 until arr.length()) {
            val w = arr.getJSONObject(k)
            val ws = w.optDouble("start")
            val we = w.optDouble("end")
            val wt = w.optString("word")
            start = min(start, ws)
            end = max(end, we)
            sb.append(wt).append(' ')
            if (wt.isNotBlank()) {
                wordCues += WordCue(wt, (ws * 1000).toLong(), (we * 1000).toLong())
            }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) return null
        return TranscriptCue((start * 1000).toLong(), (end * 1000).toLong(), text, wordCues)
    }
}
