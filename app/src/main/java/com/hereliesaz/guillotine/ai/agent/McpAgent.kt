package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.mcp.McpTools

/**
 * Picks the agent "brain" for the current settings, so the in-app AI can actually drive the
 * editor through the MCP tools.
 *
 * Selection (always-available, defaults to on-device):
 *  1. The selected provider, when it's a tool-calling LLM **and** its key is set → that cloud brain.
 *  2. Otherwise (the keyless on-device MLKit/Local analyzers, which can't tool-call) → the on-device
 *     LLM brain, if a `.task` model path is configured. On-device brain + on-device analysis =
 *     a fully offline assistant.
 *  3. Otherwise `null` — the command bar prompts the user to add a key or an on-device model.
 */
object McpAgent {

    fun forSettings(context: Context, settings: AiSettings, tools: McpTools): AgentBackend? {
        val provider = settings.provider
        val key = settings.keyFor(provider)
        val model = settings.modelFor(provider)
        return when (provider) {
            AiProviderType.ANTHROPIC ->
                if (key.isNotBlank()) AnthropicAgentBackend(key, model) else onDevice(context, settings)

            AiProviderType.OPENAI ->
                if (key.isNotBlank()) {
                    OpenAiAgentBackend(key, OPENAI_ENDPOINT, model, "OpenAI")
                } else {
                    onDevice(context, settings)
                }

            AiProviderType.OPENROUTER, AiProviderType.GROQ, AiProviderType.XAI, AiProviderType.MISTRAL ->
                if (key.isNotBlank()) {
                    OpenAiAgentBackend(key, provider.meta.openAiCompatUrl!!, model, provider.meta.label)
                } else {
                    onDevice(context, settings)
                }

            AiProviderType.GEMINI ->
                if (key.isNotBlank()) GeminiAgentBackend(key, model) else onDevice(context, settings)

            // Non-LLM on-device analyzers (the default): the brain is the on-device LLM if present.
            AiProviderType.LOCAL, AiProviderType.MLKIT -> onDevice(context, settings)
        }
    }

    private fun onDevice(context: Context, settings: AiSettings): AgentBackend? =
        settings.agentModelPath.takeIf { it.isNotBlank() }
            ?.let { OnDeviceAgentBackend(context, it) }

    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
}
