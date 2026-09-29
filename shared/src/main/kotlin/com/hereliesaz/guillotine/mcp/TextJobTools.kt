package com.hereliesaz.guillotine.mcp

import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.TimelineClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Background text jobs over a clip's captions, shared by both platforms: `summarize_transcript` and
 * `rewrite_captions` return at once with a job id, run the prompt through [completer] off the agent's
 * turn, and land the result on the timeline when it arrives (a summary title card; rewritten caption
 * text). `text_job_status` reports progress.
 *
 * [completer] is the assistant's `AgentBackend.complete`, set by the assistant view-model. With
 * `AzpLlmBrain.withSandboxTextJobs` in front of it, jobs run on the sandbox-installed azphalt `llm`
 * package picked under "Background text jobs" (minutes per call, which is why they don't block);
 * otherwise on the assistant's own brain.
 */
object TextJobTools {

    /** The text completer jobs run on; null until the assistant has a configured brain. */
    @Volatile
    var completer: (suspend (String) -> String?)? = null

    enum class State { RUNNING, DONE, FAILED }

    data class Job(val id: String, val kind: String, val clipId: String, val state: State, val detail: String = "")

    private val jobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cap on caption text sent per job, so one long clip can't produce an unbounded prompt. */
    private const val MAX_CHARS = 24_000
    private const val SUMMARY_CARD_MS = 6_000L

    val names: Set<String> = setOf("summarize_transcript", "rewrite_captions", "text_job_status")

    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } })
            .put("required", JSONArray(required))

    fun toolDefinitions(): JSONArray = JSONArray().apply {
        put(toolDefinition("summarize_transcript",
            "Summarize a clip's captions IN THE BACKGROUND and drop the summary on the timeline as a text " +
                "card at the clip's start when done. Returns immediately with a job id; do not wait for it. " +
                "Needs captions first (transcribe_clip).",
            schema(
                "clip_id" to stringProp("The captioned source clip (or one of its caption clips)."),
                "instruction" to stringProp("Optional: how to summarize (default: one or two sentences)."),
                required = listOf("clip_id"))))
        put(toolDefinition("rewrite_captions",
            "Rewrite every caption of a clip IN THE BACKGROUND (tighten, fix grammar, change tone, translate…) " +
                "and replace the caption text in place when done. Timing is kept. Returns immediately with a " +
                "job id; do not wait for it. Needs captions first (transcribe_clip).",
            schema(
                "clip_id" to stringProp("The captioned source clip (or one of its caption clips)."),
                "instruction" to stringProp("How to rewrite, e.g. \"shorter\", \"translate to Spanish\"."),
                required = listOf("clip_id", "instruction"))))
        put(toolDefinition("text_job_status",
            "Status of background text jobs (summarize_transcript, rewrite_captions). Omit job_id for all.",
            schema("job_id" to stringProp("Optional job id."))))
    }

    fun call(vm: EditorViewModel, name: String, args: JSONObject): JSONObject = when (name) {
        "summarize_transcript" -> summarize(vm, args.getString("clip_id"), args.optString("instruction"))
        "rewrite_captions" -> rewrite(vm, args.getString("clip_id"), args.getString("instruction"))
        "text_job_status" -> status(args.optString("job_id"))
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    /**
     * The caption TEXT clips grouped with [clipId]'s source (as `transcribe_clip` groups them), in time
     * order. [clipId] may be the source or any of its captions.
     */
    fun captionsFor(doc: Document, clipId: String): List<TimelineClip> {
        val clip = doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
        val gid = clip.groupId ?: return if (clip.type == ClipType.TEXT) listOf(clip) else emptyList()
        return doc.clips
            .filter { it.groupId == gid && it.type == ClipType.TEXT && it.text.isNotBlank() }
            .sortedBy { it.startTimeMs }
    }

    fun summaryPrompt(lines: List<String>, instruction: String): String =
        "Summarize this video transcript. ${instruction.ifBlank { "One or two sentences." }}\n" +
            "Reply with the summary text only.\n\nTranscript:\n" + lines.joinToString(" ").take(MAX_CHARS)

    fun rewritePrompt(lines: List<String>, instruction: String): String =
        "Rewrite each numbered caption line: $instruction\n" +
            "Keep the same number of lines and the numbering (\"N. text\"), one caption per line. " +
            "Reply with the numbered lines only.\n\n" +
            lines.mapIndexed { i, s -> "${i + 1}. ${s.replace('\n', ' ')}" }.joinToString("\n").take(MAX_CHARS)

    /** Parse a numbered rewrite back into exactly [count] lines, or null when the model lost lines. */
    fun parseRewrite(raw: String, count: Int): List<String>? {
        val byNumber = HashMap<Int, String>()
        val line = Regex("""^\s*(\d+)[.):]\s*(.*\S)\s*$""")
        for (l in raw.lines()) {
            val m = line.find(l) ?: continue
            val n = m.groupValues[1].toInt()
            if (n in 1..count && n !in byNumber) byNumber[n] = m.groupValues[2]
        }
        return if (byNumber.size == count) (1..count).map { byNumber.getValue(it) } else null
    }

    private fun summarize(vm: EditorViewModel, clipId: String, instruction: String): JSONObject {
        val doc = vm.uiState.value.document
        val captions = captionsFor(doc, clipId)
        require(captions.isNotEmpty()) { "No captions on $clipId yet. Run transcribe_clip first." }
        val source = doc.clips.first { it.id == clipId }.let { c ->
            doc.clips.firstOrNull { it.groupId != null && it.groupId == c.groupId && it.type != ClipType.TEXT } ?: c
        }
        val prompt = summaryPrompt(captions.map { it.text }, instruction)
        return launch("summarize_transcript", clipId, prompt) { raw ->
            val text = raw.trim().ifBlank { throw IllegalStateException("The model returned no summary.") }
            val track = vm.uiState.value.document.videoTracks.firstOrNull()
                ?: throw IllegalStateException("No video track to put the summary on.")
            val id = vm.addTextClip(track, text, source.startTimeMs, minOf(source.durationMs, SUMMARY_CARD_MS))
            "Summary added as text clip $id."
        }
    }

    private fun rewrite(vm: EditorViewModel, clipId: String, instruction: String): JSONObject {
        val captions = captionsFor(vm.uiState.value.document, clipId)
        require(captions.isNotEmpty()) { "No captions on $clipId yet. Run transcribe_clip first." }
        val prompt = rewritePrompt(captions.map { it.text }, instruction)
        return launch("rewrite_captions", clipId, prompt) { raw ->
            val lines = parseRewrite(raw, captions.size)
                ?: throw IllegalStateException("The model didn't return all ${captions.size} caption lines.")
            // Only captions that still exist; ones deleted while the job ran stay deleted.
            val live = vm.uiState.value.document.clips.mapTo(HashSet()) { it.id }
            var n = 0
            captions.zip(lines).forEach { (c, t) -> if (c.id in live) { vm.setClipText(c.id, t); n++ } }
            "Rewrote $n captions."
        }
    }

    private fun launch(kind: String, clipId: String, prompt: String, apply: (String) -> String): JSONObject {
        val complete = completer ?: throw IllegalStateException(
            "No text model available. Configure an assistant brain (Settings → AI) first.",
        )
        val id = "tj-" + java.util.UUID.randomUUID().toString().take(8)
        jobs[id] = Job(id, kind, clipId, State.RUNNING)
        scope.launch {
            val done = try {
                val raw = complete(prompt) ?: throw IllegalStateException("The model returned nothing.")
                jobs.getValue(id).copy(state = State.DONE, detail = apply(raw))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                jobs.getValue(id).copy(state = State.FAILED, detail = e.message ?: e.toString())
            }
            jobs[id] = done
            com.hereliesaz.guillotine.ui.ActivityLog.info("$kind ${done.state.name.lowercase()}: ${done.detail}")
        }
        return JSONObject()
            .put("ok", true)
            .put("job_id", id)
            .put("humanSummary", "Started $kind in the background (job $id). The result lands on the timeline when it's done.")
    }

    private fun status(jobId: String): JSONObject {
        val list = if (jobId.isBlank()) jobs.values.sortedBy { it.id } else listOfNotNull(jobs[jobId])
        require(jobId.isBlank() || list.isNotEmpty()) { "Unknown job: $jobId" }
        return JSONObject().put("ok", true).put(
            "jobs",
            JSONArray(list.map {
                JSONObject().put("job_id", it.id).put("kind", it.kind).put("clip_id", it.clipId)
                    .put("state", it.state.name.lowercase()).put("detail", it.detail)
            }),
        ).put("humanSummary", if (list.isEmpty()) "No background text jobs." else list.joinToString("; ") { "${it.id} ${it.kind}: ${it.state.name.lowercase()}" })
    }
}
