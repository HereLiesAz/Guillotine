package com.hereliesaz.guillotine.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Desktop port of Leonardo.ai cloud generation (BYO API key). */
object DesktopImageGen {

    object Leonardo {
        private const val BASE = "https://cloud.leonardo.ai/api/rest/v1"

        suspend fun generate(
            apiKey: String,
            modelId: String,
            prompt: String,
            width: Int = 1280,
            height: Int = 720,
        ): String = withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            require(key.isNotEmpty()) { "Add your Leonardo API key in Settings to generate with Leonardo." }

            val body = JSONObject().apply {
                put("prompt", prompt)
                if (modelId.isNotBlank()) put("modelId", modelId)
                put("width", width)
                put("height", height)
                put("num_images", 1)
            }
            val created = request("POST", "$BASE/generations", key, body)
            val generationId = JSONObject(created)
                .optJSONObject("sdGenerationJob")?.optString("generationId").orEmpty()
            if (generationId.isEmpty()) throw IllegalStateException("Leonardo did not return a generation id.")

            repeat(90) {
                delay(2_000)
                val pollText = request("GET", "$BASE/generations/$generationId", key, null)
                val pk = JSONObject(pollText).optJSONObject("generations_by_pk") ?: return@repeat
                when (pk.optString("status")) {
                    "COMPLETE" -> {
                        val imgs = pk.optJSONArray("generated_images")
                        val url = if (imgs != null && imgs.length() > 0) imgs.getJSONObject(0).optString("url") else ""
                        if (url.isBlank()) throw IllegalStateException("Leonardo returned no image.")
                        return@withContext download(url)
                    }
                    "FAILED" -> throw IllegalStateException("Leonardo generation failed.")
                }
            }
            throw IllegalStateException("Leonardo generation timed out.")
        }

        private fun request(method: String, urlStr: String, apiKey: String, body: JSONObject?): String {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 30_000
                readTimeout = 60_000
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val code = conn.responseCode
            conn.disconnect()
            if (!ok) throw IllegalStateException("Leonardo API error ($code): ${text.take(300)}")
            return text
        }

        private suspend fun download(url: String): String {
            return DesktopGenSink().saveUrl(url, "png")
        }
    }
}
