package com.hereliesaz.guillotine.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Free, on-device vision analyzer (ML Kit) — no key, no network. Matches frames against
 * the prompt (face detection when it's about people, else object/scene labeling) and turns
 * matches into keep/remove ranges per the prompt's intent ("keep only…" vs "cut/remove…").
 *
 * To keep it fast it does **not** inspect every frame: it samples at [SAMPLE_FPS] fps and, on a
 * match, claims ±[EXTEND_FRAMES] source frames (at least half a sampling step) around the sampled
 * time so consecutive matches merge into one contiguous cut region.
 *
 * Audio can't be transcribed here, so audio clips are routed to the free Local analyzer.
 */
class MlKitProvider : ClipAnalyzer {

    private data class Intent(val terms: List<String>, val keepMatches: Boolean, val useFaces: Boolean)

    override suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
        onProgress: (AnalysisProgress) -> Unit,
        checkpoint: () -> Unit,
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        if (kind == MediaKind.AUDIO) {
            throw IllegalStateException("On-device vision analyzes video and images. For audio, use the free Local analyzer.")
        }
        val parsed = parseIntent(prompt)
        if (!parsed.useFaces && parsed.terms.isEmpty()) {
            throw IllegalStateException("Tell on-device vision what to look for, e.g. \"keep shots with a face\" or \"cut clips with a phone\".")
        }
        // Map common words to COCO category names ("phone" -> "cell phone") so object detection matches.
        val intent = parsed.copy(terms = expandTerms(parsed.terms))

        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val faceDetector = if (intent.useFaces) {
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build(),
            )
        } else null
        // Precise bounding-box COCO detection for object terms (face intents stay on the face detector).
        val objectVision = if (intent.useFaces) null else ObjectVision(context)
        // When every term is a COCO class the detector owns, the whole-image labeler adds no recall
        // (too-small objects aren't top labels either) and only burns time per frame — skip it.
        val useFallback = objectVision?.available != true || intent.terms.any { !ObjectVision.coversTerm(it) }

        val uriStr = mediaUri.toString()
        // The (uri, atMs) tuple lets qualifies() consult FrameAnalysisCache so a rescan of the same
        // clip (different prompt, or the same one after a settings change) doesn't redo the ML Kit
        // work per frame. For a still image, atMs is 0 — one entry, fine.
        val match: (Long, Bitmap) -> Boolean = { atMs, bmp ->
            qualifies(uriStr, atMs, bmp, intent, labeler, faceDetector, objectVision, useFallback)
        }
        try {
            if (kind == MediaKind.IMAGE) {
                val bmp = decodeImage(context, mediaUri)
                    ?: throw IllegalStateException("Could not read image for on-device vision.")
                val matched = match(0L, bmp)
                bmp.recycle()
                val action = if (matched == intent.keepMatches) EditAction.KEEP else EditAction.REMOVE
                listOf(EditSegment(0, durationMs, action, if (matched) "match" else "no match"))
            } else {
                scanVideo(context, mediaUri, durationMs, intent.keepMatches, onProgress, checkpoint, match)
            }
        } finally {
            labeler.close()
            faceDetector?.close()
            objectVision?.close()
        }
    }

    /**
     * Like [analyze], but uses a [reference] frame the user scrubbed to as the visual target: detect the
     * prompt's object in [reference], embed its crop, then keep/remove frames whose same-class detections
     * match that embedding — so "this is my phone, cut every frame with my phone" tracks *that* phone, not
     * any phone. Falls back to generic class matching if the reference object or the embedder is missing.
     */
    suspend fun analyzeWithReference(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
        reference: Bitmap,
        onProgress: (AnalysisProgress) -> Unit = {},
        checkpoint: () -> Unit = {},
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        require(kind != MediaKind.AUDIO) { "Reference matching needs a video or image clip." }
        val parsed = parseIntent(prompt)
        val terms = expandTerms(parsed.terms)
        val objectVision = ObjectVision(context)
        val embed = ImageEmbed(context)
        try {
            fun matchesTerm(label: String) = terms.any { it.contains(label) || label.contains(it) }
            val refBox = objectVision.detect(reference)
                .filter { matchesTerm(it.label) }
                .maxByOrNull { it.score }
            val refEmbedding = if (refBox != null && embed.available) {
                crop(reference, refBox.box)?.let { c ->
                    try { embed.embed(c) } finally { if (c !== reference) c.recycle() }
                }
            } else null

            // Reference mode embeds each candidate detection against the ref, so the *similarity*
            // result depends on the runtime reference and isn't a property of the frame alone — not
            // cached. But the underlying `objectVision.detect(bmp)` IS a property of the frame, so
            // it's routed through FrameAnalysisCache: a rescan against a different reference reuses
            // every prior detect() call, only the embedding+similarity re-runs. The generic-class
            // fallback below uses the same object-labels cache as the standard analyze() path.
            val uriStr = mediaUri.toString()
            val match: (Long, Bitmap) -> Boolean = if (refEmbedding != null) {
                { atMs, bmp ->
                    FrameAnalysisCache.detections(uriStr, atMs) { objectVision.detect(bmp) }
                        .filter { matchesTerm(it.label) }
                        .any { d ->
                            val c = crop(bmp, d.box) ?: return@any false
                            try {
                                val e = embed.embed(c) ?: return@any false
                                embed.similarity(refEmbedding, e) >= REF_THRESHOLD
                            } finally {
                                if (c !== bmp) c.recycle() // free each crop — a long scan makes hundreds
                            }
                        }
                }
            } else {
                // No usable reference embedding — fall back to generic class detection, cached.
                { atMs, bmp ->
                    objectVision.available && FrameAnalysisCache
                        .objectLabels(uriStr, atMs) { objectVision.labels(bmp) }
                        .any { matchesTerm(it) }
                }
            }

            if (kind == MediaKind.IMAGE) {
                val matched = match(0L, reference)
                val action = if (matched == parsed.keepMatches) EditAction.KEEP else EditAction.REMOVE
                listOf(EditSegment(0, durationMs, action, if (matched) "match" else "no match"))
            } else {
                scanVideo(context, mediaUri, durationMs, parsed.keepMatches, onProgress, checkpoint, match)
            }
        } finally {
            objectVision.close()
            embed.close()
        }
    }

    /** Crop [box] (pixel rect) out of [bmp]; null if the rect is degenerate. */
    private fun crop(bmp: Bitmap, box: android.graphics.RectF): Bitmap? = runCatching {
        val l = box.left.toInt().coerceIn(0, bmp.width - 1)
        val t = box.top.toInt().coerceIn(0, bmp.height - 1)
        val r = box.right.toInt().coerceIn(l + 1, bmp.width)
        val b = box.bottom.toInt().coerceIn(t + 1, bmp.height)
        Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }.getOrNull()

    /** Sample frames at a fixed [SAMPLE_FPS] fps; each match claims ±[EXTEND_FRAMES] frames around it. */
    private fun scanVideo(
        context: Context,
        uri: Uri,
        durationMs: Long,
        keepMatches: Boolean,
        onProgress: (AnalysisProgress) -> Unit,
        checkpoint: () -> Unit,
        match: (atMs: Long, bmp: Bitmap) -> Boolean,
    ): List<EditSegment> {
        val retriever = MediaMetadataRetriever()
        val matched = mutableListOf<LongRange>()
        try {
            retriever.setDataSource(context, uri)
            val dur = if (durationMs > 0) durationMs
            else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0L) return emptyList()

            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.takeIf { it > 1f } ?: 30f
            val frameMs = 1000f / fps

            // Sample at a fixed 3 fps. Widen the step for very long clips so we never exceed
            // MAX_CHECKS frame grabs.
            var stepMs = (1000L / SAMPLE_FPS).coerceAtLeast(1L)
            if (dur / stepMs > MAX_CHECKS) stepMs = (dur / MAX_CHECKS).coerceAtLeast(1L)
            // Each match claims ±EXTEND_FRAMES source frames around the sampled time, but at least
            // half a step so consecutive matches always merge into one contiguous segment (at high fps
            // ±5 frames is shorter than the 3 fps step, which would otherwise leave gaps).
            val halfMs = max((EXTEND_FRAMES * frameMs).toLong(), stepMs / 2 + 1)

            val totalChecks = (dur / stepMs).coerceAtMost(MAX_CHECKS.toLong())
            // Report progress in ACTUAL source-frame numbers (fps \u00d7 time), not the sampled-check
            // count \u2014 the user sees "Frame 45 of 900" for a 30-second 30fps clip instead of the
            // meaningless-to-them "Scanning frame 3 of 90" (which counted 3-fps samples). The sheet
            // already shows the percentage on its progress bar, so the text carries only the frame
            // numbers (no redundant %).
            val totalFrames = (dur * fps / 1000f).toLong().coerceAtLeast(1L)
            var t = 0L
            var checks = 0
            var matchCount = 0
            while (t <= dur && checks < MAX_CHECKS) {
                checkpoint() // pause/cancel hook (blocks while paused, throws on cancel)
                val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    if (match(t, bmp)) {
                        matched += (t - halfMs).coerceAtLeast(0L)..(t + halfMs).coerceAtMost(dur)
                        matchCount++
                    }
                    bmp.recycle()
                }
                checks++
                val curFrame = (t * fps / 1000f).toLong().coerceAtMost(totalFrames)
                onProgress(AnalysisProgress(
                    "Frame $curFrame of $totalFrames",
                    (checks.toFloat() / totalChecks.coerceAtLeast(1)).coerceIn(0f, 1f),
                    matchCount,
                ))
                t += stepMs
            }
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            // A pause/cancel via checkpoint() must propagate — otherwise cancelling a scan silently
            // stops early and returns a partial cover as if it had finished normally.
            throw c
        } catch (_: Exception) {
            // best effort — build a cover from whatever we collected
        } finally {
            runCatching { retriever.release() }
        }
        return buildCover(mergeRanges(matched), durationMs, keepMatches)
    }

    private fun qualifies(
        uri: String,
        atMs: Long,
        bmp: Bitmap,
        intent: Intent,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        faceDetector: com.google.mlkit.vision.face.FaceDetector?,
        objectVision: ObjectVision?,
        useFallback: Boolean,
    ): Boolean {
        // Face detection isn't cached — it's fast and only runs when the prompt asks for faces.
        if (intent.useFaces && faceDetector != null) {
            val image = InputImage.fromBitmap(bmp, 0)
            return Tasks.await(faceDetector.process(image)).isNotEmpty()
        }
        // Primary signal: precise COCO object detection. Cached per (uri, atMs) so a rescan of the
        // same clip with a different prompt reuses last time's labels — the ML Kit call is skipped
        // entirely on a hit and only the string-membership check re-runs (essentially free).
        if (objectVision != null && objectVision.available) {
            val ovLabels = FrameAnalysisCache.objectLabels(uri, atMs) { objectVision.labels(bmp) }
            if (ovLabels.any { o -> intent.terms.any { t -> o.contains(t) || t.contains(o) } }) {
                return true
            }
        }
        if (!useFallback) return false
        // Fallback: whole-image scene labeling. Also cached, and snapshotted as (text, confidence)
        // pairs so the cache doesn't retain refs to the labeler (which is closed at end-of-scan).
        val scene = FrameAnalysisCache.sceneLabels(uri, atMs) {
            val image = InputImage.fromBitmap(bmp, 0)
            Tasks.await(labeler.process(image)).map {
                FrameAnalysisCache.SceneLabel(it.text, it.text.lowercase(), it.confidence)
            }
        }
        return scene.any { label ->
            label.confidence >= 0.5f && intent.terms.any { t ->
                label.lowerText.contains(t) || t.contains(label.lowerText)
            }
        }
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

    private fun parseIntent(prompt: String): Intent {
        val p = prompt.lowercase()
        val removeWords = listOf("cut", "remove", "delete", "without", "drop", "trim", "no ")
        val keepMatches = removeWords.none { p.contains(it) }
        val faceWords = listOf("face", "person", "people", "someone", "somebody", "selfie", "portrait", "human", "talking head")
        val useFaces = faceWords.any { p.contains(it) }
        val stop = setOf(
            "cut", "keep", "only", "remove", "delete", "the", "and", "with", "without", "that", "has",
            "have", "are", "where", "when", "there", "show", "shows", "clip", "clips", "part", "parts",
            "footage", "scene", "scenes", "frame", "frames", "out", "any", "all", "for", "into", "drop", "trim",
        )
        val terms = Regex("[a-z]+").findAll(p).map { it.value }
            .filter { it.length > 2 && it !in stop && it !in faceWords }
            .distinct().toList()
        return Intent(terms, keepMatches, useFaces)
    }

    /**
     * Expand parsed terms for matching: add the singular of plurals ("phones" -> "phone",
     * "cars" -> "car") and map everyday words to COCO category names ("phone" -> "cell phone").
     */
    private fun expandTerms(terms: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in terms) {
            out += t
            ALIASES[t]?.let { out += it }
            val singular = singularize(t)
            if (singular != t) {
                out += singular
                ALIASES[singular]?.let { out += it }
            }
        }
        return out.toList()
    }

    private fun singularize(w: String): String = when {
        w.length > 4 && w.endsWith("ses") -> w.dropLast(2)              // buses -> bus, glasses -> glass
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"       // berries -> berry
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1) // phones -> phone, cars -> car
        else -> w
    }

    private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf(sorted.first())
        for (r in sorted.drop(1)) {
            val last = out.last()
            if (r.first <= last.last) out[out.lastIndex] = last.first..max(last.last, r.last)
            else out += r
        }
        return out
    }

    /** Tile [0, dur]: matched regions get the match action, gaps the opposite. */
    private fun buildCover(matched: List<LongRange>, dur: Long, keepMatches: Boolean): List<EditSegment> {
        val matchAction = if (keepMatches) EditAction.KEEP else EditAction.REMOVE
        val other = if (keepMatches) EditAction.REMOVE else EditAction.KEEP
        val out = mutableListOf<EditSegment>()
        var cursor = 0L
        for (r in matched) {
            val s = r.first.coerceIn(0, dur)
            val e = r.last.coerceIn(0, dur)
            if (e <= s) continue
            if (s > cursor) out += EditSegment(cursor, s, other, "no match")
            out += EditSegment(s, e, matchAction, "match")
            cursor = e
        }
        if (cursor < dur) out += EditSegment(cursor, dur, other, "no match")
        return out
    }

    private companion object {
        // Sample the video at a fixed 3 frames/second instead of scanning every frame.
        const val SAMPLE_FPS = 3
        // On a match, extend applicability ±5 frames around the sampled time (in source frames).
        const val EXTEND_FRAMES = 5
        const val MAX_CHECKS = 600
        // Cosine-similarity cutoff for "same object as the reference" (tune on device).
        const val REF_THRESHOLD = 0.75

        /** Everyday word -> COCO category name. Substring matching covers the rest (e.g. "car"). */
        val ALIASES = mapOf(
            "phone" to "cell phone", "cellphone" to "cell phone", "smartphone" to "cell phone",
            "mobile" to "cell phone", "iphone" to "cell phone", "android" to "cell phone",
            "television" to "tv", "telly" to "tv",
            "computer" to "laptop", "sofa" to "couch", "remote" to "remote",
        )
    }
}
