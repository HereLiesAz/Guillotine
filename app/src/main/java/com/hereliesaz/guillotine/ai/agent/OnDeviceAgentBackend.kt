package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.hereliesaz.guillotine.mcp.McpTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fully on-device agent brain. Runs a BYO `.task` LLM (Gemma / Hammer / Llama) through the
 * MediaPipe LLM Inference API and drives the MCP tools with a plain-text JSON protocol — the
 * same prompt→JSON approach the app's cloud analyzers already use, so no extra SDK / proto
 * dependency is needed. Requires no key or network; pairs with the free on-device MLKit/Local
 * analyzers for a completely offline edit assistant.
 *
 * The model is asked to reply with exactly one JSON object per turn — either
 * `{"tool":"<name>","args":{…}}` to call a tool or `{"final":"<summary>"}` when finished.
 */
class OnDeviceAgentBackend(
    private val context: Context,
    private val modelPath: String,
) : AgentBackend {

    override suspend fun run(
        instruction: String,
        tools: McpTools,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var llm: LlmInference? = null
        try {
            llm = LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .build(),
            )

            val preamble = buildString {
                appendLine(AGENT_SYSTEM_PROMPT)
                appendLine()
                appendLine("Available tools:")
                appendLine(toolCatalog(tools.definitions()))
                appendLine()
                appendLine("Reply with EXACTLY ONE JSON object and nothing else, either:")
                appendLine("""  {"tool":"<tool_name>","args":{ … }}   to call a tool, or""")
                appendLine("""  {"final":"<one-sentence summary>"}     when the task is done.""")
            }

            val transcript = StringBuilder()
            transcript.append("USER: ").append(instruction).append('\n')

            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                val prompt = preamble + "\n" + transcript + "ASSISTANT: "
                val raw = llm.generateResponse(prompt).orEmpty().trim()
                val obj = extractJsonObject(raw)

                if (obj == null || obj.has("final") || !obj.has("tool")) {
                    val summary = obj?.optString("final").orEmpty().ifBlank {
                        raw.ifBlank { "Done." }
                    }
                    onEvent(AgentEvent.Done(summary.trim()))
                    return@withContext
                }

                val name = obj.optString("tool")
                val args = obj.optJSONObject("args") ?: JSONObject()
                onEvent(AgentEvent.ToolStarted(name))
                val outcome = callTool(tools, name, args)
                onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))

                transcript.append("ASSISTANT: ").append(obj.toString()).append('\n')
                transcript.append("OBSERVATION: ").append(outcome.content().take(1500)).append('\n')
            }
            onEvent(AgentEvent.Failed("Stopped after $MAX_AGENT_ITERATIONS steps."))
        } catch (e: Throwable) {
            // Throwable: model load can fail with errors/UnsatisfiedLinkError on unsupported devices.
            onEvent(AgentEvent.Failed(e.message ?: "On-device model failed (check the model path)."))
        } finally {
            runCatching { llm?.close() }
        }
    }

    /** Compact, model-readable list of tools and their argument names. */
    private fun toolCatalog(defs: JSONArray): String = buildString {
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            val props = d.optJSONObject("inputSchema")?.optJSONObject("properties")
            val argNames = props?.keys()?.asSequence()?.joinToString(", ").orEmpty()
            append("- ").append(d.getString("name"))
            append('(').append(argNames).append(')')
            append(": ").append(d.optString("description")).append('\n')
        }
    }

    /** Pull the first balanced {…} object out of model text (tolerates code fences / prose). */
    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }
}
