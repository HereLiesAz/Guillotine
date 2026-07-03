package com.hereliesaz.guillotine.ai

/**
 * Process-wide, bounded cache of per-frame vision results — the two expensive computations that
 * [MlKitProvider]'s standard `analyze` path runs for every sampled frame:
 *
 *  - **Object labels**: the lowercase COCO class names [ObjectVision] found in the frame
 *    (`ObjectVision.labels(bmp)`), independent of any specific prompt.
 *  - **Scene labels**: ML Kit's generic [ImageLabeler] results, snapshotted as `(text, confidence)`
 *    pairs so the cache doesn't hold refs to the labeler's domain objects (which get closed at the
 *    end of each scan).
 *
 * Both are keyed by `(uri, sampledTimeMs)`. Face detection is not cached — it's fast, and it only
 * runs when the prompt explicitly asks for faces. Reference-mode analysis is also not cached: its
 * per-frame work is embedding a *specific* runtime reference crop into every candidate frame's
 * detections, which isn't a property of the frame alone.
 *
 * The payoff: **rescanning the same clip with a different prompt** (e.g. "keep faces" then "cut
 * cars", or a wording tweak) reuses all the frame decodes + ML Kit inference done last time — the
 * scan turns into a per-frame set/list membership check, which is essentially free.
 *
 * A single access-order LinkedHashMap per signal caps the cache at [MAX_ENTRIES]. Access is
 * synchronised so producers can call from any coroutine dispatcher without a race — the frame
 * decode + ML Kit call happens INSIDE the compute lambda under the lock, but since operations are
 * single-slot (guarded by OperationController) contention is a non-issue in practice.
 */
object FrameAnalysisCache {
    /** Enough for ~13 minutes of 3-fps sampling per signal — well past typical single-scan needs. */
    private const val MAX_ENTRIES = 4096

    private val objectLabels: MutableMap<String, Set<String>> = boundedLru(MAX_ENTRIES)
    private val sceneLabels: MutableMap<String, List<Pair<String, Float>>> = boundedLru(MAX_ENTRIES)

    @Synchronized
    fun objectLabels(uri: String, atMs: Long, compute: () -> Set<String>): Set<String> {
        val key = keyOf(uri, atMs)
        objectLabels[key]?.let { return it }
        val v = compute()
        objectLabels[key] = v
        return v
    }

    @Synchronized
    fun sceneLabels(
        uri: String,
        atMs: Long,
        compute: () -> List<Pair<String, Float>>,
    ): List<Pair<String, Float>> {
        val key = keyOf(uri, atMs)
        sceneLabels[key]?.let { return it }
        val v = compute()
        sceneLabels[key] = v
        return v
    }

    /** Clear everything — used by tests and by teardown paths that suspect stale entries. */
    @Synchronized
    fun clear() {
        objectLabels.clear()
        sceneLabels.clear()
    }

    private fun keyOf(uri: String, atMs: Long): String = "$uri@$atMs"

    private fun <K, V> boundedLru(maxEntries: Int): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(64, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        }
}
