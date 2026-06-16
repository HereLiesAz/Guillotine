@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
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
import com.hereliesaz.guillotine.media.VideoEffects
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.MediaKind
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
 * v1 scope: exports the video track (clips carry their own audio). Separate audio
 * tracks, keyframed transforms, blur/sepia, and image clips are follow-ups.
 */
object Exporter {

    suspend fun export(
        context: Context,
        document: Document,
        outputName: String,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.Main) {
        val composition = buildComposition(context, document)
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
        val uri = saveToGallery(context, outFile, outputName)
        outFile.delete()
        uri
    }

    /**
     * Build the export composition: a video sequence (kept ranges of video clips +
     * image clips), plus a separate audio sequence for standalone audio-track clips
     * so added music/voice is mixed in. Project crop + aspect are applied to every
     * video clip via [VideoEffects.geometry].
     *
     * Known limits (verify on device): per-clip volume and keyframed opacity/scale
     * are not yet baked into the export; a single video sequence mixing image clips
     * (no audio) with video clips (audio) may need Transformer's force-audio-track
     * flag — most projects are all-video or all-image and are unaffected.
     */
    private fun buildComposition(context: Context, document: Document): Composition? {
        val geometry = VideoEffects.geometry(document.settings)

        val videoClips = document.clips.filter { it.type == ClipType.VIDEO }.sortedBy { it.startTimeMs }
        // Background-removed clips composite as a foreground layer over the rest. If nothing is
        // marked for removal, every video clip just forms the base (original behavior).
        val foreground = videoClips.filter { it.filters.removeBackground }
        val background = videoClips.filter { !it.filters.removeBackground }
        val baseClips = if (background.isNotEmpty()) background else videoClips
        val overlayClips = if (background.isNotEmpty()) foreground else emptyList()

        // One matte overlay carrying all foreground clips, attached to the first base item.
        val matteEffect = if (overlayClips.isNotEmpty()) {
            OverlayEffect(
                ImmutableList.of<TextureOverlay>(
                    MatteOverlay(context, overlayClips, { document.mediaFor(it) }, baseClips.first().startTimeMs),
                ),
            )
        } else null

        val videoItems = mutableListOf<EditedMediaItem>()
        var firstItem = true
        baseClips.forEach { clip ->
            val media = document.mediaFor(clip) ?: return@forEach
            fun effectsFor(): Effects {
                val overlay = if (firstItem) listOfNotNull(matteEffect) else emptyList()
                return Effects(emptyList(), VideoEffects.build(clip.filters) + geometry + overlay)
            }
            if (media.kind == MediaKind.IMAGE) {
                val mediaItem = ExoMediaItem.Builder()
                    .setUri(Uri.parse(media.uri))
                    .setImageDurationMs(if (clip.durationMs > 0) clip.durationMs else 5_000L)
                    .build()
                videoItems += EditedMediaItem.Builder(mediaItem)
                    .setFrameRate(30)
                    .setEffects(effectsFor())
                    .build()
                firstItem = false
            } else {
                for (range in TimelineMath.keptRanges(clip)) {
                    val startMs = range.first
                    val endMs = range.last + 1 // ranges are exclusive-end (built with `until`)
                    if (endMs <= startMs) continue
                    val mediaItem = ExoMediaItem.Builder()
                        .setUri(Uri.parse(media.uri))
                        .setClippingConfiguration(
                            ExoMediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build(),
                        )
                        .build()
                    videoItems += EditedMediaItem.Builder(mediaItem).setEffects(effectsFor()).build()
                    firstItem = false
                }
            }
        }
        if (videoItems.isEmpty()) return null

        val audioItems = mutableListOf<EditedMediaItem>()
        document.clips
            .filter { it.type == ClipType.AUDIO }
            .sortedBy { it.startTimeMs }
            .forEach { clip ->
                val media = document.mediaFor(clip) ?: return@forEach
                for (range in TimelineMath.keptRanges(clip)) {
                    val startMs = range.first
                    val endMs = range.last + 1
                    if (endMs <= startMs) continue
                    val mediaItem = ExoMediaItem.Builder()
                        .setUri(Uri.parse(media.uri))
                        .setClippingConfiguration(
                            ExoMediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build(),
                        )
                        .build()
                    audioItems += EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).build()
                }
            }

        val sequences = mutableListOf(EditedMediaItemSequence(videoItems))
        if (audioItems.isNotEmpty()) sequences += EditedMediaItemSequence(audioItems)
        return Composition.Builder(sequences).build()
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
