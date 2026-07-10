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

    // Params vary per clip, so they're part of the key — otherwise two clips with the same shader but
    // different knob values would share one baked effect.
    private data class Key(val path: String, val mtime: Long, val params: Map<String, Float>)

    private val cache = object : LinkedHashMap<Key, Effect>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Effect>?): Boolean = size > 16
    }

    fun effectFor(path: String, params: Map<String, Float> = emptyMap()): Effect? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = Key(path, file.lastModified(), params)
        synchronized(this) { cache[key]?.let { return it } }
        // Read + parse OUTSIDE the lock — disk I/O and shader parsing shouldn't block other callers.
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val parsed = runCatching { GlslShader.parse(text) }.getOrNull() ?: return null
        val effect = GlslEffect(parsed, params)
        synchronized(this) { cache[key] = effect }
        return effect
    }
}
