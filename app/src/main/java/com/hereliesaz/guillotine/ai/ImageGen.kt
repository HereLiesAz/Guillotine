package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Image-clip generators. Pollinations is the free, no-config default; Fooocus is BYO-host. */
object ImageGen {

    /** Free, no-key generation: Pollinations serves the image directly from a prompt URL. */
    object Pollinations {
        fun url(prompt: String, width: Int = 1280, height: Int = 720): String {
            val encoded = URLEncoder.encode(prompt.trim(), "UTF-8")
            return "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height&nologo=true"
        }
    }

    /**
     * Self-hosted [Fooocus-API](https://github.com/mrhan1993/Fooocus-API). The user runs it
     * on a machine with a GPU and points the app at its base URL (e.g. http://192.168.0.10:8888).
     * We call the synchronous text-to-image endpoint, decode the returned base64 PNG, and cache
     * it to a local file whose [Uri] is used as the image clip's source.
     */
    object FooocusApi {
        suspend fun generate(context: Context, baseUrl: String, prompt: String): Uri = withContext(Dispatchers.IO) {
            val root = baseUrl.trim().trimEnd('/')
            require(root.isNotEmpty()) { "Set your Fooocus-API URL in Settings to generate with Fooocus." }

            val body = JSONObject().apply {
                put("prompt", prompt)
                put("async_process", false)
                put("require_base64", true)
                put("image_number", 1)
            }
            val conn = (URL("$root/v2/generation/text-to-image").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 30_000
                readTimeout = 300_000 // generation can take minutes
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
            if (!ok) throw IllegalStateException("Fooocus-API failed (${conn.responseCode}): ${text.take(300)}")

            val arr = JSONArray(text)
            if (arr.length() == 0) throw IllegalStateException("Fooocus-API returned no image.")
            val first = arr.getJSONObject(0)

            // Prefer inline base64; fall back to a served URL if that's all the host returns.
            val b64 = first.optString("base64")
            if (b64.isEmpty()) {
                val url = first.optString("url")
                if (url.isNotEmpty()) {
                    return@withContext Uri.parse(if (url.startsWith("http")) url else "$root/${url.trimStart('/')}")
                }
                throw IllegalStateException("Fooocus-API response contained no image data.")
            }
            val clean = b64.substringAfter("base64,", b64) // tolerate a data: prefix
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            val file = File(context.cacheDir, "fooocus_${System.currentTimeMillis()}.png")
            file.outputStream().use { it.write(bytes) }
            Uri.fromFile(file)
        }
    }
}
