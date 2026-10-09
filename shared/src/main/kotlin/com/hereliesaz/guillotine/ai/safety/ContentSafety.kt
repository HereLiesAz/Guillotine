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
        "nipple", "nipples", "areola", "areolas", "breast", "breasts", "boob", "boobs", "tit", "tits",
        "titties", "cleavage", "genital", "genitals", "genitalia", "penis", "vagina", "vulva", "crotch",
        "buttocks", "sex", "sexual", "sexually", "sexy", "porn", "nsfw", "hentai", "erotic", "erotica",
        "fetish", "bdsm", "lingerie", "stripper", "striptease", "orgasm", "cum", "onlyfans", "xxx",
        "lewd", "horny", "seductive", "explicit", "uncensored", "rule34", "ecchi", "ahegao", "milf",
    )
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
        val text = prompt.trim().lowercase()
        if (text.isEmpty()) return "Enter a prompt to generate."
        val words = text.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }
        val hit = words.firstOrNull { w -> w in BLOCKED_WORDS || BLOCKED_STEMS.any { w.startsWith(it) } }
            ?: BLOCKED_PHRASES.firstOrNull { it in text }
        return hit?.let { "This prompt asks for sexual or nude content, which Guillotine doesn't generate." }
    }

    /** Throws [BlockedException] when [promptRefusal] refuses [prompt]. */
    fun checkPrompt(prompt: String) {
        promptRefusal(prompt)?.let { throw BlockedException(it) }
    }
}
