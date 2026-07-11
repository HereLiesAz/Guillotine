package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.model.LearnedConcept
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-device persistence for [LearnedConcept]s on desktop — mirrors the Android [LearnedConceptStore]
 * (a plain JSON file in the app data dir) using the shared [LearnedConcept] model. Concepts (with
 * their example embedding vectors) survive across sessions and projects.
 *
 * NOTE: on desktop the store is only ever *read* by list_concepts / delete_concept — it is populated
 * by add_reference, which needs an on-device image/face embedder (ML Kit/TFLite) not available here,
 * so the list is empty until that lands. The data ops themselves are real and faithful.
 */
object DesktopLearnedConceptStore {
    private const val FILE = "learned_concepts.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): List<LearnedConcept> {
        val f = File(DesktopStorage.dataDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(LearnedConcept.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    fun save(concepts: List<LearnedConcept>) {
        runCatching {
            File(DesktopStorage.dataDir, FILE).writeText(
                json.encodeToString(ListSerializer(LearnedConcept.serializer()), concepts),
            )
        }
    }

    fun get(name: String): LearnedConcept? =
        load().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun remove(name: String) {
        save(load().filterNot { it.name.equals(name.trim(), ignoreCase = true) })
    }
}
