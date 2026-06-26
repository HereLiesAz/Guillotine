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
 * Agent brain backed by Anthropic's Messages API tool-use. Mirrors the HTTP shape of
 * [com.hereliesaz.guillotine.ai.AnthropicProvider] (x-api-key, anthropic-version) but adds a
 * `tools` array and runs the multi-turn tool_use ⇄ tool_result loop.
 */
class AnthropicAgentBackend(
    private val apiKey: String,
    private val model: String,
) : AgentBackend {

    private val url = "https://api.anthropic.com/v1/messages"
    private val version = "2023-06-01"

    override suspend fun run(
        instruction: String,
        tools: McpTools,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val toolDefs = anthropicTools(tools.definitions())
            val messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", instruction),
            )

            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 4096)
                    put("system", AGENT_SYSTEM_PROMPT)
                    put("tools", toolDefs)
                    put("messages", messages)
                }
                val resp = post(body)
                val content = resp.optJSONArray("content") ?: JSONArray()

                val text = StringBuilder()
                val toolUses = mutableListOf<JSONObject>()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text"))
                        "tool_use" -> toolUses.add(block)
                    }
                }
                if (text.isNotBlank()) onEvent(AgentEvent.AssistantText(text.toString().trim()))

                // Append the assistant turn verbatim (tool_use blocks must round-trip unchanged).
                messages.put(JSONObject().put("role", "assistant").put("content", content))

                if (resp.optString("stop_reason") == "tool_use" && toolUses.isNotEmpty()) {
                    val results = JSONArray()
                    for (tu in toolUses) {
                        val name = tu.optString("name")
                        val input = tu.optJSONObject("input") ?: JSONObject()
                        onEvent(AgentEvent.ToolStarted(name))
                        val outcome = callTool(tools, name, input)
                        onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
                        results.put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", tu.optString("id"))
                            put("content", outcome.content())
                            if (outcome.isError) put("is_error", true)
                        })
                    }
                    messages.put(JSONObject().put("role", "user").put("content", results))
                    continue
                }

                onEvent(AgentEvent.Done(text.toString().trim().ifBlank { "Done." }))
                return@withContext
            }
            onEvent(AgentEvent.Failed("Stopped after $MAX_AGENT_ITERATIONS steps."))
        } catch (e: Exception) {
            onEvent(AgentEvent.Failed(e.message ?: "Anthropic agent failed"))
        }
    }

    /** Map MCP tool defs to Anthropic's shape: `inputSchema` (MCP) → `input_schema` (Anthropic). */
    private fun anthropicTools(defs: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            put(JSONObject().apply {
                put("name", d.getString("name"))
                put("description", d.optString("description"))
                put("input_schema", d.getJSONObject("inputSchema"))
            })
        }
    }

    private fun post(body: JSONObject): JSONObject {
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
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            if (code == 401) throw IllegalStateException("Check your Anthropic API key in Settings.")
            throw IllegalStateException("Anthropic request failed ($code): ${text.take(300)}")
        }
        return JSONObject(text)
    }
}
