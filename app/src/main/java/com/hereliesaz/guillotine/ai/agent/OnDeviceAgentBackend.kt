package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
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
 * weights, so a single multimodal model serves as both brain and eyes — no separate captioner download
 * needed, and (via [EngineCache]) only ONE copy of the model is ever resident. Pixels never leave the
 * device. Text-only models are unaffected: the vision turn is only attempted on an explicit look, and if
 * the model can't view images the backend says so and points it at `caption_frame` instead. The pure-text
 * path below is byte-for-byte unchanged.
 */
class OnDeviceAgentBackend(
    private val context: Context,
    private val modelPath: String,
    private val frames: FrameProvider? = null,
) : AgentBackend {

    /** Intercepted, backend-native "see the frame" action (not dispatched to the MCP surface). */
    private val visionTool = "look_at_frame"

    // Once a look fails because the loaded model can't accept images, stop offering/attempting vision for
    // the rest of this backend's life so a text-only model doesn't repeatedly hit the same wall.
    private var visionUnsupported = false

    // True once the single engine has been (re)loaded in vision mode for a look. The SAME engine then
    // serves text turns too (generateResponse works on a vision engine) — so only ONE multi-GB model is
    // ever resident, never the brain + a separate captioner at once. See [EngineCache].
    private var visionEngaged = false

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            // Warm the engine up front (text mode unless a prior look already upgraded it). Loading a
            // multi-GB model takes seconds, so it's cached and reused across instructions.
            EngineCache.get(context, modelPath, wantVision = visionEngaged)
            // Only advertise looking when we have a frame source AND haven't already learned this model
            // can't see — otherwise the model wastes a turn calling it just to be told no.
            val canLook = frames != null && !visionUnsupported

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

            val guard = LoopGuard()
            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                val prompt = preamble + "\n" + transcript + "ASSISTANT: "
                // Fetch each turn: a look may have upgraded the single engine to vision mode; text turns
                // run on that same engine (generateResponse works on a vision engine too).
                val llm = EngineCache.get(context, modelPath, wantVision = visionEngaged)
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
                    val (observation, isError) = lookAtFrame(args.optString("prompt"))
                    onEvent(AgentEvent.ToolFinished(name, observation.take(80), isError))
                    guard.check(name, args.toString(), isError)?.let { stop ->
                        onEvent(AgentEvent.Failed(stop))
                        return@withContext
                    }
                    transcript.append("ASSISTANT: ").append(obj.toString()).append('\n')
                    transcript.append("OBSERVATION: ").append(observation.take(1500)).append('\n')
                    continue
                }

                onEvent(AgentEvent.ToolStarted(name))
                val outcome = callTool(tools, name, args)
                onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
                guard.check(name, args.toString(), outcome.isError)?.let { stop ->
                    onEvent(AgentEvent.Failed(stop))
                    return@withContext
                }

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
     * sees, as one OBSERVATION line. Vision runs on THIS backend's [modelPath] — the same model the user
     * chose as their brain — by upgrading the single cached engine to vision mode (which evicts the
     * text engine first, so only one model is ever resident). Pixels stay on-device. Any failure (no
     * frame, or a text-only model that can't accept images) degrades gracefully into guidance rather
     * than crashing the run, and a "can't view" failure disables further look attempts for this run.
     *
     * Returns (observation, isError): isError is true for every failure path (no frame, model can't see)
     * so the caller feeds the real status to [LoopGuard] — otherwise a model spinning on failing looks
     * would never trip the error streak.
     */
    private fun lookAtFrame(prompt: String): Pair<String, Boolean> {
        val fp = frames ?: return "Vision isn't available here." to true
        if (visionUnsupported) {
            return ("This model can't view images directly. Use caption_frame (a separate vision model) " +
                "instead.") to true
        }
        val frame = fp.currentFrame()
            ?: return ("There's no video frame under the playhead to look at — scrub onto a video clip " +
                "first.") to true
        val question = prompt.ifBlank { "Describe what is happening in this frame in detail." }
        return try {
            // Upgrade the one engine to vision mode (closes the text engine → single-model residency).
            val llm = EngineCache.get(context, modelPath, wantVision = true)
            visionEngaged = true
            generateVision(llm, question, frame)
                .ifBlank { "The frame looks empty or couldn't be described." } to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The assistant model isn't multimodal (or vision failed) — don't retry; restore the text
            // engine so the run continues, and point the model at caption_frame instead.
            visionUnsupported = true
            visionEngaged = false
            runCatching { EngineCache.get(context, modelPath, wantVision = false) }
            ("This on-device model can't view images directly. Use caption_frame (a separate vision " +
                "model set in Settings) to describe the frame instead.") to true
        } finally {
            runCatching { frame.recycle() }
        }
    }

    /**
     * One multimodal turn: run [prompt] over [frame] through a vision-enabled session on the (already
     * vision-mode) [llm]. Mirrors the [com.hereliesaz.guillotine.ai.VlmCaptioner] session shape. The
     * MPImage holds native memory and is released explicitly.
     */
    private fun generateVision(llm: LlmInference, prompt: String, frame: Bitmap): String {
        val session = LlmInferenceSession.createFromOptions(
            llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(10)
                .setTemperature(0.4f)
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build(),
        )
        val mpImage = BitmapImageBuilder(frame).build()
        return try {
            session.addQueryChunk(prompt)
            session.addImage(mpImage)
            session.generateResponse().orEmpty().trim()
        } finally {
            runCatching { mpImage.close() }
            runCatching { session.close() }
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
     * Process-level cache of the loaded model. Model load is expensive and the native handle holds a lot
     * of memory, so exactly ONE engine is kept alive and reused. It's keyed by both path AND vision mode:
     * changing either closes the previous engine BEFORE creating the new one, so the brain and a vision
     * engine for the same model can never be resident simultaneously (which would double memory and OOM).
     * A text-only turn runs fine on a vision-mode engine, so once a look upgrades it we keep using it.
     * Guarded because callers could (in theory) overlap.
     */
    private object EngineCache {
        private var path: String? = null
        private var vision = false
        private var instance: LlmInference? = null

        @Synchronized
        fun get(context: Context, modelPath: String, wantVision: Boolean): LlmInference {
            instance?.let { if (path == modelPath && vision == wantVision) return it }
            // Close the old engine FIRST — never hold two multi-GB models at once.
            runCatching { instance?.close() }
            instance = null
            path = null
            val builder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
            if (wantVision) builder.setMaxNumImages(1) // enable image input for this engine
            instance = LlmInference.createFromOptions(context.applicationContext, builder.build())
            path = modelPath
            vision = wantVision
            return instance!!
        }
    }
}
