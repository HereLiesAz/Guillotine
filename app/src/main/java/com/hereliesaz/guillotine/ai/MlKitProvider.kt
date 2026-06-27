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
 * To keep it fast it does **not** inspect every frame: it checks one frame, claims a block
 * of ±[BLOCK_FRAMES] frames around it, then jumps to the next checkpoint (≈ the 11th frame)
 * and checks again — extending the matched region while consecutive checkpoints qualify.
 * This samples ~1/11 of frames while still producing contiguous cut regions.
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
        // Scan denser + claim a tighter window when real object detection is driving the match, so brief
        // or partial appearances aren't skipped and cut boundaries hug the actual range.
        val blockFrames = if (objectVision?.available == true) OBJECT_BLOCK_FRAMES else BLOCK_FRAMES
        // When every term is a COCO class the detector owns, the whole-image labeler adds no recall
        // (too-small objects aren't top labels either) and only burns time per frame — skip it.
        val useFallback = objectVision?.available != true || intent.terms.any { !ObjectVision.coversTerm(it) }

        val match: (Bitmap) -> Boolean = { bmp -> qualifies(bmp, intent, labeler, faceDetector, objectVision, useFallback) }
        try {
            if (kind == MediaKind.IMAGE) {
                val bmp = decodeImage(context, mediaUri)
                    ?: throw IllegalStateException("Could not read image for on-device vision.")
                val matched = match(bmp)
                bmp.recycle()
                val action = if (matched == intent.keepMatches) EditAction.KEEP else EditAction.REMOVE
                listOf(EditSegment(0, durationMs, action, if (matched) "match" else "no match"))
            } else {
                scanVideo(context, mediaUri, durationMs, blockFrames, intent.keepMatches, onProgress, match)
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
            val refEmbedding = refBox
                ?.let { crop(reference, it.box) }
                ?.takeIf { embed.available }
                ?.let { embed.embed(it) }

            val blockFrames = if (objectVision.available) OBJECT_BLOCK_FRAMES else BLOCK_FRAMES
            val match: (Bitmap) -> Boolean = if (refEmbedding != null) {
                { bmp ->
                    objectVision.detect(bmp).filter { matchesTerm(it.label) }.any { d ->
                        val c = crop(bmp, d.box)
                        c != null && embed.embed(c)?.let { embed.similarity(refEmbedding, it) >= REF_THRESHOLD } == true
                    }
                }
            } else {
                // No usable reference embedding — fall back to generic class detection.
                { bmp -> objectVision.available && objectVision.labels(bmp).any { matchesTerm(it) } }
            }

            if (kind == MediaKind.IMAGE) {
                val matched = match(reference)
                val action = if (matched == parsed.keepMatches) EditAction.KEEP else EditAction.REMOVE
                listOf(EditSegment(0, durationMs, action, if (matched) "match" else "no match"))
            } else {
                scanVideo(context, mediaUri, durationMs, blockFrames, parsed.keepMatches, onProgress, match)
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

    /** Stride-and-block scan: check every ~(block+1)th frame, claiming ±block frames per match. */
    private fun scanVideo(
        context: Context,
        uri: Uri,
        durationMs: Long,
        blockFrames: Int,
        keepMatches: Boolean,
        onProgress: (AnalysisProgress) -> Unit,
        match: (Bitmap) -> Boolean,
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

            // Check 1 frame, skip BLOCK, check the (BLOCK+1)th. Widen the step for very long
            // clips so we never exceed MAX_CHECKS frame grabs.
            var stepMs = ((blockFrames + 1) * frameMs).toLong().coerceAtLeast(1L)
            if (dur / stepMs > MAX_CHECKS) stepMs = (dur / MAX_CHECKS).coerceAtLeast(1L)
            // Each match claims ±block frames, but at least half a step so consecutive matches stay contiguous.
            val halfMs = max((blockFrames * frameMs).toLong(), stepMs / 2 + 1)

            val totalChecks = (dur / stepMs).coerceAtMost(MAX_CHECKS.toLong())
            var t = 0L
            var checks = 0
            var matchCount = 0
            while (t <= dur && checks < MAX_CHECKS) {
                val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    if (match(bmp)) {
                        matched += (t - halfMs).coerceAtLeast(0L)..(t + halfMs).coerceAtMost(dur)
                        matchCount++
                    }
                    bmp.recycle()
                }
                checks++
                onProgress(AnalysisProgress(
                    "Scanning frame $checks/$totalChecks\u2026",
                    (checks.toFloat() / totalChecks.coerceAtLeast(1)).coerceIn(0f, 1f),
                    matchCount,
                ))
                t += stepMs
            }
        } catch (_: Exception) {
            // best effort — build a cover from whatever we collected
        } finally {
            runCatching { retriever.release() }
        }
        return buildCover(mergeRanges(matched), durationMs, keepMatches)
    }

    private fun qualifies(
        bmp: Bitmap,
        intent: Intent,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        faceDetector: com.google.mlkit.vision.face.FaceDetector?,
        objectVision: ObjectVision?,
        useFallback: Boolean,
    ): Boolean {
        if (intent.useFaces && faceDetector != null) {
            val image = InputImage.fromBitmap(bmp, 0)
            return Tasks.await(faceDetector.process(image)).isNotEmpty()
        }
        // Primary signal: precise COCO object detection (catches non-prominent / partial objects).
        if (objectVision != null && objectVision.available) {
            if (objectVision.labels(bmp).any { o -> intent.terms.any { t -> o.contains(t) || t.contains(o) } }) {
                return true
            }
        }
        if (!useFallback) return false
        // Fallback: whole-image scene/object labeling for terms the COCO detector doesn't cover.
        val image = InputImage.fromBitmap(bmp, 0)
        val labels = Tasks.await(labeler.process(image))
        return labels.any { l ->
            l.confidence >= 0.5f && intent.terms.any { t ->
                val text = l.text.lowercase()
                text.contains(t) || t.contains(text)
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
        const val BLOCK_FRAMES = 10
        // Denser stride when COCO object detection drives the match (it's fast and we want tight cuts).
        const val OBJECT_BLOCK_FRAMES = 3
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
