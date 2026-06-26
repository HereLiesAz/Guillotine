package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.mcp.McpTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent brain backed by Gemini's `generateContent` function-calling. Maps the MCP tool defs to
 * Gemini `function_declarations` (JSON-Schema types upper-cased to the OpenAPI subset Gemini
 * expects) and runs the functionCall ⇄ functionResponse loop.
 */
class GeminiAgentBackend(
    private val apiKey: String,
    private val model: String,
) : AgentBackend {

    private val base = "https://generativelanguage.googleapis.com"

    override suspend fun run(
        instruction: String,
        tools: McpTools,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val toolBlock = JSONArray().put(
                JSONObject().put("function_declarations", geminiTools(tools.definitions())),
            )
            val contents = JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", instruction))),
            )

            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                val body = JSONObject().apply {
                    put("system_instruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", AGENT_SYSTEM_PROMPT)),
                    ))
                    put("contents", contents)
                    put("tools", toolBlock)
                }
                val resp = post(body)
                val candidate = resp.optJSONArray("candidates")?.optJSONObject(0)
                val modelContent = candidate?.optJSONObject("content")
                val parts = modelContent?.optJSONArray("parts") ?: JSONArray()

                val text = StringBuilder()
                val calls = mutableListOf<JSONObject>()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    part.optJSONObject("functionCall")?.let { calls.add(it) }
                    part.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it) }
                }
                if (text.isNotBlank()) onEvent(AgentEvent.AssistantText(text.toString().trim()))

                // Echo the model turn back (role "model") so the function call round-trips.
                if (modelContent != null) {
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }

                if (calls.isNotEmpty()) {
                    val responseParts = JSONArray()
                    for (call in calls) {
                        val name = call.optString("name")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        onEvent(AgentEvent.ToolStarted(name))
                        val outcome = callTool(tools, name, args)
                        onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
                        responseParts.put(JSONObject().put("functionResponse", JSONObject().apply {
                            put("name", name)
                            put("response", outcome.json)
                        }))
                    }
                    contents.put(JSONObject().put("role", "user").put("parts", responseParts))
                    continue
                }

                onEvent(AgentEvent.Done(text.toString().trim().ifBlank { "Done." }))
                return@withContext
            }
            onEvent(AgentEvent.Failed("Stopped after $MAX_AGENT_ITERATIONS steps."))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onEvent(AgentEvent.Failed(e.message ?: "Gemini agent failed"))
        }
    }

    private fun geminiTools(defs: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            put(JSONObject().apply {
                put("name", d.getString("name"))
                put("description", d.optString("description"))
                put("parameters", toGeminiSchema(d.getJSONObject("inputSchema")))
            })
        }
    }

    /** Recursively convert a JSON Schema to Gemini's Schema (types upper-cased, supported keys only). */
    private fun toGeminiSchema(schema: JSONObject): JSONObject = JSONObject().apply {
        schema.optString("type").takeIf { it.isNotEmpty() }?.let { put("type", it.uppercase()) }
        schema.optString("description").takeIf { it.isNotEmpty() }?.let { put("description", it) }
        schema.optJSONArray("enum")?.let { put("enum", it) }
        schema.optJSONObject("properties")?.let { props ->
            val out = JSONObject()
            props.keys().forEach { k -> out.put(k, toGeminiSchema(props.getJSONObject(k))) }
            put("properties", out)
        }
        schema.optJSONArray("required")?.let { put("required", it) }
        schema.optJSONObject("items")?.let { put("items", toGeminiSchema(it)) }
    }

    private fun post(body: JSONObject): JSONObject {
        val endpoint = "$base/v1beta/models/$model:generateContent?key=$apiKey"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            if (code == 400 || code == 403) throw IllegalStateException("Check your Gemini API key in Settings.")
            throw IllegalStateException("Gemini request failed ($code): ${text.take(300)}")
        }
        return JSONObject(text)
    }
}
