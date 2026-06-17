package com.hereliesaz.guillotine.ai

/**
 * Granular progress reported by each provider at natural checkpoints.
 * [fraction] is 0..1 when computable (upload/scan), or null for indeterminate stages.
 * [segmentsFound] is the running count of segments discovered so far.
 */
data class AnalysisProgress(
    val stage: String,
    val fraction: Float? = null,
    val segmentsFound: Int = 0,
)
