package com.hereliesaz.guillotine.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Which analyzer to use. LOCAL is free and needs no key; the rest are bring-your-own.
 * GEMINI / OPENAI / ANTHROPIC have dedicated clients; the remainder are reached through
 * a generic OpenAI-compatible client (see [meta]).
 */
enum class AiProviderType { LOCAL, MLKIT, GEMINI, OPENAI, ANTHROPIC, OPENROUTER, GROQ, XAI, MISTRAL }

/** Display + routing info for a provider, including where the user gets an API key. */
data class ProviderMeta(
    val label: String,
    val blurb: String,
    /** Where to obtain a key. null for LOCAL (no key needed). */
    val keyUrl: String? = null,
    /** Chat-completions endpoint for generic OpenAI-compatible providers (else null → dedicated client). */
    val openAiCompatUrl: String? = null,
    /** Default (user-editable) model id. null only for LOCAL. */
    val defaultModel: String? = null,
)

/**
 * Per-provider metadata. Model ids for the OpenAI-compatible providers are reasonable
 * current defaults and may need bumping as providers rotate their model line-up.
 */
val AiProviderType.meta: ProviderMeta
    get() = when (this) {
        AiProviderType.LOCAL -> ProviderMeta(
            "Local (free, on-device)",
            "Cuts silences. No key, works offline.",
        )
        AiProviderType.MLKIT -> ProviderMeta(
            "On-device vision (free)",
            "Faces & objects, no key — keep/remove by what's on screen.",
        )
        AiProviderType.GEMINI -> ProviderMeta(
            "Gemini",
            "Google · video-native analysis.",
            keyUrl = "https://aistudio.google.com/app/apikey",
            defaultModel = "gemini-2.5-flash",
        )
        AiProviderType.OPENAI -> ProviderMeta(
            "OpenAI",
            "GPT-4o frame sampling + Whisper audio.",
            keyUrl = "https://platform.openai.com/api-keys",
            defaultModel = "gpt-4o",
        )
        AiProviderType.ANTHROPIC -> ProviderMeta(
            "Anthropic",
            "Claude · video frame analysis.",
            keyUrl = "https://console.anthropic.com/settings/keys",
            defaultModel = "claude-opus-4-8",
        )
        AiProviderType.OPENROUTER -> ProviderMeta(
            "OpenRouter",
            "One key, many vision models (frames).",
            keyUrl = "https://openrouter.ai/keys",
            openAiCompatUrl = "https://openrouter.ai/api/v1/chat/completions",
            defaultModel = "openai/gpt-4o-mini",
        )
        AiProviderType.GROQ -> ProviderMeta(
            "Groq",
            "Fast Llama 4 vision (frames).",
            keyUrl = "https://console.groq.com/keys",
            openAiCompatUrl = "https://api.groq.com/openai/v1/chat/completions",
            defaultModel = "meta-llama/llama-4-scout-17b-16e-instruct",
        )
        AiProviderType.XAI -> ProviderMeta(
            "xAI (Grok)",
            "Grok vision (frames).",
            keyUrl = "https://console.x.ai",
            openAiCompatUrl = "https://api.x.ai/v1/chat/completions",
            defaultModel = "grok-2-vision-1212",
        )
        AiProviderType.MISTRAL -> ProviderMeta(
            "Mistral",
            "Pixtral vision (frames).",
            keyUrl = "https://console.mistral.ai/api-keys",
            openAiCompatUrl = "https://api.mistral.ai/v1/chat/completions",
            defaultModel = "pixtral-12b-2409",
        )
    }

/** Bring-your-own-key providers — those that actually need a key (have a signup URL). */
val byoProviders: List<AiProviderType> = AiProviderType.values().filter { it.meta.keyUrl != null }

data class AiSettings(
    // ML Kit is the default: on-device, no key required, real vision (face/label) analysis.
    val provider: AiProviderType = AiProviderType.MLKIT,
    /** API keys per provider; LOCAL has none. */
    val keys: Map<AiProviderType, String> = emptyMap(),
    /** Optional per-provider model overrides; blank/absent falls back to [ProviderMeta.defaultModel]. */
    val models: Map<AiProviderType, String> = emptyMap(),
    /** Optional Leonardo.ai API key for cloud image generation (else free Pollinations). */
    val leonardoKey: String = "",
    /** Selected Leonardo platform model id (see ImageGen.LeonardoModels). */
    val leonardoModel: String = com.hereliesaz.guillotine.ai.ImageGen.LeonardoDefaultModel,
    /** Optional on-device Vosk speech model directory (else cloud Whisper for transcription). */
    val speechModelPath: String = "",
    /** Optional on-device LLM `.task` model path — the assistant's offline brain (else a cloud key). */
    val agentModelPath: String = "",
) {
    fun keyFor(p: AiProviderType): String = keys[p].orEmpty()
    /** The effective model id for [p]: the user's override if set, else the code default. */
    fun modelFor(p: AiProviderType): String =
        models[p]?.takeIf { it.isNotBlank() } ?: p.meta.defaultModel.orEmpty()
    fun withKey(p: AiProviderType, key: String): AiSettings = copy(keys = keys + (p to key))
}

/**
 * Persists the chosen provider and the user's own API keys, **encrypted on-device**
 * via Jetpack Security ([EncryptedSharedPreferences] + a Keystore-backed master key).
 * No key is ever required (on-device ML Kit is the default), and keys never leave the
 * device except in the direct provider request the user initiated.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "guillotine_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private fun read(): AiSettings = AiSettings(
        provider = prefs.getString(KEY_PROVIDER, null)
            ?.let { runCatching { AiProviderType.valueOf(it) }.getOrNull() }
            ?: AiProviderType.MLKIT,
        keys = byoProviders.associateWith { prefs.getString(keyPref(it), "").orEmpty() }
            .filterValues { it.isNotEmpty() },
        models = byoProviders.associateWith { prefs.getString(modelPref(it), "").orEmpty() }
            .filterValues { it.isNotEmpty() },
        leonardoKey = prefs.getString(KEY_LEONARDO_KEY, "").orEmpty(),
        leonardoModel = prefs.getString(KEY_LEONARDO_MODEL, "")
            ?.takeIf { it.isNotBlank() } ?: ImageGen.LeonardoDefaultModel,
        speechModelPath = prefs.getString(KEY_SPEECH, "").orEmpty(),
        agentModelPath = prefs.getString(KEY_AGENT_MODEL, "").orEmpty(),
    )

    suspend fun save(settings: AiSettings) {
        withContext(Dispatchers.IO) {
            prefs.edit().apply {
                putString(KEY_PROVIDER, settings.provider.name)
                byoProviders.forEach {
                    putString(keyPref(it), settings.keyFor(it))
                    putString(modelPref(it), settings.models[it].orEmpty())
                }
                putString(KEY_LEONARDO_KEY, settings.leonardoKey)
                putString(KEY_LEONARDO_MODEL, settings.leonardoModel)
                putString(KEY_SPEECH, settings.speechModelPath)
                putString(KEY_AGENT_MODEL, settings.agentModelPath)
            }.apply()
        }
        _settings.value = settings
    }

    private companion object {
        const val KEY_PROVIDER = "ai_provider"
        const val KEY_LEONARDO_KEY = "leonardo_key"
        const val KEY_LEONARDO_MODEL = "leonardo_model"
        const val KEY_SPEECH = "speech_model_path"
        const val KEY_AGENT_MODEL = "agent_model_path"
        fun keyPref(p: AiProviderType) = "key_${p.name}"
        fun modelPref(p: AiProviderType) = "model_${p.name}"
    }
}
