package com.hereliesaz.guillotine.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists [RelayConfig] encrypted on-device. The Worker URL/access key aren't the end-to-end
 * secret (the MCP token is), but they live alongside it in the same encrypted store for tidiness.
 */
object McpRelayConfig {

    private const val PREFS = "guillotine_mcp_relay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "worker_url"
    private const val KEY_ACCESS = "access_key"

    fun read(context: Context): RelayConfig = prefs(context).run {
        RelayConfig(
            enabled = getBoolean(KEY_ENABLED, false),
            workerUrl = getString(KEY_URL, "").orEmpty(),
            accessKey = getString(KEY_ACCESS, "").orEmpty(),
        )
    }

    fun save(context: Context, config: RelayConfig) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_URL, config.workerUrl.trim())
            .putString(KEY_ACCESS, config.accessKey.trim())
            .apply()
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
