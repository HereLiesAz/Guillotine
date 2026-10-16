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
 * Agent brain backed by Gemini's `generateContent` function-calling. Maps the MCP tool defs to
 * Gemini `function_declarations` (JSON-Schema types upper-cased to the OpenAPI subset Gemini
 * expects) and runs the functionCall ⇄ functionResponse loop.
 */
class GeminiAgentBackend(
    private val apiKey: String,
    private val model: String,
    // Non-null ONLY when the user opted into cloud vision. When set, the model is offered look_at_frame
    // and the current frame is sent to Gemini's image API on demand. Null → strictly text-only.
    private val frames: FrameImageSource? = null,
) : AgentBackend {

    private val base = "https://generativelanguage.googleapis.com"

    // Conversation memory kept across run() calls (see AgentBackend.reset). system_instruction is sent in the
    // request body each turn, so it's not part of this. Reset on any non-clean exit to avoid a corrupt tail.
    private var contents = JSONArray()

    override fun reset() { contents = JSONArray() }

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val defs = tools.definitions()
            if (frames != null) defs.put(visionToolDefinition()) // offer look_at_frame only when opted in
            val toolBlock = JSONArray().put(
                JSONObject().put("function_declarations", geminiTools(defs)),
            )
            if (contents.length() >= MAX_CONVERSATION_MESSAGES) contents = JSONArray() // bound growth across turns
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", instruction))),
            )

            val guard = LoopGuard()
            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                ensureActive() // honor cancellation between turns — stop issuing paid API calls once cancelled
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

                // A blocked prompt comes back with no candidates (promptFeedback.blockReason), and a
                // blocked/aborted reply as a candidate with a non-STOP finishReason and no parts. Neither is
                // a successful "Done" — surface it and drop the unanswered user turn.
                if (parts.length() == 0) {
                    val blockReason = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    val finish = candidate?.optString("finishReason").orEmpty()
                    if (blockReason.isNotEmpty() || (finish.isNotEmpty() && finish != "STOP") || candidate == null) {
                        contents = JSONArray()
                        val why = when {
                            blockReason.isNotEmpty() -> "the request was blocked ($blockReason)"
                            finish.isNotEmpty() -> "the reply was stopped ($finish)"
                            else -> "no reply was returned"
                        }
                        onEvent(AgentEvent.Failed("Gemini: $why."))
                        return@withContext
                    }
                }

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
                        val outcome = if (name == VISION_TOOL_NAME) {
                            frameLookOutcome(frames, args.optString("prompt")) { img, q -> describeImage(img, q) }
                        } else {
                            callTool(tools, name, args)
                        }
                        onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
                        guard.check(name, args.toString(), outcome.isError)?.let { stop ->
                            onEvent(AgentEvent.Failed(stop))
                            contents = JSONArray() // stalled mid-turn — drop the unbalanced tail
                            return@withContext
                        }
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
            contents = JSONArray() // stopped mid-task; drop the (possibly unbalanced) history
        } catch (e: kotlinx.coroutines.CancellationException) {
            contents = JSONArray() // partial turn — reset so the next run starts clean
            throw e
        } catch (e: Exception) {
            contents = JSONArray() // partial turn (model functionCall with no functionResponse) — reset
            onEvent(AgentEvent.Failed(e.message ?: "Gemini agent failed"))
        }
    }

    /**
     * One-shot vision request: send [image] and [question] as an inline_data part and return the text.
     * Used only on the opt-in cloud-vision path (via look_at_frame). Reuses [post]; null on any failure.
     */
    private fun describeImage(image: FrameImage, question: String): String? {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("text", question))
                        .put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", image.mediaType).put("data", image.data),
                            ),
                        ),
                ),
            ),
        )
        val parts = post(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().ifBlank { null }
    }

    private fun geminiTools(defs: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            put(JSONObject().apply {
                put("name", d.getString("name"))
                put("description", d.optString("description"))
                // Gemini rejects an OBJECT schema with empty `properties`; a no-argument tool simply
                // omits `parameters`.
                val params = toGeminiSchema(d.getJSONObject("inputSchema"))
                if (params.has("properties")) put("parameters", params)
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
            if (out.length() > 0) put("properties", out)
        }
        schema.optJSONArray("required")?.takeIf { it.length() > 0 && has("properties") }?.let { put("required", it) }
        schema.optJSONObject("items")?.let { put("items", toGeminiSchema(it)) }
    }

    override suspend fun complete(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            )
            val parts = post(body).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            sb.toString().ifBlank { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun post(body: JSONObject): JSONObject {
        // Pass the key in the x-goog-api-key header, not the URL query string — a key in the URL leaks
        // into request logs, proxy access logs, and any crash trace that captures the endpoint.
        val endpoint = "$base/v1beta/models/$model:generateContent"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
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
            // Only blame the key when Google says it's the key: a 400 is far more often a schema/argument
            // problem, and hiding its message behind "check your key" makes it undiagnosable.
            val keyProblem = code == 401 || code == 403 ||
                (code == 400 && (text.contains("API_KEY_INVALID") || text.contains("API key not valid", ignoreCase = true)))
            if (keyProblem) throw IllegalStateException("Check your Gemini API key in Settings.")
            throw IllegalStateException("Gemini request failed ($code): ${text.take(300)}")
        }
        return JSONObject(text)
    }
}
