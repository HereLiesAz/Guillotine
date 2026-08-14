package com.hereliesaz.guillotine.data

import android.content.Context
import com.hereliesaz.guillotine.model.LearnedConcept
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        // Atomic write (temp file + atomic rename): a plain writeText() truncates the real file
        // to 0 bytes before writing, so a process death mid-write would silently destroy all
        // learned concepts with no error. See ProjectStore.ProjectAutosave.save for the same fix.
        runCatching {
            val real = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(LearnedConcept.serializer()), concepts))
            Files.move(tmp.toPath(), real.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun get(context: Context, name: String): LearnedConcept? =
        load(context).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Append one positive example (and merge any new [terms]/[isFace]) to the named concept. */
    fun addExample(
        context: Context,
        name: String,
        terms: List<String>,
        vector: FloatArray,
        isFace: Boolean = false,
    ): LearnedConcept = mutate(context, name, terms, isFace) { it.copy(examples = it.examples + listOf(vector.toList())) }

    /** Append "not it" examples (same-kind look-alikes) to the named concept. */
    fun addNegatives(
        context: Context,
        name: String,
        terms: List<String>,
        vectors: List<FloatArray>,
        isFace: Boolean = false,
    ): LearnedConcept = mutate(context, name, terms, isFace) {
        it.copy(negatives = it.negatives + vectors.map { v -> v.toList() })
    }

    private fun mutate(
        context: Context,
        name: String,
        terms: List<String>,
        isFace: Boolean,
        transform: (LearnedConcept) -> LearnedConcept,
    ): LearnedConcept {
        val n = name.trim()
        val all = load(context).toMutableList()
        val idx = all.indexOfFirst { it.name.equals(n, ignoreCase = true) }
        val base = if (idx >= 0) all[idx] else LearnedConcept(n)
        val updated = transform(
            base.copy(terms = (base.terms + terms).distinct(), isFace = base.isFace || isFace),
        )
        if (idx >= 0) all[idx] = updated else all.add(updated)
        save(context, all)
        return updated
    }

    fun remove(context: Context, name: String) {
        save(context, load(context).filterNot { it.name.equals(name.trim(), ignoreCase = true) })
    }
}
