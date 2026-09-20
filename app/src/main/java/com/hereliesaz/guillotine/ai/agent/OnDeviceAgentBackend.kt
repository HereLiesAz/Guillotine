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
 * Fully on-device agent brain. Local models have a much smaller context/KV budget than the cloud
 * backends, so this backend deliberately does not feed them the full cloud system prompt plus every
 * MCP definition. It selects request-relevant tools and keeps every turn under a hard prompt budget.
 */
class OnDeviceAgentBackend(
    private val context: Context,
    private val modelPath: String,
    private val frames: FrameProvider? = null,
) : AgentBackend {

    private val visionTool = "look_at_frame"
    private var visionUnsupported = false
    private var visionEngaged = false

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            // "Cut the boring parts" is the app's canonical vague-pacing example. It has a safe,
            // deterministic interpretation (dead-air/downtime removal), so do not wake a multi-GB LLM
            // just to rediscover get_timeline -> set_prompt -> analyze_clip.
            if (PromptCoach.isBoringCutRequest(instruction)) {
                ActivityLog.info("AI · instant pacing edit — skipping LLM load.")
                runBoringCutFastPath(tools, onEvent)
                return@withContext
            }

            val modelName = File(modelPath).name.ifBlank { "on-device model" }
            ActivityLog.progress("AI · loading $modelName…")
            val loadStarted = SystemClock.elapsedRealtime()
            val runtime = prepareTextModel()
            ActivityLog.info("AI · model ready in ${elapsedMs(loadStarted)} ms · $runtime")

            // Modern .litertlm assistant models are intentionally text-only here. Rich frame vision is
            // delegated to caption_frame/VLM instead of reopening them through MediaPipe's legacy API.
            val canLook = frames != null && !visionUnsupported && !isLiteRtLmModel
            val allTools = tools.definitions()
            val selectedTools = selectToolDefinitions(allTools, instruction)
            ActivityLog.info(
                "AI · tools ${selectedTools.length()}/${allTools.length()}: ${toolNames(selectedTools).joinToString(", ")}",
            )

            val preamble = buildString {
                appendLine(ON_DEVICE_SYSTEM_PROMPT.trimIndent())
                appendLine()
                appendLine("Available tools:")
                append(compactToolCatalog(selectedTools))
                if (canLook) appendLine("- $visionTool(prompt): inspect the current frame with this model's vision.")
            }.trimEnd()

            val history = ArrayDeque<String>()
            val guard = LoopGuard()
            var iterations = 0
            while (iterations++ < MAX_AGENT_ITERATIONS) {
                ensureActive()
                val prompt = buildTurnPrompt(preamble, instruction, history)
                ActivityLog.progress("AI · turn $iterations: generating (${prompt.length} chars)…")
                val generationStarted = SystemClock.elapsedRealtime()

                val raw = generateText(prompt)
                ActivityLog.info("AI · turn $iterations: ${raw.length} chars in ${elapsedMs(generationStarted)} ms")
                val obj = extractJsonObject(raw)

                if (obj == null || obj.has("final") || !obj.has("tool")) {
                    if (obj == null && raw.isNotBlank()) {
                        ActivityLog.info("AI · unstructured reply: ${raw.take(MAX_DIAGNOSTIC_REPLY_CHARS)}")
                    }
                    val summary = obj?.optString("final").orEmpty().ifBlank { raw.ifBlank { "Done." } }
                    onEvent(AgentEvent.Done(summary.trim()))
                    return@withContext
                }

                val name = obj.optString("tool")
                val args = obj.optJSONObject("args") ?: JSONObject()

                if (name == visionTool) {
                    onEvent(AgentEvent.ToolStarted(name))
                    val (observation, isError) = lookAtFrame(args.optString("prompt"))
                    onEvent(AgentEvent.ToolFinished(name, observation.take(80), isError))
                    guard.check(name, args.toString(), isError)?.let { stop ->
                        onEvent(AgentEvent.Failed(stop))
                        return@withContext
                    }
                    addHistory(history, "ASSISTANT: $obj")
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
                addHistory(history, "ASSISTANT: $obj")
                addHistory(history, "OBSERVATION: ${outcome.content().take(MAX_OBSERVATION_CHARS)}")
            }
            onEvent(AgentEvent.Failed("Stopped after $MAX_AGENT_ITERATIONS steps."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onEvent(AgentEvent.Failed(e.message ?: "On-device model failed (check the model path)."))
        }
    }

    /**
     * Deterministic execution for the common vague pacing request. The concrete instruction passed to
     * analysis deliberately says "pauses and dead air" so Analysis uses its lightweight audio/RMS path
     * instead of trying to classify "boring" as a visual object.
     */
    private fun runBoringCutFastPath(
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) {
        fun execute(name: String, args: JSONObject = JSONObject()): ToolOutcome {
            onEvent(AgentEvent.ToolStarted(name))
            val outcome = callTool(tools, name, args)
            onEvent(AgentEvent.ToolFinished(name, outcome.summary(), outcome.isError))
            return outcome
        }

        val timeline = execute("get_timeline")
        if (timeline.isError) {
            onEvent(AgentEvent.Failed("Couldn't read the timeline: ${timeline.summary()}"))
            return
        }

        val clips = timeline.json.optJSONArray("clips")
        val ids = buildList {
            if (clips != null) {
                for (i in 0 until clips.length()) {
                    val clip = clips.optJSONObject(i) ?: continue
                    if (clip.optString("type") == "VIDEO") {
                        clip.optString("id").takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            onEvent(AgentEvent.Done("There are no video clips to tighten."))
            return
        }

        var completed = 0
        var cutClips = 0
        var failed = 0
        for (id in ids) {
            val setPrompt = execute(
                "set_prompt",
                JSONObject()
                    .put("clip_id", id)
                    .put("prompt", BORING_FAST_PATH_ANALYSIS_PROMPT),
            )
            if (setPrompt.isError) {
                failed++
                continue
            }

            val analyzed = execute("analyze_clip", JSONObject().put("clip_id", id))
            if (analyzed.isError) {
                failed++
            } else {
                completed++
                if (analyzed.json.optBoolean("cutApplied", false)) cutClips++
            }
        }

        when {
            completed == 0 ->
                onEvent(AgentEvent.Failed("The pacing analysis could not run on the video clips."))
            cutClips == 0 && failed == 0 ->
                onEvent(AgentEvent.Done("Analyzed $completed video clip(s); no removable pauses or dead air were found."))
            failed == 0 ->
                onEvent(AgentEvent.Done("Cut pauses and dead air from $cutClips of $completed analyzed video clip(s)."))
            else ->
                onEvent(
                    AgentEvent.Done(
                        "Analyzed $completed video clip(s), cut dead air from $cutClips; $failed clip(s) could not be analyzed.",
                    ),
                )
        }
    }

    private fun selectToolDefinitions(defs: JSONArray, instruction: String): JSONArray {
        // A DelegatingAgentBackend already narrowed the live catalog with the dedicated router. Honor
        // that decision verbatim instead of applying a second prompt-specific heuristic that could
        // accidentally throw the routed specialist back out.
        if (defs.length() <= MAX_ROUTED_TOOL_DEFINITIONS) {
            return JSONArray().apply {
                for (i in 0 until defs.length()) defs.optJSONObject(i)?.let(::put)
            }
        }

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
        candidates.asSequence()
            .filter { it.score > 0 && it.name !in chosen }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })
            .forEach {
                if (chosen.size < MAX_ON_DEVICE_TOOLS) chosen[it.name] = it.definition
            }

        return JSONArray().apply {
            chosen.values.take(MAX_ON_DEVICE_TOOLS).forEach { definition -> put(definition) }
        }
    }

    private fun searchTerms(text: String): Set<String> =
        TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

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

    private fun compactToolCatalog(defs: JSONArray): String = buildString {
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            val props = d.optJSONObject("inputSchema")?.optJSONObject("properties")
            val argNames = props?.keys()?.asSequence()?.joinToString(", ").orEmpty().take(MAX_TOOL_ARGS_CHARS)
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
            val name = defs.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotBlank()) add(name)
        }
    }

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

    private val isLiteRtLmModel: Boolean
        get() = modelPath.endsWith(".litertlm", ignoreCase = true)

    private suspend fun prepareTextModel(): String =
        if (isLiteRtLmModel) {
            LiteRtLmTextEngine.prepare(context, modelPath)
        } else {
            EngineCache.get(context, modelPath, wantVision = visionEngaged)
            "MediaPipe LLM"
        }

    private suspend fun generateText(prompt: String): String =
        if (isLiteRtLmModel) {
            LiteRtLmTextEngine.generate(context, modelPath, prompt)
        } else {
            EngineCache.get(context, modelPath, wantVision = visionEngaged)
                .generateResponse(prompt)
                .orEmpty()
                .trim()
        }

    private fun lookAtFrame(prompt: String): Pair<String, Boolean> {
        if (isLiteRtLmModel) {
            return "This assistant model is text-only. Use caption_frame (the configured VLM/vision model) instead." to true
        }
        val fp = frames ?: return "Vision isn't available here." to true
        if (visionUnsupported) {
            return "This model can't view images directly. Use caption_frame (a separate vision model) instead." to true
        }
        val frame = fp.currentFrame()
            ?: return "There's no video frame under the playhead to look at — scrub onto a video clip first." to true
        val question = prompt.ifBlank { "Describe what is happening in this frame in detail." }
        return try {
            val llm = EngineCache.get(context, modelPath, wantVision = true)
            visionEngaged = true
            generateVision(llm, question, frame).ifBlank { "The frame looks empty or couldn't be described." } to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            visionUnsupported = true
            visionEngaged = false
            runCatching { EngineCache.get(context, modelPath, wantVision = false) }
            "This on-device model can't view images directly. Use caption_frame (a separate vision model set in Settings) to describe the frame instead." to true
        } finally {
            runCatching { frame.recycle() }
        }
    }

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

    private object EngineCache {
        private var path: String? = null
        private var vision = false
        private var instance: LlmInference? = null

        @Synchronized
        fun get(context: Context, modelPath: String, wantVision: Boolean): LlmInference {
            instance?.let { if (path == modelPath && vision == wantVision) return it }
            runCatching { instance?.close() }
            instance = null
            path = null
            val builder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
            if (wantVision) builder.setMaxNumImages(1)
            instance = LlmInference.createFromOptions(context.applicationContext, builder.build())
            path = modelPath
            vision = wantVision
            return instance!!
        }
    }

    private companion object {
        private const val BORING_FAST_PATH_ANALYSIS_PROMPT = "Cut pauses and dead air."
        private const val MAX_ROUTED_TOOL_DEFINITIONS = 16
        private const val MAX_ON_DEVICE_TOOLS = 10
        private const val MAX_TOOL_DESCRIPTION_CHARS = 96
        private const val MAX_TOOL_ARGS_CHARS = 96
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
