package com.hereliesaz.guillotine.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the live list of models a source currently offers, so the user picks from a
 * dropdown instead of typing an id. Every call is best-effort: any failure (no key, network,
 * unexpected shape) returns an empty list and the UI falls back to a default/curated value.
 */
object ModelCatalog {

    /** Model ids for the selected analyzer [provider]; empty for free providers or on failure. */
    suspend fun analyzerModels(provider: AiProviderType, apiKey: String): List<String> =
        withContext(Dispatchers.IO) { runCatching { fetchAnalyzer(provider, apiKey) }.getOrDefault(emptyList()) }

    /** Leonardo platform models (id+name); empty → caller falls back to the curated list. */
    suspend fun leonardoModels(apiKey: String): List<ImageGen.LeonardoModel> =
        withContext(Dispatchers.IO) { runCatching { fetchLeonardo(apiKey) }.getOrDefault(emptyList()) }

    private fun fetchAnalyzer(provider: AiProviderType, apiKey: String): List<String> = when (provider) {
        AiProviderType.OPENAI -> openAiStyle("https://api.openai.com/v1/models", bearer(apiKey))
        AiProviderType.OPENROUTER -> openAiStyle("https://openrouter.ai/api/v1/models", bearer(apiKey))
        AiProviderType.GROQ -> openAiStyle("https://api.groq.com/openai/v1/models", bearer(apiKey))
        AiProviderType.XAI -> openAiStyle("https://api.x.ai/v1/models", bearer(apiKey))
        AiProviderType.MISTRAL -> openAiStyle("https://api.mistral.ai/v1/models", bearer(apiKey))
        AiProviderType.ANTHROPIC -> openAiStyle(
            "https://api.anthropic.com/v1/models",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
        )
        AiProviderType.GEMINI -> gemini(apiKey)
        else -> emptyList() // LOCAL, MLKIT — no remote model list
    }

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")

    /** OpenAI-shaped: { "data": [ { "id": "..." } ] } (also matches Anthropic's /v1/models). */
    private fun openAiStyle(url: String, headers: Map<String, String>): List<String> {
        val data = JSONObject(get(url, headers)).optJSONArray("data") ?: return emptyList()
        return (0 until data.length())
            .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
            .distinct().sorted()
    }

    /** Gemini: { "models": [ { "name": "models/gemini-..." } ] }. */
    private fun gemini(apiKey: String): List<String> {
        val arr = JSONObject(get("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey", emptyMap()))
            .optJSONArray("models") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("name")?.removePrefix("models/")?.takeIf { s -> s.isNotBlank() } }
            .distinct().sorted()
    }

    private fun fetchLeonardo(apiKey: String): List<ImageGen.LeonardoModel> {
        val json = JSONObject(get("https://cloud.leonardo.ai/api/rest/v1/platformModels", bearer(apiKey)))
        val arr = json.optJSONArray("custom_models") ?: json.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else ImageGen.LeonardoModel(id, o.optString("name").ifBlank { id })
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        val ok = conn.responseCode in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        val code = conn.responseCode
        conn.disconnect()
        if (!ok) throw IllegalStateException("model list fetch failed ($code)")
        return text
    }
}
