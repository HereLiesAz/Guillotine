package com.hereliesaz.guillotine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * On-device subject segmentation (ML Kit selfie model, bundled — no key, no network). Produces
 * a foreground cutout: the subject kept, the background made transparent, so a clip on the
 * track below shows through. This step only generates/cuts out a representative frame for
 * confirmation; live-preview and export compositing build on this.
 */
object SubjectSegmenter {

    /** Foreground-only bitmap (transparent background) of the frame at [atMs], or null. */
    suspend fun cutout(context: Context, uri: String, kind: MediaKind, atMs: Long): Bitmap? =
        withContext(Dispatchers.IO) { cutoutBlocking(context, uri, kind, atMs) }

    /**
     * Blocking variant for use off the main thread (e.g. inside a Media3 overlay's frame
     * callback). Decodes the frame, runs segmentation, returns the matted foreground or null.
     */
    fun cutoutBlocking(context: Context, uri: String, kind: MediaKind, atMs: Long): Bitmap? {
        val frame = grabFrame(context, uri, kind, atMs) ?: return null
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build(),
        )
        return try {
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(frame, 0)))
            applyMask(frame, mask)
        } catch (_: Exception) {
            null
        } finally {
            segmenter.close()
        }
    }

    private fun grabFrame(context: Context, uri: String, kind: MediaKind, atMs: Long): Bitmap? {
        val parsed = Uri.parse(uri)
        return runCatching {
            if (kind == MediaKind.IMAGE) {
                decodeSampledImage(context, parsed)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, parsed)
                    r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    r.release()
                }
            }
        }.getOrNull()
    }

    /**
     * Decode an image clip's frame, subsampled so a huge source photo can't OOM (and so ML Kit
     * isn't handed a needlessly large bitmap). Caps the longest edge near [MAX_EDGE_PX].
     */
    private fun decodeSampledImage(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private const val MAX_EDGE_PX = 1920

    /** Multiply the frame's alpha by the per-pixel foreground confidence from the mask. */
    private fun applyMask(frame: Bitmap, mask: SegmentationMask): Bitmap {
        val mw = mask.width
        val mh = mask.height
        val buffer = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val src = if (frame.width == mw && frame.height == mh) frame
        else Bitmap.createScaledBitmap(frame, mw, mh, true)

        val pixels = IntArray(mw * mh)
        src.getPixels(pixels, 0, mw, 0, 0, mw, mh)
        buffer.rewind()
        for (i in pixels.indices) {
            val conf = if (buffer.hasRemaining()) buffer.get() else 0f
            val alpha = (conf.coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (alpha shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        val out = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, mw, 0, 0, mw, mh)
        if (src !== frame) src.recycle()
        return out
    }
}
