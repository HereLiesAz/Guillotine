@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.media

import android.graphics.Color
import androidx.media3.common.Effect
import androidx.media3.effect.SingleColorLut
import java.io.File

/**
 * Loads a `.cube` 3D LUT (parsed by the shared [CubeLut]) into a Media3 [SingleColorLut] color effect,
 * so it grades identically in live preview and export. Results are cached by (path, mtime) — a `.cube`
 * parse + cube build is cheap but happens on every clip-effects rebuild, so caching keeps scrubbing
 * smooth. On-device only.
 */
object LutCube {

    private data class Key(val path: String, val mtime: Long)

    // A small cache is plenty — a project rarely uses more than a handful of distinct LUTs at once.
    private val cache = object : LinkedHashMap<Key, Effect>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Effect>?): Boolean = size > 8
    }

    /** The LUT [Effect] for the `.cube` at [path], or null if the path is blank/unreadable/malformed. */
    @Synchronized
    fun effectFor(path: String): Effect? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = Key(path, file.lastModified())
        cache[key]?.let { return it }
        val effect = runCatching { build(file) }.getOrNull() ?: return null
        cache[key] = effect
        return effect
    }

    private fun build(file: File): Effect {
        val lut = CubeLut.parse(file.readText())
        val n = lut.size
        // Media3 SingleColorLut.createFromCube expects cube[R][G][B] packed as ARGB_8888.
        val cube = Array(n) { Array(n) { IntArray(n) } }
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val (rf, gf, bf) = lut.at(r, g, b)
            cube[r][g][b] = Color.argb(
                255,
                (rf * 255f + 0.5f).toInt().coerceIn(0, 255),
                (gf * 255f + 0.5f).toInt().coerceIn(0, 255),
                (bf * 255f + 0.5f).toInt().coerceIn(0, 255),
            )
        }
        return SingleColorLut.createFromCube(cube)
    }
}
