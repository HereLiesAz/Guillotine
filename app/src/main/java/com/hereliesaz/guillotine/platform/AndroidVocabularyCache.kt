package com.hereliesaz.guillotine.platform

import android.content.Context
import com.hereliesaz.guillotine.ai.vocab.VocabularyCache
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Persists the generated vocabulary expansion to a file under the app's private files dir. */
class AndroidVocabularyCache(context: Context) : VocabularyCache {
    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, "vocab_expansion.json")

    override fun load(): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun save(json: String) {
        runCatching {
            val tmp = File(appContext.filesDir, "vocab_expansion.json.tmp")
            tmp.writeText(json)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
