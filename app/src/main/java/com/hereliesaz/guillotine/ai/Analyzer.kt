package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.MediaKind

/** A source of keep/remove suggestions for one clip's media. */
interface ClipAnalyzer {
    suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
    ): List<EditSegment>
}

/**
 * Routes an analysis request to the configured provider. LOCAL never needs a key
 * or network; GEMINI uses the user's own key. Throws on provider error so the UI
 * can surface it and the user can fall back to LOCAL.
 */
object Analysis {
    suspend fun run(
        context: Context,
        settings: AiSettings,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
    ): List<EditSegment> {
        val key = settings.keyFor(settings.provider)
        if (settings.provider != AiProviderType.LOCAL) {
            require(key.isNotBlank()) {
                "Add your ${settings.provider.meta.label} API key in Settings, or use the free Local analyzer."
            }
        }
        return when (settings.provider) {
            AiProviderType.LOCAL -> LocalHeuristicProvider.analyze(context, mediaUri, kind, prompt, durationMs)
            AiProviderType.GEMINI -> GeminiProvider(key).analyze(context, mediaUri, kind, prompt, durationMs)
            AiProviderType.OPENAI -> OpenAiProvider(key).analyze(context, mediaUri, kind, prompt, durationMs)
            AiProviderType.ANTHROPIC -> AnthropicProvider(key).analyze(context, mediaUri, kind, prompt, durationMs)
            else -> {
                // OpenRouter / Groq / xAI / Mistral — generic OpenAI-compatible endpoint.
                val meta = settings.provider.meta
                OpenAiCompatibleProvider(
                    apiKey = key,
                    endpoint = requireNotNull(meta.openAiCompatUrl),
                    model = requireNotNull(meta.openAiCompatModel),
                    label = meta.label,
                ).analyze(context, mediaUri, kind, prompt, durationMs)
            }
        }
    }
}
