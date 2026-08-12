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
    private const val KEY_ZOOM = "preview_zoom"
    private const val KEY_PAN_X = "preview_pan_x"
    private const val KEY_PAN_Y = "preview_pan_y"
    private const val KEY_TIMELINE = "timeline_weight"

    const val DEFAULT_WIDE = 0.65f
    const val DEFAULT_TALL = 0.5f
    const val DEFAULT_TIMELINE = 0.4f
    const val DEFAULT_ZOOM = 1f // 1x = fit-to-view (the floor: the preview never zooms out past fit)
    const val MAX_ZOOM = 5f
    /** Step size for the explicit Zoom In/Out buttons (vs. the popup slider's continuous drag). */
    const val ZOOM_STEP = 0.25f

    /** [orientationOverride]: true = force side-by-side, false = force stacked, null = follow screen. */
    data class Layout(
        val previewWeightWide: Float,
        val previewWeightTall: Float,
        val orientationOverride: Boolean?,
        val timelineWeight: Float,
    )

    fun load(context: Context): Layout {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Layout(
            previewWeightWide = p.getFloat(KEY_WIDE, DEFAULT_WIDE),
            previewWeightTall = p.getFloat(KEY_TALL, DEFAULT_TALL),
            orientationOverride = if (p.contains(KEY_ORIENTATION)) p.getInt(KEY_ORIENTATION, 1) == 1 else null,
            timelineWeight = p.getFloat(KEY_TIMELINE, DEFAULT_TIMELINE),
        )
    }

    fun saveTimeline(context: Context, v: Float) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_TIMELINE, v).apply()

    fun saveWide(context: Context, v: Float) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_WIDE, v).apply()

    fun saveTall(context: Context, v: Float) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_TALL, v).apply()

    fun saveOrientation(context: Context, o: Boolean?) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (o == null) e.remove(KEY_ORIENTATION) else e.putInt(KEY_ORIENTATION, if (o) 1 else 0)
        e.apply()
    }

    /** Persistent preview viewport: [zoom] (>= 1; 1 = fit) and pan offset in px from centre. */
    data class PreviewView(val zoom: Float, val panX: Float, val panY: Float)

    fun loadPreview(context: Context): PreviewView {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PreviewView(
            zoom = p.getFloat(KEY_ZOOM, DEFAULT_ZOOM).coerceIn(DEFAULT_ZOOM, MAX_ZOOM),
            panX = p.getFloat(KEY_PAN_X, 0f),
            panY = p.getFloat(KEY_PAN_Y, 0f),
        )
    }

    fun savePreview(context: Context, zoom: Float, panX: Float, panY: Float) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_ZOOM, zoom).putFloat(KEY_PAN_X, panX).putFloat(KEY_PAN_Y, panY).apply()
}
