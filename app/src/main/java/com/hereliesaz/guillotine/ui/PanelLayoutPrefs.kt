package com.hereliesaz.guillotine.ui

import android.content.Context

/**
 * Persists where the user put the clip-properties panel (AdvancedToolView): the preview/panel split
 * for each arrangement, and whether they've pinned an orientation (side-by-side vs stacked) instead of
 * letting it follow the screen shape. Plain [Context.getSharedPreferences] — this is UI chrome, not a
 * secret, so it matches CrashReporter's unencrypted prefs. `apply()` writes off the caller's thread.
 */
object PanelLayoutPrefs {
    private const val PREFS = "panel_layout"
    private const val KEY_WIDE = "preview_weight_wide"
    private const val KEY_TALL = "preview_weight_tall"
    private const val KEY_ORIENTATION = "orientation_override" // 1 = wide, 0 = tall, absent = auto

    const val DEFAULT_WIDE = 0.65f
    const val DEFAULT_TALL = 0.5f

    /** [orientationOverride]: true = force side-by-side, false = force stacked, null = follow screen. */
    data class Layout(
        val previewWeightWide: Float,
        val previewWeightTall: Float,
        val orientationOverride: Boolean?,
    )

    fun load(context: Context): Layout {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Layout(
            previewWeightWide = p.getFloat(KEY_WIDE, DEFAULT_WIDE),
            previewWeightTall = p.getFloat(KEY_TALL, DEFAULT_TALL),
            orientationOverride = if (p.contains(KEY_ORIENTATION)) p.getInt(KEY_ORIENTATION, 1) == 1 else null,
        )
    }

    fun saveWide(context: Context, v: Float) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_WIDE, v).apply()

    fun saveTall(context: Context, v: Float) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_TALL, v).apply()

    fun saveOrientation(context: Context, o: Boolean?) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (o == null) e.remove(KEY_ORIENTATION) else e.putInt(KEY_ORIENTATION, if (o) 1 else 0)
        e.apply()
    }
}
