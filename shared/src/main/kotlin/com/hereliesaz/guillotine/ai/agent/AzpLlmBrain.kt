package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.azphalt.AzpLlm
import java.io.File

/**
 * The assistant brain from an installed azphalt `llm` package, when the user picked one
 * ([AiSettings.azpLlmId]). An extra option next to the built-in providers, not a replacement for them:
 * returns null (so the platform factory falls through to its usual selection) when nothing is picked,
 * the package is gone or unusable, or it needs a key that isn't set.
 *
 * Always text-only: no frame source is passed, whatever the cloud-vision opt-in says. A package declares
 * nothing about image support, so offering `look_at_frame` would be a guess that ships pixels.
 */
object AzpLlmBrain {

    fun forSettings(extensionsDir: File, hostAppId: String, settings: AiSettings): AgentBackend? {
        val endpoint = AzpLlm.find(extensionsDir, hostAppId, settings.azpLlmId) ?: return null
        val key = settings.azpLlmKeys[endpoint.packageId].orEmpty()
        if (endpoint.keyRequired && key.isBlank()) return null
        val model = settings.azpLlmModels[endpoint.packageId]?.takeIf { it.isNotBlank() } ?: endpoint.defaultModel
        return OpenAiAgentBackend(
            apiKey = if (endpoint.auth == AzpLlm.AUTH_NONE) "" else key,
            endpoint = endpoint.chatCompletionsUrl,
            model = model,
            label = endpoint.name,
            frames = null,
        )
    }
}
