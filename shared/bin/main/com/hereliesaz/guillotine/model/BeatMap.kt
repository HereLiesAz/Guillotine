package com.hereliesaz.guillotine.model

/**
 * The rhythm of an audio clip, produced on-device by `BeatAnalyzer`. All timestamps are in
 * **source-media milliseconds** (offsets into the audio itself, not timeline position). Consumed by
 * the "edit to the beat" MCP tools to derive cut points and on-beat keyframes.
 */
data class BeatMap(
    /** Estimated tempo in beats per minute. */
    val bpm: Float,
    /** Every detected beat, in source ms, ascending. */
    val beatsMs: List<Long>,
    /** The stronger beats (typically every 4th — bar starts), in source ms. */
    val downbeatsMs: List<Long>,
    /** Note/percussion onsets, in source ms (denser than beats). */
    val onsetsMs: List<Long>,
) {
    /** Pick the timestamps for a named [mode]: "beats" (default), "downbeats", or "onsets". */
    fun points(mode: String): List<Long> = when (mode.lowercase().trim()) {
        "downbeat", "downbeats", "bar", "bars" -> downbeatsMs.ifEmpty { beatsMs }
        "onset", "onsets" -> onsetsMs.ifEmpty { beatsMs }
        else -> beatsMs
    }
}
