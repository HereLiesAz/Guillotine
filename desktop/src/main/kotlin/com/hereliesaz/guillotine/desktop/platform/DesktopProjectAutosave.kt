package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.model.Document
import kotlinx.serialization.json.Json
import java.io.File

object DesktopProjectAutosave {
    private const val FILE = "current_project.gilt"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    fun save(document: Document) {
        runCatching {
            File(DesktopStorage.dataDir, FILE).writeText(
                json.encodeToString(Document.serializer(), document),
            )
        }
    }

    fun load(): Document? {
        val f = File(DesktopStorage.dataDir, FILE)
        if (!f.exists()) return null
        return runCatching {
            json.decodeFromString(Document.serializer(), f.readText())
        }.getOrNull()
    }

    fun saveToFile(file: File, document: Document) {
        file.writeText(json.encodeToString(Document.serializer(), document))
    }

    fun loadFromFile(file: File): Document =
        json.decodeFromString(Document.serializer(), file.readText())
}
