package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech-to-text → timed cues, used to generate grouped text/caption clips. Tries three engines in
 * order: the on-device Vosk engine ([VoskTranscriber]) when a model directory is set (Settings →
 * Transcription), then the on-device sherpa-onnx offline Whisper model ([SherpaAsr]) when
 * `asrModelPath` is set (Settings → AI Analyzer → Speech (ASR)), then cloud OpenAI Whisper (BYO key)
 * — the same engine the OpenAI analyzer uses for audio.
 *
 * [VoskTranscriber] shipped fully implemented but was never called from here — `transcribe_clip` and
 * `animated_transcribe_clip` always required an OpenAI key even though their own tool descriptions
 * (and the Transcription tab's model-path field) advertised "on-device Vosk or cloud Whisper."
 *
 * The sherpa-onnx fallback closes a real dead end: unlike Vosk, `asrModelPath`'s sherpa Whisper
 * model already has a one-tap in-app download (the Model Manager's ASR category, backed by
 * `RECOMMENDED_ASR_MODELS`) — Vosk has no catalog entry or download path anywhere in the app, only a
 * manual "browse to a folder you already have" field, so a user told "install a Vosk model" with
 * nothing telling them where to get one had a genuine dead end. `transcribe_precise`/`remove_fillers`
 * (McpTools) already used sherpa-onnx this way; this reuses the identical decode pipeline
 * (`PcmDecoder` + `YamnetClassifier.resampleTo16k`) so `transcribe_clip`'s caption path gets the same
 * already-downloadable on-device option instead of only Vosk-or-cloud.
 */
object Transcription {

    suspend fun transcribe(context: Context, settings: AiSettings, uri: Uri): List<TranscriptCue> {
        val voskPath = settings.speechModelPath
        // Existence-checked, not just non-blank: a restored settings backup, a since-cleared import
        // dir, or app storage wiped externally would otherwise route every transcription into a
        // Vosk Model() constructor call that can only fail, even for a user with a working OpenAI
        // key configured and no idea their Vosk path went stale.
        if (voskPath.isNotBlank() && File(voskPath).exists()) {
            return VoskTranscriber.transcribe(context, voskPath, uri.toString())
        }
        val asrPath = settings.asrModelPath
        if (asrPath.isNotBlank() && File(asrPath).exists()) {
            val cues = withContext(Dispatchers.IO) { transcribeSherpa(context, asrPath, uri) }
            if (cues != null) return cues
        }
        val key = settings.keyFor(AiProviderType.OPENAI)
        require(key.isNotBlank()) {
            "Transcription needs an on-device model — Vosk (Settings → Transcription) or Whisper " +
                "(Settings → AI Analyzer → Speech (ASR), one-tap download) — or an OpenAI key."
        }
        return whisper(context, key, uri)
    }

    /** Null (not empty) if [uri] has no audio track at all, so the caller still falls through to cloud. */
    private fun transcribeSherpa(context: Context, modelDir: String, uri: Uri): List<TranscriptCue>? {
        val pcm = PcmDecoder.decode(context, uri, com.hereliesaz.guillotine.ai.tflite.YamnetClassifier.SAMPLE_RATE) ?: return null
        val samples = com.hereliesaz.guillotine.ai.tflite.YamnetClassifier.resampleTo16k(pcm.samples, pcm.sampleRate)
        return groupSherpaWordsIntoCues(SherpaAsr.transcribeWords(modelDir, samples))
    }

    /**
     * Sherpa's offline recognizer decodes a whole clip in one pass with no per-utterance boundaries the
     * way Vosk's streaming recognizer emits them — so cues are reconstructed from word timings via a
     * pause heuristic: a gap of more than [PAUSE_GAP_MS] between two consecutive words starts a new cue.
     */
    private fun groupSherpaWordsIntoCues(words: List<SherpaAsr.Word>): List<TranscriptCue> {
        if (words.isEmpty()) return emptyList()
        val cues = mutableListOf<TranscriptCue>()
        var bucket = mutableListOf<SherpaAsr.Word>()
        fun flush() {
            if (bucket.isEmpty()) return
            val text = bucket.joinToString(" ") { it.text }.trim()
            if (text.isNotEmpty()) {
                cues += TranscriptCue(
                    bucket.first().startMs,
                    bucket.last().endMs,
                    text,
                    bucket.map { WordCue(it.text, it.startMs, it.endMs) },
                )
            }
            bucket = mutableListOf()
        }
        for (w in words) {
            if (bucket.isNotEmpty() && w.startMs - bucket.last().endMs > PAUSE_GAP_MS) flush()
            bucket.add(w)
        }
        flush()
        return cues
    }

    private const val PAUSE_GAP_MS = 700L

    private suspend fun whisper(context: Context, apiKey: String, uri: Uri): List<TranscriptCue> =
        withContext(Dispatchers.IO) {
            val boundary = "----guillotine${System.nanoTime()}"
            val conn = (URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 30_000
                readTimeout = 180_000
                doOutput = true
                setChunkedStreamingMode(2 * 1024 * 1024) // stream in 2 MB chunks to avoid OOM
            }
            val (ok, body) = try {
                DataOutputStream(conn.outputStream).use { out ->
                    fun field(name: String, value: String) {
                        out.writeBytes("--$boundary\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        out.writeBytes("$value\r\n")
                    }
                    field("model", "whisper-1")
                    field("response_format", "verbose_json")
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.mp4\"\r\n")
                    out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                    // Stream the source straight through rather than buffering the whole file in memory.
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    } ?: throw IllegalStateException("Could not read media for transcription.")
                    out.writeBytes("\r\n--$boundary--\r\n")
                }
                val ok = conn.responseCode in 200..299
                val body = (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                ok to body
            } finally {
                // Always release the connection — the old code called disconnect() only on the
                // happy path, so a throw from the multipart write (e.g. null input stream) leaked it.
                conn.disconnect()
            }
            if (!ok) throw IllegalStateException("Transcription failed (${conn.responseCode}): ${body.take(300)}")

            val json = JSONObject(body)
            val segs = json.optJSONArray("segments")
                ?: return@withContext listOf(TranscriptCue(0, 0, json.optString("text").trim()))
            buildList {
                for (i in 0 until segs.length()) {
                    val s = segs.getJSONObject(i)
                    val text = s.optString("text").trim()
                    if (text.isEmpty()) continue
                    add(
                        TranscriptCue(
                            startMs = (s.optDouble("start") * 1000).toLong(),
                            endMs = (s.optDouble("end") * 1000).toLong(),
                            text = text,
                        ),
                    )
                }
            }
        }
}
