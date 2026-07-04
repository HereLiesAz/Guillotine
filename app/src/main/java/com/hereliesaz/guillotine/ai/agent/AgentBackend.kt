package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.mcp.McpTools
import org.json.JSONObject

/**
 * One observation from a running agent, surfaced to the assistant's status line. The agent
 * drives the editor by calling the MCP tools, so these events let the UI show exactly which
 * tools it touched (and the timeline updates live as edits are applied).
 */
sealed class AgentEvent {
    /** The model decided to call [tool]; execution is about to start. */
    data class ToolStarted(val tool: String) : AgentEvent()

    /** [tool] finished; [summary] is a short human description, [isError] if it threw. */
    data class ToolFinished(val tool: String, val summary: String, val isError: Boolean) : AgentEvent()

    /** The model emitted prose (a plan or a partial answer) without a tool call. */
    data class AssistantText(val text: String) : AgentEvent()

    /** The run finished successfully; [summary] is the model's closing sentence. */
    data class Done(val summary: String) : AgentEvent()

    /** The run failed (network/auth/parse/no-model); [message] is user-facing. */
    data class Failed(val message: String) : AgentEvent()
}

/**
 * Drives the Guillotine editor by letting an LLM call the MCP tools in a loop
 * (tool-call → execute → result → repeat). Each backend owns its full multi-turn
 * conversation against one provider's wire format, executing every call **in-process**
 * via [McpTools.call] — the same object the embedded MCP server uses — so the in-app AI
 * exercises exactly the tooling external agents do.
 */
interface AgentBackend {
    /**
     * Run [instruction] to completion, executing tools via [tools] and reporting progress
     * through [onEvent]. Must not throw: failures are reported as [AgentEvent.Failed].
     */
    suspend fun run(instruction: String, tools: McpTools, onEvent: (AgentEvent) -> Unit)
}

/** Hard cap on tool round-trips so a confused model can't loop forever / burn tokens. */
internal const val MAX_AGENT_ITERATIONS = 12

/** Shared role prompt: tells the model it operates the editor purely through the tools. */
internal val AGENT_SYSTEM_PROMPT = """
    You operate the Guillotine video editor by calling tools. The user gives a high-level
    instruction; use the tools to inspect the timeline and edit it to satisfy them.

    Typical workflow:
    - call get_timeline to list clips and their ids (it also returns currentTimeMs, the playhead);
    - when the user points at the current preview ("this frame", "what's on screen", "the thing
      here"), call describe_current_frame FIRST to learn what's in it — the raw pixels stay on
      the device; you get back detected objects (labels + bounding boxes) and can then decide what
      to act on (e.g. use analyze_clip_with_reference to match the same object across the clip);
    - to CUT/REMOVE/DELETE content, set_prompt on a clip then call analyze_clip. This finds the matching
      frames AND performs the real cut in one step: the clip is split into its kept pieces and the matched
      ranges are deleted, the timeline closing up (no black gaps) — it actually shortens the video, it does
      not just mark or grey out frames. If the user points at the current frame — e.g. "this is my phone"
      or "the thing on screen now" — call analyze_clip_with_reference instead, so it matches THAT specific
      object across the clip using the frame the user scrubbed to (it cuts the same way);
    - distinguish CUT from ERASE: "cut/delete/trim/remove the frames with X" shortens the clip →
      analyze_clip. But "remove X but make it look natural / keep the length / like it was never there /
      erase X" means keep the clip the SAME length and repaint X out → call remove_object_generative (it
      generates inpainted replacement segments, grouped with the originals);
    - every edit is a REAL timeline operation the user could do by hand — splitting clips and deleting
      pieces. There is no "mark/script" mode that greys or skips frames; analyze_clip already splits the
      clip and deletes the matched pieces. For other manual edits use split_clip (at a timeline ms),
      delete_clip, segment_clip, or ripple_delete_range; use select_clip / get_clip as needed.

    "Keep only X" = analyze_clip for X — analysis removes the non-matching ranges and the cut is applied
    automatically. Clip ids always come from get_timeline / get_clip — never invent them. Keep calling
    tools until the instruction is satisfied, then give a single short sentence summarizing what you
    changed.

    CAPTIONS / TRANSCRIPTION:
    - "transcribe", "add captions/subtitles" → transcribe_clip: adds timed caption text clips synced to
      the spoken words;
    - "animated captions", "kinetic text", "per-word/syllable animation", "grow as said", "words appear
      as spoken" → animated_transcribe_clip: splits each word into syllables on separate tracks with
      scale keyframes that grow each syllable as it's spoken — kinetic typography;
    - when the user describes an animated or dynamic caption style without using exact keywords, prefer
      animated_transcribe_clip over plain transcribe_clip.

    USER-DEFINED TOOLS (editing methods):
    - The user can teach you named editing methods: "save this as X", "remember this method as X",
      "create a tool called X that does Y" → create_user_tool with the name and step-by-step description;
    - When the user says "do X on this clip" and X matches a saved method → run_user_tool, then FOLLOW
      the returned instructions by calling the appropriate tools on that clip;
    - list_user_tools shows all saved methods; delete_user_tool removes one.

    RECORDING EDITING METHODS:
    - "record what I do", "watch me edit this", "learn from my edits" → start_recording on the target clip;
    - while recording, every UI action the user performs (split, trim, delete, keyframe, filter change, etc.)
      is automatically captured — you don't need to do anything, just confirm recording has started;
    - "save that as X", "stop recording and call it X" → stop_recording with the name; the captured steps
      become the tool's description automatically;
    - the user can add caveats ("but adapt timings to clip length", "ignore exact positions") via the
      extra_instructions parameter of stop_recording, or edit the tool later with create_user_tool;
    - discard_recording cancels without saving.

    Prefer to act on reasonable defaults rather than pause to ask. Only ask a clarifying question when
    the instruction is genuinely ambiguous and no reasonable default exists (e.g. "shorten the video"
    without a target length, or two clips both matching "the intro"). When you do ask, end your turn
    with a single sentence ending in "?" and stop — the user's answer will come back as a new turn
    with the original request and your question quoted for context, so continue from there.
""".trimIndent()

/** Result of executing one tool: the JSON to feed back to the model, plus an error flag. */
internal data class ToolOutcome(val json: JSONObject, val isError: Boolean) {
    fun content(): String = json.toString()

    /**
     * A short, human-readable summary for the bottom-sheet log. Prefers the tool's own
     * [humanSummary] — every tool populates it with a plain-English description of what it did
     * (counts, ranges, decisions) so the user sees the model's work, not raw JSON. The other
     * branches are a legacy fallback for tools that predate humanSummary or return raw JSON.
     */
    fun summary(): String = when {
        isError -> json.optString("error", "error")
        json.has("humanSummary") -> json.optString("humanSummary")
        json.has("segmentsFound") -> "${json.optInt("segmentsFound")} segments"
        json.has("segmentsApplied") -> "${json.optInt("segmentsApplied")} applied"
        json.has("clipCount") -> "${json.optInt("clipCount")} clips"
        else -> "ok"
    }
}

/** Execute one MCP tool in-process, capturing thrown errors as a result the model can recover from. */
internal fun callTool(tools: McpTools, name: String, args: JSONObject): ToolOutcome =
    try {
        ToolOutcome(tools.call(name, args), isError = false)
    } catch (e: Exception) {
        ToolOutcome(JSONObject().put("error", e.message ?: "tool failed"), isError = true)
    }
