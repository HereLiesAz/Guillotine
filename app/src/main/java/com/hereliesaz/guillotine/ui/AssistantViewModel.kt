package com.hereliesaz.guillotine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.guillotine.ai.agent.AgentBackend
import com.hereliesaz.guillotine.ai.agent.AgentEvent
import com.hereliesaz.guillotine.mcp.McpTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State + driver for the assistant command bar. Owns its own small UI state (kept separate from
 * [com.hereliesaz.guillotine.editor.EditorViewModel], which owns the document/undo history). The
 * agent it runs drives the editor through the MCP tools, so the timeline updates live; this VM only
 * tracks the input text and a single status/result line.
 */
class AssistantViewModel : ViewModel() {

    data class UiState(
        val input: String = "",
        val status: String = "",
        val running: Boolean = false,
        val isError: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    /**
     * Run [instruction] with [agent], reporting progress on the status line. A null [agent] means
     * no brain is configured, so we point the user at Settings instead of failing silently.
     */
    fun run(instruction: String, tools: McpTools, agent: AgentBackend?) {
        if (instruction.isBlank() || _state.value.running) return
        if (agent == null) {
            _state.update {
                it.copy(
                    status = "Add an API key, or set an on-device model path, in Settings to use the assistant.",
                    isError = true,
                )
            }
            return
        }
        _state.update { it.copy(running = true, isError = false, status = "Thinking…", input = "") }
        viewModelScope.launch {
            try {
                agent.run(instruction, tools) { event -> apply(event) }
            } catch (e: Exception) {
                _state.update { it.copy(status = e.message ?: "Assistant failed", running = false, isError = true) }
            } finally {
                _state.update { if (it.running) it.copy(running = false) else it }
            }
        }
    }

    private fun apply(event: AgentEvent) = _state.update { st ->
        when (event) {
            is AgentEvent.ToolStarted -> st.copy(status = "→ ${event.tool}…", isError = false)
            is AgentEvent.ToolFinished ->
                st.copy(status = "${if (event.isError) "✗" else "✓"} ${event.tool}: ${event.summary}", isError = event.isError)
            is AgentEvent.AssistantText -> st.copy(status = event.text.take(160), isError = false)
            is AgentEvent.Done -> st.copy(status = event.summary.ifBlank { "Done." }, running = false, isError = false)
            is AgentEvent.Failed -> st.copy(status = event.message, running = false, isError = true)
        }
    }
}
