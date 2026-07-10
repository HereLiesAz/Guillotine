@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.media

import androidx.media3.common.Effect
import java.io.File

/**
 * Loads a GLSL shader file (`.fs` / `.glsl` / `.isf`) into a Media3 [GlslEffect], parsed by the shared
 * [GlslShader]. Cached by (path, mtime) so scrubbing doesn't re-parse per clip-effects rebuild — mirrors
 * [LutCube]. Returns null for a blank/unreadable/unsupported shader (the effect is then simply skipped).
 */
object ShaderCache {

    private data class Key(val path: String, val mtime: Long)

    private val cache = object : LinkedHashMap<Key, Effect>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Effect>?): Boolean = size > 8
    }

    @Synchronized
    fun effectFor(path: String): Effect? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = Key(path, file.lastModified())
        cache[key]?.let { return it }
        val effect = runCatching { GlslEffect(GlslShader.parse(file.readText())) }.getOrNull() ?: return null
        cache[key] = effect
        return effect
    }
}
