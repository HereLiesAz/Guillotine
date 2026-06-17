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
import java.net.HttpURLConnection
import java.net.URL

/**
 * BYO-key analyzer for any OpenAI-compatible chat-completions endpoint (OpenRouter,
 * Groq, xAI, Mistral, …). Video/image clips are analyzed by sampling frames and sending
 * them to the configured vision [model]. These endpoints don't offer Whisper-style
 * transcription, so audio clips are routed elsewhere (OpenAI, Gemini, or free Local).
 *
 * Unlike [OpenAiProvider] this omits `response_format` (not universally supported) and
 * relies on the prompt + [SegmentJson]'s tolerant parsing instead.
 */
class OpenAiCompatibleProvider(
    private val apiKey: String,
    private val endpoint: String,
    private val model: String,
    private val label: String,
) : ClipAnalyzer {

    override suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
        onProgress: (AnalysisProgress) -> Unit,
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        require(kind != MediaKind.AUDIO) {
            "$label analyzes video and images. For audio, use OpenAI, Gemini, or the free Local analyzer."
        }
        onProgress(AnalysisProgress("Sampling frames\u2026"))
        val frames = FrameSampler.sample(context, mediaUri, kind, durationMs)
        if (frames.isEmpty()) throw IllegalStateException("Could not read frames for $label analysis.")
        onProgress(AnalysisProgress("Analyzing\u2026"))

        val durSec = durationMs / 1000.0
        val content = JSONArray()
        content.put(JSONObject().apply { put("type", "text"); put("text", instructions(prompt, durSec)) })
        for (f in frames) {
            content.put(JSONObject().apply { put("type", "text"); put("text", "[t=${"%.1f".format(f.timeMs / 1000.0)}s]") })
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${f.jpegBase64}"))
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }
        SegmentJson.parse(chat(body))
    }

    private fun instructions(prompt: String, durSec: Double): String = """
        You are an expert video editor. The user instruction is: "$prompt".
        The clip is ${"%.1f".format(durSec)} seconds long.
        Decide which time ranges to keep or remove to satisfy the instruction.
        Respond with JSON only, no prose:
        {"segments":[{"start":<seconds>,"end":<seconds>,"action":"keep"|"remove","reason":"<short>"}]}
        Cover the clip from 0 to ${"%.1f".format(durSec)} without overlaps.
    """.trimIndent()

    private fun chat(body: JSONObject): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) {
            val msg = runCatching { readBody(conn) }.getOrNull().orEmpty().take(300)
            throw IllegalStateException("$label request failed (${conn.responseCode}): $msg")
        }
        val json = JSONObject(readBody(conn))
        conn.disconnect()
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    }
}
