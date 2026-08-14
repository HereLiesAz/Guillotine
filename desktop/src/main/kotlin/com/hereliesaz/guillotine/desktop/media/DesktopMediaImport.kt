package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.newId
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File

object DesktopMediaImport {

    fun probe(file: File): MediaItem? = runCatching {
        val grabber = FFmpegFrameGrabber(file)
        try {
            grabber.start()
        } catch (e: Exception) {
            // start() can throw (truncated/corrupt file, unsupported codec) before allocating the
            // native handles `finally` below would otherwise release — release explicitly here too,
            // or a bad import leaks the native AVFormatContext.
            runCatching { grabber.release() }
            throw e
        }
        try {
            val hasVideo = grabber.imageWidth > 0 && grabber.imageHeight > 0
            val hasAudio = grabber.audioChannels > 0
            if (!hasVideo && !hasAudio) return@runCatching null

            val durationMs = (grabber.lengthInTime / 1000L).coerceAtLeast(1L)
            val kind = when {
                !hasVideo && hasAudio -> MediaKind.AUDIO
                isImageFile(file) -> MediaKind.IMAGE
                else -> MediaKind.VIDEO
            }

            MediaItem(
                id = newId(),
                uri = file.toURI().toString(),
                name = file.nameWithoutExtension,
                kind = kind,
                durationMs = if (kind == MediaKind.IMAGE) 5000L else durationMs,
                hasAudio = hasVideo && hasAudio,
                widthPx = if (hasVideo) grabber.imageWidth else null,
                heightPx = if (hasVideo) grabber.imageHeight else null,
            )
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }.getOrNull()

    private fun isImageFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("png", "jpg", "jpeg", "bmp", "gif", "webp", "tiff", "tif")
    }
}
