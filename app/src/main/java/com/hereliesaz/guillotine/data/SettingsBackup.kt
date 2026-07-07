package com.hereliesaz.guillotine.data

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.gen.GenKind
import com.hereliesaz.guillotine.ai.gen.GenProviderType
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
    val idEmbedModelPath: String = "",
    val faceEmbedModelPath: String = "",
    val effectModelPaths: Map<String, String> = emptyMap(),
    val audioEventModelPath: String = "",
    val asrModelPath: String = "",
    val ttsModelPath: String = "",
    val vlmModelPath: String = "",
    val diarizeSegModelPath: String = "",
    val diarizeEmbedModelPath: String = "",
    val stemModelPath: String = "",
    val frameAnalysisCacheSize: Int = 200,
    val genKeys: Map<String, String> = emptyMap(),
    val genModels: Map<String, String> = emptyMap(),
    val genExtras: Map<String, String> = emptyMap(),
    val genDefaults: Map<String, String> = emptyMap(),
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
            idEmbedModelPath = settings.idEmbedModelPath,
            faceEmbedModelPath = settings.faceEmbedModelPath,
            effectModelPaths = settings.effectModelPaths,
            audioEventModelPath = settings.audioEventModelPath,
            asrModelPath = settings.asrModelPath,
            ttsModelPath = settings.ttsModelPath,
            vlmModelPath = settings.vlmModelPath,
            diarizeSegModelPath = settings.diarizeSegModelPath,
            diarizeEmbedModelPath = settings.diarizeEmbedModelPath,
            stemModelPath = settings.stemModelPath,
            frameAnalysisCacheSize = settings.frameAnalysisCacheSize,
            genKeys = settings.genKeys.mapKeys { it.key.name },
            genModels = settings.genModels.mapKeys { it.key.name },
            genExtras = settings.genExtras.mapKeys { it.key.name },
            genDefaults = settings.genDefaults.entries.associate { it.key.name to it.value.name },
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
            idEmbedModelPath = bundle.idEmbedModelPath,
            faceEmbedModelPath = bundle.faceEmbedModelPath,
            effectModelPaths = bundle.effectModelPaths,
            audioEventModelPath = bundle.audioEventModelPath,
            asrModelPath = bundle.asrModelPath,
            ttsModelPath = bundle.ttsModelPath,
            vlmModelPath = bundle.vlmModelPath,
            diarizeSegModelPath = bundle.diarizeSegModelPath,
            diarizeEmbedModelPath = bundle.diarizeEmbedModelPath,
            stemModelPath = bundle.stemModelPath,
            frameAnalysisCacheSize = bundle.frameAnalysisCacheSize,
            genKeys = bundle.genKeys.mapNotNull { (k, v) ->
                runCatching { GenProviderType.valueOf(k) to v }.getOrNull()
            }.toMap(),
            genModels = bundle.genModels.mapNotNull { (k, v) ->
                runCatching { GenProviderType.valueOf(k) to v }.getOrNull()
            }.toMap(),
            genExtras = bundle.genExtras.mapNotNull { (k, v) ->
                runCatching { GenProviderType.valueOf(k) to v }.getOrNull()
            }.toMap(),
            genDefaults = bundle.genDefaults.mapNotNull { (k, v) ->
                runCatching { GenKind.valueOf(k) to GenProviderType.valueOf(v) }.getOrNull()
            }.toMap(),
        )
    }
}
