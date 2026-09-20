package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.agent.AgentBackend
import com.hereliesaz.guillotine.ai.agent.AgentEvent
import com.hereliesaz.guillotine.ai.agent.FrameImageSource
import com.hereliesaz.guillotine.ai.agent.OpenAiAgentBackend
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local desktop assistant backed by Ollama's OpenAI-compatible endpoint.
 *
 * The model stays entirely on the workstation. We use the same tool-calling backend as cloud
 * providers, but point it at localhost and never require an API key.
 */
class DesktopOllamaAgentBackend(
    private val model: String,
    frames: FrameImageSource? = null,
) : AgentBackend {
    private val delegate = OpenAiAgentBackend(
        apiKey = "ollama",
        endpoint = "http://127.0.0.1:11434/v1/chat/completions",
        model = model,
        label = "Ollama",
        frames = frames,
    )

    override suspend fun run(
        instruction: String,
        tools: McpToolsSurface,
        onEvent: (AgentEvent) -> Unit,
    ) {
        val ready = withContext(Dispatchers.IO) { DesktopOllama.ensureRunning() }
        if (!ready) {
            onEvent(
                AgentEvent.Failed(
                    "Ollama is not installed or its local server could not start. " +
                        "Install Ollama, then choose a desktop-local model in Settings.",
                ),
            )
            return
        }
        delegate.run(instruction, tools, onEvent)
    }

    override suspend fun complete(prompt: String): String? {
        val ready = withContext(Dispatchers.IO) { DesktopOllama.ensureRunning() }
        if (!ready) return null
        return delegate.complete(prompt)
    }

    override fun reset() = delegate.reset()
}
