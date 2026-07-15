package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.AiProviderType
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.FrameAnalysisCache
import com.hereliesaz.guillotine.ai.LeonardoDefaultModel
import com.hereliesaz.guillotine.ai.byoProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DesktopKeyStore {

    private val storeFile = File(DesktopStorage.dataDir, "keystore.p12")
    private val dataFile = File(DesktopStorage.dataDir, "settings.enc")
    private val storePassword = derivePassword().toCharArray()

    private val secretKey: SecretKey by lazy { loadOrCreateKey() }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    val onboardingDone: Boolean
        get() = File(DesktopStorage.dataDir, "onboarding_done").exists()

    fun markOnboardingDone() {
        runCatching { File(DesktopStorage.dataDir, "onboarding_done").writeText("1") }
    }

    suspend fun save(settings: AiSettings) {
        withContext(Dispatchers.IO) { write(settings) }
        _settings.value = settings
    }

    private fun read(): AiSettings {
        if (!dataFile.exists()) return AiSettings()
        return runCatching {
            val encrypted = dataFile.readBytes()
            val json = JSONObject(decrypt(encrypted))
            AiSettings(
                provider = json.optString("provider").let { name ->
                    runCatching { AiProviderType.valueOf(name) }.getOrNull()
                } ?: AiProviderType.MLKIT,
                keys = byoProviders.associateWith { json.optString("key_${it.name}", "") }
                    .filterValues { it.isNotEmpty() },
                models = byoProviders.associateWith { json.optString("model_${it.name}", "") }
                    .filterValues { it.isNotEmpty() },
                leonardoKey = json.optString("leonardo_key", ""),
                leonardoModel = json.optString("leonardo_model", "")
                    .takeIf { it.isNotBlank() } ?: LeonardoDefaultModel,
                speechModelPath = json.optString("speech_model_path", ""),
                agentModelPath = json.optString("agent_model_path", ""),
                labelModelPath = json.optString("label_model_path", ""),
                audioEventModelPath = json.optString("audio_event_model_path", ""),
                faceDetectModelPath = json.optString("face_detect_model_path", ""),
                idEmbedModelPath = json.optString("id_embed_model_path", ""),
                segModelPath = json.optString("seg_model_path", ""),
                frameAnalysisCacheSize = json.optInt(
                    "frame_analysis_cache_size",
                    FrameAnalysisCache.DEFAULT_MAX_ENTRIES,
                ).coerceIn(FrameAnalysisCache.MIN_MAX_ENTRIES, FrameAnalysisCache.MAX_MAX_ENTRIES),
            )
        }.getOrDefault(AiSettings())
    }

    private fun write(settings: AiSettings) {
        val json = JSONObject().apply {
            put("provider", settings.provider.name)
            byoProviders.forEach {
                put("key_${it.name}", settings.keyFor(it))
                put("model_${it.name}", settings.models[it].orEmpty())
            }
            put("leonardo_key", settings.leonardoKey)
            put("leonardo_model", settings.leonardoModel)
            put("speech_model_path", settings.speechModelPath)
            put("agent_model_path", settings.agentModelPath)
            put("label_model_path", settings.labelModelPath)
            put("audio_event_model_path", settings.audioEventModelPath)
            put("face_detect_model_path", settings.faceDetectModelPath)
            put("id_embed_model_path", settings.idEmbedModelPath)
            put("seg_model_path", settings.segModelPath)
            put("frame_analysis_cache_size", settings.frameAnalysisCacheSize)
        }
        dataFile.writeBytes(encrypt(json.toString()))
    }

    private fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ct
    }

    private fun decrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val alias = "guillotine_settings"
        if (storeFile.exists()) {
            val ks = KeyStore.getInstance("PKCS12")
            storeFile.inputStream().use { ks.load(it, storePassword) }
            val entry = ks.getEntry(alias, KeyStore.PasswordProtection(storePassword))
            if (entry is KeyStore.SecretKeyEntry) return entry.secretKey
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, storePassword)
        ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(storePassword))
        storeFile.outputStream().use { ks.store(it, storePassword) }
        return key
    }

    private fun derivePassword(): String {
        val user = System.getProperty("user.name", "user")
        val arch = System.getProperty("os.arch", "unknown")
        val home = System.getProperty("user.home", "")
        return "guillotine-$user-$arch-$home"
    }
}
