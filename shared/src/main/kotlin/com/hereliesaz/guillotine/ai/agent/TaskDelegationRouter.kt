package com.hereliesaz.guillotine.ai.agent

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TaskRoute(
    val tools: List<String>,
    val modelRoles: List<String>,
    val reason: String,
)

/**
 * Builds and validates the tiny router model's only task: choose which capabilities should receive
 * the user's request. It never executes edits and never invents arguments.
 *
 * The candidate list is generated from the live MCP tool catalog, so adding a new tool does not need
 * another prompt-specific workflow. A generic lexical prefilter keeps the 135M router's context small;
 * the model makes the actual delegation decision.
 */
object TaskDelegationRouter {
    private const val MAX_CANDIDATES = 36
    private const val MAX_DESCRIPTION_CHARS = 96

    fun prompt(
        instruction: String,
        definitions: JSONArray,
        modelStatus: String,
    ): String {
        val candidates = candidateDefinitions(instruction, definitions)
        return buildString {
            appendLine("You are Guillotine's task router. Your ONLY job is delegation.")
            appendLine("Do not edit, plan steps, answer the user, or invent tool arguments.")
            appendLine("Choose the smallest useful set of MCP tools and model roles for a planner to use.")
            appendLine("Use ONLY tool names from the catalog below.")
            appendLine("Return exactly one JSON object:")
            appendLine("""{"tools":["tool_name"],"model_roles":["ASR"],"reason":"short reason"}""")
            appendLine("Select at most 8 tools and at most 4 model roles.")
            appendLine()
            appendLine("Installed/available model roles:")
            appendLine(modelStatus.ifBlank { "Unknown; prefer tools that do not require optional models." })
            appendLine()
            appendLine("Candidate tools:")
            for (i in 0 until candidates.length()) {
                val d = candidates.optJSONObject(i) ?: continue
                val desc = d.optString("description")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_DESCRIPTION_CHARS)
                append("- ").append(d.optString("name"))
                if (desc.isNotBlank()) append(": ").append(desc)
                appendLine()
            }
            appendLine()
            append("User request: ").append(instruction.trim().take(600))
        }
    }

    fun parse(raw: String?, definitions: JSONArray): TaskRoute? {
        if (raw.isNullOrBlank()) return null
        val valid = buildSet {
            for (i in 0 until definitions.length()) {
                definitions.optJSONObject(i)?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
        val obj = extractObject(raw) ?: return null
        val tools = obj.optJSONArray("tools").toStringList()
            .filter { it in valid }
            .distinct()
            .take(8)
        if (tools.isEmpty()) return null
        val roles = obj.optJSONArray("model_roles").toStringList()
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.matches(Regex("[A-Z0-9_ -]{2,32}")) }
            .distinct()
            .take(4)
        val reason = obj.optString("reason")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)
        return TaskRoute(tools, roles, reason)
    }

    fun candidateDefinitions(
        instruction: String,
        definitions: JSONArray,
        limit: Int = MAX_CANDIDATES,
    ): JSONArray {
        data class Candidate(val index: Int, val definition: JSONObject, val score: Int)
        val terms = TOKEN.findAll(instruction.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP }
            .toSet()

        val ranked = buildList {
            for (i in 0 until definitions.length()) {
                val d = definitions.optJSONObject(i) ?: continue
                val name = d.optString("name")
                val description = d.optString("description")
                val hay = "$name $description".lowercase(Locale.ROOT)
                var score = terms.count { hay.contains(it) } * 10
                if (name in UNIVERSAL_TOOLS) score += 6
                // Name tokens are stronger than prose matches, without knowing anything about prompts.
                val nameTokens = name.split('_')
                score += terms.count { t -> nameTokens.any { it.contains(t) || t.contains(it) } } * 12
                add(Candidate(i, d, score))
            }
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })

        val chosen = LinkedHashMap<String, JSONObject>()
        ranked.filter { it.score > 0 }.take(limit).forEach {
            chosen.putIfAbsent(it.definition.optString("name"), it.definition)
        }
        // Sparse/novel wording can have little lexical overlap. Fill remaining room deterministically
        // from the catalog so the router still sees broad capabilities rather than failing closed.
        if (chosen.size < limit) {
            ranked.asSequence()
                .filter { it.definition.optString("name") !in chosen }
                .take(limit - chosen.size)
                .forEach { chosen[it.definition.optString("name")] = it.definition }
        }
        return JSONArray().apply { chosen.values.forEach(::put) }
    }

    private fun extractObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONArray?.toStringList(): List<String> = buildList {
        val a = this@toStringList ?: return@buildList
        for (i in 0 until a.length()) {
            a.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    val UNIVERSAL_TOOLS: Set<String> = setOf(
        "get_timeline",
        "get_clip",
        "select_clip",
        "seek",
        "set_prompt",
        "undo",
        "redo",
    )

    private val TOKEN = Regex("[a-z0-9]+")
    private val STOP = setOf(
        "the", "and", "for", "with", "this", "that", "from", "into", "then", "please",
        "want", "make", "video", "clip", "clips", "timeline", "some", "just",
    )
}
