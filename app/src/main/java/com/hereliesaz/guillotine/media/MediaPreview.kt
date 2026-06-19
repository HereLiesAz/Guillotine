package com.hereliesaz.guillotine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * On-device, no-network previews for timeline clips: a representative thumbnail for
 * video/image clips and a coarse amplitude waveform for audio clips. Both are decoded
 * on a background dispatcher and cached in memory. Everything is best-effort — any
 * failure returns null and the timeline falls back to a plain clip block.
 */
object MediaPreview {

    private val thumbs = LruCache<String, ImageBitmap>(96)
    private val waves = LruCache<String, Waveform>(64)

    /** Per-channel peak envelopes (0..1). Mono sources have [left] == [right] (same values). */
    data class Waveform(val left: FloatArray, val right: FloatArray)

    /** A downscaled frame ([MediaKind.IMAGE] decodes the image itself) at [atMs] of the source. */
    suspend fun thumbnail(
        context: Context,
        uri: String,
        kind: MediaKind,
        atMs: Long,
        maxPx: Int = 160,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        // maxPx is part of the key: the same frame requested at two sizes must not collide
        // (otherwise the first-cached, possibly smaller, bitmap is returned for the larger request).
        val key = "$uri@$atMs@$maxPx"
        thumbs.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            if (kind == MediaKind.IMAGE) decodeImage(context, uri, maxPx) else decodeFrame(context, uri, atMs)
        }.getOrNull() ?: return@withContext null
        val img = downscale(bmp, maxPx).asImageBitmap()
        thumbs.put(key, img)
        img
    }

    /** Stereo (L/R) peak envelopes of [buckets] each across the clip's audio track, or null. */
    suspend fun waveform(context: Context, uri: String, buckets: Int = 240): Waveform? =
        withContext(Dispatchers.IO) {
            waves.get(uri)?.let { return@withContext it }
            // The decode loop calls this; throwing on cancellation lets it propagate properly
            // (don't swallow CancellationException — only turn real decode failures into null).
            val result = try {
                decodeWaveform(context, Uri.parse(uri), buckets) {
                    if (!isActive) throw CancellationException("waveform decode cancelled")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            result?.let { waves.put(uri, it) }
            result
        }

    // ---- thumbnails --------------------------------------------------------

    private fun decodeFrame(context: Context, uri: String, atMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }

    private fun decodeImage(context: Context, uri: String, maxPx: Int): Bitmap? {
        // Two-pass decode: read bounds, then subsample so we never load a huge bitmap.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        val largest = max(opts.outWidth, opts.outHeight)
        while (largest > 0 && largest / sample > maxPx * 2) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    }

    private fun downscale(bmp: Bitmap, maxPx: Int): Bitmap {
        val largest = max(bmp.width, bmp.height)
        if (largest <= maxPx) return bmp
        val scale = maxPx.toFloat() / largest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    }

    // ---- waveform ----------------------------------------------------------

    /** Hard cap so a malformed stream that never signals end-of-stream can't spin forever. */
    private const val MAX_DECODE_NS = 30_000_000_000L // 30s

    private fun decodeWaveform(context: Context, uri: Uri, buckets: Int, ensureActive: () -> Unit): Waveform? {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return null
            val totalUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (totalUs <= 0L) return null
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // Interleaved PCM is L,R,L,R… for stereo; track left/right envelopes separately.
            val leftPeaks = FloatArray(buckets)
            val rightPeaks = FloatArray(buckets)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L
            val deadlineNs = System.nanoTime() + MAX_DECODE_NS
            try {
                while (!outputDone) {
                    // Cooperative cancellation (throws) + a hard deadline so a stuck/malformed
                    // decode can't pin a thread indefinitely.
                    ensureActive()
                    if (System.nanoTime() > deadlineNs) break
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)
                            val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                    if (outIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIndex)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val bucket = ((info.presentationTimeUs.toDouble() / totalUs) * buckets)
                                    .toInt().coerceIn(0, buckets - 1)
                                var lPeak = leftPeaks[bucket]
                                var rPeak = rightPeaks[bucket]
                                val n = shorts.remaining()
                                // Stride by whole frames (×4 for speed); read L at i, R at i+1.
                                val step = channels * 4
                                var i = 0
                                while (i + channels - 1 < n) {
                                    val l = abs(shorts.get(i).toInt()) / 32768f
                                    if (l > lPeak) lPeak = l
                                    val r = if (channels > 1) abs(shorts.get(i + 1).toInt()) / 32768f else l
                                    if (r > rPeak) rPeak = r
                                    i += step
                                }
                                leftPeaks[bucket] = lPeak
                                rightPeaks[bucket] = rPeak
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            // Hit the deadline (cancellation would have thrown) — don't cache a partial envelope.
            if (!outputDone) return null
            // Fill any empty buckets from the previous one so each line is continuous.
            fillGaps(leftPeaks)
            fillGaps(rightPeaks)
            return Waveform(leftPeaks, rightPeaks)
        } finally {
            extractor.release()
        }
    }

    private fun fillGaps(peaks: FloatArray) {
        var last = 0f
        for (i in peaks.indices) {
            if (peaks[i] == 0f) peaks[i] = last else last = peaks[i]
        }
    }
}
