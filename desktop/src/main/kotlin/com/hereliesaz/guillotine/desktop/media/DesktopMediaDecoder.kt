package com.hereliesaz.guillotine.desktop.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ShortBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

object DesktopMediaDecoder {

    data class Waveform(val left: FloatArray, val right: FloatArray)

    private val thumbnailCache = LinkedHashMap<String, ImageBitmap>(96, 0.75f, true)
    private val waveformCache = LinkedHashMap<String, Waveform>(64, 0.75f, true)
    private const val THUMB_CACHE_MAX = 96
    private const val WAVE_CACHE_MAX = 64
    private const val MAX_DECODE_SECS = 30L

    fun normalizeGain(wf: Waveform): Float {
        val peak = maxOf(
            wf.left.maxOrNull() ?: 0f,
            wf.right.maxOrNull() ?: 0f,
        )
        if (peak < 0.001f) return 1f
        return (0.97f / peak).coerceIn(0.1f, 8f)
    }

    suspend fun thumbnail(uri: String, kind: MediaKind, atMs: Long, maxPx: Int = 160): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val key = "$uri@$atMs@$maxPx"
            synchronized(thumbnailCache) { thumbnailCache[key] }?.let { return@withContext it }

            val file = uriToFile(uri) ?: return@withContext null
            val bmp = when (kind) {
                MediaKind.IMAGE -> runCatching {
                    val img = ImageIO.read(file) ?: return@withContext null
                    downscale(img, maxPx)
                }.getOrNull()

                MediaKind.VIDEO, MediaKind.AUDIO -> runCatching {
                    extractVideoFrame(file, atMs, maxPx)
                }.getOrNull()
            } ?: return@withContext null

            val result = bmp.toComposeImageBitmap()
            synchronized(thumbnailCache) {
                thumbnailCache[key] = result
                while (thumbnailCache.size > THUMB_CACHE_MAX) {
                    thumbnailCache.remove(thumbnailCache.keys.first())
                }
            }
            result
        }

    suspend fun waveform(uri: String, buckets: Int = 240): Waveform? =
        withContext(Dispatchers.IO) {
            val key = "$uri@$buckets"
            synchronized(waveformCache) { waveformCache[key] }?.let { return@withContext it }

            val file = uriToFile(uri) ?: return@withContext null
            val result = runCatching { decodeWaveform(file, buckets) }.getOrNull()
                ?: return@withContext null

            synchronized(waveformCache) {
                waveformCache[key] = result
                while (waveformCache.size > WAVE_CACHE_MAX) {
                    waveformCache.remove(waveformCache.keys.first())
                }
            }
            result
        }

    private fun extractVideoFrame(file: File, atMs: Long, maxPx: Int): BufferedImage? {
        val converter = Java2DFrameConverter()
        val grabber = FFmpegFrameGrabber(file)
        grabber.start()
        try {
            val ts = atMs * 1000L // microseconds
            grabber.timestamp = ts
            var frame = grabber.grabImage() ?: return null
            // grabImage can return a frame without image data on some codecs; try a few more
            var attempts = 0
            while (frame.image == null && attempts++ < 5) {
                frame = grabber.grabImage() ?: return null
            }
            val img = converter.convert(frame) ?: return null
            return downscale(img, maxPx)
        } finally {
            grabber.stop()
            grabber.release()
        }
    }

    private suspend fun decodeWaveform(file: File, buckets: Int): Waveform {
        val left = FloatArray(buckets)
        val right = FloatArray(buckets)
        val grabber = FFmpegFrameGrabber(file)
        grabber.start()
        try {
            val totalUs = grabber.lengthInTime
            if (totalUs <= 0) return Waveform(left, right)
            val channels = grabber.audioChannels.coerceAtLeast(1)

            val deadline = System.nanoTime() + MAX_DECODE_SECS * 1_000_000_000L
            while (true) {
                ensureActive()
                if (System.nanoTime() > deadline) break
                val frame = grabber.grabSamples() ?: break
                val samples = frame.samples ?: continue
                if (samples.isEmpty()) continue

                val buf = samples[0] as? ShortBuffer ?: continue
                buf.rewind()
                val sampleCount = buf.remaining() / channels.coerceAtLeast(1)
                val frameTimeUs = frame.timestamp

                for (i in 0 until sampleCount) {
                    val l = buf.get()
                    val r = if (channels >= 2) buf.get() else l
                    // skip extra channels
                    for (ch in 2 until channels) if (buf.hasRemaining()) buf.get()

                    val sampleTimeUs = frameTimeUs + (i.toLong() * 1_000_000L / grabber.sampleRate)
                    val bucket = ((sampleTimeUs.toDouble() / totalUs) * buckets).toInt()
                        .coerceIn(0, buckets - 1)

                    val lNorm = abs(l.toFloat()) / 32768f
                    val rNorm = abs(r.toFloat()) / 32768f
                    if (lNorm > left[bucket]) left[bucket] = lNorm
                    if (rNorm > right[bucket]) right[bucket] = rNorm
                }
            }
        } finally {
            grabber.stop()
            grabber.release()
        }

        fillGaps(left)
        fillGaps(right)
        return Waveform(left, right)
    }

    private fun fillGaps(arr: FloatArray) {
        var lastNonZero = -1
        for (i in arr.indices) {
            if (arr[i] > 0f) {
                if (lastNonZero >= 0 && i - lastNonZero > 1) {
                    val from = arr[lastNonZero]
                    val to = arr[i]
                    for (j in (lastNonZero + 1) until i) {
                        val t = (j - lastNonZero).toFloat() / (i - lastNonZero)
                        arr[j] = from + (to - from) * t
                    }
                }
                lastNonZero = i
            }
        }
    }

    private fun downscale(img: BufferedImage, maxPx: Int): BufferedImage {
        val w = img.width
        val h = img.height
        if (w <= maxPx && h <= maxPx) return img
        val scale = maxPx.toFloat() / maxOf(w, h)
        val nw = (w * scale).roundToInt().coerceAtLeast(1)
        val nh = (h * scale).roundToInt().coerceAtLeast(1)
        val scaled = BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(img, 0, 0, nw, nh, null)
        g.dispose()
        return scaled
    }

    private fun uriToFile(uri: String): File? = runCatching {
        when {
            uri.startsWith("file:") -> File(URI(uri))
            uri.startsWith("/") -> File(uri)
            else -> null
        }
    }.getOrNull()
}

fun BufferedImage.toComposeImageBitmap(): ImageBitmap {
    val bos = ByteArrayOutputStream()
    ImageIO.write(this, "png", bos)
    return org.jetbrains.skia.Image.makeFromEncoded(bos.toByteArray()).toComposeImageBitmap()
}
