@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
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

    suspend fun export(
        context: Context,
        document: Document,
        outputName: String,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.Main) {
        // Peak-normalization gains for clips with "Normalize audio" — computed off the main thread
        // (reuses the cached waveform decoder) before the Transformer is built on Main.
        val normalizeGains = withContext(Dispatchers.IO) { computeNormalizeGains(context, document) }
        // Background-removal mattes are segmented off the main thread up front (not per render frame).
        val mattes = withContext(Dispatchers.IO) { precomputeMattes(context, document) }
        val composition = buildComposition(document, normalizeGains, mattes)
        require(composition != null) { "Nothing to export — add a video clip first." }

        val outFile = File(context.cacheDir, "guillotine_export_${System.currentTimeMillis()}.mp4")

        coroutineScope {
            var poller: Job? = null
            try {
                suspendCancellableCoroutine { cont ->
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
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

        onProgress(1f)
        // The encode runs on Main (Transformer requires it), but copying the finished MP4 into
        // the gallery is blocking file I/O — do it off the main thread so a large export can't ANR.
        val uri = withContext(Dispatchers.IO) {
            val u = saveToGallery(context, outFile, outputName)
            outFile.delete()
            u
        }
        uri
    }

    /**
     * Build the export composition: a video sequence (kept ranges of video clips + image clips),
     * plus a separate audio sequence for standalone audio-track clips. Project crop + aspect apply
     * to every video clip via [VideoEffects.geometry]. Per clip/item this bakes in: color filters,
     * the Crop-tool transform, keyframed opacity/scale (via [VideoEffects.keyframeEffects]),
     * keyframed/static volume + pan + normalize, track opacity, and the matte + caption overlays
     * (attached to every base item with that item's timeline start, so they stay in sync across
     * 'remove' cuts).
     *
     * Known limit (verify on device): mixing image clips (no audio) with video clips (audio) in one
     * sequence may need Transformer's force-audio-track flag — most projects are all-video/all-image.
     */
    private fun buildComposition(
        document: Document,
        normalizeGains: Map<String, Float>,
        mattes: Map<Long, Bitmap>,
    ): Composition? {
        val geometry = VideoEffects.geometry(document.settings)

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

        // Overlays (matte + captions) are attached to EVERY base item with that item's timeline
        // start, so they stay aligned even after 'remove' ranges are physically cut.
        fun overlaysFor(timelineStartMs: Long): OverlayEffect? {
            val list = mutableListOf<TextureOverlay>()
            if (hasMatte) list += MatteOverlay(mattes, timelineStartMs)
            textClips.forEach { list += CaptionOverlay(it, timelineStartMs) }
            return if (list.isNotEmpty()) OverlayEffect(ImmutableList.copyOf(list)) else null
        }

        fun audioFor(clip: TimelineClip, clipLocalStartMs: Long): List<AudioProcessor> {
            val ts = document.trackSettingsFor(clip.trackId)
            val norm = normalizeGains[clip.id] ?: 1f
            return if (clip.keyframes.any { it.property == KeyframeProperty.VOLUME }) {
                // Time-varying gain (valueAt VOLUME) folding in track volume + normalize, plus pan.
                val staticMult = (if (ts.muted) 0f else ts.volume) * norm
                listOf(KeyframeVolumeProcessor(clip, clipLocalStartMs, staticMult)) + panOnly(clip.filters.pan)
            } else {
                val vol = (if (ts.muted) 0f else clip.filters.volume * ts.volume) * norm
                audioProcessors(vol, clip.filters.pan)
            }
        }

        fun videoEffectsFor(clip: TimelineClip, clipLocalStartMs: Long, timelineStartMs: Long): Effects {
            val ts = document.trackSettingsFor(clip.trackId)
            val alpha = if (ts.opacity < 1f) listOf(AlphaScale(ts.opacity)) else emptyList()
            val transform = VideoEffects.transform(clip.scale, clip.rotation, clip.offsetX, clip.offsetY)
            val keyframes = VideoEffects.keyframeEffects(clip, clipLocalStartMs)
            val overlay = listOfNotNull(overlaysFor(timelineStartMs))
            return Effects(
                audioFor(clip, clipLocalStartMs),
                VideoEffects.build(clip.filters) + transform + keyframes + geometry + overlay + alpha,
            )
        }

        // Video sequence with clips at their timeline positions: gaps fill the space between
        // clips. The sequence is zeroed to its first clip (Media3 1.5 can't lead with a gap),
        // so the earliest clip starts the output; inter-clip spacing is preserved.
        val videoSeq = EditedMediaItemSequence.Builder()
        var videoCursor = baseClips.firstOrNull()?.startTimeMs ?: 0L
        var addedVideo = false
        baseClips.forEach { clip ->
            val media = document.mediaFor(clip) ?: return@forEach
            val gap = clip.startTimeMs - videoCursor
            if (gap > 0) { videoSeq.addGap(gap * 1000); videoCursor += gap }
            if (media.kind == MediaKind.IMAGE) {
                val dur = if (clip.durationMs > 0) clip.durationMs else 5_000L
                val mediaItem = ExoMediaItem.Builder()
                    .setUri(Uri.parse(media.uri))
                    .setImageDurationMs(dur)
                    .build()
                videoSeq.addItem(
                    EditedMediaItem.Builder(mediaItem).setFrameRate(30)
                        .setEffects(videoEffectsFor(clip, 0L, clip.startTimeMs)).build(),
                )
                addedVideo = true; videoCursor += dur
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
                    videoSeq.addItem(
                        EditedMediaItem.Builder(mediaItem)
                            .setEffects(videoEffectsFor(clip, clipLocalStart, timelineStart)).build(),
                    )
                    addedVideo = true; videoCursor += (endMs - startMs)
                }
            }
        }
        if (!addedVideo) return null

        val audioClips = document.clips
            // Skip linked shadow clips — that audio is rendered by their video clip already.
            .filter { it.type == ClipType.AUDIO && it.linkedClipId == null && it.trackId !in disabled }
            .sortedBy { it.startTimeMs }
        val audioSeq = EditedMediaItemSequence.Builder()
        var audioCursor = audioClips.firstOrNull()?.startTimeMs ?: 0L
        var addedAudio = false
        audioClips.forEach { clip ->
            val media = document.mediaFor(clip) ?: return@forEach
            val gap = clip.startTimeMs - audioCursor
            if (gap > 0) { audioSeq.addGap(gap * 1000); audioCursor += gap }
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
                audioSeq.addItem(
                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveVideo(true)
                        .setEffects(Effects(audioFor(clip, clipLocalStart), emptyList()))
                        .build(),
                )
                addedAudio = true; audioCursor += (endMs - startMs)
            }
        }

        val sequences = mutableListOf(videoSeq.build())
        if (addedAudio) sequences += audioSeq.build()
        return Composition.Builder(sequences).build()
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

    /** Pan-only (no gain) — used alongside [KeyframeVolumeProcessor], which handles the gain. */
    private fun panOnly(pan: Float): List<AudioProcessor> {
        if (pan == 0f) return emptyList()
        val left = if (pan <= 0f) 1f else 1f - pan
        val right = if (pan >= 0f) 1f else 1f + pan
        return listOf(ChannelMixingAudioProcessor().apply {
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
        val out = HashMap<Long, Bitmap>()
        foreground.sortedBy { it.startTimeMs }.forEach { clip ->
            val media = document.mediaFor(clip) ?: return@forEach
            var t = clip.startTimeMs
            while (t < clip.endTimeMs) {
                val bucket = t / MatteOverlay.CACHE_MS
                if (!out.containsKey(bucket)) {
                    val src = clip.trimStartMs + (t - clip.startTimeMs)
                    SubjectSegmenter.cutoutBlocking(context, media.uri, media.kind, src)?.let { out[bucket] = it }
                }
                t += MatteOverlay.CACHE_MS
            }
        }
        return out
    }

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
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Could not write export to gallery.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
