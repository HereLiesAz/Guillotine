package com.hereliesaz.guillotine.ai.gen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tiny hand-rolled HTTP helper shared by all generation backends — mirrors the style already used in
 * `ImageGen.Leonardo` and the agent backends (no SDK, just `HttpURLConnection` + org.json). Every
 * call runs on [Dispatchers.IO].
 */
object GenHttp {

    /** JSON request returning the response body as text. Throws [GenException] on non-2xx. */
    suspend fun requestJson(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 30_000
            readTimeout = 120_000
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
        readOrThrow(conn, url)
    }

    /** Raw string request with an explicit content type (e.g. form-encoded). */
    suspend fun requestRaw(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
        body: ByteArray? = null,
    ): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 30_000
            readTimeout = 120_000
            if (body != null) {
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                doOutput = true
            }
        }
        if (body != null) conn.outputStream.use { it.write(body) }
        readOrThrow(conn, url)
    }

    /** POST a JSON body and read the response as raw bytes (image/audio/video). */
    suspend fun getBytesViaJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
    ): ByteArray = postBytes(url, headers + ("Content-Type" to "application/json"), body.toString().toByteArray())

    /** POST a multipart body and read the response as raw bytes. */
    suspend fun getBytesViaMultipart(
        url: String,
        headers: Map<String, String>,
        boundary: String,
        body: ByteArray,
    ): ByteArray = postBytes(url, headers + ("Content-Type" to "multipart/form-data; boundary=$boundary"), body)

    /** POST a multipart body and read the response as text. */
    suspend fun requestRawMultipart(
        url: String,
        headers: Map<String, String>,
        boundary: String,
        body: ByteArray,
    ): String = requestRaw("POST", url, headers, "multipart/form-data; boundary=$boundary", body)

    private suspend fun postBytes(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body) }
        val ok = conn.responseCode in 200..299
        if (!ok) {
            val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val code = conn.responseCode
            conn.disconnect()
            val host = runCatching { URL(url).host }.getOrNull() ?: url
            throw GenException("$host error ($code): ${err.take(300)}")
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        bytes
    }

    /** Download raw bytes (a generated image/audio/video). */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = 30_000
                readTimeout = 180_000
            }
            val ok = conn.responseCode in 200..299
            if (!ok) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                conn.disconnect()
                throw GenException("Download failed (${conn.responseCode}): ${err.take(200)}")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            bytes
        }

    private fun readOrThrow(conn: HttpURLConnection, url: String): String {
        val ok = conn.responseCode in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        val code = conn.responseCode
        conn.disconnect()
        if (!ok) {
            val host = runCatching { URL(url).host }.getOrNull() ?: url
            throw GenException("$host error ($code): ${text.take(300)}")
        }
        return text
    }

    fun bearer(key: String): Map<String, String> = mapOf("Authorization" to "Bearer $key")

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
