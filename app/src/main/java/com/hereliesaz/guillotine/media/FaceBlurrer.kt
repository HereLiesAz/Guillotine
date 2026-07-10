package com.hereliesaz.guillotine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device face anonymization (ML Kit face detection, bundled — no key, no network). Produces a
 * *transparent overlay* the size of the source frame, with each detected face covered by a blurred,
 * softly-feathered copy of that region. Rendered over the clip's own picture in both live preview
 * (an [Image] above the player surface) and export (a `BitmapOverlay`). On-device only — no pixels
 * leave the device.
 */
object FaceBlurrer {

    /** Fraction of the face box to pad outward so hair/jaw/edges are covered, not just the core. */
    private const val PAD = 0.25f

    /** Overlay bitmap (transparent except blurred face patches) for the frame at [atMs], or null when
     *  no faces are found / decoding fails. */
    suspend fun blurOverlay(context: Context, uri: String, kind: MediaKind, atMs: Long): Bitmap? =
        withContext(Dispatchers.IO) { blurOverlayBlocking(context, uri, kind, atMs) }

    /** Blocking variant for the export precompute / render threads. */
    fun blurOverlayBlocking(context: Context, uri: String, kind: MediaKind, atMs: Long): Bitmap? {
        val frame = grabFrame(context, uri, kind, atMs) ?: return null
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build(),
        )
        return try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
            if (faces.isEmpty()) return null
            val overlay = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(overlay)
            for (face in faces) {
                drawBlurredPatch(canvas, frame, face.boundingBox)
            }
            overlay
        } catch (_: Exception) {
            null
        } finally {
            runCatching { detector.close() }
            runCatching { frame.recycle() }
        }
    }

    /** Blur one face region of [frame] and paint it, feathered, onto [canvas] at the same position. */
    private fun drawBlurredPatch(canvas: Canvas, frame: Bitmap, box: Rect) {
        // Pad the box, clamped to the frame.
        val padX = (box.width() * PAD).toInt()
        val padY = (box.height() * PAD).toInt()
        val l = (box.left - padX).coerceIn(0, frame.width - 1)
        val t = (box.top - padY).coerceIn(0, frame.height - 1)
        val r = (box.right + padX).coerceIn(l + 1, frame.width)
        val b = (box.bottom + padY).coerceIn(t + 1, frame.height)
        val w = r - l
        val h = b - t
        val region = runCatching { Bitmap.createBitmap(frame, l, t, w, h) }.getOrNull() ?: return

        // Cheap heavy blur: downscale to a few px then upscale with bilinear filtering.
        val small = maxOf(1, minOf(w, h) / DOWNSCALE)
        val down = Bitmap.createScaledBitmap(region, maxOf(1, w * small / maxOf(w, h)), maxOf(1, h * small / maxOf(w, h)), true)
        val blurred = Bitmap.createScaledBitmap(down, w, h, true)

        // Feathered oval mask so the patch blends into the surrounding image instead of a hard box.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { postTranslate(l.toFloat(), t.toFloat()) })
            }
        }
        val cx = l + w / 2f
        val cy = t + h / 2f
        // Radial alpha: opaque in the middle, fading to transparent at the edge.
        val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            shader = RadialGradient(
                cx, cy, maxOf(w, h) / 2f,
                intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        // Draw into a scratch layer so DST_IN feathering only affects this patch.
        val save = canvas.saveLayer(RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()), null)
        canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
        canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), fade)
        canvas.restoreToCount(save)

        region.recycle()
        down.recycle()
        blurred.recycle()
    }

    private const val DOWNSCALE = 14

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
}
