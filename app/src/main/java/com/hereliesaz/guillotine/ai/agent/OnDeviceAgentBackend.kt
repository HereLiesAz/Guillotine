package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.hereliesaz.guillotine.ai.VlmCaptioner
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import kotlinx.coroutines.CancellationException
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
 *
 * **Vision (on-device only).** When a [frames] source is supplied and the assistant `.task` is itself
 * multimodal (e.g. Gemma-3n), the model can emit `{"tool":"look_at_frame"}` to actually SEE the current
 * frame: the backend decodes the playhead frame and runs one vision turn through the assistant's own
 * weights ([VlmCaptioner]), so a single multimodal model serves as both brain and eyes — no separate
 * captioner download needed. Pixels never leave the device. Text-only models are unaffected: the vision
 * turn is only attempted on an explicit look, and if the model can't view images the backend says so and
 * points it at `caption_frame` instead. The pure-text path below is byte-for-byte unchanged.
 */
class OnDeviceAgentBackend(
    private val context: Context,
    private val modelPath: String,
    private val frames: FrameProvider? = null,
) : AgentBackend {

    /** Intercepted, backend-native "see the frame" action (not dispatched to the MCP surface). */
    private val visionTool = "look_at_frame"

    // Once a look fails because the loaded model can't accept images, stop offering/attempting vision for
    // the rest of this run so a text-only model doesn't repeatedly hit the same wall.
    private var visionUnsupported = false

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            // Loading a multi-GB model takes seconds, so reuse it across instructions.
            val llm = LlmCache.get(context, modelPath)
            val canLook = frames != null

            val preamble = buildString {
                appendLine(AGENT_SYSTEM_PROMPT)
                appendLine()
                appendLine("Available tools:")
                appendLine(toolCatalog(tools.definitions()))
                if (canLook) {
                    appendLine(
                        "- $visionTool(): LOOK at the current frame with your own eyes and get a description " +
                            "of what's actually on screen. Use this when you need to know what the footage shows " +
                            "before deciding (e.g. \"is this shot indoors?\", \"what's in this frame?\").",
                    )
                }
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

                // Intercept the vision action: it's served by the model's own eyes, not the MCP tools.
                if (name == visionTool) {
                    onEvent(AgentEvent.ToolStarted(name))
                    val observation = lookAtFrame(args.optString("prompt"))
                    onEvent(AgentEvent.ToolFinished(name, observation.take(80), false))
                    transcript.append("ASSISTANT: ").append(obj.toString()).append('\n')
                    transcript.append("OBSERVATION: ").append(observation.take(1500)).append('\n')
                    continue
                }

                onEvent(AgentEvent.ToolStarted(name))
                val outcome = callTool(tools, name, args)
                onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))

                transcript.append("ASSISTANT: ").append(obj.toString()).append('\n')
                transcript.append("OBSERVATION: ").append(outcome.content().take(1500)).append('\n')
            }
            onEvent(AgentEvent.Failed("Stopped after $MAX_AGENT_ITERATIONS steps."))
        } catch (e: CancellationException) {
            throw e // preserve structured concurrency — a cancel is not a failure
        } catch (e: Throwable) {
            // Throwable: model load can fail with errors/UnsatisfiedLinkError on unsupported devices.
            onEvent(AgentEvent.Failed(e.message ?: "On-device model failed (check the model path)."))
        }
    }

    /**
     * Feed the current playhead frame into the assistant's own multimodal weights and return what it
     * sees, as one OBSERVATION line. Uses [VlmCaptioner] with THIS backend's [modelPath], so vision runs
     * on the same model the user chose as their brain. Pixels stay on-device. Any failure (no frame, or a
     * text-only model that can't accept images) degrades gracefully into guidance rather than crashing
     * the run — and a "can't view" failure disables further look attempts for this run.
     */
    private fun lookAtFrame(prompt: String): String {
        val fp = frames ?: return "Vision isn't available here."
        if (visionUnsupported) {
            return "This model can't view images directly. Use caption_frame (a separate vision model) instead."
        }
        val frame = fp.currentFrame()
            ?: return "There's no video frame under the playhead to look at — scrub onto a video clip first."
        val question = prompt.ifBlank { "Describe what is happening in this frame in detail." }
        return try {
            VlmCaptioner.describe(context, modelPath, frame, question)
                .ifBlank { "The frame looks empty or couldn't be described." }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The assistant model isn't multimodal (or vision failed) — don't retry, guide instead.
            visionUnsupported = true
            "This on-device model can't view images directly. Use caption_frame (a separate vision " +
                "model set in Settings) to describe the frame instead."
        } finally {
            runCatching { frame.recycle() }
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

    /**
     * Pull the first balanced {…} object out of model text (tolerates code fences / prose).
     * String-aware: braces inside JSON string literals don't affect nesting depth.
     */
    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    /**
     * Process-level cache of the loaded model, keyed by path. Model load is expensive and the
     * native handle holds a lot of memory, so we keep one alive and reuse it; switching paths
     * closes the previous one. Guarded because callers could (in theory) overlap.
     */
    private object LlmCache {
        private var path: String? = null
        private var instance: LlmInference? = null

        @Synchronized
        fun get(context: Context, modelPath: String): LlmInference {
            instance?.let { if (path == modelPath) return it }
            runCatching { instance?.close() }
            instance = LlmInference.createFromOptions(
                context.applicationContext,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .build(),
            )
            path = modelPath
            return instance!!
        }
    }
}
