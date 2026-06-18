package com.hereliesaz.guillotine.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * The bearer token that gates the embedded [McpServer]. External AI/ML tools must send it as
 * `Authorization: Bearer <token>` to call `/mcp`. The token is generated on first access and
 * persisted **encrypted on-device** (Jetpack Security, Keystore-backed), mirroring how API keys
 * are stored. An in-memory cache keeps per-request lookups cheap and lets a regenerate take
 * effect immediately without restarting the server.
 */
object McpAuth {

    private const val PREFS = "guillotine_mcp_secure"
    private const val KEY_TOKEN = "mcp_token"

    @Volatile
    private var cached: String? = null

    /** The current token, creating and persisting one on first use. */
    fun token(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = prefs(context)
            val existing = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
            val token = existing ?: generate().also { prefs.edit().putString(KEY_TOKEN, it).apply() }
            cached = token
            return token
        }
    }

    /** Rotate the token (invalidates any tool still using the old one). Takes effect immediately. */
    fun regenerate(context: Context): String {
        val token = generate()
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        cached = token
        return token
    }

    private fun generate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
