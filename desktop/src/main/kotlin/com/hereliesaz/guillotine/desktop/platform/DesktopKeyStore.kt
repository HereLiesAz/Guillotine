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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DesktopKeyStore {

    private val storeFile = File(DesktopStorage.dataDir, "keystore.p12")
    private val dataFile = File(DesktopStorage.dataDir, "settings.enc")
    // SECURITY FIX: the PKCS12 store password used to be derived purely from public OS properties
    // (System.getProperty user.name / os.arch / user.home) -- trivially readable or guessable by any
    // other local process running as the same user, which defeats the point of "encrypting" API keys
    // at all. It's now a real random secret (256 bits from SecureRandom), generated once on first run
    // and persisted in its own file, protected exactly the way DesktopMcpAuth already protects its
    // bearer token (see writeRestricted below): POSIX 0600 (or the Windows-safe File API fallback)
    // applied to the file BEFORE the secret is ever written into it.
    private val passwordFile = File(DesktopStorage.dataDir, "keystore.pass")
    private val storePassword = loadOrCreateStorePassword()

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

                cloudVision = json.optBoolean("cloud_vision_optin", false),
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

            put("cloud_vision_optin", settings.cloudVision)
            put("frame_analysis_cache_size", settings.frameAnalysisCacheSize)
        }
        val bytes = encrypt(json.toString())
        // Same owner-only lockdown pattern as the password/token files below: make sure the file's
        // permissions are restricted BEFORE the encrypted API keys land in it, not after.
        dataFile.parentFile?.mkdirs()
        if (!dataFile.exists()) runCatching { dataFile.writeBytes(ByteArray(0)) }
        lockDownFile(dataFile)
        dataFile.writeBytes(bytes)
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
            // Current scheme: the store was already created (or migrated, below) under the random password.
            loadKeyFrom(storePassword, alias)?.let { return it }
            // Backward compat: a keystore.p12 written by a build from before this fix, still protected by
            // the old guessable username/arch/home-derived password. Open it ONCE with that legacy password,
            // then immediately re-save the whole store under the new random password so the guessable
            // password is never needed (or valid) again. This only re-wraps the existing AES key -- the key
            // itself, and therefore every previously-saved API key, is preserved.
            val migrated = runCatching {
                val legacyPassword = legacyDerivedPassword()
                val ks = KeyStore.getInstance("PKCS12")
                storeFile.inputStream().use { ks.load(it, legacyPassword) }
                val entry = ks.getEntry(alias, KeyStore.PasswordProtection(legacyPassword))
                (entry as? KeyStore.SecretKeyEntry)?.secretKey?.also { key ->
                    ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(storePassword))
                    lockDownFile(storeFile)
                    storeFile.outputStream().use { ks.store(it, storePassword) }
                }
            }.getOrNull()
            if (migrated != null) return migrated
            // Neither the new nor the legacy password opens it (corrupt file, or something else entirely) --
            // settings.enc is encrypted with a key that only ever lived inside this store, so if the store
            // can't be recovered the saved settings can't be either. Fall through and mint a fresh key/store;
            // read() already tolerates a dataFile that no longer decrypts and falls back to AiSettings(), so
            // the net effect is the documented one-time "please re-enter your API keys" reset.
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, storePassword)
        ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(storePassword))
        storeFile.parentFile?.mkdirs()
        if (!storeFile.exists()) runCatching { storeFile.writeBytes(ByteArray(0)) }
        lockDownFile(storeFile)
        storeFile.outputStream().use { ks.store(it, storePassword) }
        return key
    }

    private fun loadKeyFrom(password: CharArray, alias: String): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("PKCS12")
        storeFile.inputStream().use { ks.load(it, password) }
        (ks.getEntry(alias, KeyStore.PasswordProtection(password)) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    /**
     * Legacy store password used before this fix -- derived purely from public OS properties that any
     * local process running as the same user could reconstruct or guess. Kept ONLY so a keystore.p12
     * written by an older build can be opened once for migration in [loadOrCreateKey]; never used to
     * write anything new.
     */
    private fun legacyDerivedPassword(): CharArray {
        val user = System.getProperty("user.name", "user")
        val arch = System.getProperty("os.arch", "unknown")
        val home = System.getProperty("user.home", "")
        return "guillotine-$user-$arch-$home".toCharArray()
    }

    /**
     * The store's own password -- a real secret now, not something derivable from public OS properties.
     * First run under this fix: generate 256 random bits and persist them to [passwordFile], locked to
     * owner-only permissions before the secret ever touches disk (see [writeRestricted], the exact
     * pattern `DesktopMcpAuth.writeRestricted` already uses for its bearer token). Later runs just read
     * the same password back.
     */
    private fun loadOrCreateStorePassword(): CharArray {
        val existing = runCatching { passwordFile.readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (existing != null) return existing.toCharArray()
        val generated = generateRandomPassword()
        writeRestricted(passwordFile, generated)
        return generated.toCharArray()
    }

    private fun generateRandomPassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Write [content] into [f] owner-readable only -- mirrors `DesktopMcpAuth.writeRestricted` exactly:
     * create/truncate the (empty) file, restrict it, THEN write the secret, so the bytes never exist in
     * a world-readable file even momentarily.
     */
    private fun writeRestricted(f: File, content: String) {
        f.parentFile?.mkdirs()
        runCatching { f.writeText("") }
        lockDownFile(f)
        f.writeText(content)
    }

    /**
     * Owner-only permissions (POSIX 0600) on an already-existing file, with the same Windows-safe
     * `setReadable`/`setWritable` fallback `DesktopMcpAuth.writeRestricted` uses for filesystems (or
     * platforms) without POSIX permission bits.
     */
    private fun lockDownFile(f: File) {
        val restricted = runCatching {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), perms)
        }.isSuccess
        if (!restricted) {
            f.setReadable(false, false); f.setWritable(false, false)
            f.setReadable(true, true); f.setWritable(true, true)
        }
    }
}
