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
 * BYO-key Anthropic analyzer. Samples frames and sends them to the Messages API
 * as base64 image blocks for vision analysis. Audio is not natively supported by
 * the vision model, so audio clips are rejected with a clear message pointing to
 * Gemini / OpenAI / the free Local analyzer.
 *
 * Note: current Claude models reject assistant prefills, so we ask for JSON in the
 * prompt and parse the text response rather than prefilling.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String = "claude-opus-4-8",
) : ClipAnalyzer {

    private val url = "https://api.anthropic.com/v1/messages"
    private val version = "2023-06-01"

    override suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        if (kind == MediaKind.AUDIO) {
            throw IllegalStateException("Anthropic can't analyze audio. Use Gemini, OpenAI, or the free Local analyzer for audio clips.")
        }
        val frames = FrameSampler.sample(context, mediaUri, kind, durationMs)
        if (frames.isEmpty()) throw IllegalStateException("Could not read frames for Anthropic analysis.")

        val durSec = durationMs / 1000.0
        val content = JSONArray()
        content.put(JSONObject().apply { put("type", "text"); put("text", instructions(prompt, durSec)) })
        for (f in frames) {
            content.put(JSONObject().apply { put("type", "text"); put("text", "[t=${"%.1f".format(f.timeMs / 1000.0)}s]") })
            content.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", f.jpegBase64)
                })
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }
        SegmentJson.parse(send(body))
    }

    private fun instructions(prompt: String, durSec: Double): String = """
        You are an expert video editor. The user instruction is: "$prompt".
        You are shown frames sampled from a clip that is ${"%.1f".format(durSec)} seconds long,
        each labeled with its timestamp. Decide which time ranges to keep or remove.
        Respond with a JSON array ONLY (no prose, no code fences):
        [{"start":<seconds>,"end":<seconds>,"action":"keep"|"remove","reason":"<short>"}]
        Cover the clip from 0 to ${"%.1f".format(durSec)} without overlaps.
    """.trimIndent()

    private fun send(body: JSONObject): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", version)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) {
            val msg = runCatching { readBody(conn) }.getOrNull().orEmpty().take(300)
            throw IllegalStateException("Anthropic request failed (${conn.responseCode}): $msg")
        }
        val json = JSONObject(readBody(conn))
        conn.disconnect()
        // content: [{type:"text", text:"..."}]
        val blocks = json.getJSONArray("content")
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "text") return b.getString("text")
        }
        return "[]"
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    }
}
