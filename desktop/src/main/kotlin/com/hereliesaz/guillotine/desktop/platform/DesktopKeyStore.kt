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
    // The PKCS12 store password used to be derived purely from public OS properties (user.name /
    // os.arch / user.home), so anyone could reconstruct it. It is now 256 random bits generated on first
    // run and kept in keystore.pass next to the store, owner-only (POSIX 0600, or the Windows File-API
    // fallback) before the secret is written. Be clear about what that buys: the password sits in the
    // same directory as the store, so protection is exactly "the OS file permissions on the data dir"
    // -- it stops other users on the machine, NOT malware or anything running as the same user. It is
    // not an OS keychain.
    private val passwordFile = File(DesktopStorage.dataDir, "keystore.pass")

    /**
     * True when keystore.p12 exists but keystore.pass doesn't. A store written by a pre-random-password
     * build legitimately has no pass file (opened via the legacy password and migrated); anything else
     * means the password was lost, and minting a new key would silently orphan every saved API key.
     */
    private val passwordMissingForExistingStore = storeFile.exists() && !hasStoredPassword()
    private val storePassword = loadOrCreateStorePassword()

    private val secretKey: SecretKey by lazy { loadOrCreateKey() }

    private val _unlockError = MutableStateFlow<String?>(null)

    /**
     * Non-null when the existing key store could not be opened (password file lost, or the store is
     * unreadable). While set, settings are kept in memory only and NEVER written, so the encrypted
     * settings and the store stay intact for recovery. The message says what to do.
     */
    val unlockError: StateFlow<String?> = _unlockError.asStateFlow()

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    init {
        _unlockError.value?.let { msg ->
            System.err.println("Guillotine: $msg")
            // Loud on purpose: the alternative used to be silently wiping every saved key.
            runCatching {
                if (!java.awt.GraphicsEnvironment.isHeadless()) {
                    javax.swing.JOptionPane.showMessageDialog(
                        null, msg, "Guillotine — saved keys locked", javax.swing.JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    val onboardingDone: Boolean
        get() = File(DesktopStorage.dataDir, "onboarding_done").exists()

    fun markOnboardingDone() {
        runCatching { File(DesktopStorage.dataDir, "onboarding_done").writeText("1") }
    }

    /**
     * Persist [settings]. When the store is locked ([unlockError]) the new settings apply for this
     * session only and nothing is written — overwriting settings.enc / keystore.p12 then would destroy
     * the keys the user may still recover by restoring keystore.pass.
     */
    suspend fun save(settings: AiSettings) {
        _settings.value = settings
        if (_unlockError.value != null) {
            System.err.println("Guillotine: settings not saved — the key store is locked (${_unlockError.value}).")
            return
        }
        withContext(Dispatchers.IO) { write(settings) }
    }

    private fun read(): AiSettings {
        val key = try {
            secretKey
        } catch (e: KeyStoreLockedException) {
            _unlockError.value = e.message
            return AiSettings()
        }
        if (!dataFile.exists()) return AiSettings()
        return runCatching {
            val encrypted = dataFile.readBytes()
            val json = JSONObject(decrypt(encrypted, key))
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
                azpLlmId = json.optString("azp_llm_id", ""),
                azpLlmKeys = json.optJSONObject("azp_llm_keys").toStringMap(),
                azpLlmModels = json.optJSONObject("azp_llm_models").toStringMap(),
                azpSandboxToken = json.optString("azp_sandbox_token", ""),
                azpSandboxRepo = json.optString("azp_sandbox_repo", ""),
                azpSandboxInstalls = json.optJSONObject("azp_sandbox_installs").toStringMap(),
                azpLlmTextId = json.optString("azp_llm_text_id", ""),
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
            put("azp_llm_id", settings.azpLlmId)
            put("azp_llm_keys", JSONObject(settings.azpLlmKeys.filterValues { it.isNotEmpty() }))
            put("azp_llm_models", JSONObject(settings.azpLlmModels.filterValues { it.isNotEmpty() }))
            put("azp_sandbox_token", settings.azpSandboxToken)
            put("azp_sandbox_repo", settings.azpSandboxRepo)
            put("azp_sandbox_installs", JSONObject(settings.azpSandboxInstalls.filterValues { it.isNotEmpty() }))
            put("azp_llm_text_id", settings.azpLlmTextId)
        }
        val bytes = encrypt(json.toString())
        dataFile.parentFile?.mkdirs()
        // Write to a temp file first, then atomically rename so a crash mid-write can't destroy
        // settings.enc. Apply owner-only permissions before either file holds the ciphertext.
        val tmp = File(dataFile.parentFile, "${dataFile.name}.tmp")
        if (!tmp.exists()) runCatching { tmp.writeBytes(ByteArray(0)) }
        lockDownFile(tmp)
        tmp.writeBytes(bytes)
        if (!dataFile.exists()) runCatching { dataFile.writeBytes(ByteArray(0)) }
        lockDownFile(dataFile)
        java.nio.file.Files.move(tmp.toPath(), dataFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun JSONObject?.toStringMap(): Map<String, String> =
        this?.keys()?.asSequence()?.associateWith { optString(it) }?.filterValues { it.isNotEmpty() }.orEmpty()

    private fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ct
    }

    private fun decrypt(data: ByteArray, key: SecretKey = secretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val alias = "guillotine_settings"
        if (storeFile.exists()) {
            // Current scheme: the store was already created (or migrated, below) under the random password.
            if (!passwordMissingForExistingStore) loadKeyFrom(storePassword, alias)?.let { return it }
            // Backward compat: a keystore.p12 written by a build from before the random password, still
            // protected by the old username/arch/home-derived password. Open it ONCE with that password,
            // then re-save the store under the random one (persisting keystore.pass only now that it is
            // known to be needed). This only re-wraps the existing AES key, so saved API keys survive.
            val migrated = runCatching {
                val legacyPassword = legacyDerivedPassword()
                val ks = KeyStore.getInstance("PKCS12")
                storeFile.inputStream().use { ks.load(it, legacyPassword) }
                val entry = ks.getEntry(alias, KeyStore.PasswordProtection(legacyPassword))
                (entry as? KeyStore.SecretKeyEntry)?.secretKey?.also { key ->
                    if (!hasStoredPassword()) writeRestricted(passwordFile, String(storePassword))
                    ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(storePassword))
                    lockDownFile(storeFile)
                    storeFile.outputStream().use { ks.store(it, storePassword) }
                }
            }.getOrNull()
            if (migrated != null) return migrated
            // Neither password opens it. settings.enc is encrypted with a key that only lives in this
            // store, so minting a new one would silently and permanently lose every saved API key. Refuse:
            // leave both files untouched and report it (see unlockError), so the user can restore
            // keystore.pass from a backup — or deliberately reset by moving the files aside.
            throw KeyStoreLockedException(
                if (passwordMissingForExistingStore) {
                    "Your saved API keys are locked: keystore.pass is missing from ${DesktopStorage.dataDir}, " +
                        "so keystore.p12 can't be opened. Restore keystore.pass from a backup and restart. " +
                        "To start over instead, move keystore.p12 and settings.enc out of that folder and " +
                        "re-enter your keys. Until then, settings changes are not saved."
                } else {
                    "Your saved API keys are locked: keystore.p12 in ${DesktopStorage.dataDir} can't be opened " +
                        "with keystore.pass (corrupt or mismatched). Restore both from a backup and restart, " +
                        "or move keystore.p12, keystore.pass and settings.enc aside to start over. Until " +
                        "then, settings changes are not saved."
                },
            )
        }
        // Fresh install: persist the password, then create the store under it.
        if (!hasStoredPassword()) writeRestricted(passwordFile, String(storePassword))
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

    /** The existing key store couldn't be opened; the message is user-facing. */
    private class KeyStoreLockedException(message: String) : IllegalStateException(message)

    private fun hasStoredPassword(): Boolean =
        runCatching { passwordFile.readText().isNotBlank() }.getOrDefault(false)

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
     * The store's own password: read from [passwordFile], or freshly generated (256 random bits). A
     * generated password is NOT written here — [loadOrCreateKey] persists it only when it creates a new
     * store or migrates a legacy one, so a lost pass file can never be "replaced" by a new random one
     * that silently doesn't match the existing store.
     */
    private fun loadOrCreateStorePassword(): CharArray {
        val existing = runCatching { passwordFile.readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (existing != null) return existing.toCharArray()
        return generateRandomPassword().toCharArray()
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
