package com.hereliesaz.guillotine.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.guillotine.ai.gen.GenKind
import com.hereliesaz.guillotine.ai.gen.GenProviderType
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
        idEmbedModelPath = prefs.getString(KEY_ID_EMBED, "").orEmpty(),
        faceEmbedModelPath = prefs.getString(KEY_FACE_EMBED, "").orEmpty(),
        effectModelPaths = EFFECT_KEYS
            .associateWith { prefs.getString("effect_model_$it", "").orEmpty() }.filterValues { it.isNotEmpty() },
        audioEventModelPath = prefs.getString(KEY_AUDIO_EVENT, "").orEmpty(),
        frameAnalysisCacheSize = prefs
            .getInt(KEY_FRAME_CACHE_SIZE, FrameAnalysisCache.DEFAULT_MAX_ENTRIES)
            .coerceIn(FrameAnalysisCache.MIN_MAX_ENTRIES, FrameAnalysisCache.MAX_MAX_ENTRIES),
        genKeys = GenProviderType.entries
            .associateWith { prefs.getString(genKeyPref(it), "").orEmpty() }.filterValues { it.isNotEmpty() },
        genModels = GenProviderType.entries
            .associateWith { prefs.getString(genModelPref(it), "").orEmpty() }.filterValues { it.isNotEmpty() },
        genExtras = GenProviderType.entries
            .associateWith { prefs.getString(genExtraPref(it), "").orEmpty() }.filterValues { it.isNotEmpty() },
        genDefaults = GenKind.entries.mapNotNull { k ->
            prefs.getString(genDefaultPref(k), null)
                ?.let { runCatching { GenProviderType.valueOf(it) }.getOrNull() }
                ?.let { k to it }
        }.toMap(),
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
                putString(KEY_ID_EMBED, settings.idEmbedModelPath)
                putString(KEY_FACE_EMBED, settings.faceEmbedModelPath)
                EFFECT_KEYS.forEach { putString("effect_model_$it", settings.effectModelPaths[it].orEmpty()) }
                putString(KEY_AUDIO_EVENT, settings.audioEventModelPath)
                putInt(KEY_FRAME_CACHE_SIZE, settings.frameAnalysisCacheSize)
                GenProviderType.entries.forEach {
                    putString(genKeyPref(it), settings.genKeys[it].orEmpty())
                    putString(genModelPref(it), settings.genModels[it].orEmpty())
                    putString(genExtraPref(it), settings.genExtras[it].orEmpty())
                }
                GenKind.entries.forEach { k ->
                    val p = settings.genDefaults[k]
                    if (p != null) putString(genDefaultPref(k), p.name) else remove(genDefaultPref(k))
                }
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
        const val KEY_ID_EMBED = "id_embed_model_path"
        const val KEY_FACE_EMBED = "face_embed_model_path"
        val EFFECT_KEYS = listOf("superres", "style", "depth")
        const val KEY_AUDIO_EVENT = "audio_event_model_path"
        const val KEY_FRAME_CACHE_SIZE = "frame_analysis_cache_size"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        fun keyPref(p: AiProviderType) = "key_${p.name}"
        fun modelPref(p: AiProviderType) = "model_${p.name}"
        fun genKeyPref(p: GenProviderType) = "gen_key_${p.name}"
        fun genModelPref(p: GenProviderType) = "gen_model_${p.name}"
        fun genExtraPref(p: GenProviderType) = "gen_extra_${p.name}"
        fun genDefaultPref(k: GenKind) = "gen_default_${k.name}"
    }
}
