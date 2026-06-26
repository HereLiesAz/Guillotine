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
 * Agent brain backed by any OpenAI-compatible Chat Completions endpoint with tool-calling —
 * OpenAI itself plus OpenRouter / Groq / xAI / Mistral (the [endpoint] is supplied by the
 * factory). Runs the `tool_calls` ⇄ `role:"tool"` loop.
 */
class OpenAiAgentBackend(
    private val apiKey: String,
    private val endpoint: String,
    private val model: String,
    private val label: String,
) : AgentBackend {

    override suspend fun run(
        instruction: String,
        tools: McpTools,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val toolDefs = openAiTools(tools.definitions())
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", AGENT_SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", instruction))

            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("tools", toolDefs)
                }
                val resp = post(body)
                val message = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val toolCalls = message.optJSONArray("tool_calls")
                val text = message.optString("content").takeIf { it.isNotBlank() && it != "null" }
                if (text != null) onEvent(AgentEvent.AssistantText(text.trim()))

                // Append the assistant message verbatim (carries tool_calls the API must see echoed).
                messages.put(message)

                if (toolCalls != null && toolCalls.length() > 0) {
                    for (j in 0 until toolCalls.length()) {
                        val tc = toolCalls.getJSONObject(j)
                        val fn = tc.getJSONObject("function")
                        val name = fn.getString("name")
                        val args = runCatching {
                            JSONObject(fn.optString("arguments").ifBlank { "{}" })
                        }.getOrDefault(JSONObject())
                        onEvent(AgentEvent.ToolStarted(name))
                        val outcome = callTool(tools, name, args)
                        onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.optString("id"))
                            put("content", outcome.content())
                        })
                    }
                    continue
                }

                onEvent(AgentEvent.Done((text ?: "").trim().ifBlank { "Done." }))
                return@withContext
            }
            onEvent(AgentEvent.Failed("Stopped after $MAX_AGENT_ITERATIONS steps."))
        } catch (e: Exception) {
            onEvent(AgentEvent.Failed(e.message ?: "$label agent failed"))
        }
    }

    /** Map MCP tool defs to OpenAI's shape: `{type:"function", function:{name, description, parameters}}`. */
    private fun openAiTools(defs: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", d.getString("name"))
                    put("description", d.optString("description"))
                    put("parameters", d.getJSONObject("inputSchema"))
                })
            })
        }
    }

    private fun post(body: JSONObject): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
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
            if (code == 401) throw IllegalStateException("Check your $label API key in Settings.")
            throw IllegalStateException("$label request failed ($code): ${text.take(300)}")
        }
        return JSONObject(text)
    }
}
