package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * BYO-key analyzer using the Gemini REST API (Files API upload + generateContent).
 * No SDK dependency — plain HttpURLConnection + org.json. The key is the user's own.
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash",
) : ClipAnalyzer {

    private val base = "https://generativelanguage.googleapis.com"
    private val chunkSize = 2 * 1024 * 1024 // 2 MB – multiple of 256 KB as required

    override suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(mediaUri) ?: defaultMime(kind)
        val size = context.contentResolver.openAssetFileDescriptor(mediaUri, "r")
            ?.use { it.length }
            ?: throw IllegalStateException("Could not determine media size.")

        val uploadUrl = startResumable(size, mime)
        var file = context.contentResolver.openInputStream(mediaUri)?.use { stream ->
            streamUpload(uploadUrl, stream, size)
        } ?: throw IllegalStateException("Could not read media.")

        var tries = 60
        while (file.state == "PROCESSING" && tries-- > 0) {
            delay(2000)
            file = getFile(file.name)
        }
        if (file.state != "ACTIVE") throw IllegalStateException("Gemini failed to process the media.")

        val text = generate(prompt, kind, file.uri, mime)
        parseSegments(text)
    }

    private data class GeminiFile(val name: String, val uri: String, val state: String)

    private fun startResumable(size: Long, mime: String): String {
        val conn = open("$base/upload/v1beta/files?key=$apiKey", "POST")
        conn.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        conn.setRequestProperty("X-Goog-Upload-Command", "start")
        conn.setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
        conn.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write("""{"file":{"display_name":"clip"}}""".toByteArray()) }
        if (conn.responseCode !in 200..299) error("upload start", conn)
        val url = conn.getHeaderField("x-goog-upload-url")
            ?: throw IllegalStateException("No upload URL returned by Gemini.")
        conn.disconnect()
        return url
    }

    /** Streams the file to the Gemini upload URL in 2 MB chunks to avoid OOM. */
    private fun streamUpload(uploadUrl: String, input: InputStream, totalSize: Long): GeminiFile {
        val buf = ByteArray(chunkSize)
        var offset = 0L
        while (offset < totalSize) {
            val toRead = minOf(totalSize - offset, chunkSize.toLong()).toInt()
            var filled = 0
            while (filled < toRead) {
                val n = input.read(buf, filled, toRead - filled)
                if (n == -1) break
                filled += n
            }
            val isLast = offset + filled >= totalSize
            val conn = open(uploadUrl, "POST")
            conn.setRequestProperty("Content-Length", filled.toString())
            conn.setRequestProperty("X-Goog-Upload-Offset", offset.toString())
            conn.setRequestProperty(
                "X-Goog-Upload-Command",
                if (isLast) "upload, finalize" else "upload",
            )
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(filled)
            conn.outputStream.use { it.write(buf, 0, filled) }
            if (conn.responseCode !in 200..299) error("upload chunk", conn)
            if (isLast) {
                val json = JSONObject(readBody(conn))
                conn.disconnect()
                val f = json.getJSONObject("file")
                return GeminiFile(f.getString("name"), f.optString("uri"), f.optString("state", "PROCESSING"))
            }
            conn.disconnect()
            offset += filled
        }
        throw IllegalStateException("Upload ended without finalization.")
    }

    private fun getFile(name: String): GeminiFile {
        val conn = open("$base/v1beta/$name?key=$apiKey", "GET")
        if (conn.responseCode !in 200..299) error("file status", conn)
        val f = JSONObject(readBody(conn))
        conn.disconnect()
        return GeminiFile(f.getString("name"), f.optString("uri"), f.optString("state", "PROCESSING"))
    }

    private fun generate(prompt: String, kind: MediaKind, fileUri: String, mime: String): String {
        val sys = systemPrompt(prompt, kind)
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray()
                    .put(JSONObject().put("text", sys))
                    .put(JSONObject().put("file_data", JSONObject().apply {
                        put("mime_type", mime)
                        put("file_uri", fileUri)
                    })))
            }))
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("response_schema", segmentSchema())
            })
        }
        val conn = open("$base/v1beta/models/$model:generateContent?key=$apiKey", "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) error("generate", conn)
        val json = JSONObject(readBody(conn))
        conn.disconnect()
        return json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }

    private fun parseSegments(text: String): List<EditSegment> {
        val arr = JSONArray(text)
        val out = mutableListOf<EditSegment>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val start = (o.optDouble("start", 0.0) * 1000).toLong()
            val end = (o.optDouble("end", 0.0) * 1000).toLong()
            val action = if (o.optString("action", "keep").equals("remove", true)) EditAction.REMOVE else EditAction.KEEP
            out += EditSegment(start, end, action, o.optString("reason", ""))
        }
        return out
    }

    private fun segmentSchema() = JSONObject().apply {
        put("type", "ARRAY")
        put("items", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("start", JSONObject().put("type", "NUMBER"))
                put("end", JSONObject().put("type", "NUMBER"))
                put("action", JSONObject().put("type", "STRING"))
                put("reason", JSONObject().put("type", "STRING"))
            })
            put("required", JSONArray().put("start").put("end").put("action").put("reason"))
        })
    }

    private fun systemPrompt(prompt: String, kind: MediaKind): String {
        val noun = if (kind == MediaKind.AUDIO) "audio" else "video"
        return """
            You are an expert AI $noun editor. Analyze the $noun and the user prompt.
            User Prompt: "$prompt"

            Identify segments to 'keep' or 'remove' based on the instructions.
            Return a JSON array; each object has: start (seconds), end (seconds),
            action ("keep" or "remove"), reason (string). Cover the relevant portions
            without overlaps; leave a little padding around key moments.
        """.trimIndent()
    }

    private fun defaultMime(kind: MediaKind) = when (kind) {
        MediaKind.AUDIO -> "audio/mp4"
        MediaKind.IMAGE -> "image/jpeg"
        MediaKind.VIDEO -> "video/mp4"
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
        }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    }

    private fun error(stage: String, conn: HttpURLConnection): Nothing {
        val msg = runCatching { readBody(conn) }.getOrNull().orEmpty().take(300)
        throw IllegalStateException("Gemini $stage failed (${conn.responseCode}): $msg")
    }
}
