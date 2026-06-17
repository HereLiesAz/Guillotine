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
        val intent = parseIntent(prompt)
        if (!intent.useFaces && intent.terms.isEmpty()) {
            throw IllegalStateException("Tell on-device vision what to look for, e.g. \"keep shots with a face\" or \"cut clips with a car\".")
        }

        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val faceDetector = if (intent.useFaces) {
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build(),
            )
        } else null

        try {
            if (kind == MediaKind.IMAGE) {
                val bmp = decodeImage(context, mediaUri)
                    ?: throw IllegalStateException("Could not read image for on-device vision.")
                val match = qualifies(bmp, intent, labeler, faceDetector)
                bmp.recycle()
                val action = if (match == intent.keepMatches) EditAction.KEEP else EditAction.REMOVE
                listOf(EditSegment(0, durationMs, action, if (match) "match" else "no match"))
            } else {
                scanVideo(context, mediaUri, durationMs, intent, labeler, faceDetector, onProgress)
            }
        } finally {
            labeler.close()
            faceDetector?.close()
        }
    }

    /** Stride-and-block scan: check every ~(BLOCK+1)th frame, claiming ±BLOCK frames per match. */
    private fun scanVideo(
        context: Context,
        uri: Uri,
        durationMs: Long,
        intent: Intent,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        faceDetector: com.google.mlkit.vision.face.FaceDetector?,
        onProgress: (AnalysisProgress) -> Unit,
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
            var stepMs = ((BLOCK_FRAMES + 1) * frameMs).toLong().coerceAtLeast(1L)
            if (dur / stepMs > MAX_CHECKS) stepMs = (dur / MAX_CHECKS).coerceAtLeast(1L)
            // Each match claims ±BLOCK frames, but at least half a step so consecutive matches stay contiguous.
            val halfMs = max((BLOCK_FRAMES * frameMs).toLong(), stepMs / 2 + 1)

            val totalChecks = (dur / stepMs).coerceAtMost(MAX_CHECKS.toLong())
            var t = 0L
            var checks = 0
            var matchCount = 0
            while (t <= dur && checks < MAX_CHECKS) {
                val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    if (qualifies(bmp, intent, labeler, faceDetector)) {
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
        return buildCover(mergeRanges(matched), durationMs, intent.keepMatches)
    }

    private fun qualifies(
        bmp: Bitmap,
        intent: Intent,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        faceDetector: com.google.mlkit.vision.face.FaceDetector?,
    ): Boolean {
        val image = InputImage.fromBitmap(bmp, 0)
        return if (intent.useFaces && faceDetector != null) {
            Tasks.await(faceDetector.process(image)).isNotEmpty()
        } else {
            val labels = Tasks.await(labeler.process(image))
            labels.any { l ->
                l.confidence >= 0.5f && intent.terms.any { t ->
                    val text = l.text.lowercase()
                    text.contains(t) || t.contains(text)
                }
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
        const val MAX_CHECKS = 600
    }
}
