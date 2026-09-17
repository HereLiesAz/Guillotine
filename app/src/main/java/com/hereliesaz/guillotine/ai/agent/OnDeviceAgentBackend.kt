package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.ui.ActivityLog
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
 * Unlike cloud backends, the local model has a small token/KV budget. Feeding it the complete cloud
 * system prompt plus every Guillotine MCP definition can exceed that budget before generation even
 * begins, which presents to the user as an endless "Thinking…". This backend therefore uses a compact
 * local prompt, a relevance-selected tool set, and a hard per-turn prompt budget. The cloud backends
 * still receive the full catalog and full system prompt.
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
 * the model can't view images the backend says so and points it at `caption_frame` instead.
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
            val modelName = File(modelPath).name.ifBlank { "on-device model" }
            ActivityLog.progress("AI · loading $modelName…")
            val loadStarted = SystemClock.elapsedRealtime()

            // Warm the engine up front (text mode unless a prior look already upgraded it). Loading a
            // multi-GB model takes seconds, so it's cached and reused across instructions.
            EngineCache.get(context, modelPath, wantVision = visionEngaged)
            ActivityLog.info("AI · model ready in ${elapsedMs(loadStarted)} ms")

            // Only advertise looking when we have a frame source AND haven't already learned this model
            // can't see — otherwise the model wastes a turn calling it just to be told no.
            val canLook = frames != null && !visionUnsupported

            val allTools = tools.definitions()
            val selectedTools = selectToolDefinitions(allTools, instruction)
            val selectedNames = toolNames(selectedTools)
            ActivityLog.info(
                "AI · tools ${selectedTools.length()}/${allTools.length()}: ${selectedNames.joinToString(", ")}",
            )

            val preamble = buildString {
                appendLine(ON_DEVICE_SYSTEM_PROMPT.trimIndent())
                appendLine()
                appendLine("Available tools:")
                append(compactToolCatalog(selectedTools))
                if (canLook) {
                    appendLine(
                        "- $visionTool(prompt): inspect the current frame with this model's vision.",
                    )
                }
            }.trimEnd()

            // Keep only recent tool traffic. The original instruction is injected separately on every
            // turn, so trimming old observations never makes the local model forget the task itself.
            val history = ArrayDeque<String>()
            val guard = LoopGuard()
            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                ensureActive() // honor cancellation between turns — stop mutating the document once cancelled
                val prompt = buildTurnPrompt(preamble, instruction, history)
                ActivityLog.progress("AI · turn $iterations: generating (${prompt.length} chars)…")
                val generationStarted = SystemClock.elapsedRealtime()

                // Fetch each turn: a look may have upgraded the single engine to vision mode; text turns
                // run on that same engine (generateResponse works on a vision engine too).
                val llm = EngineCache.get(context, modelPath, wantVision = visionEngaged)
                val raw = llm.generateResponse(prompt).orEmpty().trim()
                ActivityLog.info(
                    "AI · turn $iterations: ${raw.length} chars in ${elapsedMs(generationStarted)} ms",
                )
                val obj = extractJsonObject(raw)

                if (obj == null || obj.has("final") || !obj.has("tool")) {
                    if (obj == null && raw.isNotBlank()) {
                        // A malformed local-model reply is exactly the sort of thing a copied Activity log
                        // needs to expose. Cap it so one hallucinated essay can't consume the whole feed.
                        ActivityLog.info("AI · unstructured reply: ${raw.take(MAX_DIAGNOSTIC_REPLY_CHARS)}")
                    }
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
                    addHistory(history, "ASSISTANT: ${obj}")
                    addHistory(history, "OBSERVATION: ${observation.take(MAX_OBSERVATION_CHARS)}")
                    continue
                }

                onEvent(AgentEvent.ToolStarted(name))
                val outcome = callTool(tools, name, args)
                onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
                guard.check(name, args.toString(), outcome.isError)?.let { stop ->
                    onEvent(AgentEvent.Failed(stop))
                    return@withContext
                }

                addHistory(history, "ASSISTANT: ${obj}")
                addHistory(history, "OBSERVATION: ${outcome.content().take(MAX_OBSERVATION_CHARS)}")
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
     * Choose only the tools relevant to this request. Six basic editor tools are always present; up to
     * [MAX_ON_DEVICE_TOOLS] are added by explicit intent hints and word overlap with each tool's name /
     * description. This keeps the local prompt well below the MediaPipe model's small context budget.
     */
    private fun selectToolDefinitions(defs: JSONArray, instruction: String): JSONArray {
        data class Candidate(val index: Int, val definition: JSONObject, val name: String, val score: Int)

        val terms = searchTerms(instruction)
        val hinted = hintedTools(instruction)
        val candidates = buildList {
            for (i in 0 until defs.length()) {
                val d = defs.optJSONObject(i) ?: continue
                val name = d.optString("name")
                val description = d.optString("description")
                val haystack = "$name $description".lowercase(Locale.ROOT)
                val overlap = terms.count { haystack.contains(it) }
                val score = overlap * 10 + if (name in hinted) 100 else 0
                add(Candidate(i, d, name, score))
            }
        }

        val chosen = LinkedHashMap<String, JSONObject>()
        fun addNamed(name: String) {
            candidates.firstOrNull { it.name == name }?.let { chosen.putIfAbsent(name, it.definition) }
        }

        CORE_TOOL_NAMES.forEach(::addNamed)
        hinted.forEach(::addNamed)
        candidates
            .asSequence()
            .filter { it.score > 0 && it.name !in chosen }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })
            .forEach {
                if (chosen.size < MAX_ON_DEVICE_TOOLS) chosen[it.name] = it.definition
            }

        return JSONArray().apply {
            chosen.values.take(MAX_ON_DEVICE_TOOLS).forEach(::put)
        }
    }

    private fun searchTerms(text: String): Set<String> =
        TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    /** Intent words whose corresponding tool may not literally repeat the user's phrasing. */
    private fun hintedTools(text: String): Set<String> {
        val t = text.lowercase(Locale.ROOT)
        return buildSet {
            if (DEICTIC_HINTS.any(t::contains)) {
                add("describe_current_frame")
                add("analyze_clip_with_reference")
            }
            if (listOf("caption", "subtitle", "transcrib").any(t::contains)) add("transcribe_clip")
            if (listOf("animated caption", "kinetic", "syllable").any(t::contains)) add("animated_transcribe_clip")
            if (listOf("transition", "crossfade", "dissolve", "wipe").any(t::contains)) add("apply_transition")
            if (listOf("bokeh", "portrait mode", "background blur").any(t::contains)) add("apply_bokeh")
            if (listOf("lufs", "normalize loud", "loudness").any(t::contains)) add("normalize_loudness")
            if (listOf("beat", "tempo", "bpm").any(t::contains)) {
                add("get_beat_map")
                add("cut_to_beats")
            }
            if (listOf("remember this", "learn this", "teach", "recognize this", "recognise this").any(t::contains)) {
                add("add_reference")
                add("analyze_clip_with_concept")
            }
            if (listOf("erase", "inpaint", "same length", "make it look natural").any(t::contains)) {
                add("remove_object_generative")
            }
        }
    }

    /** Compact, model-readable list of selected tools and their argument names. */
    private fun compactToolCatalog(defs: JSONArray): String = buildString {
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            val props = d.optJSONObject("inputSchema")?.optJSONObject("properties")
            val argNames = props?.keys()?.asSequence()?.joinToString(", ").orEmpty()
            val description = d.optString("description")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .take(MAX_TOOL_DESCRIPTION_CHARS)
            append("- ").append(d.getString("name"))
            append('(').append(argNames).append(')')
            if (description.isNotEmpty()) append(": ").append(description)
            append('\n')
        }
    }

    private fun toolNames(defs: JSONArray): List<String> = buildList {
        for (i in 0 until defs.length()) {
            defs.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    /**
     * Build one turn under a hard character ceiling. The ceiling is deliberately conservative relative
     * to the 1024-token MediaPipe engine setting: exact tokenization varies by downloaded model, and a
     * little unused context is much cheaper than a phone apparently freezing during prompt prefill.
     */
    private fun buildTurnPrompt(
        preamble: String,
        instruction: String,
        history: ArrayDeque<String>,
    ): String {
        val suffix = "\nASSISTANT: "
        val prefix = "$preamble\n\nUSER REQUEST: "
        val roomAfterPreamble = (MAX_PROMPT_CHARS - prefix.length - suffix.length).coerceAtLeast(0)
        val instructionBudget = minOf(MAX_INSTRUCTION_CHARS, roomAfterPreamble)
        val safeInstruction = instruction.take(instructionBudget)
        val historyBudget = (roomAfterPreamble - safeInstruction.length - 1).coerceAtLeast(0)
        val recent = recentHistory(history, historyBudget)
        return buildString {
            append(prefix)
            append(safeInstruction)
            if (recent.isNotEmpty()) {
                append('\n')
                append(recent)
            }
            append(suffix)
        }
    }

    private fun recentHistory(history: ArrayDeque<String>, budget: Int): String {
        if (budget <= 0 || history.isEmpty()) return ""
        val newestFirst = mutableListOf<String>()
        var used = 0
        for (line in history.reversed()) {
            val cost = line.length + if (newestFirst.isEmpty()) 0 else 1
            if (used + cost > budget) break
            newestFirst += line
            used += cost
        }
        return newestFirst.asReversed().joinToString("\n")
    }

    private fun addHistory(history: ArrayDeque<String>, line: String) {
        history.addLast(line)
        var chars = history.sumOf { it.length + 1 }
        while (chars > MAX_HISTORY_CHARS && history.isNotEmpty()) {
            chars -= history.removeFirst().length + 1
        }
    }

    private fun elapsedMs(started: Long): Long = SystemClock.elapsedRealtime() - started

    /**
     * Feed the current playhead frame into the assistant's own multimodal weights and return what it
     * sees, as one OBSERVATION line. Vision runs on THIS backend's [modelPath] — the same model the user
     * chose as their brain — by upgrading the single cached engine to vision mode (which evicts the text
     * engine first, so only one model is ever resident). Pixels stay on-device. Any failure (no frame, or
     * a text-only model that can't accept images) degrades gracefully into guidance rather than crashing
     * the run, and a "can't view" failure disables further look attempts for this run.
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

    private companion object {
        private const val MAX_ON_DEVICE_TOOLS = 10
        private const val MAX_TOOL_DESCRIPTION_CHARS = 96
        private const val MAX_PROMPT_CHARS = 2_800
        private const val MAX_INSTRUCTION_CHARS = 1_000
        private const val MAX_HISTORY_CHARS = 1_200
        private const val MAX_OBSERVATION_CHARS = 360
        private const val MAX_DIAGNOSTIC_REPLY_CHARS = 600

        private val CORE_TOOL_NAMES = listOf(
            "get_timeline",
            "get_clip",
            "set_prompt",
            "analyze_clip",
            "select_clip",
            "split_clip",
            "delete_clip",
        )

        private val DEICTIC_HINTS = listOf(
            "this frame", "that frame", "on screen", "on-screen", "preview", "this object", "that object",
            "remove this", "remove that", "cut this", "cut that", "what is this", "what's this",
        )

        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "into", "all", "part", "parts",
            "please", "video", "clip", "clips", "want", "make", "then", "than", "have", "just",
        )

        private val TOKEN_REGEX = Regex("[a-z0-9_]+")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private const val ON_DEVICE_SYSTEM_PROMPT = """
            You control the Guillotine video editor only through the listed tools.
            Reply with exactly ONE JSON object per turn and no prose:
            {"tool":"name","args":{...}} or {"final":"short summary"}.
            Inspect the timeline for real clip IDs; never invent IDs. For cut/remove/keep-by-content requests,
            set_prompt then analyze_clip; analyze_clip performs the actual cut. Use split/delete for direct
            timeline edits. Keep calling tools until the request is complete. If a tool fails, do not loop on
            the same failed call; use another valid approach or finish with the error.
        """
    }
}
