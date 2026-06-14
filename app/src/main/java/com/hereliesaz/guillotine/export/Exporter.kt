@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effects
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.hereliesaz.guillotine.media.VideoEffects
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
        val composition = buildComposition(document)
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

    /** Build a single video sequence from the kept ranges of all video clips. */
    private fun buildComposition(document: Document): Composition? {
        val videoClips = document.clips
            .filter { it.type == com.hereliesaz.guillotine.model.ClipType.VIDEO }
            .sortedBy { it.startTimeMs }

        val items = mutableListOf<EditedMediaItem>()
        for (clip in videoClips) {
            val media = document.mediaFor(clip) ?: continue
            if (media.kind == MediaKind.IMAGE) continue // images are a follow-up
            val effects = Effects(emptyList(), VideoEffects.build(clip.filters))
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
                items += EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
            }
        }
        if (items.isEmpty()) return null
        return Composition.Builder(EditedMediaItemSequence(items)).build()
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
