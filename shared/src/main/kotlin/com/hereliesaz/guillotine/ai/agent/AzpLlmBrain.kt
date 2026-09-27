package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.azphalt.AzpLlm
import com.hereliesaz.guillotine.azphalt.AzpLlmDelimiters
import com.hereliesaz.guillotine.azphalt.AzpLlmSandbox
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
        // Only a package that speaks openai-chat can drive the editor: the runner protocol takes minutes
        // per call and has no tool calling (see AzpLlmSandbox for what it's used for instead).
        val endpoint = AzpLlm.find(extensionsDir, hostAppId, settings.azpLlmId)?.takeIf { it.supportsChat } ?: return null
        val key = settings.azpLlmKeys[endpoint.packageId].orEmpty()
        if (endpoint.keyRequired && key.isBlank()) return null
        val model = settings.azpLlmModels[endpoint.packageId]?.takeIf { it.isNotBlank() } ?: endpoint.defaultModel
        return OpenAiAgentBackend(
            apiKey = if (endpoint.auth == AzpLlm.AUTH_NONE) "" else key,
            endpoint = endpoint.chatCompletionsUrl,
            model = model,
            label = endpoint.name,
            frames = null,
            // The host is the rolling-delimiter translator for openai-chat (spec/llm.md § Rolling delimiters).
            delimiters = AzpLlmDelimiters.Session(),
        )
    }

    /**
     * Routes [brain]'s background text jobs ([AgentBackend.complete]) to the sandbox-installed `llm`
     * package the user picked ([AiSettings.azpLlmTextId]), leaving its interactive [AgentBackend.run]
     * untouched. Returns [brain] unchanged when nothing is picked, the pick isn't set up, or there's no
     * token. A failed sandbox run falls back to [brain]'s own completion.
     */
    fun withSandboxTextJobs(brain: AgentBackend?, settings: AiSettings): AgentBackend? {
        if (brain == null) return null
        val id = settings.azpLlmTextId.takeIf { it.isNotBlank() } ?: return brain
        val install = settings.azpSandboxInstalls[id]?.let { AzpLlmSandbox.Install.fromJson(it) } ?: return brain
        val token = settings.azpSandboxToken.takeIf { it.isNotBlank() } ?: return brain
        return SandboxTextBackend(brain, AzpLlmSandbox.GitHub(token), install)
    }

    private class SandboxTextBackend(
        private val delegate: AgentBackend,
        private val github: AzpLlmSandbox.GitHub,
        private val install: AzpLlmSandbox.Install,
    ) : AgentBackend by delegate {
        override suspend fun complete(prompt: String): String? =
            try {
                AzpLlmSandbox.complete(github, install, prompt) ?: delegate.complete(prompt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                delegate.complete(prompt)
            }
    }
}
