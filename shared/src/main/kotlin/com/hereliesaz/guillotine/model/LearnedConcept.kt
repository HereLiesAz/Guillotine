package com.hereliesaz.guillotine.model

import kotlinx.serialization.Serializable

/**
 * A visual thing the user taught the app by pointing it out in one or more frames — few-shot, on
 * device. Each example is an L2-normalized image-embedding vector of a crop the user pointed at;
 * recognition matches a frame when any of its objects is close (cosine similarity) to ANY example, so
 * more examples make it robust to angle/lighting/scale. [terms] are optional class hints ("dog") used
 * to narrow which detections to compare. Persisted so a learned thing is reusable across clips/projects.
 */
@Serializable
data class LearnedConcept(
    val name: String,
    val terms: List<String> = emptyList(),
    val examples: List<List<Float>> = emptyList(),
) {
    val exampleCount: Int get() = examples.size
}
