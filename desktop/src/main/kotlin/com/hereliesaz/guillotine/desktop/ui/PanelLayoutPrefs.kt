package com.hereliesaz.guillotine.desktop.ui

import com.hereliesaz.guillotine.desktop.platform.DesktopStorage
import java.io.File
import java.util.Properties

/**
 * Persists the desktop editor's one resizable split — preview vs. timeline height — across restarts.
 * Desktop's layout has a single degree of freedom here (no AdvancedToolView side-panel split, no
 * orientation toggle the way Android's arrangement has; see the app's `PanelLayoutPrefs`), so this is
 * deliberately a small `.properties` file rather than a full prefs API: "Save/recall panel layout"
 * (Vegas A.6/A.7's Window Layouts) for a single fixed arrangement just means "remember where the user
 * left the one divider," restored automatically on next launch.
 */
object PanelLayoutPrefs {
    private const val KEY_PREVIEW_WEIGHT = "preview_weight"
    private const val KEY_ZOOM = "preview_zoom"
    private const val KEY_PAN_X = "preview_pan_x"
    private const val KEY_PAN_Y = "preview_pan_y"
    const val DEFAULT_PREVIEW_WEIGHT = 0.6f
    const val DEFAULT_ZOOM = 1f
    const val MAX_ZOOM = 5f
    /** Step size for the explicit Zoom In/Out buttons and the popup slider's upper bound. */
    const val ZOOM_STEP = 0.25f

    private val file: File by lazy { File(DesktopStorage.dataDir, "panel_layout.properties") }

    private fun readAll(): Properties {
        val p = Properties()
        runCatching { file.inputStream().use { p.load(it) } }
        return p
    }

    private fun write(mutate: Properties.() -> Unit) {
        val p = readAll()
        p.mutate()
        runCatching { file.outputStream().use { p.store(it, null) } }
    }

    fun loadPreviewWeight(): Float = readAll().getProperty(KEY_PREVIEW_WEIGHT)?.toFloatOrNull() ?: DEFAULT_PREVIEW_WEIGHT

    fun savePreviewWeight(v: Float) = write { setProperty(KEY_PREVIEW_WEIGHT, v.toString()) }

    /** Persistent preview viewport: [zoom] (>= 1; 1 = fit) and pan offset in px from centre. */
    data class PreviewView(val zoom: Float, val panX: Float, val panY: Float)

    fun loadPreview(): PreviewView {
        val p = readAll()
        return PreviewView(
            zoom = (p.getProperty(KEY_ZOOM)?.toFloatOrNull() ?: DEFAULT_ZOOM).coerceIn(DEFAULT_ZOOM, MAX_ZOOM),
            panX = p.getProperty(KEY_PAN_X)?.toFloatOrNull() ?: 0f,
            panY = p.getProperty(KEY_PAN_Y)?.toFloatOrNull() ?: 0f,
        )
    }

    fun savePreview(zoom: Float, panX: Float, panY: Float) = write {
        setProperty(KEY_ZOOM, zoom.toString())
        setProperty(KEY_PAN_X, panX.toString())
        setProperty(KEY_PAN_Y, panY.toString())
    }
}
