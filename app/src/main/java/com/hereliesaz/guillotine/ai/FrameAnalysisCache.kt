package com.hereliesaz.guillotine.ai

/**
 * Process-wide, bounded cache of per-frame vision results — the expensive computations that
 * [MlKitProvider]'s scan paths run for every sampled frame:
 *
 *  - **Object labels**: the lowercase COCO class names [ObjectVision] found in the frame
 *    (`ObjectVision.labels(bmp)`), independent of any specific prompt.
 *  - **Classifier labels**: [SceneClassifier] (EfficientNet-Lite0 / ImageNet ~1000 classes),
 *    snapshotted as [SceneLabel] with pre-lowercased text.
 *  - **Scene labels**: ML Kit's generic [ImageLabeler] results (~400 labels), same [SceneLabel]
 *    format. Separate cache from classifier labels to avoid cross-contamination.
 *  - **Detections**: full COCO detections with bounding boxes (`ObjectVision.detect(bmp)`), used
 *    by reference-mode analysis to crop each candidate object before embedding. Boxes are read-only
 *    to callers — do not mutate a returned Detection's RectF.
 *
 * All are keyed by [FrameKey] `(uri, atMs)`. Face detection is not cached — it's fast, and it only
 * runs when the prompt explicitly asks for faces. Reference-mode's *embedding-similarity result* is
 * still not cached (it depends on the runtime reference crop, not just the frame), but the raw
 * detections it consumes are — so rescanning the same clip against a different reference reuses
 * every `detect()` call from the previous run.
 *
 * The payoff: **rescanning the same clip with a different prompt** (e.g. "keep faces" then "cut
 * cars", or a wording tweak) reuses all the frame decodes + ML Kit inference done last time — the
 * scan turns into a per-frame set/list membership check, which is essentially free.
 *
 * A single access-order LinkedHashMap per signal caps the cache at [MAX_ENTRIES]. Heavy compute
 * lambdas run outside the lock via double-checked locking: check → compute unlocked → re-check
 * and insert under the lock. This avoids blocking concurrent callers during ML Kit inference.
 */
object FrameAnalysisCache {
    /** Default cap — ~13 minutes of 3-fps sampling per signal. Settings can override at runtime. */
    const val DEFAULT_MAX_ENTRIES = 4096

    /** Reasonable UI range for the Settings slider. 0 effectively disables the cache. */
    const val MIN_MAX_ENTRIES = 0
    const val MAX_MAX_ENTRIES = 32_768

    data class FrameKey(val uri: String, val atMs: Long)

    data class SceneLabel(val text: String, val lowerText: String, val confidence: Float)

    /**
     * Live cap read on every eviction check. `@Volatile` — the setter can fire from the settings
     * scope while a scan is running on another dispatcher; a write here is visible on the next
     * `removeEldestEntry` call, which trims the cache naturally as new entries arrive rather than
     * eagerly (so the setter stays cheap). Set to 0 to effectively disable: every put becomes its
     * own eldest and is immediately evicted, so the cache stays empty.
     */
    @Volatile
    private var maxEntries: Int = DEFAULT_MAX_ENTRIES

    private val objectLabels: MutableMap<FrameKey, Set<String>> = boundedLru()
    private val classifierLabels: MutableMap<FrameKey, List<SceneLabel>> = boundedLru()
    private val sceneLabels: MutableMap<FrameKey, List<SceneLabel>> = boundedLru()
    private val detections: MutableMap<FrameKey, List<ObjectVision.Detection>> = boundedLru()

    /** Apply a new cap. Takes effect on the next put/get (trim happens as entries arrive). */
    fun setMaxEntries(n: Int) {
        maxEntries = n.coerceIn(MIN_MAX_ENTRIES, MAX_MAX_ENTRIES)
    }

    fun objectLabels(uri: String, atMs: Long, compute: () -> Set<String>): Set<String> {
        val key = FrameKey(uri, atMs)
        synchronized(objectLabels) { objectLabels[key]?.let { return it } }
        val v = compute()
        synchronized(objectLabels) { return objectLabels.getOrPut(key) { v } }
    }

    fun classifierLabels(
        uri: String,
        atMs: Long,
        compute: () -> List<SceneLabel>,
    ): List<SceneLabel> {
        val key = FrameKey(uri, atMs)
        synchronized(classifierLabels) { classifierLabels[key]?.let { return it } }
        val v = compute()
        synchronized(classifierLabels) { return classifierLabels.getOrPut(key) { v } }
    }

    fun sceneLabels(
        uri: String,
        atMs: Long,
        compute: () -> List<SceneLabel>,
    ): List<SceneLabel> {
        val key = FrameKey(uri, atMs)
        synchronized(sceneLabels) { sceneLabels[key]?.let { return it } }
        val v = compute()
        synchronized(sceneLabels) { return sceneLabels.getOrPut(key) { v } }
    }

    fun detections(
        uri: String,
        atMs: Long,
        compute: () -> List<ObjectVision.Detection>,
    ): List<ObjectVision.Detection> {
        val key = FrameKey(uri, atMs)
        synchronized(detections) { detections[key]?.let { return it } }
        val v = compute()
        synchronized(detections) { return detections.getOrPut(key) { v } }
    }

    /** Clear everything — used by tests and by teardown paths that suspect stale entries. */
    fun clear() {
        synchronized(objectLabels) { objectLabels.clear() }
        synchronized(classifierLabels) { classifierLabels.clear() }
        synchronized(sceneLabels) { sceneLabels.clear() }
        synchronized(detections) { detections.clear() }
    }

    private fun <K, V> boundedLru(): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(64, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        }
}
