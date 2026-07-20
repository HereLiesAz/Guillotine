package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.vocab.VocabularyCache
import java.io.File

/** Persists the generated vocabulary expansion to a file under the desktop app data dir. */
object DesktopVocabularyCache : VocabularyCache {
    private val file: File get() = File(DesktopStorage.dataDir, "vocab_expansion.json")

    override fun load(): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun save(json: String) {
        runCatching {
            DesktopStorage.dataDir.mkdirs()
            file.writeText(json)
        }
    }
}
