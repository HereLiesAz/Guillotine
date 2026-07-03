package com.hereliesaz.guillotine.ai

/**
 * Process-wide, bounded cache of per-frame vision results — the expensive computations that
 * [MlKitProvider]'s scan paths run for every sampled frame:
 *
 *  - **Object labels**: the lowercase COCO class names [ObjectVision] found in the frame
 *    (`ObjectVision.labels(bmp)`), independent of any specific prompt.
 *  - **Scene labels**: ML Kit's generic [ImageLabeler] results, snapshotted as `(text, confidence)`
 *    pairs so the cache doesn't hold refs to the labeler's domain objects (which get closed at the
 *    end of each scan).
 *  - **Detections**: full COCO detections with bounding boxes (`ObjectVision.detect(bmp)`), used
 *    by reference-mode analysis to crop each candidate object before embedding. Boxes are read-only
 *    to callers — do not mutate a returned Detection's RectF.
 *
 * All are keyed by `(uri, sampledTimeMs)`. Face detection is not cached — it's fast, and it only
 * runs when the prompt explicitly asks for faces. Reference-mode's *embedding-similarity result* is
 * still not cached (it depends on the runtime reference crop, not just the frame), but the raw
 * detections it consumes are — so rescanning the same clip against a different reference reuses
 * every `detect()` call from the previous run.
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
    /** Default cap — ~13 minutes of 3-fps sampling per signal. Settings can override at runtime. */
    const val DEFAULT_MAX_ENTRIES = 4096

    /** Reasonable UI range for the Settings slider. 0 effectively disables the cache. */
    const val MIN_MAX_ENTRIES = 0
    const val MAX_MAX_ENTRIES = 32_768

    /**
     * Live cap read on every eviction check. `@Volatile` — the setter can fire from the settings
     * scope while a scan is running on another dispatcher; a write here is visible on the next
     * `removeEldestEntry` call, which trims the cache naturally as new entries arrive rather than
     * eagerly (so the setter stays cheap). Set to 0 to effectively disable: every put becomes its
     * own eldest and is immediately evicted, so the cache stays empty.
     */
    @Volatile
    private var maxEntries: Int = DEFAULT_MAX_ENTRIES

    private val objectLabels: MutableMap<String, Set<String>> = boundedLru()
    private val sceneLabels: MutableMap<String, List<Pair<String, Float>>> = boundedLru()
    private val detections: MutableMap<String, List<ObjectVision.Detection>> = boundedLru()

    /** Apply a new cap. Takes effect on the next put/get (trim happens as entries arrive). */
    fun setMaxEntries(n: Int) {
        maxEntries = n.coerceIn(MIN_MAX_ENTRIES, MAX_MAX_ENTRIES)
    }

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

    @Synchronized
    fun detections(
        uri: String,
        atMs: Long,
        compute: () -> List<ObjectVision.Detection>,
    ): List<ObjectVision.Detection> {
        val key = keyOf(uri, atMs)
        detections[key]?.let { return it }
        val v = compute()
        detections[key] = v
        return v
    }

    /** Clear everything — used by tests and by teardown paths that suspect stale entries. */
    @Synchronized
    fun clear() {
        objectLabels.clear()
        sceneLabels.clear()
        detections.clear()
    }

    private fun keyOf(uri: String, atMs: Long): String = "$uri@$atMs"

    private fun <K, V> boundedLru(): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(64, 0.75f, /* accessOrder = */ true) {
            // Read the volatile cap on every check so runtime setMaxEntries() calls take effect
            // without recreating the map.
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        }
}
