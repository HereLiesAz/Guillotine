package com.hereliesaz.guillotine.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Opens an [EncryptedSharedPreferences] file, recovering from an unreadable one instead of crashing.
 *
 * The Keystore master key never leaves the device, so a prefs file restored onto a new device (or left
 * behind after a key was invalidated) can't be decrypted and `create` throws on every launch — a crash
 * loop. These files are excluded from backup (res/xml/data_extraction_rules.xml, backup_rules.xml), but
 * if one is still unreadable we delete it and start fresh: the user re-enters a key / gets a new MCP
 * token, which beats an app that never opens.
 */
internal object SecurePrefs {

    fun open(context: Context, name: String): SharedPreferences = try {
        create(context, name)
    } catch (e: Exception) {
        Log.w("SecurePrefs", "Encrypted prefs '$name' unreadable; resetting it", e)
        context.deleteSharedPreferences(name)
        create(context, name)
    }

    private fun create(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
