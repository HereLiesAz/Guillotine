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
        return when (provider) {
            AiProviderType.ANTHROPIC ->
                if (key.isNotBlank()) AnthropicAgentBackend(key, model, cloudFrames) else null

            AiProviderType.OPENAI ->
                if (key.isNotBlank()) OpenAiAgentBackend(key, OPENAI_ENDPOINT, model, "OpenAI", cloudFrames) else null

            AiProviderType.OPENROUTER, AiProviderType.GROQ, AiProviderType.XAI, AiProviderType.MISTRAL -> {
                val compatUrl = provider.meta.openAiCompatUrl
                if (key.isNotBlank() && compatUrl != null) {
                    OpenAiAgentBackend(key, compatUrl, model, provider.meta.label, cloudFrames)
                } else {
                    null
                }
            }

            AiProviderType.GEMINI ->
                if (key.isNotBlank()) GeminiAgentBackend(key, model, cloudFrames) else null

            AiProviderType.LOCAL, AiProviderType.MLKIT -> null
        }
    }

    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
}
