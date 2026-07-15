package com.hereliesaz.guillotine.ai

data class WordCue(val word: String, val startMs: Long, val endMs: Long)

data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<WordCue> = emptyList(),
)
