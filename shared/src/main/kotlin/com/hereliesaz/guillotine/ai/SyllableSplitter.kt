package com.hereliesaz.guillotine.ai

/**
 * Heuristic English syllable splitter. Splits a word into syllable strings by finding vowel
 * groups and placing breaks at consonant boundaries between them. Not perfect — true syllable
 * boundaries depend on morphology and pronunciation — but accurate enough for timed caption
 * animation where the visual effect matters more than linguistic precision.
 */
object SyllableSplitter {

    private const val VOWELS = "aeiouy"

    fun split(word: String): List<String> {
        if (word.length <= 2) return listOf(word)
        val lower = word.lowercase()

        val vowelGroupStarts = mutableListOf<Int>()
        var inVowel = false
        for (i in lower.indices) {
            val v = lower[i] in VOWELS
            if (v && !inVowel) vowelGroupStarts.add(i)
            inVowel = v
        }

        // Silent final 'e': drop the last vowel group if it's a lone 'e' at the end
        // preceded by a consonant (e.g., "make" = 1 syllable, not 2).
        if (vowelGroupStarts.size > 1 && lower.endsWith("e") &&
            lower.length >= 3 && lower[lower.length - 2] !in VOWELS
        ) {
            val lastStart = vowelGroupStarts.last()
            if (lastStart == lower.length - 1) vowelGroupStarts.removeAt(vowelGroupStarts.lastIndex)
        }

        if (vowelGroupStarts.size <= 1) return listOf(word)

        // Place a break between each pair of consecutive vowel groups. The break goes
        // inside the consonant run separating them: if there's one consonant it stays
        // with the next syllable; if two or more, split after the first.
        val breaks = mutableListOf<Int>()
        for (g in 0 until vowelGroupStarts.size - 1) {
            // End of current vowel group
            var endV = vowelGroupStarts[g]
            while (endV + 1 < lower.length && lower[endV + 1] in VOWELS) endV++
            val conStart = endV + 1
            val conEnd = vowelGroupStarts[g + 1]
            val conLen = conEnd - conStart
            val breakAt = when {
                conLen <= 1 -> conStart
                else -> conStart + 1
            }
            breaks.add(breakAt)
        }

        // Slice the original (preserving case) at the break points.
        val parts = mutableListOf<String>()
        var start = 0
        for (b in breaks) {
            val bp = b.coerceIn(start + 1, word.length - 1)
            parts.add(word.substring(start, bp))
            start = bp
        }
        parts.add(word.substring(start))
        return parts.filter { it.isNotBlank() }
    }
}
