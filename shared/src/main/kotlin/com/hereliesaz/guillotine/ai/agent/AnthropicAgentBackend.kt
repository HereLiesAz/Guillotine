package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.mcp.McpToolsSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent brain backed by Anthropic's Messages API tool-use. Uses the standard Messages HTTP
 * shape (x-api-key, anthropic-version) with a `tools` array, running the multi-turn
 * tool_use ⇄ tool_result loop. It only ever exchanges text — tool definitions and JSON
 * results — and never sends any media.
 */
class AnthropicAgentBackend(
    private val apiKey: String,
    private val model: String,
    // Non-null ONLY when the user opted into cloud vision. When set, the model is offered look_at_frame
    // and the current frame is sent to Claude's image API on demand. Null → strictly text-only.
    private val frames: FrameImageSource? = null,
) : AgentBackend {

    private val url = "https://api.anthropic.com/v1/messages"
    private val version = "2023-06-01"

    // Conversation memory: retained across run() calls so the model remembers earlier turns. Reset on any
    // non-clean exit so a partial exchange (an assistant tool_use with no tool_result) can't corrupt the next.
    private var messages = JSONArray()

    override fun reset() { messages = JSONArray() }

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val defs = tools.definitions()
            if (frames != null) defs.put(visionToolDefinition()) // offer look_at_frame only when opted in
            val toolDefs = anthropicTools(defs)
            if (messages.length() >= MAX_CONVERSATION_MESSAGES) messages = JSONArray() // bound growth across turns
            messages.put(JSONObject().put("role", "user").put("content", instruction))

            val guard = LoopGuard()
            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                ensureActive() // honor cancellation between turns — stop issuing paid API calls once cancelled
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
                        val outcome = if (name == VISION_TOOL_NAME) {
                            frameLookOutcome(frames, input.optString("prompt")) { img, q -> describeImage(img, q) }
                        } else {
                            callTool(tools, name, input)
                        }
                        onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
                        guard.check(name, input.toString(), outcome.isError)?.let { stop ->
                            onEvent(AgentEvent.Failed(stop))
                            messages = JSONArray() // stalled mid-turn — drop the unbalanced tail
                            return@withContext
                        }
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
            messages = JSONArray() // stopped mid-task; drop the (possibly unbalanced) history
        } catch (e: kotlinx.coroutines.CancellationException) {
            messages = JSONArray() // partial turn — reset so the next run starts clean
            throw e
        } catch (e: Exception) {
            messages = JSONArray() // partial turn (e.g. tool_use with no tool_result) — reset
            onEvent(AgentEvent.Failed(e.message ?: "Anthropic agent failed"))
        }
    }

    override suspend fun complete(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 2048)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            }
            val content = post(body).optJSONArray("content") ?: return@withContext null
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            sb.toString().ifBlank { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * One-shot vision request: send [image] and [question] to Claude's image API and return the text.
     * Used only on the opt-in cloud-vision path (via look_at_frame). Reuses [post]; null on any failure.
     */
    private fun describeImage(image: FrameImage, question: String): String? {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", question))
                            .put(
                                JSONObject().put("type", "image").put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", image.mediaType)
                                        .put("data", image.data),
                                ),
                            ),
                    ),
                ),
            )
        }
        val content = post(body).optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().ifBlank { null }
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
