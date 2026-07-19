package com.hereliesaz.guillotine.desktop.ui

import com.hereliesaz.guillotine.ai.agent.AgentBackend
import com.hereliesaz.guillotine.ai.agent.AgentEvent
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.ui.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistantViewModel {

    data class UiState(
        val input: String = "",
        val status: String = "",
        val running: Boolean = false,
        val isError: Boolean = false,
        val awaitingReply: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastAssistantText: String = ""
    private var originalPrompt: String = ""
    private var lastQuestion: String = ""

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    fun run(instruction: String, tools: McpToolsSurface, agent: AgentBackend?) =
        startRun(instruction, tools, agent, logAs = instruction, isReply = false)

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

    private var vocabExpansionStarted = false

    /** Disk persistence for the LLM vocabulary expansion; defaults to the app data dir. */
    var vocabCache: com.hereliesaz.guillotine.ai.vocab.VocabularyCache? =
        com.hereliesaz.guillotine.desktop.platform.DesktopVocabularyCache

    /**
     * One-time background LLM vocabulary expansion (guarded; never blocks or throws). Loads the cached
     * expansion if present (no API call), else runs the model once and persists the result so the next
     * launch is offline. File IO is kept off the main thread.
     */
    private fun maybeExpandVocabulary(agent: AgentBackend) {
        if (vocabExpansionStarted) return
        vocabExpansionStarted = true
        scope.launch {
            try {
                val cache = vocabCache
                val cached = cache?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { it.load() } }
                val fresh = com.hereliesaz.guillotine.ai.vocab.VocabularyExpander.ensureExpanded(cached) { p ->
                    agent.complete(p)
                }
                if (fresh != null && cache != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.save(fresh) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // don't swallow cancellation — structured concurrency
            } catch (e: Exception) {
                // Vocabulary expansion is optional; ignore failures (the seed vocabulary stays in effect).
            }
        }
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
            val msg = "Add an API key in Settings to use the assistant."
            _state.update { it.copy(status = msg, isError = true) }
            ActivityLog.error(msg)
            return
        }
        // Grow the vocabulary graph via a one-time background LLM pass (guarded, non-blocking, silent on
        // failure) so later turns and lookup_vocabulary recognise more phrasings. Seed is the fallback.
        maybeExpandVocabulary(agent)
        ActivityLog.user(logAs)
        ActivityLog.info("Thinking…")
        if (!isReply) originalPrompt = logAs
        lastAssistantText = ""
        lastQuestion = ""
        _state.update {
            it.copy(running = true, isError = false, status = "Thinking…", input = "", awaitingReply = false)
        }
        scope.launch {
            try {
                agent.run(instruction, tools) { event -> apply(event) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = e.message ?: "Assistant failed", running = false, isError = true) }
            } finally {
                _state.update { if (it.running) it.copy(running = false) else it }
            }
        }
    }

    private fun apply(event: AgentEvent) {
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
                    st.copy(
                        status = "${if (event.isError) "✗" else "✓"} ${event.tool}: ${event.summary}",
                        isError = event.isError,
                    )
                is AgentEvent.AssistantText -> st.copy(status = event.text.take(160), isError = false)
                is AgentEvent.Done -> {
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
