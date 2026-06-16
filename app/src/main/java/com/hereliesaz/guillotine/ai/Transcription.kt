package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A timed transcript line: start/end in source-media milliseconds plus the spoken text. */
data class TranscriptCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Speech-to-text → timed cues, used to generate grouped text/caption clips. Currently backed
 * by OpenAI Whisper (BYO key) — the same engine the OpenAI analyzer uses for audio. An
 * on-device engine can implement the same [TranscriptCue] output later without touching callers.
 */
object Transcription {

    suspend fun transcribe(context: Context, settings: AiSettings, uri: Uri): List<TranscriptCue> {
        val key = settings.keyFor(AiProviderType.OPENAI)
        require(key.isNotBlank()) {
            "Transcription needs an OpenAI key (Settings → OpenAI). On-device speech is on the roadmap."
        }
        return whisper(context, key, uri)
    }

    private suspend fun whisper(context: Context, apiKey: String, uri: Uri): List<TranscriptCue> =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not read media for transcription.")
            val boundary = "----guillotine${System.nanoTime()}"
            val conn = (URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 30_000
                readTimeout = 180_000
                doOutput = true
            }
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
                out.write(bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val ok = conn.responseCode in 200..299
            val body = (if (ok) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
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
