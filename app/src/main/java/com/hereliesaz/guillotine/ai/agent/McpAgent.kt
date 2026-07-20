package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.meta
import com.hereliesaz.guillotine.mcp.McpToolsSurface

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

    fun forSettings(context: Context, settings: AiSettings, tools: McpToolsSurface): AgentBackend? {
        val provider = settings.provider
        val key = settings.keyFor(provider)
        val model = settings.modelFor(provider)
        // The concrete tool surface knows how to decode the current frame. It's handed to the on-device
        // brain as raw-pixel vision (always local), and — ONLY when the user opted into cloud vision — to
        // a cloud brain as an encoded-image source. With the opt-in off, cloud brains stay text-only
        // (the on-device invariant: pixels never leave the device).
        val frames = tools as? FrameProvider
        val cloudFrames = if (settings.cloudVision) tools as? FrameImageSource else null
        return when (provider) {
            AiProviderType.ANTHROPIC ->
                if (key.isNotBlank()) AnthropicAgentBackend(key, model, cloudFrames) else onDevice(context, settings, frames)

            AiProviderType.OPENAI ->
                if (key.isNotBlank()) {
                    OpenAiAgentBackend(key, OPENAI_ENDPOINT, model, "OpenAI", cloudFrames)
                } else {
                    onDevice(context, settings, frames)
                }

            AiProviderType.OPENROUTER, AiProviderType.GROQ, AiProviderType.XAI, AiProviderType.MISTRAL -> {
                val compatUrl = provider.meta.openAiCompatUrl
                if (key.isNotBlank() && compatUrl != null) {
                    OpenAiAgentBackend(key, compatUrl, model, provider.meta.label, cloudFrames)
                } else {
                    onDevice(context, settings, frames)
                }
            }

            AiProviderType.GEMINI ->
                if (key.isNotBlank()) GeminiAgentBackend(key, model, cloudFrames) else onDevice(context, settings, frames)

            // Non-LLM on-device analyzers (the default): the brain is the on-device LLM if present.
            AiProviderType.LOCAL, AiProviderType.MLKIT -> onDevice(context, settings, frames)
        }
    }

    private fun onDevice(context: Context, settings: AiSettings, frames: FrameProvider?): AgentBackend? =
        com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "agentModelPath").takeIf { it.isNotBlank() }
            ?.let { OnDeviceAgentBackend(context, it, frames) }

    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
}
