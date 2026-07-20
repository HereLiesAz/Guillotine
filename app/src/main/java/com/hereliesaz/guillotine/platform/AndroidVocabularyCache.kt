package com.hereliesaz.guillotine.platform

import android.content.Context
import com.hereliesaz.guillotine.ai.vocab.VocabularyCache
import java.io.File

/** Persists the generated vocabulary expansion to a file under the app's private files dir. */
class AndroidVocabularyCache(context: Context) : VocabularyCache {
    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, "vocab_expansion.json")

    override fun load(): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun save(json: String) {
        runCatching { file.writeText(json) }
    }
}
