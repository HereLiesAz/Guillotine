@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.hereliesaz.guillotine.media.MediaPreview
import com.hereliesaz.guillotine.media.SubjectSegmenter
import com.hereliesaz.guillotine.media.VideoEffects
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.Quality
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
import com.hereliesaz.guillotine.ui.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real on-device export with Media3 [Transformer]. Concatenates the *kept* ranges
 * of every video clip (so AI 'remove' segments are physically cut), applies the
 * shared [VideoEffects], encodes mp4, and saves it to the gallery (Movies/Guillotine).
 *
 * Applies per-clip color filters (incl. sepia + Gaussian blur), the Crop-tool transform, keyframed
 * opacity/scale/volume, audio volume/pan/peak-normalization, track opacity, and the
 * background-removal matte + caption overlays (kept in sync across 'remove' cuts). Mattes are
 * pre-segmented off-thread. Image clips and a separate audio sequence are supported.
 */
object Exporter {

    /**
     * @param region When non-null, "Render Loop Region Only" (Vegas J.4): the export is pre-clamped to
     * this `[start, end)` timeline window ([Document.clampedToRegion]) before anything else runs, so the
     * rest of this pipeline is unchanged — it just sees a shorter document.
     */
    suspend fun export(
        context: Context,
        document: Document,
        outputName: String,
        onProgress: (Float, Long) -> Unit,
        onPhase: (String) -> Unit = {},
        region: LongRange? = null,
    ): Uri = withContext(Dispatchers.Main) {
        val document = if (region != null) document.clampedToRegion(region.first, region.last) else document
        fun phase(name: String) {
            onPhase(name)
            ActivityLog.info(name)
        }

        // Peak-normalization gains for clips with "Normalize audio" — computed off the main thread
        // (reuses the cached waveform decoder) before the Transformer is built on Main.
        val normalizeCount = document.clips.count { it.filters.normalize }
        if (normalizeCount > 0) phase("Analyzing audio levels for $normalizeCount clip(s)…")
        val normalizeGains = withContext(Dispatchers.IO) {
            // Deliberately NOT runCatching — that swallows CancellationException and would
            // silently let the export continue after the user hit Cancel. Only catch non-CE errors.
            try {
                computeNormalizeGains(context, document)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                ActivityLog.error("Normalize scan failed (continuing without): ${describeCauseChain(e)}")
                emptyMap()
            }
        }

        // Background-removal mattes are segmented off the main thread up front (not per render frame).
        val matteCandidates = document.clips.count { it.type == ClipType.VIDEO && it.filters.removeBackground }
        if (matteCandidates > 0) phase("Precomputing subject mattes for $matteCandidates clip(s)…")
        val mattes = withContext(Dispatchers.IO) {
            try {
                precomputeMattes(context, document)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                ActivityLog.error("Matte precompute failed (continuing without): ${describeCauseChain(e)}")
                emptyMap()
            }
        }

        // Face-blur patches are detected off the main thread up front too (not per render frame).
        val faceBlurCandidates = document.clips.count { it.type == ClipType.VIDEO && it.filters.blurFaces }
        if (faceBlurCandidates > 0) phase("Precomputing face blur for $faceBlurCandidates clip(s)…")
        val faceBlur = withContext(Dispatchers.IO) {
            try {
                precomputeFaceBlur(context, document)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                ActivityLog.error("Face-blur precompute failed (continuing without): ${describeCauseChain(e)}")
                emptyMap()
            }
        }

        try {
            phase("Building export composition…")
            val composition = buildComposition(document, normalizeGains, mattes, faceBlur)
            require(composition != null) { "Nothing to export — add a video clip first." }

            // Detect whether the composition actually carries audio. Calling setAudioMimeType(AAC)
            // on an audio-less composition trips Media3 into an IllegalStateException from inside
            // the muxer because there's no track to enforce that MIME onto. Only force AAC when
            // there IS audio to encode.
            val disabled = document.disabledTrackIds
            val hasAudio = document.clips.any { c ->
                if (c.trackId in disabled) return@any false
                when (c.type) {
                    ClipType.AUDIO -> true
                    ClipType.VIDEO -> document.mediaFor(c)?.hasAudio == true
                    else -> false
                }
            }

            val outFile = File(context.cacheDir, "guillotine_export_${System.currentTimeMillis()}.mp4")

            phase("Encoding video and audio…")
            coroutineScope {
                var poller: Job? = null
                try {
                    suspendCancellableCoroutine { cont ->
                        val builder = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                        // Media3's MP4 muxer rejects Opus/Vorbis/FLAC source audio; force AAC
                        // re-encode so mixed-codec projects don't die with a bare "Export failed".
                        // Skip the call entirely when the composition has no audio — the muxer
                        // otherwise raises IllegalStateException with no message.
                        if (hasAudio) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                        val transformer = builder
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(c: Composition, result: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onError(c: Composition, result: ExportResult, e: ExportException) {
                                    if (cont.isActive) cont.resumeWithException(e)
                                }
                            })
                            .build()

                        poller = launch {
                            val holder = ProgressHolder()
                            var lastMilestone = -1
                            while (isActive) {
                                transformer.getProgress(holder)
                                val p = (holder.progress / 100f).coerceIn(0f, 1f)
                                onProgress(p, (p * document.totalDurationMs).toLong())
                                // Emit at 25/50/75/100 so the log tells a story without spamming it.
                                val ms = (p * 4).toInt()
                                if (ms > lastMilestone && ms in 1..4) {
                                    lastMilestone = ms
                                    ActivityLog.progress("Encoding ${ms * 25}%…")
                                }
                                delay(200)
                            }
                        }

                        cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                        transformer.start(composition, outFile.absolutePath)
                    }
                } finally {
                    poller?.cancel()
                }
            }

            onProgress(1f, document.totalDurationMs)
            phase("Saving to gallery…")
            // The encode runs on Main (Transformer requires it), but copying the finished MP4 into
            // the gallery is blocking file I/O — do it off the main thread so a large export can't ANR.
            withContext(Dispatchers.IO) {
                try {
                    saveToGallery(context, outFile, outputName)
                } finally {
                    outFile.delete()
                }
            }
        } finally {
            // Free the precomputed matte bitmaps once the encode is done (or it failed/cancelled).
            mattes.values.forEach { runCatching { it.recycle() } }
            // Free the precomputed face-blur patch bitmaps too — a 60s face-blur clip can hold
            // hundreds of MB across ~600 bitmaps (one per 100ms bucket, up to 720x720 ARGB_8888).
            faceBlur.values.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * Render the current playback region to an expendable, low-resolution cache file for smooth
     * timeline playback (Vegas-style "render preview"). Nothing is written to MediaStore/gallery.
     *
     * The render uses the exact export composition/effects path, but clamps to [region], caps the
     * frame rate at 24fps, and downsizes to 720p. The caller owns/deletes the returned cache file.
     */
    suspend fun renderPreviewBuffer(
        context: Context,
        document: Document,
        region: LongRange,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.Main) {
        require(region.last > region.first) { "Playback region must have positive duration." }
        val previewDocument = document
            .clampedToRegion(region.first, region.last)
            .copy(
                settings = document.settings.copy(
                    quality = Quality.HD_720P,
                    fps = minOf(document.settings.fps, 24).coerceAtLeast(1),
                ),
            )

        val normalizeGains = withContext(Dispatchers.IO) {
            try {
                computeNormalizeGains(context, previewDocument)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                ActivityLog.error("Preview normalize scan failed (continuing without): ${describeCauseChain(e)}")
                emptyMap()
            }
        }
        val mattes = withContext(Dispatchers.IO) {
            try {
                precomputeMattes(context, previewDocument)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                ActivityLog.error("Preview matte precompute failed (continuing without): ${describeCauseChain(e)}")
                emptyMap()
            }
        }
        val faceBlur = withContext(Dispatchers.IO) {
            try {
                precomputeFaceBlur(context, previewDocument)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                ActivityLog.error("Preview face-blur precompute failed (continuing without): ${describeCauseChain(e)}")
                emptyMap()
            }
        }

        try {
            val composition = buildComposition(previewDocument, normalizeGains, mattes, faceBlur)
            require(composition != null) { "Nothing to preview in this playback region." }

            val disabled = previewDocument.disabledTrackIds
            val hasAudio = previewDocument.clips.any { clip ->
                if (clip.trackId in disabled) return@any false
                when (clip.type) {
                    ClipType.AUDIO -> true
                    ClipType.VIDEO -> previewDocument.mediaFor(clip)?.hasAudio == true
                    else -> false
                }
            }

            val outFile = File(
                context.cacheDir,
                "guillotine_preview_${System.currentTimeMillis()}.mp4",
            )
            try {
                coroutineScope {
                    var poller: Job? = null
                    try {
                        suspendCancellableCoroutine<Unit> { cont ->
                            val builder = Transformer.Builder(context)
                                .setVideoMimeType(MimeTypes.VIDEO_H264)
                            if (hasAudio) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                            val transformer = builder
                                .addListener(object : Transformer.Listener {
                                    override fun onCompleted(c: Composition, result: ExportResult) {
                                        if (cont.isActive) cont.resume(Unit)
                                    }

                                    override fun onError(c: Composition, result: ExportResult, e: ExportException) {
                                        if (cont.isActive) cont.resumeWithException(e)
                                    }
                                })
                                .build()

                            poller = launch {
                                val holder = ProgressHolder()
                                while (isActive) {
                                    transformer.getProgress(holder)
                                    onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                                    delay(150)
                                }
                            }
                            cont.invokeOnCancellation {
                                runCatching { transformer.cancel() }
                                runCatching { outFile.delete() }
                            }
                            transformer.start(composition, outFile.absolutePath)
                        }
                    } finally {
                        poller?.cancel()
                    }
                }
            } catch (e: Exception) {
                runCatching { outFile.delete() }
                throw e
            }
            onProgress(1f)
            outFile
        } finally {
            mattes.values.forEach { runCatching { it.recycle() } }
            faceBlur.values.forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * Turn any export failure into an actionable, copyable diagnostic. Media3's [ExportException]
     * often carries a null message and puts the real detail on errorCode/errorCodeName/cause, so
     * a naive `e.message ?: "Export failed"` throws that away. Also many internal exceptions
     * (IllegalStateException in particular) throw with no message at all — for those we include
     * the top three stack frames so the caller can at least see WHERE the failure came from.
     */
    fun describeExportError(e: Throwable): String {
        val head = when (e) {
            is ExportException -> {
                val name = runCatching { ExportException.getErrorCodeName(e.errorCode) }.getOrNull()
                    ?: "ERROR_CODE_${e.errorCode}"
                "Media3 export failed: $name (code ${e.errorCode})"
            }
            else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
        return head + "\n\n" + describeCauseChain(e)
    }

    /**
     * Walk the cause chain. For each level, print the class + message (or just the class when the
     * message is null), then the top three stack frames indented under it. This turns an opaque
     * "IllegalStateException" into something the maintainer can actually locate in the source.
     */
    private fun describeCauseChain(e: Throwable): String {
        val out = StringBuilder()
        var cur: Throwable? = e
        val seen = HashSet<Throwable>()
        var depth = 0
        while (cur != null && seen.add(cur)) {
            if (depth > 0) out.append("\nCaused by ")
            val msg = cur.message?.trim().orEmpty()
            out.append(cur.javaClass.name)
            if (msg.isNotEmpty()) out.append(": ").append(msg)
            cur.stackTrace.take(3).forEach { frame ->
                out.append("\n    at ").append(frame.toString())
            }
            cur = cur.cause
            depth++
        }
        return out.toString()
    }

    /** Bundle carrying a filed-ready issue: title + fully-populated body. */
    data class IssueReport(val title: String, val body: String)

    /**
     * Assemble the title + body for an export failure. Used by both the relay POST (so the
     * Worker can file the issue automatically) and the browser fallback (URL below).
     */
    fun buildIssueReport(context: Context, diagnostic: String): IssueReport {
        val body = buildString {
            appendLine("**What I was doing:** exporting my project.")
            appendLine()
            appendLine("**Device:** ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            val pi = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            appendLine("**App version:** ${pi?.versionName ?: "?"} (${pi?.longVersionCode ?: "?"})")
            appendLine()
            appendLine("**Diagnostic**")
            appendLine("```")
            appendLine(diagnostic)
            appendLine("```")
        }
        val title = "Export failed: " + diagnostic.lineSequence().firstOrNull().orEmpty().take(80)
        return IssueReport(title, body)
    }

    /**
     * Pre-filled GitHub issue URL for the fallback path — when the relay is offline or the
     * app couldn't reach it, we still open the browser with the title/body ready so the user
     * only has to hit Submit (assuming they have a GH account). Preferred path is
     * [reportManual] via the Cloudflare Worker, which needs no account on the user side.
     */
    fun buildIssueUrl(report: IssueReport): String {
        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "https://github.com/HereLiesAz/Guillotine/issues/new" +
            "?title=" + enc(report.title) +
            "&body=" + enc(report.body) +
            "&labels=" + enc("bug,export")
    }

    /**
     * Build the export composition: one or more video sequences (kept ranges of video clips + image
     * clips) plus a separate audio sequence for standalone audio-track clips. When the opaque layers
     * can't be flattened into one sequence — several background tracks, or two clips overlapping on one
     * track — an "advanced" path builds one sequence per track *lane* (a lane per overlap depth) composited
     * bottom-to-top (top-of-panel track on top, matching the preview), **crossfades** overlapping clips
     * by ramping the incoming clip in over a held outgoing clip (via [VideoEffects.fadeIn]), and draws
     * the **background-removal subjects + captions over the final composite** as Composition-level
     * overlays (so a bg-removed clip on an upper track shows lower tracks through its matte). Otherwise a
     * single flattened sequence + per-item matte/face overlay is used; captions are always applied
     * at Composition level so their placement is project-canvas-relative. Project aspect is an output
     * canvas boundary; it is never applied as a per-clip transform. Per clip/item this bakes in: color filters,
     * the Crop-tool transform, keyframed opacity/scale (via [VideoEffects.keyframeEffects]),
     * keyframed/static volume + pan + normalize, track opacity, and item-timed matte/face overlays
     * (which stay in sync across 'remove' cuts via each item's timeline start). Captions are applied
     * once at Composition level so their anchors are evaluated against the project canvas.
     *
     */
    private fun buildComposition(
        document: Document,
        normalizeGains: Map<String, Float>,
        mattes: Map<Long, Bitmap>,
        faceBlur: Map<Long, Bitmap>,
    ): Composition? {
        val canvas = document.projectCanvasSize()
        val geometry = VideoEffects.geometry(document.settings, canvas.height)

        val disabled = document.disabledTrackIds
        val videoClips = document.clips
            .filter { it.type == ClipType.VIDEO && it.trackId !in disabled }
            .sortedBy { it.startTimeMs }
        // Background-removed clips composite as a foreground layer over the rest. If nothing is
        // marked for removal, every video clip just forms the base (original behavior).
        val foreground = videoClips.filter { it.filters.removeBackground }
        val background = videoClips.filter { !it.filters.removeBackground }
        val baseClips = if (background.isNotEmpty()) background else videoClips
        val hasMatte = background.isNotEmpty() && foreground.isNotEmpty()

        val textClips = document.clips
            .filter { it.type == ClipType.TEXT && it.trackId !in disabled && it.text.isNotBlank() }

        // Best-effort expected output height, so CaptionOverlay can size its text proportionally to
        // the actual frame instead of a fixed pixel count regardless of resolution. An explicit
        // Quality target is exact; otherwise the geometry's height-only Presentation (see
        // VideoEffects.geometry) leaves the source's own decoded height untouched, so the first video
        // clip's own probed dimensions are the best available estimate. Falls back to 1080 (this
        // overlay's own reference height, i.e. no scaling) for a genuinely undecodable/unprobed clip.
        val refHeightPx = canvas.height

        // Matte/face overlays are item-timed because remove ranges physically split source items.
        // Captions deliberately are NOT attached here: per-item caption anchors are evaluated in the
        // source-shaped item frame, so they move incorrectly when project and source aspects differ.
        // Captions are added once at Composition level below, where anchors use the project canvas.
        fun itemOverlaysFor(timelineStartMs: Long): OverlayEffect? {
            val list = mutableListOf<TextureOverlay>()
            if (hasMatte) list += MatteOverlay(mattes, timelineStartMs)
            if (faceBlur.isNotEmpty()) list += FaceBlurOverlay(faceBlur, timelineStartMs)
            return if (list.isNotEmpty()) OverlayEffect(ImmutableList.copyOf(list)) else null
        }

        fun compositionOverlaysFor(
            timelineStartMs: Long,
            includeMatteAndFace: Boolean,
            captionTimelineSegments: List<CaptionTimelineSegment>,
        ): OverlayEffect? {
            val list = mutableListOf<TextureOverlay>()
            if (includeMatteAndFace && hasMatte) list += MatteOverlay(mattes, timelineStartMs)
            if (includeMatteAndFace && faceBlur.isNotEmpty()) list += FaceBlurOverlay(faceBlur, timelineStartMs)
            textClips.forEach { clip ->
                list += CaptionOverlay(
                    clip = clip,
                    timelineStartMs = timelineStartMs,
                    refHeightPx = refHeightPx,
                    timelineSegments = captionTimelineSegments,
                    trackOpacity = document.trackSettingsFor(clip.trackId).opacity,
                )
            }
            return if (list.isNotEmpty()) OverlayEffect(ImmutableList.copyOf(list)) else null
        }

        fun audioFor(clip: TimelineClip, clipLocalStartMs: Long): List<AudioProcessor> {
            val ts = document.trackSettingsFor(clip.trackId)
            val norm = normalizeGains[clip.id] ?: 1f
            val animated = clip.keyframes.any {
                it.property == KeyframeProperty.VOLUME || it.property == KeyframeProperty.PAN
            }
            return if (animated) {
                // Time-varying gain + pan (valueAt VOLUME/PAN, defaults = static) folding in track
                // volume + normalize. The processor handles pan per-frame, so no separate panOnly.
                val staticMult = (if (ts.muted) 0f else ts.volume) * norm
                listOf(KeyframeVolumeProcessor(clip, clipLocalStartMs, staticMult))
            } else {
                val vol = (if (ts.muted) 0f else clip.filters.volume * ts.volume) * norm
                audioProcessors(vol, clip.filters.pan)
            }
        }

        fun videoEffectsFor(
            clip: TimelineClip,
            clipLocalStartMs: Long,
            timelineStartMs: Long,
            withOverlays: Boolean,
            fade: LongRange? = null,
        ): Effects {
            val ts = document.trackSettingsFor(clip.trackId)
            val alpha = if (ts.opacity < 1f) listOf(AlphaScale(ts.opacity)) else emptyList()
            // Keyframe-aware color + crop/placement transform + opacity (animated when keyframed, static
            // otherwise). clipLocalStartMs maps the item's presentationTime to clip-relative time.
            val color = VideoEffects.colorEffects(clip, clipLocalStartMs)
            val sourceAspect = document.mediaFor(clip)?.aspectRatioValue
                ?.toFloat()
                ?.takeIf { it > 0f }
                ?: canvas.aspectRatio
            val offsetXScale = canvas.aspectRatio / sourceAspect
            val transform = VideoEffects.transformEffects(clip, clipLocalStartMs, offsetXScale)
            val opacity = VideoEffects.opacityEffects(clip, clipLocalStartMs)
            // Frame decimation (frameStep): drop frames to fps/step up front so the rest of the pipeline
            // processes fewer frames. Kept frames keep their timestamps, so the presentationTime-driven
            // color/opacity/fade animations below still line up. No-op when frameStep <= 1.
            val decimate = VideoEffects.frameDrop(clip.filters.frameStep, document.settings.fps.toFloat())
            // Crossfade ramp: the incoming clip fades 0→1 across its overlap with the held outgoing clip.
            val fadeFx = fade?.let { listOf(VideoEffects.fadeIn(timelineStartMs, it.first, it.last)) } ?: emptyList()
            val overlay = if (withOverlays) listOfNotNull(itemOverlaysFor(timelineStartMs)) else emptyList()
            return Effects(
                audioFor(clip, clipLocalStartMs),
                decimate + color + transform + opacity + fadeFx + geometry + overlay + alpha,
            )
        }

        // Append a list of video clips into [seq] starting at [startCursor] on the timeline (a leading
        // gap fills startCursor..firstClip, so stacked track sequences stay time-aligned). Matte/face
        // overlays are attached only when [withOverlays]. When [captionTimelineSegments] is supplied,
        // record every real item's composition-time → source-timeline mapping for the composition-level
        // caption pass; gaps deliberately produce no segment, matching the old per-item caption behavior.
        // Returns true if any real item (not just a gap) was added.
        fun appendVideoItems(
            seq: EditedMediaItemSequence.Builder,
            clips: List<TimelineClip>,
            startCursor: Long,
            withOverlays: Boolean,
            fadeFor: (TimelineClip) -> LongRange? = { null },
            captionTimelineSegments: MutableList<CaptionTimelineSegment>? = null,
        ): Boolean {
            var cursor = startCursor
            var presentationCursor = 0L
            var added = false
            clips.sortedBy { it.startTimeMs }.forEach { clip ->
                val media = document.mediaFor(clip) ?: return@forEach
                val fade = fadeFor(clip)
                val gap = clip.startTimeMs - cursor
                if (gap > 0) {
                    seq.addGap(gap * 1000)
                    cursor += gap
                    presentationCursor += gap
                }
                if (media.kind == MediaKind.IMAGE) {
                    val dur = if (clip.durationMs > 0) clip.durationMs else 5_000L
                    val mediaItem = ExoMediaItem.Builder()
                        .setUri(Uri.parse(media.uri))
                        .setImageDurationMs(dur)
                        .build()
                    seq.addItem(
                        EditedMediaItem.Builder(mediaItem).setFrameRate(30)
                            .setEffects(videoEffectsFor(clip, 0L, clip.startTimeMs, withOverlays, fade)).build(),
                    )
                    captionTimelineSegments?.add(
                        CaptionTimelineSegment(
                            presentationStartMs = presentationCursor,
                            presentationEndMs = presentationCursor + dur,
                            timelineStartMs = clip.startTimeMs,
                        ),
                    )
                    presentationCursor += dur
                    added = true
                    cursor += dur
                } else {
                    for (range in TimelineMath.keptRanges(clip)) {
                        val startMs = range.first
                        val endMs = range.last + 1 // ranges are exclusive-end (built with `until`)
                        if (endMs <= startMs) continue
                        val clipLocalStart = startMs - clip.trimStartMs
                        val timelineStart = clip.startTimeMs + clipLocalStart
                        val mediaItem = ExoMediaItem.Builder()
                            .setUri(Uri.parse(media.uri))
                            .setClippingConfiguration(
                                ExoMediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(startMs)
                                    .setEndPositionMs(endMs)
                                    .build(),
                            )
                            .build()
                        seq.addItem(
                            EditedMediaItem.Builder(mediaItem)
                                .setEffects(videoEffectsFor(clip, clipLocalStart, timelineStart, withOverlays, fade)).build(),
                        )
                        val keptDuration = endMs - startMs
                        captionTimelineSegments?.add(
                            CaptionTimelineSegment(
                                presentationStartMs = presentationCursor,
                                presentationEndMs = presentationCursor + keptDuration,
                                timelineStartMs = timelineStart,
                            ),
                        )
                        presentationCursor += keptDuration
                        added = true
                        cursor += keptDuration
                    }
                }
            }
            return added
        }

        // Video sequences. The "advanced" compositor kicks in when the OPAQUE (background) layers can't
        // be expressed as one flattened sequence: several background tracks, or two background clips
        // overlapping on one track (a crossfade). It builds one sequence per track *lane* (a track gets
        // a lane per simultaneous-overlap depth), composites them bottom-to-top, ramps the incoming clip in
        // with VideoEffects.fadeIn over a held outgoing clip, and draws the background-removal subjects +
        // captions over the FINAL composite as Composition-level overlays. Otherwise (single background
        // track, no overlap — incl. the classic foreground-over-one-background matte) we keep the
        // original single flattened sequence + per-item overlay path, byte-for-byte unchanged.
        // Each sequence is zeroed to a common timeline origin; a track/lane that starts later gets a
        // leading gap to stay aligned (verify leading gaps on device — older Media3 couldn't lead with a
        // gap; we target 1.10.1).
        val videoTrackOrder = document.videoTracks
        fun trackPos(tid: String) = videoTrackOrder.indexOf(tid).let { if (it < 0) Int.MAX_VALUE else it }
        // Tracks carrying an opaque (non-bg-removed) clip, top-of-panel first.
        val bgTracks = background.filter { document.mediaFor(it) != null }
            .map { it.trackId }.distinct().sortedBy { trackPos(it) }
        val sameTrackOverlap = bgTracks.any { tid ->
            background.filter { it.trackId == tid && document.mediaFor(it) != null }
                .sortedBy { it.startTimeMs }
                .zipWithNext().any { (a, b) -> b.startTimeMs < a.endTimeMs }
        }
        val advanced = bgTracks.size >= 2 || sameTrackOverlap
        // Common zero across every composited clip (incl. foreground, whose matte overlay is timed
        // against it): composition time 0 == this timeline instant.
        val globalZero = if (advanced) {
            videoClips.filter { document.mediaFor(it) != null }.minOf { it.startTimeMs }
        } else {
            baseClips.firstOrNull()?.startTimeMs ?: 0L
        }
        // Simple export has one authoritative sequence, so record exactly how each real item maps
        // composition presentation time back to the editor timeline. This preserves caption timing
        // across a non-zero first clip and across REMOVE-range discontinuities. Advanced export keeps
        // its existing common-zero clock because several parallel lanes do not have one unique item map.
        val captionTimelineSegments = mutableListOf<CaptionTimelineSegment>()

        // Whether the composition has any real audio source. Video sequences declare an AUDIO
        // trackType only when this is true — otherwise Media3 would synthesise a silent audio
        // track and inflate the export for no gain. Standalone audio tracks with all-muted clips
        // still count (the muxer would still carry the track); only the disabled tracks are
        // dropped. Kept as a var-free expression so buildComposition stays a pure function.
        val hasAudio = document.clips.any { c ->
            c.trackId !in disabled && when (c.type) {
                ClipType.AUDIO -> true
                ClipType.VIDEO -> document.mediaFor(c)?.hasAudio == true
                else -> false
            }
        }
        val videoTrackTypes: Set<Int> = if (hasAudio) {
            setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        } else {
            setOf(C.TRACK_TYPE_VIDEO)
        }

        // Split a track's opaque clips into lanes so overlapping clips land on different sequences and
        // can crossfade. Each lane's running end time is tracked independently; a clip is placed on the
        // lowest lane that is FREE at its start AND above the top (highest) lane it overlaps — a fresh
        // lane only when none qualifies — so no two clips on a lane ever overlap (no invalid sequence)
        // and the incoming clip always composites on top of the one it dissolves from. It fades in over
        // that top overlapping clip. Lane count = max simultaneous overlap depth (1–2 in practice).
        fun laneLayout(trackClips: List<TimelineClip>): List<Triple<TimelineClip, Int, LongRange?>> {
            val out = ArrayList<Triple<TimelineClip, Int, LongRange?>>()
            val laneEnds = ArrayList<Long>() // running end-time of the clip currently on each lane
            trackClips.sortedBy { it.startTimeMs }.forEach { c ->
                // Top lane whose clip is still playing at c.start = the clip c dissolves from (-1 = none).
                var topOverlap = -1
                var topOverlapEnd = Long.MIN_VALUE
                for (i in laneEnds.indices) if (laneEnds[i] > c.startTimeMs) { topOverlap = i; topOverlapEnd = laneEnds[i] }
                // Lowest free lane strictly above the top overlapping lane; else a new lane.
                var lane = ((topOverlap + 1) until laneEnds.size).firstOrNull { laneEnds[it] <= c.startTimeMs }
                    ?: laneEnds.size.also { laneEnds.add(Long.MIN_VALUE) }
                val fade = if (topOverlap >= 0) c.startTimeMs..minOf(c.endTimeMs, topOverlapEnd) else null
                out += Triple(c, lane, fade)
                laneEnds[lane] = c.endTimeMs
            }
            return out
        }

        val videoSequences: List<EditedMediaItemSequence> = if (advanced) {
            // Bottom track first (so it's the composite base), top track last = on top; within a track,
            // lane 0 then lane 1 (lane 1 holds the incoming clip, fading in over lane 0). Overlays are
            // NOT attached per item here — they go on the Composition below so they sit over every layer
            // and survive gaps in any one track/lane.
            buildList {
                bgTracks.asReversed().forEach { tid ->
                    val layout = laneLayout(background.filter { it.trackId == tid && document.mediaFor(it) != null })
                    val fadeByClip = layout.associate { (c, _, fade) -> c.id to fade }
                    val laneCount = (layout.maxOfOrNull { it.second } ?: -1) + 1
                    // Lane 0 first (composite base), higher lanes on top (incoming dissolves over outgoing).
                    for (lane in 0 until laneCount) {
                        val laneClips = layout.filter { it.second == lane }.map { it.first }
                        if (laneClips.isEmpty()) continue
                        // Every sequence must declare its track types explicitly. The deprecated
                        // no-arg Builder() defaults trackTypes to {TRACK_TYPE_NONE} (-2), which
                        // Media3's Composition.build() unions with TRACK_TYPE_AUDIO when force-
                        // audio is on — the {NONE, AUDIO} set trips "must only contain AUDIO
                        // and/or VIDEO" on the internal re-wrap. videoTrackTypes above adds AUDIO
                        // only when the project actually has audio, so an audio-less project
                        // exports without a synthesised silent track.
                        val seq = EditedMediaItemSequence.Builder(videoTrackTypes)
                        val any = appendVideoItems(
                            seq, laneClips, globalZero, withOverlays = false, fadeFor = { fadeByClip[it.id] },
                        )
                        if (any) add(seq.build())
                    }
                }
            }
        } else {
            val seq = EditedMediaItemSequence.Builder(videoTrackTypes)
            val any = appendVideoItems(
                seq,
                baseClips,
                globalZero,
                withOverlays = true,
                captionTimelineSegments = captionTimelineSegments,
            )
            if (any) listOf(seq.build()) else emptyList()
        }
        if (videoSequences.isEmpty()) return null

        // Audio: one EditedMediaItemSequence PER audio track so parallel tracks (music + voiceover +
        // effects) actually MIX at render — a Composition's sequences play concurrently and their
        // audio is mixed by Media3. Before this we built a single time-sorted sequence, which
        // concatenated overlapping-in-time clips from different tracks (only one won at any moment).
        // Linked shadow clips (a video's own sound displayed on an audio track) still filter out —
        // that audio is carried by the video sequence itself, so including the shadow would double it.
        val audioByTrack: Map<String, List<TimelineClip>> = document.clips
            .filter { it.type == ClipType.AUDIO && it.linkedClipId == null && it.trackId !in disabled }
            .sortedBy { it.startTimeMs }
            .groupBy { it.trackId }
        val audioSequences: List<EditedMediaItemSequence> = audioByTrack.mapNotNull { (_, clips) ->
            // Audio-only sequences: declare TRACK_TYPE_AUDIO explicitly so Composition.build()'s
            // force-audio re-wrap doesn't union with the deprecated no-arg default
            // {TRACK_TYPE_NONE} and blow up the precondition on the wrap constructor.
            val seq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            var cursor = clips.firstOrNull()?.startTimeMs ?: return@mapNotNull null
            var addedAny = false
            clips.forEach { clip ->
                val media = document.mediaFor(clip) ?: return@forEach
                val gap = clip.startTimeMs - cursor
                if (gap > 0) { seq.addGap(gap * 1000); cursor += gap }
                for (range in TimelineMath.keptRanges(clip)) {
                    val startMs = range.first
                    val endMs = range.last + 1
                    if (endMs <= startMs) continue
                    val clipLocalStart = startMs - clip.trimStartMs
                    val mediaItem = ExoMediaItem.Builder()
                        .setUri(Uri.parse(media.uri))
                        .setClippingConfiguration(
                            ExoMediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build(),
                        )
                        .build()
                    seq.addItem(
                        EditedMediaItem.Builder(mediaItem)
                            .setRemoveVideo(true)
                            .setEffects(Effects(audioFor(clip, clipLocalStart), emptyList()))
                            .build(),
                    )
                    addedAny = true; cursor += (endMs - startMs)
                }
            }
            if (addedAny) seq.build() else null
        }

        val sequences = videoSequences.toMutableList()
        sequences.addAll(audioSequences)
        // With every sequence declaring its own trackTypes above, the force-audio wrap is not
        // only unnecessary but actively destructive — it would union AUDIO into a video-only
        // sequence's {VIDEO} set and re-wrap through the internal constructor, which no longer
        // accepts the mixed shape in 1.10.1. Media3 synthesises silent audio on its own when a
        // sequence declares AUDIO but has no audio items.
        val composition = Composition.Builder(sequences)
            // Aspect ratio belongs to the OUTPUT canvas, not to any input clip. Identity overlay
            // settings keep every sequence centered at its own geometry; the user's Crop-tool
            // transform remains the only per-clip scale/pan/rotation.
            .setVideoCompositorSettings(ProjectVideoCompositorSettings(canvas))
        // Captions always composite over the FINAL project canvas so their offsets are measured against
        // the project frame rather than a source item's aspect. The simple path supplies the exact
        // presentation→timeline segment map recorded while its sequence was built, preserving non-zero
        // starts and REMOVE discontinuities. Advanced mode retains its shared common-zero timeline and
        // also puts matte/face overlays here because they must sit above the fully composited track stack.
        compositionOverlaysFor(
            timelineStartMs = globalZero,
            includeMatteAndFace = advanced,
            captionTimelineSegments = if (advanced) emptyList() else captionTimelineSegments,
        )?.let {
            composition.setEffects(Effects(emptyList(), listOf(it)))
        }
        return composition.build()
    }

    /**
     * Audio processors applying [volume] gain and stereo [pan] (-1 left … 0 center … +1 right).
     * With no pan it's a simple gain on the existing channel layout; with pan it folds the gain
     * into per-side gains and (for mono sources) upmixes to stereo so the balance is audible.
     */
    private fun audioProcessors(volume: Float, pan: Float): List<AudioProcessor> {
        if (pan == 0f) {
            if (volume == 1f) return emptyList()
            return listOf(ChannelMixingAudioProcessor().apply {
                putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(volume))
                putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(volume))
            })
        }
        val left = (if (pan <= 0f) 1f else 1f - pan) * volume
        val right = (if (pan >= 0f) 1f else 1f + pan) * volume
        return listOf(ChannelMixingAudioProcessor().apply {
            // Coefficients are row-major: index = inputChannel * outputChannelCount + outputChannel.
            putChannelMixingMatrix(ChannelMixingMatrix(1, 2, floatArrayOf(left, right)))
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(left, 0f, 0f, right)))
        })
    }

    /**
     * Pre-segment background-removal mattes off the main thread, keyed by timeline bucket
     * (`timelineMs / MatteOverlay.CACHE_MS`), so [MatteOverlay] is a cheap lookup at render time
     * instead of running ML Kit per frame on the encoder thread. Returns empty when there's no
     * foreground-over-background composite. The matte bitmaps are mask-sized (small), so holding
     * the whole clip's worth is cheap.
     */
    private fun precomputeMattes(context: Context, document: Document): Map<Long, Bitmap> {
        val disabled = document.disabledTrackIds
        val videoClips = document.clips.filter { it.type == ClipType.VIDEO && it.trackId !in disabled }
        val foreground = videoClips.filter { it.filters.removeBackground }
        val background = videoClips.filter { !it.filters.removeBackground }
        if (foreground.isEmpty() || background.isEmpty()) return emptyMap()

        val videoTracks = document.videoTracks
        // Only segment frames that survive into the output: the kept timeline ranges of the base clips.
        val keptTimelineRanges = background.flatMap { base ->
            TimelineMath.keptRanges(base).map { r ->
                val s = base.startTimeMs + (r.first - base.trimStartMs)
                val e = base.startTimeMs + (r.last + 1 - base.trimStartMs)
                s until e
            }
        }

        val out = HashMap<Long, Bitmap>()
        val minStart = foreground.minOf { it.startTimeMs }
        val maxEnd = foreground.maxOf { it.endTimeMs }
        var t = minStart
        while (t < maxEnd) {
            val bucket = t / MatteOverlay.CACHE_MS
            if (!out.containsKey(bucket) && keptTimelineRanges.any { t in it }) {
                // Topmost foreground track wins, matching the preview's compositing.
                val topmost = foreground
                    .filter { t >= it.startTimeMs && t < it.endTimeMs }
                    .minByOrNull { videoTracks.indexOf(it.trackId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                val media = topmost?.let { document.mediaFor(it) }
                if (topmost != null && media != null) {
                    val src = topmost.trimStartMs + (t - topmost.startTimeMs)
                    SubjectSegmenter.cutoutBlocking(context, media.uri, media.kind, src)
                        ?.let { out[bucket] = boundMatte(it) }
                }
            }
            t += MatteOverlay.CACHE_MS
        }
        return out
    }

    /**
     * Pre-detect face-blur patches off the main thread, keyed by timeline bucket
     * (`timelineMs / FaceBlurOverlay.CACHE_MS`), so [FaceBlurOverlay] is a cheap lookup at render time
     * instead of running ML Kit face detection per frame on the encoder thread. Covers every video
     * clip that anonymizes faces (topmost visible track wins at each instant, matching the preview).
     * Returns empty when no clip blurs faces.
     */
    private fun precomputeFaceBlur(context: Context, document: Document): Map<Long, Bitmap> {
        val disabled = document.disabledTrackIds
        val blurClips = document.clips.filter {
            it.type == ClipType.VIDEO && it.trackId !in disabled && it.filters.blurFaces
        }
        if (blurClips.isEmpty()) return emptyMap()

        val videoTracks = document.videoTracks
        // Only detect on frames that survive into the output (kept ranges of each blur clip).
        val keptTimelineRanges = blurClips.flatMap { clip ->
            TimelineMath.keptRanges(clip).map { r ->
                val s = clip.startTimeMs + (r.first - clip.trimStartMs)
                val e = clip.startTimeMs + (r.last + 1 - clip.trimStartMs)
                s until e
            }
        }

        val out = HashMap<Long, Bitmap>()
        val minStart = blurClips.minOf { it.startTimeMs }
        val maxEnd = blurClips.maxOf { it.endTimeMs }
        var t = minStart
        while (t < maxEnd) {
            val bucket = t / FaceBlurOverlay.CACHE_MS
            if (!out.containsKey(bucket) && keptTimelineRanges.any { t in it }) {
                val topmost = blurClips
                    .filter { t >= it.startTimeMs && t < it.endTimeMs }
                    .minByOrNull { videoTracks.indexOf(it.trackId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                val media = topmost?.let { document.mediaFor(it) }
                if (topmost != null && media != null) {
                    val src = topmost.trimStartMs + (t - topmost.startTimeMs)
                    com.hereliesaz.guillotine.media.FaceBlurrer
                        .blurOverlayBlocking(context, media.uri, media.kind, src)
                        ?.let { out[bucket] = boundMatte(it) }
                }
            }
            t += FaceBlurOverlay.CACHE_MS
        }
        return out
    }

    /** Downscale a matte so holding a clip's worth can't OOM (the overlay scales it to frame anyway). */
    private fun boundMatte(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MATTE_MAX_EDGE) return bmp
        val scale = MATTE_MAX_EDGE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private const val MATTE_MAX_EDGE = 720

    /**
     * Peak-normalization gain per clip that has "Normalize audio" enabled: scans the clip's audio
     * (via the cached waveform decoder) for its loudest sample and returns the gain that lifts that
     * peak to ~0.97 full-scale, clamped to a sane range. Keyed by clip id.
     */
    private suspend fun computeNormalizeGains(context: Context, document: Document): Map<String, Float> {
        val gains = HashMap<String, Float>()
        document.clips.filter { it.filters.normalize }.forEach { clip ->
            val media = document.mediaFor(clip) ?: return@forEach
            val wf = MediaPreview.waveform(context, media.uri) ?: return@forEach
            val gain = MediaPreview.normalizeGain(wf)
            if (gain != 1f) gains[clip.id] = gain
        }
        return gains
    }

    /** Copy the encoded file into the gallery via MediaStore (no permission on API 29+). */
    private fun saveToGallery(context: Context, file: File, name: String): Uri {
        val safeName = (if (name.endsWith(".mp4")) name else "$name.mp4")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Guillotine")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create gallery entry.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Could not write export to gallery.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Throwable) {
            // Don't leave an invisible IS_PENDING row behind on a failed/partial write.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}
