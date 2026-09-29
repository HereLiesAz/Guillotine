package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.agent.AgentBackend
import com.hereliesaz.guillotine.ai.agent.AnthropicAgentBackend
import com.hereliesaz.guillotine.ai.agent.FrameImageSource
import com.hereliesaz.guillotine.ai.agent.GeminiAgentBackend
import com.hereliesaz.guillotine.ai.agent.OpenAiAgentBackend
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.ai.meta

object DesktopMcpAgent {

    fun forSettings(settings: AiSettings, tools: McpToolsSurface? = null): AgentBackend? {
        val provider = settings.provider
        val key = settings.keyFor(provider)
        val model = settings.modelFor(provider)
        // Only when the user opted into cloud vision does a cloud brain get an encoded-image source;
        // otherwise it stays strictly text-only (no frame ever leaves the machine).
        val cloudFrames = if (settings.cloudVision) tools as? FrameImageSource else null

        // Desktop local assistants are intentionally a DIFFERENT catalog/runtime from Android:
        // Settings stores an Ollama selector as "ollama:<tag>" instead of pointing at a phone .task
        // or .litertlm file. The same MCP tool loop still runs in-process; only the LLM server is local.
        val localModel = settings.agentModelPath
            .takeIf { it.startsWith("ollama:") }
            ?.removePrefix("ollama:")
            ?.takeIf { it.isNotBlank() }
        val localBackend = localModel?.let { tag ->
            // Qwen3.5 and Gemma 4 Ollama variants are natively multimodal. Because this endpoint is
            // localhost, giving them FrameImageSource does NOT invoke the cloud-vision privacy path.
            // Text-only models (Phi-4 Mini / gpt-oss) keep vision delegated to specialist MCP tools.
            val localFrames = if (DesktopOllama.isMultimodal(tag)) {
                tools as? FrameImageSource
            } else {
                null
            }
            DesktopOllamaAgentBackend(tag, frames = localFrames)
        }

        // An installed azphalt `llm` package the user picked comes first; otherwise the usual selection.
        val brain = com.hereliesaz.guillotine.ai.agent.AzpLlmBrain.forSettings(
            java.io.File(DesktopStorage.dataDir, "extensions"),
            DesktopPluginApplier.HOST_APP_ID,
            settings,
        ) ?: when (provider) {
            AiProviderType.ANTHROPIC ->
                if (key.isNotBlank()) AnthropicAgentBackend(key, model, cloudFrames) else localBackend

            AiProviderType.OPENAI ->
                if (key.isNotBlank()) OpenAiAgentBackend(key, OPENAI_ENDPOINT, model, "OpenAI", cloudFrames)
                else localBackend

            AiProviderType.OPENROUTER, AiProviderType.GROQ, AiProviderType.XAI, AiProviderType.MISTRAL -> {
                val compatUrl = provider.meta.openAiCompatUrl
                if (key.isNotBlank() && compatUrl != null) {
                    OpenAiAgentBackend(key, compatUrl, model, provider.meta.label, cloudFrames)
                } else {
                    localBackend
                }
            }

            AiProviderType.GEMINI ->
                if (key.isNotBlank()) GeminiAgentBackend(key, model, cloudFrames) else localBackend

            AiProviderType.LOCAL, AiProviderType.MLKIT -> localBackend
        }

        return com.hereliesaz.guillotine.ai.agent.AzpLlmBrain.withSandboxTextJobs(
            brain?.let { DesktopDelegatingAgentBackend(settings, it) },
            settings,
        )
    }

    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
}
