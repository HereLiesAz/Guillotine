package com.hereliesaz.guillotine.data

import android.content.Context
import com.hereliesaz.guillotine.model.LearnedConcept
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-device persistence for [LearnedConcept]s — mirrors [UserToolStore]. A plain JSON file in
 * filesDir; concepts (with their example embedding vectors) survive across sessions and projects.
 */
object LearnedConceptStore {
    private const val FILE = "learned_concepts.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(context: Context): List<LearnedConcept> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(LearnedConcept.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, concepts: List<LearnedConcept>) {
        runCatching {
            File(context.filesDir, FILE).writeText(
                json.encodeToString(ListSerializer(LearnedConcept.serializer()), concepts),
            )
        }
    }

    fun get(context: Context, name: String): LearnedConcept? =
        load(context).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Append one example (and merge any new [terms]) to the named concept, creating it if new. */
    fun addExample(context: Context, name: String, terms: List<String>, vector: FloatArray): LearnedConcept {
        val n = name.trim()
        val all = load(context).toMutableList()
        val idx = all.indexOfFirst { it.name.equals(n, ignoreCase = true) }
        val vec = vector.toList()
        val updated = if (idx >= 0) {
            val c = all[idx]
            c.copy(terms = (c.terms + terms).distinct(), examples = c.examples + listOf(vec))
        } else {
            LearnedConcept(n, terms.distinct(), listOf(vec))
        }
        if (idx >= 0) all[idx] = updated else all.add(updated)
        save(context, all)
        return updated
    }

    fun remove(context: Context, name: String) {
        save(context, load(context).filterNot { it.name.equals(name.trim(), ignoreCase = true) })
    }
}
