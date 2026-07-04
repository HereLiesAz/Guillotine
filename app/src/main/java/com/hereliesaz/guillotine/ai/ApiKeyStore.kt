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
            ?.takeIf { it.isNotBlank() } ?: LeonardoDefaultModel,
        speechModelPath = prefs.getString(KEY_SPEECH, "").orEmpty(),
        agentModelPath = prefs.getString(KEY_AGENT_MODEL, "").orEmpty(),
        frameAnalysisCacheSize = prefs
            .getInt(KEY_FRAME_CACHE_SIZE, FrameAnalysisCache.DEFAULT_MAX_ENTRIES)
            .coerceIn(FrameAnalysisCache.MIN_MAX_ENTRIES, FrameAnalysisCache.MAX_MAX_ENTRIES),
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
                putInt(KEY_FRAME_CACHE_SIZE, settings.frameAnalysisCacheSize)
            }.apply()
        }
        _settings.value = settings
    }

    val onboardingDone: Boolean get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun markOnboardingDone() { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply() }

    private companion object {
        const val KEY_PROVIDER = "ai_provider"
        const val KEY_LEONARDO_KEY = "leonardo_key"
        const val KEY_LEONARDO_MODEL = "leonardo_model"
        const val KEY_SPEECH = "speech_model_path"
        const val KEY_AGENT_MODEL = "agent_model_path"
        const val KEY_FRAME_CACHE_SIZE = "frame_analysis_cache_size"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        fun keyPref(p: AiProviderType) = "key_${p.name}"
        fun modelPref(p: AiProviderType) = "model_${p.name}"
    }
}
