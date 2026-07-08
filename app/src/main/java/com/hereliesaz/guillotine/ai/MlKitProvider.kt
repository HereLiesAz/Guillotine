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

    /** One frame's on-device verdict: whether it matched, the labels vision saw, and the term that hit. */
    private data class Verdict(val matched: Boolean, val labels: List<String>, val term: String?)

    /** Short mm:ss.d timestamp for finding lines in the activity feed. */
    private fun tsFmt(ms: Long): String {
        val s = ms / 1000.0
        return if (s < 60) String.format(java.util.Locale.US, "%.1fs", s)
        else String.format(java.util.Locale.US, "%d:%02.0f", (ms / 60000L), (ms % 60000L) / 1000.0)
    }

    /** Build a feed line describing what a frame contained and its fate. */
    private fun findingLine(atMs: Long, v: Verdict, keep: Boolean): String {
        val labels = if (v.labels.isEmpty()) "nothing recognized" else v.labels.joinToString(", ")
        return "${tsFmt(atMs)} · $labels · ${if (keep) "keep" else "cut"}"
    }

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
        val match: (Long, Bitmap) -> Verdict = { atMs, bmp ->
            qualifies(uriStr, atMs, bmp, intent, labeler, faceDetector, objectVision, sceneClassifier, useMlKitFallback)
        }
        try {
            if (kind == MediaKind.IMAGE) {
                val bmp = decodeImage(context, mediaUri)
                    ?: throw IllegalStateException("Could not read image for on-device vision.")
                val v = match(0L, bmp)
                bmp.recycle()
                val keep = v.matched == intent.keepMatches
                onProgress(AnalysisProgress("Analyzed image", 1f, if (v.matched) 1 else 0, findingLine(0, v, keep), 0L))
                val action = if (keep) EditAction.KEEP else EditAction.REMOVE
                listOf(EditSegment(0, durationMs, action, v.term ?: "no match"))
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
        embedModelPath: String? = null,
        onProgress: (AnalysisProgress) -> Unit = {},
        checkpoint: () -> Unit = {},
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        require(kind != MediaKind.AUDIO) { "Reference matching needs a video or image clip." }
        val parsed = parseIntent(prompt)
        val terms = expandTerms(parsed.terms)
        val objectVision = ObjectVision(context)
        val embed = ImageEmbed(context, embedModelPath?.takeIf { it.isNotBlank() })
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
            val match: (Long, Bitmap) -> Verdict = if (refEmbedding != null) {
                { atMs, bmp ->
                    val dets = FrameAnalysisCache.detections(uriStr, atMs) { objectVision.detect(bmp) }
                        .filter { matchesTerm(it.label) }
                    val hit = dets.any { d ->
                        val c = crop(bmp, d.box) ?: return@any false
                        try {
                            val e = embed.embed(c) ?: return@any false
                            embed.similarity(refEmbedding, e) >= REF_THRESHOLD
                        } finally {
                            if (c !== bmp) c.recycle()
                        }
                    }
                    val labels = dets.map { it.label }.distinct().take(5)
                    Verdict(hit, labels, if (hit) labels.firstOrNull() else null)
                }
            } else {
                { atMs, bmp ->
                    val labs = FrameAnalysisCache.objectLabels(uriStr, atMs) { objectVision.labels(bmp) }
                    val term = if (objectVision.available) labs.firstOrNull { matchesTerm(it) } else null
                    Verdict(term != null, labs.take(5), term)
                }
            }

            if (kind == MediaKind.IMAGE) {
                val v = match(0L, reference)
                val keep = v.matched == parsed.keepMatches
                onProgress(AnalysisProgress("Analyzed image", 1f, if (v.matched) 1 else 0, findingLine(0, v, keep), 0L))
                val action = if (keep) EditAction.KEEP else EditAction.REMOVE
                listOf(EditSegment(0, durationMs, action, v.term ?: "no match"))
            } else {
                scanVideo(context, mediaUri, durationMs, parsed.keepMatches, onProgress, checkpoint, match)
            }
        } finally {
            objectVision.close()
            embed.close()
        }
    }

    /**
     * Capture an on-device fingerprint (image embedding) of the thing the user is pointing at in
     * [frame], to teach a [com.hereliesaz.guillotine.model.LearnedConcept]. If [term] names a kind of
     * object ("dog"), the matching detection is used; otherwise the largest detection; failing any
     * detection, the centre of the frame. Returns the L2-normalized vector, or null if the on-device
     * embedder isn't available.
     */
    fun captureReferenceEmbedding(
        context: Context,
        frame: Bitmap,
        term: String?,
        isFace: Boolean = false,
        embedModelPath: String? = null,
        faceModelPath: String? = null,
    ): FloatArray? {
        val embed = ImageEmbed(context, embedderModel(isFace, embedModelPath, faceModelPath))
        try {
            if (!embed.available) return null
            val cropBmp = pickCrop(context, frame, term, isFace)
            return cropBmp?.let { c ->
                try { embed.embed(c)?.floatEmbedding() } finally { if (c !== frame) c.recycle() }
            }
        } finally {
            embed.close()
        }
    }

    /**
     * Fingerprint the "not it" look-alikes in [frame] (same-kind objects, or faces for a person
     * concept), so recognition can reject near-duplicates. Returns up to a handful of vectors.
     */
    fun captureNegativeEmbeddings(
        context: Context,
        frame: Bitmap,
        term: String?,
        isFace: Boolean = false,
        embedModelPath: String? = null,
        faceModelPath: String? = null,
    ): List<FloatArray> {
        val embed = ImageEmbed(context, embedderModel(isFace, embedModelPath, faceModelPath))
        try {
            if (!embed.available) return emptyList()
            val crops = collectCrops(context, frame, term, isFace).ifEmpty { listOfNotNull(centerCrop(frame)) }
            val keep = crops.take(5)
            crops.drop(5).forEach { if (it !== frame) it.recycle() }
            return keep.mapNotNull { c ->
                try { embed.embed(c)?.floatEmbedding() } finally { if (c !== frame) c.recycle() }
            }
        } finally {
            embed.close()
        }
    }

    /** The single best crop to fingerprint as a positive: largest face, or the term/largest object. */
    private fun pickCrop(context: Context, frame: Bitmap, term: String?, isFace: Boolean): Bitmap? {
        if (isFace) {
            val faces = FaceEmbed.detectFaces(context, frame)
            faces.drop(1).forEach { it.recycle() } // keep only the largest
            return faces.firstOrNull() ?: centerCrop(frame)
        }
        val ov = ObjectVision(context)
        return try {
            val dets = runCatching { ov.detect(frame) }.getOrDefault(emptyList())
            val terms = term?.takeIf { it.isNotBlank() }?.let { expandTerms(parseIntent(it).terms) } ?: emptyList()
            val box = when {
                terms.isNotEmpty() -> dets
                    .filter { d -> terms.any { it.contains(d.label) || d.label.contains(it) } }
                    .maxByOrNull { it.score }?.box
                else -> dets.maxByOrNull { it.box.width() * it.box.height() }?.box
            }
            if (box != null) crop(frame, box) else centerCrop(frame)
        } finally {
            ov.close()
        }
    }

    /** All candidate crops in [frame] (faces, or same-kind objects) — for negatives. */
    private fun collectCrops(context: Context, frame: Bitmap, term: String?, isFace: Boolean): List<Bitmap> {
        if (isFace) return FaceEmbed.detectFaces(context, frame)
        val ov = ObjectVision(context)
        return try {
            val dets = runCatching { ov.detect(frame) }.getOrDefault(emptyList())
            val terms = term?.takeIf { it.isNotBlank() }?.let { expandTerms(parseIntent(it).terms) } ?: emptyList()
            val chosen = if (terms.isNotEmpty()) {
                dets.filter { d -> terms.any { it.contains(d.label) || d.label.contains(it) } }
            } else dets
            chosen.sortedByDescending { it.score }.take(5).mapNotNull { crop(frame, it.box) }
        } finally {
            ov.close()
        }
    }

    /** Which embedding model to use: the face model for person concepts (falling back to the general
     *  model), else the general model (null → the bundled default). */
    private fun embedderModel(isFace: Boolean, embedModelPath: String?, faceModelPath: String?): String? {
        val face = faceModelPath?.takeIf { it.isNotBlank() }
        val general = embedModelPath?.takeIf { it.isNotBlank() }
        return if (isFace) face ?: general else general
    }

    /**
     * Keep/remove a clip by a learned concept: a frame matches when any of its objects is close (cosine
     * similarity ≥ [REF_THRESHOLD]) to ANY of the concept's [examples]. [terms] (if any) narrow which
     * detections are compared. Reuses the same adaptive scanner as the other analyzers.
     */
    suspend fun analyzeWithConcept(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        durationMs: Long,
        examples: List<FloatArray>,
        negatives: List<FloatArray>,
        terms: List<String>,
        isFace: Boolean,
        keepMatches: Boolean,
        embedModelPath: String? = null,
        faceModelPath: String? = null,
        onProgress: (AnalysisProgress) -> Unit = {},
        checkpoint: () -> Unit = {},
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        require(kind != MediaKind.AUDIO) { "Learned-thing matching needs a video or image clip." }
        require(examples.isNotEmpty()) { "Point the thing out in a frame first (add_reference)." }
        val ov = if (isFace) null else ObjectVision(context)
        val embed = ImageEmbed(context, embedderModel(isFace, embedModelPath, faceModelPath))
        try {
            val uriStr = mediaUri.toString()
            val match: (Long, Bitmap) -> Verdict = { atMs, bmp ->
                // Candidate (crop, label) pairs: faces for a person concept, else same-kind objects.
                val crops: List<Pair<Bitmap, String>> = if (isFace) {
                    FaceEmbed.detectFaces(context, bmp).map { it to "face" }
                } else {
                    val dets = FrameAnalysisCache.detections(uriStr, atMs) { ov!!.detect(bmp) }
                    val relevant = if (terms.isNotEmpty()) {
                        dets.filter { d -> terms.any { it.contains(d.label) || d.label.contains(it) } }
                    } else dets
                    relevant.mapNotNull { d -> crop(bmp, d.box)?.let { it to d.label } }
                }
                try {
                    var hitLabel: String? = null
                    val hit = embed.available && crops.any { (c, label) ->
                        val v = embed.embed(c)?.floatEmbedding() ?: return@any false
                        val bestPos = examples.maxOf { cosine(it, v) }
                        // Nearest-prototype: match only when closer to a positive than to any negative,
                        // so a same-kind look-alike (near a negative) is rejected.
                        val bestNeg = if (negatives.isEmpty()) -1.0 else negatives.maxOf { cosine(it, v) }
                        (bestPos >= REF_THRESHOLD && bestPos >= bestNeg).also { if (it) hitLabel = label }
                    }
                    val labels = if (isFace) {
                        if (crops.isNotEmpty()) listOf("face") else emptyList()
                    } else crops.map { it.second }.distinct().take(5)
                    Verdict(hit, labels, hitLabel)
                } finally {
                    crops.forEach { (c, _) -> if (c !== bmp) c.recycle() }
                }
            }
            if (kind == MediaKind.IMAGE) {
                val bmp = decodeImage(context, mediaUri)
                    ?: throw IllegalStateException("Could not read image.")
                val v = match(0L, bmp)
                bmp.recycle()
                val keep = v.matched == keepMatches
                listOf(EditSegment(0, durationMs, if (keep) EditAction.KEEP else EditAction.REMOVE, v.term ?: "no match"))
            } else {
                scanVideo(context, mediaUri, durationMs, keepMatches, onProgress, checkpoint, match)
            }
        } finally {
            ov?.close()
            embed.close()
        }
    }

    /** Cosine similarity of two L2-normalized vectors = their dot product. 0 on size mismatch. */
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += (a[i] * b[i]).toDouble()
        return dot
    }

    /** Central 60% crop — fallback when no object was detected at the pointed frame. */
    private fun centerCrop(bmp: Bitmap): Bitmap? = runCatching {
        val w = (bmp.width * 0.6f).toInt().coerceAtLeast(1)
        val h = (bmp.height * 0.6f).toInt().coerceAtLeast(1)
        val x = ((bmp.width - w) / 2).coerceAtLeast(0)
        val y = ((bmp.height - h) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(bmp, x, y, w, h)
    }.getOrNull()

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
        match: (atMs: Long, bmp: Bitmap) -> Verdict,
    ): List<EditSegment> {
        val retriever = MediaMetadataRetriever()
        val matched = mutableListOf<LongRange>()
        val matchedTerms = LinkedHashSet<String>()
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
            var firstEmitted = false

            fun probe(atMs: Long): Verdict? {
                if (checks >= MAX_CHECKS) return null
                checkpoint()
                val bmp = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                val v = match(atMs, bmp)
                bmp.recycle()
                checks++
                if (v.matched) v.term?.let { matchedTerms.add(it) }
                return v
            }

            fun reportProgress(atMs: Long, finding: String?) {
                val curFrame = (atMs * fps / 1000f).toLong().coerceAtMost(totalFrames)
                val fraction = (atMs.toFloat() / dur).coerceIn(0f, 1f)
                onProgress(AnalysisProgress("Frame $curFrame of $totalFrames", fraction, matchCount, finding, atMs))
            }

            fun binaryRefine(matchSide: Long, missSide: Long) {
                var lo = min(matchSide, missSide)
                var hi = max(matchSide, missSide)
                while (hi - lo > baseStep && checks < MAX_CHECKS) {
                    val mid = (lo + hi) / 2
                    checkpoint()
                    val midV = probe(mid) ?: break
                    if (midV.matched) {
                        matched += (mid - halfMs).coerceAtLeast(0L)..(mid + halfMs).coerceAtMost(dur)
                        matchCount++
                    }
                    if (midV.matched == (matchSide < missSide)) lo = mid else hi = mid
                }
            }

            while (t <= dur && checks < MAX_CHECKS) {
                val v = probe(t) ?: break
                val result = v.matched

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

                // Emit a feed finding at the first sample and at each region boundary (entering or
                // leaving a matching stretch) — enough to show what's in the clip and why a region is
                // kept or cut, without a line per frame (the frame counter stays live on the progress
                // indicator).
                val boundary = !firstEmitted || result != lastResult
                reportProgress(t, if (boundary) findingLine(t, v, result == keepMatches) else null)
                firstEmitted = true
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
        return buildCover(mergeRanges(matched), durationMs, keepMatches, matchedTerms.joinToString(", "))
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
    ): Verdict {
        fun matchIn(labels: Collection<String>): String? =
            labels.firstOrNull { o -> intent.terms.any { t -> o.contains(t) || t.contains(o) } }

        if (intent.useFaces && faceDetector != null) {
            val image = InputImage.fromBitmap(bmp, 0)
            val present = Tasks.await(faceDetector.process(image)).isNotEmpty()
            return Verdict(present, if (present) listOf("face") else emptyList(), if (present) "face" else null)
        }
        val seen = LinkedHashSet<String>()
        // Tier 1: precise COCO bounding-box detection (EfficientDet-Lite2).
        if (objectVision != null && objectVision.available) {
            val ovLabels = FrameAnalysisCache.objectLabels(uri, atMs) { objectVision.labels(bmp) }
            seen += ovLabels
            matchIn(ovLabels)?.let { return Verdict(true, seen.take(5).toList(), it) }
        }
        // Tier 2: fine-grained ImageNet classification (~1000 categories).
        if (sceneClassifier != null && sceneClassifier.available) {
            val scene = FrameAnalysisCache.classifierLabels(uri, atMs) { sceneClassifier.classify(bmp) }
                .filter { it.confidence >= 0.2f }
            seen += scene.map { it.lowerText }
            matchIn(scene.map { it.lowerText })?.let { return Verdict(true, seen.take(5).toList(), it) }
        }
        if (!useMlKitFallback) return Verdict(false, seen.take(5).toList(), null)
        // Tier 3: ML Kit generic labels (tertiary fallback when both models unavailable).
        val mlLabels = FrameAnalysisCache.sceneLabels(uri, atMs) {
            val image = InputImage.fromBitmap(bmp, 0)
            Tasks.await(labeler.process(image)).map {
                FrameAnalysisCache.SceneLabel(it.text, it.text.lowercase(), it.confidence)
            }
        }.filter { it.confidence >= 0.5f }
        seen += mlLabels.map { it.lowerText }
        matchIn(mlLabels.map { it.lowerText })?.let { return Verdict(true, seen.take(5).toList(), it) }
        return Verdict(false, seen.take(5).toList(), null)
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
    private fun buildCover(
        matched: List<LongRange>,
        dur: Long,
        keepMatches: Boolean,
        matchLabel: String = "",
    ): List<EditSegment> {
        val matchAction = if (keepMatches) EditAction.KEEP else EditAction.REMOVE
        val other = if (keepMatches) EditAction.REMOVE else EditAction.KEEP
        // Reason strings explain each range in the activity feed: matched ranges name what was found;
        // the gaps say the search term was absent.
        val found = matchLabel.ifBlank { "match" }
        val absent = if (matchLabel.isBlank()) "no match" else "no $matchLabel"
        val out = mutableListOf<EditSegment>()
        var cursor = 0L
        for (r in matched) {
            val s = r.first.coerceIn(0, dur)
            val e = r.last.coerceIn(0, dur)
            if (e <= s) continue
            if (s > cursor) out += EditSegment(cursor, s, other, absent)
            out += EditSegment(s, e, matchAction, found)
            cursor = e
        }
        if (cursor < dur) out += EditSegment(cursor, dur, other, absent)
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
