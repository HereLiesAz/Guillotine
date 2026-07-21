package com.hereliesaz.guillotine.ui

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Persists the values a user sets on an azphalt extension's control panel ([AzpUiSchema]), keyed by
 * package id. This is real, honest config storage — the values are the extension's `params`. Applying
 * them to media is the extension runtime's job (azphalt jobs #2–#5), not this store's.
 *
 * Plain per-package JSON in `SharedPreferences` (UI config, not a secret — same choice as CrashReporter
 * / PanelLayoutPrefs). Values are stored as raw azphalt `params` JSON so any control type round-trips.
 */
object AzpParamStore {
    private const val PREFS = "azp_params"
    private val json = Json { ignoreUnknownKeys = true }

    /** Load the stored params for [packageId] as a mutable key→JSON map (empty when none saved yet). */
    fun load(context: Context, packageId: String): Map<String, JsonElement> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(packageId, null)
            ?: return emptyMap()
        return try {
            (json.parseToJsonElement(raw) as? JsonObject)?.toMap() ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Persist [params] for [packageId]. */
    fun save(context: Context, packageId: String, params: Map<String, JsonElement>) {
        val obj = JsonObject(params)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(packageId, obj.toString()).apply()
    }
}
