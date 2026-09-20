package com.hereliesaz.guillotine.ai.agent

import java.util.Locale

/**
 * Fast prompt guidance for the editor command box.
 *
 * Stage 1 is deliberately deterministic and synchronous so useful suggestions appear on the same
 * keystroke that makes the user's intent recognisable. Stage 2 can ask a tiny local model for help
 * only when Stage 1 has no confident match; [modelPrompt] and [parseModelSuggestions] keep that
 * fallback constrained to short, executable Guillotine instructions.
 */
data class PromptSuggestion(
    val label: String,
    val prompt: String,
)

object PromptCoach {

    fun suggest(input: String, limit: Int = 4): List<PromptSuggestion> {
        val q = normalize(input)
        if (q.length < 3) return emptyList()

        val suggestions = when {
            looksLikeBoring(q) -> listOf(
                PromptSuggestion(
                    "Cut dead air",
                    "Cut pauses and dead air across the timeline.",
                ),
                PromptSuggestion(
                    "Faster pacing",
                    "Make the pacing faster by cutting pauses, repetitive moments, and low-action sections.",
                ),
                PromptSuggestion(
                    "Keep dialogue",
                    "Keep all spoken dialogue, but remove downtime and low-action sections.",
                ),
                PromptSuggestion(
                    "Cut repetition",
                    "Cut repetitive moments and long stretches where very little happens.",
                ),
            )

            looksLikeAudioFix(q) -> listOf(
                PromptSuggestion(
                    "Clean dialogue",
                    "Remove background noise from the dialogue and make speech clearer.",
                ),
                PromptSuggestion(
                    "Normalize loudness",
                    "Normalize the dialogue loudness across the timeline.",
                ),
                PromptSuggestion(
                    "Lower music under speech",
                    "Duck the music whenever someone is speaking.",
                ),
            )

            looksLikeCinematic(q) -> listOf(
                PromptSuggestion(
                    "Cinematic grade",
                    "Apply a cinematic color grade to the video clips in the timeline.",
                ),
                PromptSuggestion(
                    "Background blur",
                    "Add a cinematic depth-of-field look with a softly blurred background.",
                ),
                PromptSuggestion(
                    "Cinematic pacing",
                    "Make the edit feel more cinematic by slowing down the cut density and keeping the strongest shots.",
                ),
            )

            looksLikeCleanup(q) -> listOf(
                PromptSuggestion(
                    "Stabilize footage",
                    "Stabilize shaky video clips across the timeline.",
                ),
                PromptSuggestion(
                    "Clean video",
                    "Denoise and lightly sharpen the video clips across the timeline.",
                ),
                PromptSuggestion(
                    "Tighten pacing",
                    "Tighten the edit by removing pauses and repetitive moments.",
                ),
            )

            looksLikeCaptions(q) -> listOf(
                PromptSuggestion(
                    "Add captions",
                    "Transcribe the speech and add captions.",
                ),
                PromptSuggestion(
                    "Animated captions",
                    "Transcribe the speech and add animated captions.",
                ),
            )

            looksLikeShorter(q) -> listOf(
                PromptSuggestion(
                    "Cut downtime",
                    "Shorten the edit by removing pauses, dead air, and repetitive moments.",
                ),
                PromptSuggestion(
                    "Keep strongest moments",
                    "Make this shorter while keeping the strongest moments and all important dialogue.",
                ),
            )

            looksLikeHighlights(q) -> listOf(
                PromptSuggestion(
                    "Find highlights",
                    "Find the best moments and turn them into a tighter highlight edit.",
                ),
                PromptSuggestion(
                    "Keep crowd reactions",
                    "Keep the strongest applause, cheering, laughter, and crowd-reaction moments.",
                ),
            )

            else -> emptyList()
        }

        return suggestions
            .distinctBy { normalize(it.prompt) }
            .take(limit.coerceAtLeast(0))
    }

    /**
     * A submitted request that is safe to execute as a deterministic pacing edit without waking the
     * assistant LLM. This is intentionally narrower than [looksLikeBoring]: merely mentioning pacing
     * should not delete anything. We require an editing verb plus a boring/dead-air style target.
     */
    fun isBoringCutRequest(input: String): Boolean {
        val q = normalize(input)
        if (q.contains("slow motion") || containsAny(q, "make it slower", "slow it down", "slower pacing")) {
            return false
        }
        val action = containsAny(
            q,
            "cut", "remove", "delete", "trim", "shorten", "tighten", "speed up", "faster",
        )
        val target = BORING_PREFIX_REGEX.containsMatchIn(q) || containsAny(
            q,
            "dead air", "downtime", "long pause", "long pauses", "slow parts", "dragging", "drags",
            "nothing happens", "low action", "low-action",
        )
        return action && target
    }

    /**
     * Keep the model fallback for short, vague commands. Concrete/long prompts do not need a rewrite,
     * and skipping them avoids waking the local model unnecessarily while the user is still typing.
     */
    fun shouldUseModel(input: String): Boolean {
        val q = normalize(input)
        if (q.length !in 4..180) return false
        if (q.split(' ').count { it.isNotBlank() } > 12) return false
        return containsAny(
            q,
            "make", "fix", "cut", "remove", "edit", "better", "good", "bad",
            "interesting", "cool", "nice", "clean", "sound", "audio", "look", "feel",
        )
    }

    /** Prompt for the tiny on-device fallback used only when [suggest] has no confident match. */
    fun modelPrompt(input: String): String = """
        You are Guillotine's prompt coach, not the editor.
        Rewrite the user's vague video-editing request into 2 or 3 concrete commands Guillotine can execute.
        Do not perform edits. Do not mention tool names. Keep each command under 18 words.
        Output ONLY one command per line. No bullets, numbering, explanation, or quotes.

        User request: ${input.trim().take(320)}
    """.trimIndent()

    /**
     * Turn an unconstrained small-model reply into safe UI suggestions.
     * Bad/verbose output simply disappears and the editor keeps the instant deterministic layer.
     */
    fun parseModelSuggestions(raw: String?, limit: Int = 3): List<PromptSuggestion> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw
            .lineSequence()
            .map { it.trim().trimStart('-', '•', '*').trim() }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.length in 8..180 }
            .filterNot { TOOL_NAME_REGEX.matches(it) }
            .distinctBy { normalize(it) }
            .take(limit.coerceAtLeast(0))
            .map { text ->
                PromptSuggestion(
                    label = text.trimEnd('.').take(42),
                    prompt = if (text.endsWith('.')) text else "$text.",
                )
            }
            .toList()
    }

    private fun looksLikeBoring(q: String): Boolean =
        (
            BORING_PREFIX_REGEX.containsMatchIn(q) ||
                containsAny(
                    q,
                    "dull", "dead air", "nothing happens", "low action",
                    "dragging", "drags", "too slow", "slow parts", "repetitive", "repetition",
                    "pacing", "make it interesting", "more interesting",
                )
        ) && !q.contains("slow motion")

    private fun looksLikeAudioFix(q: String): Boolean =
        containsAny(
            q,
            "fix the sound", "fix sound", "fix audio", "clean audio", "clean the audio",
            "bad audio", "bad sound", "noisy", "noise", "dialogue too quiet", "voice too quiet",
        )

    private fun looksLikeCinematic(q: String): Boolean =
        containsAny(q, "cinematic", "movie look", "film look", "look like a movie")

    private fun looksLikeCleanup(q: String): Boolean =
        containsAny(q, "make it better", "clean this up", "clean it up", "fix this", "improve this", "polish this")

    private fun looksLikeCaptions(q: String): Boolean =
        containsAny(q, "caption", "subtitle", "subtitles", "transcribe")

    private fun looksLikeShorter(q: String): Boolean =
        containsAny(q, "make it shorter", "shorten this", "shorter", "trim this down", "too long")

    private fun looksLikeHighlights(q: String): Boolean =
        containsAny(q, "highlight", "best parts", "best moments", "good parts", "exciting parts")

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any(text::contains)

    private val BORING_PREFIX_REGEX = Regex("\\bbor(?:$|i|e)")
    private val TOOL_NAME_REGEX = Regex("^[a-z0-9]+(?:_[a-z0-9]+)+$")

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
}
