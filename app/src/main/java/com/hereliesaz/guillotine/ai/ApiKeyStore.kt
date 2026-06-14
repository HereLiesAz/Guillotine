package com.hereliesaz.guillotine.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

private val Context.dataStore by preferencesDataStore(name = "guillotine_settings")

/**
 * Persists the chosen provider and the user's own API keys. Keys are stored
 * on-device only. No key is ever required (LOCAL is the default).
 */
class ApiKeyStore(private val context: Context) {

    private val providerKey = stringPreferencesKey("ai_provider")
    private val geminiKeyKey = stringPreferencesKey("gemini_key")
    private val openaiKeyKey = stringPreferencesKey("openai_key")
    private val anthropicKeyKey = stringPreferencesKey("anthropic_key")

    val settings: Flow<AiSettings> = context.dataStore.data.map { prefs ->
        AiSettings(
            provider = prefs[providerKey]?.let { runCatching { AiProviderType.valueOf(it) }.getOrNull() }
                ?: AiProviderType.LOCAL,
            geminiKey = prefs[geminiKeyKey].orEmpty(),
            openaiKey = prefs[openaiKeyKey].orEmpty(),
            anthropicKey = prefs[anthropicKeyKey].orEmpty(),
        )
    }

    suspend fun save(settings: AiSettings) {
        context.dataStore.edit {
            it[providerKey] = settings.provider.name
            it[geminiKeyKey] = settings.geminiKey
            it[openaiKeyKey] = settings.openaiKey
            it[anthropicKeyKey] = settings.anthropicKey
        }
    }
}
