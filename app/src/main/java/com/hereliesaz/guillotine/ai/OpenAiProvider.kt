package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * BYO-key OpenAI analyzer. Video/image is analyzed by sampling frames and sending
 * them to a vision model (gpt-4o); audio is transcribed with Whisper and the
 * transcript is classified into keep/remove ranges. Plain HttpURLConnection + org.json.
 */
class OpenAiProvider(
    private val apiKey: String,
    private val visionModel: String = "gpt-4o",
) : ClipAnalyzer {

    private val chatUrl = "https://api.openai.com/v1/chat/completions"
    private val audioUrl = "https://api.openai.com/v1/audio/transcriptions"

    override suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        if (kind == MediaKind.AUDIO) analyzeAudio(context, mediaUri, prompt, durationMs)
        else analyzeFrames(context, mediaUri, kind, prompt, durationMs)
    }

    private fun analyzeFrames(context: Context, uri: Uri, kind: MediaKind, prompt: String, durationMs: Long): List<EditSegment> {
        val frames = FrameSampler.sample(context, uri, kind, durationMs)
        if (frames.isEmpty()) throw IllegalStateException("Could not read frames for OpenAI analysis.")

        val durSec = durationMs / 1000.0
        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", instructions(prompt, durSec))
        })
        for (f in frames) {
            content.put(JSONObject().apply { put("type", "text"); put("text", "[t=${"%.1f".format(f.timeMs / 1000.0)}s]") })
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${f.jpegBase64}"))
            })
        }

        val body = JSONObject().apply {
            put("model", visionModel)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }
        return SegmentJson.parse(chat(body))
    }

    private fun analyzeAudio(context: Context, uri: Uri, prompt: String, durationMs: Long): List<EditSegment> {
        val transcript = transcribe(context, uri)
        val durSec = durationMs / 1000.0
        val body = JSONObject().apply {
            put("model", visionModel)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "${instructions(prompt, durSec)}\n\nTranscript segments (start-end seconds):\n$transcript")
            }))
        }
        return SegmentJson.parse(chat(body))
    }

    private fun instructions(prompt: String, durSec: Double): String = """
        You are an expert video/audio editor. The user instruction is: "$prompt".
        The clip is ${"%.1f".format(durSec)} seconds long.
        Decide which time ranges to keep or remove to satisfy the instruction.
        Respond with JSON only, no prose:
        {"segments":[{"start":<seconds>,"end":<seconds>,"action":"keep"|"remove","reason":"<short>"}]}
        Cover the clip from 0 to ${"%.1f".format(durSec)} without overlaps.
    """.trimIndent()

    private fun chat(body: JSONObject): String {
        val conn = open(chatUrl)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) fail("chat", conn)
        val json = JSONObject(readBody(conn))
        conn.disconnect()
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    /** Whisper transcription → "start-end: text" lines. */
    private fun transcribe(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not read audio for OpenAI transcription.")
        val boundary = "----guillotine${System.nanoTime()}"
        val conn = open(audioUrl)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
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
        if (conn.responseCode !in 200..299) fail("transcription", conn)
        val json = JSONObject(readBody(conn))
        conn.disconnect()
        val segs = json.optJSONArray("segments") ?: return json.optString("text")
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            sb.append("${"%.1f".format(s.optDouble("start"))}-${"%.1f".format(s.optDouble("end"))}: ${s.optString("text").trim()}\n")
        }
        return sb.toString()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 30_000
            readTimeout = 120_000
        }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    }

    private fun fail(stage: String, conn: HttpURLConnection): Nothing {
        val msg = runCatching { readBody(conn) }.getOrNull().orEmpty().take(300)
        throw IllegalStateException("OpenAI $stage failed (${conn.responseCode}): $msg")
    }
}
