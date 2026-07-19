package com.hereliesaz.guillotine.ai.vocab

import org.json.JSONArray
import org.json.JSONObject

/** A raw text completion: prompt in, model reply out (or null when unavailable). Platform-supplied. */
typealias LlmCompletion = suspend (String) -> String?

/**
 * Grows the [VocabularyGraph]'s synonyms via an LLM pass, applying and caching the result. The model call
 * is injected as an [LlmCompletion] so this stays platform-agnostic and unit-testable — the platform wires
 * a real completion (the configured backend, WITHOUT the editing system prompt) plus a cache load/save.
 *
 * Flow: if a cache exists, apply it (offline, instant); otherwise call the model once, apply the parsed
 * expansion, and return the canonical JSON for the caller to persist. Everything degrades to the built-in
 * rule-based synonyms on any failure — the graph is never left worse than its seed.
 */
object VocabularyExpander {

    /** Strict-JSON expansion prompt: for each concept id (+ its seeds), ask for synonym phrasings only. */
    fun buildPrompt(concepts: List<VocabularyGraph.Concept> = VocabularyGraph.concepts): String = buildString {
        append("You expand a video-editor's command vocabulary. For each concept id below, list common ")
        append("English words and short phrases a user might say to MEAN it (synonyms only — never its ")
        append("opposite). Reply with ONLY a JSON object mapping each id to an array of lowercase strings, ")
        append("no prose, no code fences.\n\n")
        for (c in concepts) append(c.id).append(": ").append(c.seeds.joinToString(", ")).append('\n')
    }

    /** Parse the model reply into conceptId -> synonyms. Tolerant of surrounding prose / code fences. */
    fun parse(text: String): Map<String, Set<String>> {
        val json = extractJsonObject(text) ?: return emptyMap()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Set<String>>()
        for (key in obj.keys()) {
            val arr = obj.optJSONArray(key) ?: continue
            val set = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim().lowercase()
                if (s.isNotEmpty() && s.length <= 40) set.add(s)
            }
            if (set.isNotEmpty()) out[key] = set
        }
        return out
    }

    /** The smallest object spanning the first `{` and last `}` (handles fences/prose around the JSON). */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    /** Serialise an expansion back to canonical JSON for caching. */
    fun toJson(expansion: Map<String, Set<String>>): String {
        val obj = JSONObject()
        for ((k, v) in expansion) obj.put(k, JSONArray(v.toList()))
        return obj.toString()
    }

    /**
     * Apply a cached expansion if [cached] is usable and return null; otherwise run [complete] once, apply
     * the fresh expansion, and return its canonical JSON for the caller to persist. Never throws; returns
     * null when nothing needs saving (cache used, model unavailable, or empty reply).
     */
    suspend fun ensureExpanded(cached: String?, complete: LlmCompletion): String? {
        if (!cached.isNullOrBlank()) {
            val parsed = parse(cached)
            if (parsed.isNotEmpty()) {
                VocabularyGraph.applyExpansion(parsed)
                return null
            }
        }
        val reply = runCatching { complete(buildPrompt()) }.getOrNull() ?: return null
        val parsed = parse(reply)
        if (parsed.isEmpty()) return null
        VocabularyGraph.applyExpansion(parsed)
        return toJson(parsed)
    }
}
