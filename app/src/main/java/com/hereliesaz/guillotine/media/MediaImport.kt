package com.hereliesaz.guillotine.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.newId
import com.hereliesaz.guillotine.ui.ActivityLog

/**
 * Imports media via the Storage Access Framework. SAF (unlike the Photo Picker)
 * covers video, audio AND images, and yields **persistable** URI grants so saved
 * projects can re-open their media across sessions. Works on Chromebooks too.
 */
object MediaImport {

    val MIME_TYPES = arrayOf("video/*", "audio/*", "image/*")

    /**
     * Probe a freshly-picked [uri] into a [MediaItem]. Blocking I/O — call from a
     * background dispatcher. Returns null if the URI can't be read.
     */
    fun probe(context: Context, uri: Uri): MediaItem? {
        // Persist read access so the URI survives app/process restarts.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "media"

        var kind = when {
            mime.startsWith("image") -> MediaKind.IMAGE
            mime.startsWith("audio") -> MediaKind.AUDIO
            mime.startsWith("video") -> MediaKind.VIDEO
            else -> MediaKind.VIDEO
        }

        var durationMs = 0L
        var hasAudio = false
        var retrieverError: String? = null
        if (kind != MediaKind.IMAGE) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                // Refine kind from actual streams when mime was ambiguous.
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                if (!hasVideo && hasAudio) kind = MediaKind.AUDIO
                else if (hasVideo) kind = MediaKind.VIDEO
            } catch (e: Exception) {
                retrieverError = "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
            } finally {
                runCatching { retriever.release() }
            }
        }

        // Reject unreadable / bogus media that the retriever couldn't measure. Before this, an
        // unreadable file with a null/0 duration was still imported as a 0-duration VIDEO clip —
        // it would land on the timeline as an invisible zero-width blip and confuse every downstream
        // operation. Images legitimately have durationMs == 0 here; the editor's addMedia gives them
        // a default display duration when they become clips.
        if (kind != MediaKind.IMAGE && durationMs <= 0L) {
            val reason = retrieverError ?: "unreadable or zero-duration media"
            ActivityLog.error("Skipped \"$name\" — $reason")
            return null
        }

        return MediaItem(
            id = newId(),
            uri = uri.toString(),
            name = name,
            kind = kind,
            durationMs = durationMs,
            // Only a VIDEO file's audio is split to its own track; a pure AUDIO file already is audio.
            hasAudio = hasAudio && kind == MediaKind.VIDEO,
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }
}

/**
 * Remembers a launcher for the multi-select document picker. Invoke the returned
 * lambda to open the picker; [onPicked] receives the chosen URIs (empty if cancelled).
 */
@Composable
fun rememberMediaImportLauncher(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onPicked(uris) }
    return { launcher.launch(MediaImport.MIME_TYPES) }
}
