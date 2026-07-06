package com.hereliesaz.guillotine.ai

/**
 * Granular progress reported by each provider at natural checkpoints.
 * [fraction] is 0..1 when computable (upload/scan), or null for indeterminate stages.
 * [segmentsFound] is the running count of segments discovered so far.
 * [finding] is an optional human-readable note about what the analysis just saw and decided
 * (e.g. "0:03 · person, chair · will cut") — surfaced in the activity feed so the user can see
 * *what* was found and *why* a region is kept or cut, not just which frame is being scanned.
 * [stage] remains the live one-line status (the frame counter) shown on the progress indicator.
 */
data class AnalysisProgress(
    val stage: String,
    val fraction: Float? = null,
    val segmentsFound: Int = 0,
    val finding: String? = null,
)
