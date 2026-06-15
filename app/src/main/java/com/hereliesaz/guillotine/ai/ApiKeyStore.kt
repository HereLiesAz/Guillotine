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

/** Which analyzer to use. LOCAL is free and needs no key; the rest need a BYO key. */
enum class AiProviderType { LOCAL, GEMINI, OPENAI, ANTHROPIC }

data class AiSettings(
    val provider: AiProviderType = AiProviderType.LOCAL,
    val geminiKey: String = "",
    val openaiKey: String = "",
    val anthropicKey: String = "",
) {
    fun keyFor(p: AiProviderType): String = when (p) {
        AiProviderType.LOCAL -> ""
        AiProviderType.GEMINI -> geminiKey
        AiProviderType.OPENAI -> openaiKey
        AiProviderType.ANTHROPIC -> anthropicKey
    }
}

/**
 * Persists the chosen provider and the user's own API keys, **encrypted on-device**
 * via Jetpack Security ([EncryptedSharedPreferences] + a Keystore-backed master key).
 * No key is ever required (LOCAL is the default), and keys never leave the device
 * except in the direct provider request the user initiated.
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
            ?: AiProviderType.LOCAL,
        geminiKey = prefs.getString(KEY_GEMINI, "").orEmpty(),
        openaiKey = prefs.getString(KEY_OPENAI, "").orEmpty(),
        anthropicKey = prefs.getString(KEY_ANTHROPIC, "").orEmpty(),
    )

    suspend fun save(settings: AiSettings) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_PROVIDER, settings.provider.name)
                .putString(KEY_GEMINI, settings.geminiKey)
                .putString(KEY_OPENAI, settings.openaiKey)
                .putString(KEY_ANTHROPIC, settings.anthropicKey)
                .apply()
        }
        _settings.value = settings
    }

    private companion object {
        const val KEY_PROVIDER = "ai_provider"
        const val KEY_GEMINI = "gemini_key"
        const val KEY_OPENAI = "openai_key"
        const val KEY_ANTHROPIC = "anthropic_key"
    }
}
