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
    - to find content, set_prompt on a clip then call analyze_clip (runs on-device vision and marks
      keep/remove ranges). If the user points at the current frame — e.g. "this is my phone" or "the
      thing on screen now" — call analyze_clip_with_reference instead, so it matches THAT specific
      object across the clip using the frame the user scrubbed to;
    - to actually REMOVE/CUT/DELETE content (not just mark it), call apply_cuts on the clip: the kept
      ranges become separate clips grouped together and the removed ranges are deleted with the
      timeline closing up (no black gaps). Just marking with analyze_clip/apply_edits does NOT remove
      anything on screen — you must call apply_cuts to make the cut real;
    - for manual edits use split_clip (at a timeline ms), delete_clip, segment_clip, or
      ripple_delete_range; use select_clip / get_clip as needed.

    "Keep only X" = analyze for X (analysis marks the rest REMOVE) then apply_cuts. Clip ids always
    come from get_timeline / get_clip — never invent them. Keep calling tools until the instruction is
    satisfied, then give a single short sentence summarizing what you changed. Do not ask the user
    questions; act on reasonable defaults.
""".trimIndent()

/** Result of executing one tool: the JSON to feed back to the model, plus an error flag. */
internal data class ToolOutcome(val json: JSONObject, val isError: Boolean) {
    fun content(): String = json.toString()

    /** A short, human-readable summary for the status line. */
    fun summary(): String = when {
        isError -> json.optString("error", "error")
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
