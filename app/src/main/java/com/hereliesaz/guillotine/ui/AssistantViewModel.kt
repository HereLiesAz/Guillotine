package com.hereliesaz.guillotine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.guillotine.ai.agent.AgentBackend
import com.hereliesaz.guillotine.ai.agent.AgentEvent
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State + driver for the assistant command bar. Owns its own small UI state (kept separate from
 * [com.hereliesaz.guillotine.editor.EditorViewModel], which owns the document/undo history). The
 * agent it runs drives the editor through the MCP tools, so the timeline updates live; this VM
 * tracks the input text, a one-line status, and — for interactive clarification — the last question
 * the agent asked plus the original prompt, so a follow-up reply can carry both back as context.
 */
class AssistantViewModel : ViewModel() {

    data class UiState(
        val input: String = "",
        val status: String = "",
        val running: Boolean = false,
        val isError: Boolean = false,
        /**
         * True after a run finishes with the agent asking a clarifying question (last chat prose
         * ended with "?"). The bottom sheet shows a reply input while this is true, and sending it
         * kicks off a new run that carries the original prompt + question + reply as context.
         */
        val awaitingReply: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Remembered across [apply] events so we can detect the "ended with a question" case at Done,
    // and so [sendReply] has the original prompt + question to feed back as context.
    private var lastAssistantText: String = ""
    private var originalPrompt: String = ""
    private var lastQuestion: String = ""

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    /**
     * Run [instruction] with [agent], reporting progress on the status line. A null [agent] means
     * no brain is configured, so we point the user at Settings instead of failing silently.
     */
    fun run(instruction: String, tools: McpToolsSurface, agent: AgentBackend?) =
        startRun(instruction, tools, agent, logAs = instruction, isReply = false)

    /**
     * Continue a paused-for-clarification exchange: [reply] is the user's answer to the question the
     * agent last asked. Feeds the original prompt + question + reply back as a single new turn so the
     * agent has full context to resume (the backends are one-shot per [AgentBackend.run], so we
     * synthesize continuity at the prompt layer instead of teaching every backend a message log).
     */
    fun sendReply(reply: String, tools: McpToolsSurface, agent: AgentBackend?) {
        val r = reply.trim()
        if (r.isBlank() || !_state.value.awaitingReply) return
        val prompt = buildString {
            append("You were working on this request:\n")
            append(originalPrompt.ifBlank { "(no earlier request recorded)" })
            append("\n\nYou asked:\n")
            append(lastQuestion.ifBlank { "(no question captured — check the log)" })
            append("\n\nMy answer:\n")
            append(r)
            append("\n\nPlease continue the request now. Do not ask again unless something is still ambiguous.")
        }
        _state.update { it.copy(awaitingReply = false) }
        startRun(prompt, tools, agent, logAs = r, isReply = true)
    }

    private fun startRun(
        instruction: String,
        tools: McpToolsSurface,
        agent: AgentBackend?,
        logAs: String,
        isReply: Boolean,
    ) {
        if (instruction.isBlank() || _state.value.running) return
        if (agent == null) {
            val msg = "Add an API key, or set an on-device model path, in Settings to use the assistant."
            _state.update { it.copy(status = msg, isError = true) }
            ActivityLog.error(msg)
            return
        }
        // Log only what the user actually typed (the raw reply on a reply, the raw prompt otherwise) —
        // the synthesized continuation prompt is verbose and belongs to the agent, not the user log.
        ActivityLog.user(logAs)
        ActivityLog.info("Thinking…")
        // The original prompt is what the first user turn was — remember it for later replies. A reply
        // keeps the earlier originalPrompt; a fresh run resets it.
        if (!isReply) originalPrompt = logAs
        lastAssistantText = ""
        lastQuestion = ""
        _state.update {
            it.copy(running = true, isError = false, status = "Thinking…", input = "", awaitingReply = false)
        }
        viewModelScope.launch {
            try {
                agent.run(instruction, tools) { event -> apply(event) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // let cancellation propagate cleanly instead of showing a false error
            } catch (e: Exception) {
                _state.update { it.copy(status = e.message ?: "Assistant failed", running = false, isError = true) }
            } finally {
                _state.update { if (it.running) it.copy(running = false) else it }
            }
        }
    }

    private fun apply(event: AgentEvent) {
        // Mirror every event into the shared activity log (the bottom sheet) as well as the
        // one-line status the tool strip reads for the spinner.
        when (event) {
            is AgentEvent.ToolStarted -> ActivityLog.info("→ ${event.tool}…")
            is AgentEvent.ToolFinished ->
                if (event.isError) ActivityLog.error("${event.tool}: ${event.summary}")
                else ActivityLog.success("${event.tool}: ${event.summary}")
            is AgentEvent.AssistantText -> {
                lastAssistantText = event.text
                ActivityLog.chat(event.text)
            }
            is AgentEvent.Done -> ActivityLog.success(event.summary.ifBlank { "Done." })
            is AgentEvent.Failed -> ActivityLog.error(event.message)
        }
        _state.update { st ->
            when (event) {
                is AgentEvent.ToolStarted -> st.copy(status = "→ ${event.tool}…", isError = false)
                is AgentEvent.ToolFinished ->
                    st.copy(status = "${if (event.isError) "✗" else "✓"} ${event.tool}: ${event.summary}", isError = event.isError)
                is AgentEvent.AssistantText -> st.copy(status = event.text.take(160), isError = false)
                is AgentEvent.Done -> {
                    // If the agent's LAST prose ended with a question mark, we treat this as a
                    // clarification pause: keep the question and flip awaitingReply so the sheet
                    // shows a reply input. Otherwise the run is truly done.
                    val q = lastAssistantText.trim()
                    val isQuestion = q.endsWith("?")
                    if (isQuestion) lastQuestion = q
                    st.copy(
                        status = if (isQuestion) q.take(160) else event.summary.ifBlank { "Done." },
                        running = false,
                        isError = false,
                        awaitingReply = isQuestion,
                    )
                }
                is AgentEvent.Failed -> st.copy(status = event.message, running = false, isError = true)
            }
        }
    }
}
