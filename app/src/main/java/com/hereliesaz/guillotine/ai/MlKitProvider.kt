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
import kotlin.math.min

/**
 * Free, on-device vision analyzer — no key, no network. Matches frames against
 * the prompt (face detection when it's about people, else object/scene labeling) and turns
 * matches into keep/remove ranges per the prompt's intent ("keep only…" vs "cut/remove…").
 *
 * Detection pipeline (most precise first):
 *  1. **ObjectVision** (EfficientDet-Lite2) — 80 COCO classes with bounding boxes
 *  2. **SceneClassifier** (EfficientNet-Lite0 on ImageNet) — ~1000 fine-grained categories
 *  3. **ML Kit ImageLabeler** — ~400 generic labels (tertiary fallback)
 *
 * Uses adaptive sampling: starts at [BASE_SAMPLE_FPS], accelerates through stable regions
 * (consecutive matches or misses), and does binary-search refinement at transition boundaries
 * for frame-accurate cut points.
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
        val intent = parsed.copy(terms = expandTerms(parsed.terms))

        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val faceDetector = if (intent.useFaces) {
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build(),
            )
        } else null
        val objectVision = if (intent.useFaces) null else ObjectVision(context)
        val sceneClassifier = if (intent.useFaces) null else SceneClassifier(context)
        val useMlKitFallback = objectVision?.available != true &&
            sceneClassifier?.available != true

        val uriStr = mediaUri.toString()
        val match: (Long, Bitmap) -> Boolean = { atMs, bmp ->
            qualifies(uriStr, atMs, bmp, intent, labeler, faceDetector, objectVision, sceneClassifier, useMlKitFallback)
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
            sceneClassifier?.close()
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
                                if (c !== bmp) c.recycle()
                            }
                        }
                }
            } else {
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
    private fun crop(bmp: Bitmap, box: BoundingBox): Bitmap? = runCatching {
        val l = box.left.toInt().coerceIn(0, bmp.width - 1)
        val t = box.top.toInt().coerceIn(0, bmp.height - 1)
        val r = box.right.toInt().coerceIn(l + 1, bmp.width)
        val b = box.bottom.toInt().coerceIn(t + 1, bmp.height)
        Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }.getOrNull()

    /**
     * Adaptive-rate video scanner. Starts at [BASE_SAMPLE_FPS] and accelerates through stable
     * regions (consecutive identical results). On a match→miss or miss→match transition it does
     * a binary search between the two sample points to find the boundary within ±1 base step,
     * giving frame-accurate cut points without scanning every frame.
     */
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

            val baseStep = (1000L / BASE_SAMPLE_FPS).coerceAtLeast(1L)
            val halfMs = max((EXTEND_FRAMES * frameMs).toLong(), baseStep / 2 + 1)
            val totalFrames = (dur * fps / 1000f).toLong().coerceAtLeast(1L)

            var t = 0L
            var checks = 0
            var matchCount = 0
            var streak = 0         // consecutive same-result samples
            var lastResult = false // result of the previous sample

            fun probe(atMs: Long): Boolean? {
                if (checks >= MAX_CHECKS) return null
                checkpoint()
                val bmp = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                val result = match(atMs, bmp)
                bmp.recycle()
                checks++
                return result
            }

            fun reportProgress(atMs: Long) {
                val curFrame = (atMs * fps / 1000f).toLong().coerceAtMost(totalFrames)
                val fraction = (atMs.toFloat() / dur).coerceIn(0f, 1f)
                onProgress(AnalysisProgress("Frame $curFrame of $totalFrames", fraction, matchCount))
            }

            fun binaryRefine(matchSide: Long, missSide: Long) {
                var lo = min(matchSide, missSide)
                var hi = max(matchSide, missSide)
                while (hi - lo > baseStep && checks < MAX_CHECKS) {
                    val mid = (lo + hi) / 2
                    checkpoint()
                    val midResult = probe(mid) ?: break
                    if (midResult) {
                        matched += (mid - halfMs).coerceAtLeast(0L)..(mid + halfMs).coerceAtMost(dur)
                        matchCount++
                    }
                    if (midResult == (matchSide < missSide)) lo = mid else hi = mid
                }
            }

            while (t <= dur && checks < MAX_CHECKS) {
                val result = probe(t) ?: break

                if (result) {
                    matched += (t - halfMs).coerceAtLeast(0L)..(t + halfMs).coerceAtMost(dur)
                    matchCount++
                }

                if (checks > 1 && result != lastResult) {
                    binaryRefine(
                        if (result) t else t - baseStep.coerceAtMost(t),
                        if (result) t - baseStep.coerceAtMost(t) else t,
                    )
                    streak = 0
                } else {
                    streak++
                }

                reportProgress(t)
                lastResult = result

                // Accelerate through stable regions: after 3+ identical consecutive results,
                // widen the step up to 4x base. Reset to base step on any transition.
                val adaptiveStep = when {
                    streak >= 6 -> baseStep * 4
                    streak >= 3 -> baseStep * 2
                    else -> baseStep
                }
                t += adaptiveStep
            }
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (_: Exception) {
            // best effort
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
        sceneClassifier: SceneClassifier?,
        useMlKitFallback: Boolean,
    ): Boolean {
        if (intent.useFaces && faceDetector != null) {
            val image = InputImage.fromBitmap(bmp, 0)
            return Tasks.await(faceDetector.process(image)).isNotEmpty()
        }
        // Tier 1: precise COCO bounding-box detection (EfficientDet-Lite2).
        if (objectVision != null && objectVision.available) {
            val ovLabels = FrameAnalysisCache.objectLabels(uri, atMs) { objectVision.labels(bmp) }
            if (ovLabels.any { o -> intent.terms.any { t -> o.contains(t) || t.contains(o) } }) {
                return true
            }
        }
        // Tier 2: fine-grained ImageNet classification (~1000 categories).
        if (sceneClassifier != null && sceneClassifier.available) {
            val scene = FrameAnalysisCache.classifierLabels(uri, atMs) { sceneClassifier.classify(bmp) }
            if (scene.any { label ->
                    label.confidence >= 0.2f && intent.terms.any { t ->
                        label.lowerText.contains(t) || t.contains(label.lowerText)
                    }
                }) {
                return true
            }
        }
        if (!useMlKitFallback) return false
        // Tier 3: ML Kit generic labels (tertiary fallback when both models unavailable).
        val mlLabels = FrameAnalysisCache.sceneLabels(uri, atMs) {
            val image = InputImage.fromBitmap(bmp, 0)
            Tasks.await(labeler.process(image)).map {
                FrameAnalysisCache.SceneLabel(it.text, it.text.lowercase(), it.confidence)
            }
        }
        return mlLabels.any { label ->
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
        const val BASE_SAMPLE_FPS = 3
        const val EXTEND_FRAMES = 5
        const val MAX_CHECKS = 600
        const val REF_THRESHOLD = 0.75

        val ALIASES = mapOf(
            "phone" to "cell phone", "cellphone" to "cell phone", "smartphone" to "cell phone",
            "mobile" to "cell phone", "iphone" to "cell phone", "android" to "cell phone",
            "television" to "tv", "telly" to "tv",
            "computer" to "laptop", "sofa" to "couch", "remote" to "remote",
        )
    }
}
