package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Image-clip generators: Pollinations is the free, no-key default; Leonardo.ai is BYO key. */
object ImageGen {

    /** Free, no-key generation: Pollinations serves the image directly from a prompt URL. */
    object Pollinations {
        fun url(prompt: String, width: Int = 1280, height: Int = 720): String {
            val encoded = URLEncoder.encode(prompt.trim(), "UTF-8")
            return "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height&nologo=true"
        }
    }

    /** A Leonardo.ai platform model the user can pick to generate with. */
    data class LeonardoModel(val id: String, val name: String)

    /**
     * Leonardo.ai cloud generation (BYO API key from https://app.leonardo.ai → Settings → API
     * Access). Generation is async: create a job, poll until COMPLETE, then download the first
     * image to a cache file. The model is chosen by id from [LeonardoModels].
     */
    object Leonardo {
        private const val BASE = "https://cloud.leonardo.ai/api/rest/v1"

        suspend fun generate(
            context: Context,
            apiKey: String,
            modelId: String,
            prompt: String,
            width: Int = 1280,
            height: Int = 720,
        ): Uri = withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            require(key.isNotEmpty()) { "Add your Leonardo API key in Settings to generate with Leonardo." }

            // 1. Kick off the generation.
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

            // 2. Poll until the images are ready (Leonardo is async; ~seconds to a couple minutes).
            repeat(90) {
                delay(2_000)
                val pollText = request("GET", "$BASE/generations/$generationId", key, null)
                val pk = JSONObject(pollText).optJSONObject("generations_by_pk") ?: return@repeat
                when (pk.optString("status")) {
                    "COMPLETE" -> {
                        val imgs = pk.optJSONArray("generated_images")
                        val url = if (imgs != null && imgs.length() > 0) imgs.getJSONObject(0).optString("url") else ""
                        if (url.isBlank()) throw IllegalStateException("Leonardo returned no image.")
                        return@withContext download(context, url)
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

        private fun download(context: Context, url: String): Uri {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 120_000
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val file = java.io.File(context.cacheDir, "leonardo_${System.currentTimeMillis()}.png")
            file.outputStream().use { it.write(bytes) }
            return Uri.fromFile(file)
        }
    }

    /**
     * Curated Leonardo platform models (id → display name). These are Leonardo's featured
     * models spanning photoreal, FLUX, anime, and fast/SDXL pipelines; the first is the default.
     */
    val LeonardoModels: List<LeonardoModel> = listOf(
        LeonardoModel("de7d3faf-762f-48e0-b3b7-9d0ac3a3fcf3", "Leonardo Phoenix 1.0"),
        LeonardoModel("6b645e3a-d64f-4341-a6d8-7a3690fbf042", "Leonardo Phoenix 0.9"),
        LeonardoModel("b2614463-296c-462a-9586-aafdb8f00e36", "FLUX.1 Dev (Precision)"),
        LeonardoModel("1dd50843-d653-4516-a8e3-f0238ee453ff", "FLUX.1 Schnell (Speed)"),
        LeonardoModel("b24e16ff-06e3-43eb-8d33-4416c2d75876", "Leonardo Lightning XL"),
        LeonardoModel("e71a1c2f-4f80-4800-934f-2c68979d8cc8", "Leonardo Anime XL"),
        LeonardoModel("aa77f04e-3eec-4034-9c07-d0f619684628", "Leonardo Kino XL (cinematic)"),
        LeonardoModel("5c232a9e-9061-4777-980a-ddc8e65647c6", "Leonardo Vision XL"),
        LeonardoModel("1e60896f-3c26-4296-8ecc-53e2afecc132", "Leonardo Diffusion XL"),
        LeonardoModel("2067ae52-33fd-4a82-bb92-c2c55e7d2786", "AlbedoBase XL"),
        LeonardoModel("16e7060a-803e-4df3-97ee-edcfa5dc9cc8", "SDXL 1.0"),
    )

    val LeonardoDefaultModel: String = LeonardoModels.first().id
}
