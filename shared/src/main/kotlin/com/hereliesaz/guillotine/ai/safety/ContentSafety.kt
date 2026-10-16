package com.hereliesaz.guillotine.ai.safety

/**
 * Guillotine's content policy for AI generation (Google Play's Sexual Content and AI-Generated Content
 * policies): no sexual or nude content, from any provider, by any route (the generate dialogs, the
 * assistant's tools, the MCP server). Three layers, applied in this order:
 *
 * 1. [checkPrompt] refuses a blank prompt or one asking for sexual content, before anything is sent.
 * 2. The provider's own filter, where it has one (fal's `enable_safety_checker`).
 *
 * Text-to-image generation is not offered at all.
 * 3. [NsfwModel] classifies the result on-device before it reaches the timeline; flagged output is
 *    deleted. Generation fails closed: no classifier, no image.
 *
 * Every generated clip carries its provenance ([com.hereliesaz.guillotine.model.AiProvenance]) so it
 * can be reported ([ContentReport]).
 */
object ContentSafety {

    /** Shown in the generate dialogs and Settings; the policy in one line. */
    const val NOTICE =
        "AI generation refuses sexual or nude content and checks every result on this device. " +
            "Report anything that slips through from the clip's menu."

    class BlockedException(message: String) : IllegalArgumentException(message)

    // Whole words (after lower-casing and splitting on anything that isn't a letter or digit). Prefix
    // stems catch inflections ("masturbating", "pornographic").
    private val BLOCKED_WORDS = setOf(
        "nude", "nudes", "nudity", "naked", "topless", "bottomless", "undressed", "unclothed", "undress",
        "nipple", "nipples", "areola", "areolas", "breast", "breasts", "boob", "boobs", "tits",
        "titties", "cleavage", "genital", "genitals", "genitalia", "penis", "vagina", "vulva", "crotch",
        "buttocks", "sex", "sexual", "sexually", "sexy", "porn", "nsfw", "hentai", "erotic", "erotica",
        "fetish", "bdsm", "lingerie", "stripper", "striptease", "orgasm", "cum", "onlyfans", "xxx",
        "lewd", "horny", "seductive", "uncensored", "rule34", "ecchi", "ahegao", "milf",
    )
    // Dropped as whole words because they were mostly false positives: "tit" (the bird), "explicit"
    // ("explicit lyrics", "be explicit"). Benign phrases containing a blocked word are blanked out
    // before matching.
    private val BENIGN_PHRASES = listOf(
        "chicken breast", "turkey breast", "duck breast", "breast cancer", "breast stroke", "breaststroke",
        "breastfeeding awareness", "cum laude", "magna cum", "summa cum",
    )
    // Common character substitutions used to dodge a word list ("p0rn", "nud3", "s3x", "$exy").
    private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')
    private val BLOCKED_STEMS = listOf("porn", "masturbat", "erotic")
    private val BLOCKED_PHRASES = listOf(
        "see through", "see-through", "no clothes", "without clothes", "no clothing", "wet t-shirt",
        "open shirt", "unbuttoned shirt", "no bra", "no shirt", "wardrobe malfunction", "deepfake",
    )

    /**
     * The reason [prompt] is refused, or null when it may be sent. Blank prompts are refused too: an
     * empty prompt leaves the provider to pick the subject.
     */
    fun promptRefusal(prompt: String): String? {
        // NFKC folds full-width/compatibility forms ("ｎｕｄｅ") to plain letters before matching.
        var text = java.text.Normalizer.normalize(prompt.trim(), java.text.Normalizer.Form.NFKC).lowercase()
        if (text.isEmpty()) return "Enter a prompt to generate."
        for (b in BENIGN_PHRASES) text = text.replace(b, " ")
        val raw = text.split(Regex("[^\\p{L}\\p{Nd}@$]+")).filter { it.isNotEmpty() }
        // Each word as written and with leetspeak undone; plus runs of single characters glued back
        // together, so spaced-out "n u d e" / "p.o.r.n" can't slip past whole-word matching.
        val words = raw.flatMap { w -> listOf(w, w.map { c -> LEET[c] ?: c }.joinToString("")) } + spacedRuns(raw)
        val spaced = text.replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
        val hit = words.firstOrNull { w -> w in BLOCKED_WORDS || BLOCKED_STEMS.any { w.startsWith(it) } }
            ?: BLOCKED_PHRASES.firstOrNull { it in text || it.replace('-', ' ') in spaced }
        return hit?.let { "This prompt asks for sexual or nude content, which Guillotine doesn't generate." }
    }

    /** Consecutive single-character tokens joined into one word ("n u d e" → "nude"). */
    private fun spacedRuns(words: List<String>): List<String> {
        val out = ArrayList<String>()
        val run = StringBuilder()
        for (w in words + "") {
            if (w.length == 1) { run.append(LEET[w[0]] ?: w[0]); continue }
            if (run.length >= 3) out.add(run.toString())
            run.setLength(0)
        }
        return out
    }

    /** Throws [BlockedException] when [promptRefusal] refuses [prompt]. */
    fun checkPrompt(prompt: String) {
        promptRefusal(prompt)?.let { throw BlockedException(it) }
    }

    /**
     * Inpainting may remove or change people, never add them. True when a repainted frame holds
     * more people than the original did ([before]/[after] are person-detection counts).
     */
    fun inpaintAddsPeople(before: Int, after: Int): Boolean = after > before

    /** Shown when an inpainted frame is discarded by [inpaintAddsPeople]. */
    const val INPAINT_ADDED_PERSON =
        "Inpainting can remove or change people, never add them. That result was discarded."
}
