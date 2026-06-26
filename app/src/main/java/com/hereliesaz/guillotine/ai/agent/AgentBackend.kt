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
    instruction; use the tools to inspect the timeline and apply edits to satisfy it.

    Typical workflow:
    - call get_timeline to list clips and their ids,
    - for keep/remove work, set_prompt on a clip then call analyze_clip (it runs the user's
      configured analyzer and applies the result), or call apply_edits directly with explicit
      {startMs, endMs, action:"keep"|"remove"} segments,
    - use select_clip / get_clip as needed.

    Clip ids always come from get_timeline or get_clip — never invent them. Keep calling tools
    until the instruction is satisfied, then give a single short sentence summarizing what you
    changed. Do not ask the user questions; act on reasonable defaults.
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
