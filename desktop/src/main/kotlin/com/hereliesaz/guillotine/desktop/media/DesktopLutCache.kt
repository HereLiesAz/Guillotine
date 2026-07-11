package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.media.CubeLut
import java.io.File

/**
 * Parses and caches `.cube` 3D LUTs by absolute path so the render path (preview + export) parses
 * each file once instead of per frame. Invalidates when the file's `lastModified` timestamp changes
 * (e.g. the user re-exports over the same path). Reuses the shared [CubeLut] parser. On-device only.
 */
object DesktopLutCache {

    private data class Entry(val lastModified: Long, val lastChecked: Long, val lut: CubeLut.Lut3d)

    private val cache = HashMap<String, Entry>()

    /**
     * The parsed [CubeLut.Lut3d] for the `.cube` file at [path], or null when the path is blank,
     * missing, or unparseable (a bad LUT never crashes the render — it just renders ungraded).
     *
     * [get] runs once per frame during playback/export, so the file's `lastModified` is re-checked
     * at most once per second per path — otherwise a `lastModified()` syscall every frame would
     * stutter the render. Hot-reload of an edited LUT still happens within ~1s.
     */
    @Synchronized
    fun get(path: String): CubeLut.Lut3d? {
        if (path.isBlank()) return null
        val now = System.currentTimeMillis()
        val existing = cache[path]
        if (existing != null && now - existing.lastChecked < 1000L) return existing.lut
        val file = File(path)
        if (!file.isFile) return null
        val stamp = file.lastModified()
        if (existing != null && existing.lastModified == stamp) {
            cache[path] = existing.copy(lastChecked = now)
            return existing.lut
        }
        val lut = runCatching { CubeLut.parse(file.readText()) }.getOrNull() ?: return null
        cache[path] = Entry(stamp, now, lut)
        return lut
    }
}
