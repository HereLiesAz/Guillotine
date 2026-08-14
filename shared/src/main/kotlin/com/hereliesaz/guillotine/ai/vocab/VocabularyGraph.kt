package com.hereliesaz.guillotine.ai.vocab

/**
 * A domain vocabulary graph for the editor. Each [Concept] is an editing idea (often mapped to an MCP
 * tool) carrying a small SEED of synonyms and an optional ANTONYM link to the opposing concept. From that
 * compact seed the graph derives a much larger recognised vocabulary **programmatically** — an expander
 * generates surface forms (see [LexiconExpander]/[RuleExpander]) and the resolver reads negation/intensity
 * cues at query time so that e.g. "less sharp" resolves to the *opposite* of `sharpen`.
 *
 * It feeds two things:
 *  1. the agent system prompt, via [promptAppendix] (so the model maps varied phrasings + opposites);
 *  2. the queryable `lookup_vocabulary` MCP tool, via [lookupJson] — which internally calls [lookup] and
 *     [resolve] (the latter for negation/inversion: word → tool + whether the sense is flipped). [resolve]
 *     has no other caller; it is not an independent pre-model routing path, only [lookupJson]'s helper.
 *
 * The seed is intentionally small and hand-checked; breadth comes from expansion, so a fuller lexical
 * source (WordNet, an embeddings/LLM pass) can be plugged in later as another [LexiconExpander] without
 * touching consumers.
 */
object VocabularyGraph {

    /** One editing concept. [seeds] are hand-written; the expander grows them into the full synonym set. */
    data class Concept(
        val id: String,
        val seeds: List<String>,
        val tool: String? = null,
        val antonym: String? = null,
    )

    /** A resolved user phrase: the concept it maps to, its tool (if any), and whether the sense was flipped. */
    data class Resolution(
        val conceptId: String,
        val tool: String?,
        val inverted: Boolean,
        val matched: String,
    )

    /** Turns a concept's seeds into its full recognised synonym set. Implementations may be rule-based, or
     *  backed by a lexical database / embeddings / an LLM pass — consumers only see the expanded set. */
    fun interface LexiconExpander {
        fun expand(seeds: List<String>): Set<String>
    }

    /** Negation / reduction cues: when one sits just before a matched term the sense flips to the antonym. */
    val NEGATIONS: Set<String> = setOf(
        "less", "least", "not", "no", "reduce", "reduced", "remove", "lower", "decrease", "decreased",
        "drop", "de", "un", "kill", "without", "anti", "negative", "minus",
    )

    /** Intensity cues: keep the same concept but signal "more of it" (used to strengthen, never to flip). */
    val INTENSIFIERS: Set<String> = setOf(
        "more", "most", "extra", "very", "increase", "increased", "boost", "boosted", "add", "added",
        "raise", "up", "heavy", "heavier", "strong", "stronger", "really", "super",
    )

    // ---- seed lexicon (editing domain) --------------------------------------
    // Antonym links are declared on one side; [concepts] wires them both ways at load.
    private val seedConcepts: List<Concept> = listOf(
        Concept("sharpen", listOf("sharpen", "sharp", "crisp", "crisper", "clarity", "clearer", "detail"), tool = "sharpen", antonym = "soften"),
        Concept("soften", listOf("soften", "soft", "blur", "blurry", "dreamy", "hazy", "smooth"), tool = "glow"),
        Concept("brighter", listOf("brighter", "bright", "brighten", "lighter", "lighten", "lift"), tool = "brighter", antonym = "darker"),
        Concept("darker", listOf("darker", "dark", "darken", "dim", "dimmer", "underexpose"), tool = "darker"),
        Concept("warm", listOf("warm", "warmer", "orange", "golden", "cozy", "toasty"), tool = "warm", antonym = "cool"),
        Concept("cool", listOf("cool", "cooler", "cold", "blue", "icy", "chilly"), tool = "cool"),
        Concept("contrast", listOf("contrast", "punchy", "punch", "pop"), tool = "increase_contrast", antonym = "flat"),
        Concept("flat", listOf("flat", "flatten", "muted", "washed", "faded", "dull"), tool = "vintage"),
        Concept("grain", listOf("grain", "grainy", "noise", "noisy", "texture", "film"), tool = "film_grain", antonym = "denoise"),
        Concept("denoise", listOf("denoise", "clean", "cleaner", "smooth", "grainless"), tool = "denoise_video"),
        Concept("stabilize", listOf("stabilize", "stabilise", "steady", "steadier", "smooth", "shake", "shaky", "jittery"), tool = "stabilize"),
        Concept("vignette", listOf("vignette", "vignetting", "darkened edges", "darken edges", "framing"), tool = "vignette"),
        Concept("mirror", listOf("mirror", "flip", "flop", "reverse horizontally", "left right"), tool = "mirror"),
        Concept("flip_vertical", listOf("upside down", "flip vertical", "vertical flip", "invert vertically"), tool = "flip_vertical"),
        Concept("pixelate", listOf("pixelate", "pixelated", "mosaic", "blocky", "8 bit", "censor"), tool = "pixelate"),
        Concept("glow", listOf("glow", "bloom", "ethereal", "halation", "soft light"), tool = "glow"),
        Concept("vhs", listOf("vhs", "retro", "analog", "tape", "camcorder", "eighties"), tool = "vhs"),
        Concept("thermal", listOf("thermal", "infrared", "heat map", "heatmap", "predator"), tool = "thermal"),
        Concept("edge_detect", listOf("edge detect", "outline", "sketch", "line art", "contours"), tool = "edge_detect"),
        Concept("cinematic", listOf("cinematic", "teal orange", "movie look", "filmic", "blockbuster"), tool = "cinematic"),
        Concept("noir", listOf("noir", "black and white", "monochrome", "greyscale", "grayscale"), tool = "noir"),
        Concept("chromatic_aberration", listOf("chromatic aberration", "chroma shift", "colour fringe", "color fringe", "rgb split"), tool = "chromatic_aberration"),
        Concept("motion_trail", listOf("motion trail", "echo", "ghosting", "smear", "trails"), tool = "motion_trail"),
        // Action / timeline concepts (no dedicated bake tool; help the model map intent + opposites).
        Concept("cut", listOf("cut", "remove", "delete", "trim", "erase", "drop"), antonym = "keep"),
        Concept("keep", listOf("keep", "retain", "isolate", "only", "preserve")),
        Concept("faster", listOf("faster", "speed up", "quicker", "fast forward", "accelerate"), antonym = "slower"),
        Concept("slower", listOf("slower", "slow down", "slow motion", "slomo", "slo mo")),
        Concept("louder", listOf("louder", "volume up", "amplify", "boost audio", "turn up"), antonym = "quieter"),
        Concept("quieter", listOf("quieter", "volume down", "softer audio", "turn down", "mute")),
        Concept("zoom_in", listOf("zoom in", "closer", "punch in", "tighter"), antonym = "zoom_out"),
        Concept("zoom_out", listOf("zoom out", "wider", "pull back", "further")),
        Concept("longer", listOf("longer", "extend", "lengthen", "stretch"), antonym = "shorter"),
        Concept("shorter", listOf("shorter", "shorten", "tighten", "condense")),
        // Timeline verbs (map to the core TimelineTools).
        Concept("seek", listOf("seek", "go to", "jump to", "scrub", "move the playhead", "skip to"), tool = "seek"),
        Concept("move_clip", listOf("move", "reposition", "shift", "slide", "relocate", "drag"), tool = "move_clip"),
        Concept("trim", listOf("trim", "shorten the edge", "clip the edge", "trim the ends"), tool = "trim_clip"),
        Concept("title", listOf("title", "caption", "subtitle", "lower third", "label", "text card"), tool = "add_text"),
        Concept("undo", listOf("undo", "revert", "take it back", "go back"), tool = "undo", antonym = "redo"),
        Concept("redo", listOf("redo", "put it back", "restore"), tool = "redo"),
        Concept("add_track", listOf("new track", "add track", "add layer", "another track"), tool = "add_track"),
        Concept("mute", listOf("mute", "silence", "unmute", "hide track", "solo"), tool = "set_track"),
        Concept("reframe", listOf("reframe", "crop the clip", "punch in", "scale the clip", "reposition the frame"), tool = "transform_clip"),
    )

    // ---- graph assembly ------------------------------------------------------

    /** All concepts with antonym links symmetrised (declared on either side wires both). */
    val concepts: List<Concept> by lazy {
        val byId = seedConcepts.associateBy { it.id }.toMutableMap()
        for (c in seedConcepts) {
            val ant = c.antonym ?: continue
            val other = byId[ant] ?: continue
            if (other.antonym == null) byId[other.id] = other.copy(antonym = c.id)
        }
        byId.values.toList()
    }

    private val byId: Map<String, Concept> by lazy { concepts.associateBy { it.id } }

    /** The active expander. Swap for a WordNet/embeddings/LLM-backed [LexiconExpander] to widen coverage. */
    var expander: LexiconExpander = RuleExpander

    /** Extra synonyms per concept from a generated pass (e.g. the LLM expander), merged on top of the seeds. */
    private var overrideExpansion: Map<String, Set<String>> = emptyMap()

    // Rebuildable (not `lazy`) so a generated expansion applied at runtime takes effect. Reference
    // assignment is atomic; the expansion is applied once at startup, before heavy concurrent use.
    private var synCache: Map<String, Set<String>>? = null
    private var indexCache: Map<String, String>? = null

    /** conceptId -> its fully expanded synonym set (seed expansion + any generated [overrideExpansion]). */
    val synonyms: Map<String, Set<String>>
        get() = synCache ?: buildSynonyms().also { synCache = it }

    private fun buildSynonyms(): Map<String, Set<String>> = concepts.associate { c ->
        val merged = LinkedHashSet(expander.expand(c.seeds))
        overrideExpansion[c.id]?.forEach { s -> normalize(s).takeIf { it.isNotEmpty() }?.let(merged::add) }
        c.id to merged
    }

    /** Reverse index: normalised synonym -> conceptId (first concept wins on the rare collision). */
    private val index: Map<String, String>
        get() = indexCache ?: buildIndex().also { indexCache = it }

    private fun buildIndex(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (c in concepts) for (s in synonyms.getValue(c.id)) m.putIfAbsent(normalize(s), c.id)
        return m
    }

    /**
     * Merge a generated synonym expansion (conceptId -> extra synonyms) into the graph and rebuild the
     * indices. Unknown concept ids are ignored. Safe to call once at startup after loading a cache or a
     * fresh LLM pass; see [VocabularyExpander].
     */
    fun applyExpansion(expansion: Map<String, Set<String>>) {
        overrideExpansion = expansion.filterKeys { byId.containsKey(it) }
        synCache = null
        indexCache = null
    }

    /** Lowercase, strip punctuation, collapse whitespace. */
    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun conceptFor(id: String): Concept? = byId[id]

    fun antonymOf(id: String): Concept? = byId[id]?.antonym?.let { byId[it] }

    /**
     * Deterministically resolve the first known term in [text] to a concept. If a negation cue sits within
     * the two tokens before the match AND the concept has an antonym, the sense is flipped to that antonym
     * (so "make it less sharp" → the `soften` concept). Returns null when nothing is recognised.
     */
    fun resolve(text: String): Resolution? {
        val toks = normalize(text).split(' ').filter { it.isNotEmpty() }
        for (i in toks.indices) {
            // Prefer a two-word phrase match ("zoom in") over the single token.
            val bigram = if (i + 1 < toks.size) "${toks[i]} ${toks[i + 1]}" else null
            val id = bigram?.let { index[it] } ?: index[toks[i]] ?: continue
            val concept = byId.getValue(id)
            val negated = (i >= 1 && toks[i - 1] in NEGATIONS) || (i >= 2 && toks[i - 2] in NEGATIONS)
            return if (negated && concept.antonym != null) {
                val ant = byId.getValue(concept.antonym)
                Resolution(ant.id, ant.tool, inverted = true, matched = toks[i])
            } else {
                Resolution(concept.id, concept.tool, inverted = false, matched = toks[i])
            }
        }
        return null
    }

    /** Synonyms + antonym + mapped tool for the concept a [term] belongs to (or null if unknown). */
    fun lookup(term: String): LookupResult? {
        val id = index[normalize(term)] ?: resolve(term)?.conceptId ?: return null
        val c = byId.getValue(id)
        return LookupResult(
            conceptId = c.id,
            tool = c.tool,
            synonyms = synonyms.getValue(c.id).sorted(),
            antonym = c.antonym,
            antonymSynonyms = c.antonym?.let { synonyms[it]?.sorted() } ?: emptyList(),
        )
    }

    data class LookupResult(
        val conceptId: String,
        val tool: String?,
        val synonyms: List<String>,
        val antonym: String?,
        val antonymSynonyms: List<String>,
    )

    /** The MCP `lookup_vocabulary` response for [term] (shared so both platforms return the same shape). */
    fun lookupJson(term: String): org.json.JSONObject {
        val l = lookup(term)
        val r = resolve(term)
        return org.json.JSONObject().apply {
            if (l == null && r == null) {
                put("found", false)
                put("humanSummary", "No known editing concept for \"$term\".")
                return@apply
            }
            put("found", true)
            l?.let {
                put("concept", it.conceptId)
                it.tool?.let { t -> put("tool", t) }
                put("synonyms", org.json.JSONArray(it.synonyms))
                it.antonym?.let { a -> put("antonym", a) }
                put("antonymSynonyms", org.json.JSONArray(it.antonymSynonyms))
            }
            r?.let {
                put("resolvedTool", it.tool ?: org.json.JSONObject.NULL)
                put("inverted", it.inverted)
            }
            val where = l?.tool?.let { " → $it" } ?: (l?.conceptId?.let { " → $it" } ?: "")
            val flip = if (r?.inverted == true) " (negated — routes to the opposite tool)" else ""
            put("humanSummary", "\"$term\"$where$flip")
        }
    }

    /**
     * A compact appendix for the agent system prompt: one line per tool-mapped concept listing a few
     * synonyms and its opposite, so the model maps varied wording (and negations) to the right tool.
     */
    fun promptAppendix(): String = buildString {
        append("    VOCABULARY (map these words/phrasings — and their opposites — to the right tool):\n")
        for (c in concepts) {
            if (c.tool == null) continue
            val syns = c.seeds.take(4).joinToString(", ")
            val opp = c.antonym?.let { byId[it] }
            append("    - ${c.tool}: $syns")
            if (opp != null) append("  (opposite → ${opp.tool ?: opp.id})")
            append("\n")
        }
        append("    A negation before a term (\"less/not/reduce/remove …\") means the OPPOSITE — route it to ")
        append("the opposite tool, not the named one.")
    }
}

/**
 * The default, dependency-free generator: expands each seed with light morphology (comparative `-er`,
 * `-y`) and a couple of common phrasings, all normalised and de-duplicated. This is the "programmatic
 * expansion" — a compact seed becomes a broad recognised set — and the swap-in point for a richer
 * [VocabularyGraph.LexiconExpander] (WordNet, embeddings, an LLM pass) later.
 */
object RuleExpander : VocabularyGraph.LexiconExpander {
    override fun expand(seeds: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in seeds) {
            val s = raw.lowercase().trim()
            if (s.isEmpty()) continue
            out.add(s)
            // Multi-word seeds are already specific phrasings; only single words get morphology.
            if (' ' in s) continue
            out.add("$s it")
            when {
                s.endsWith("e") -> { out.add("${s}r"); out.add("${s}d") }      // sharpe->? guard below
                s.endsWith("y") -> out.add(s.dropLast(1) + "ier")               // grainy->grainier
                s.endsWith("er") -> {}                                          // already comparative
                else -> { out.add("${s}er"); out.add("${s}y") }                // sharp->sharper/sharpy(harmless)
            }
        }
        return out
    }
}
