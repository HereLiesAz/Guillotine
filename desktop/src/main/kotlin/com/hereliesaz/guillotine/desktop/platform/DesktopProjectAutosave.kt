package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.model.Document
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Project persistence: the always-on autosave (`current_project.gilt` in the data dir) and explicit
 * Save/Open. Writes are atomic with a `.bak` of the previous version, so a crash mid-save or a corrupt
 * file no longer silently wipes the project on next launch.
 */
object DesktopProjectAutosave {
    private const val FILE = "current_project.gilt"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    /** Autosave to the data dir. Atomic + `.bak` ([AtomicFiles.writeAtomic]); failures are logged, not thrown. */
    fun save(document: Document) {
        runCatching { saveToFile(File(DesktopStorage.dataDir, FILE), document) }
            .onFailure { System.err.println("Guillotine: autosave failed: ${it.message}") }
    }

    /** Load the autosave, falling back to its `.bak` when the primary is corrupt (null if neither parses). */
    fun load(): Document? = loadWithRecovery(File(DesktopStorage.dataDir, FILE))

    /** [load] against an explicit file. */
    fun loadWithRecovery(file: File): Document? =
        AtomicFiles.readWithRecovery(file) { json.decodeFromString(Document.serializer(), it) }

    /** Write [document] to [file] atomically (temp + ATOMIC_MOVE), keeping the previous version as `.bak`. */
    fun saveToFile(file: File, document: Document) {
        AtomicFiles.writeAtomic(file, json.encodeToString(Document.serializer(), document))
    }

    fun loadFromFile(file: File): Document =
        json.decodeFromString(Document.serializer(), file.readText())
}
