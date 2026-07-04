package com.hereliesaz.guillotine.data

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SettingsBundle(
    val provider: String = "",
    val keys: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
    val leonardoKey: String = "",
    val leonardoModel: String = "",
    val speechModelPath: String = "",
    val agentModelPath: String = "",
    val frameAnalysisCacheSize: Int = 200,
    val userTools: List<UserTool> = emptyList(),
)

object SettingsBackup {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    fun export(context: Context, uri: Uri, settings: AiSettings) {
        val bundle = SettingsBundle(
            provider = settings.provider.name,
            keys = settings.keys.mapKeys { it.key.name },
            models = settings.models.mapKeys { it.key.name },
            leonardoKey = settings.leonardoKey,
            leonardoModel = settings.leonardoModel,
            speechModelPath = settings.speechModelPath,
            agentModelPath = settings.agentModelPath,
            frameAnalysisCacheSize = settings.frameAnalysisCacheSize,
            userTools = UserToolStore.load(context),
        )
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.encodeToString(SettingsBundle.serializer(), bundle).toByteArray())
        } ?: throw IllegalStateException("Could not open file for writing.")
    }

    fun `import`(context: Context, uri: Uri): AiSettings {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Could not read settings file.")
        val bundle = json.decodeFromString(SettingsBundle.serializer(), text)
        UserToolStore.save(context, bundle.userTools)
        return AiSettings(
            provider = runCatching { AiProviderType.valueOf(bundle.provider) }
                .getOrDefault(AiProviderType.MLKIT),
            keys = bundle.keys.mapNotNull { (k, v) ->
                runCatching { AiProviderType.valueOf(k) to v }.getOrNull()
            }.toMap(),
            models = bundle.models.mapNotNull { (k, v) ->
                runCatching { AiProviderType.valueOf(k) to v }.getOrNull()
            }.toMap(),
            leonardoKey = bundle.leonardoKey,
            leonardoModel = bundle.leonardoModel,
            speechModelPath = bundle.speechModelPath,
            agentModelPath = bundle.agentModelPath,
            frameAnalysisCacheSize = bundle.frameAnalysisCacheSize,
        )
    }
}
