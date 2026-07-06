package com.hereliesaz.guillotine.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Face stage for the person-identity recognition route: ML Kit face detection (already a dependency)
 * to find and crop faces, which the caller then embeds with a face model via [ImageEmbed]. Kept
 * separate from the generic object path so identifying a specific *person* uses face features, not a
 * whole-body/scene embedding. On-device only.
 */
object FaceEmbed {

    private fun detector() = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build(),
    )

    /** Crops of every detected face in [frame], largest first. Empty if none / detection fails. */
    fun detectFaces(context: Context, frame: Bitmap): List<Bitmap> {
        val d = detector()
        return try {
            Tasks.await(d.process(InputImage.fromBitmap(frame, 0)))
                .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                .mapNotNull { cropRect(frame, it.boundingBox) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { d.close() }
        }
    }

    /** Cheap check: is there at least one face in [frame]? Used to route a concept to the face path. */
    fun hasFace(context: Context, frame: Bitmap): Boolean {
        val d = detector()
        return try {
            Tasks.await(d.process(InputImage.fromBitmap(frame, 0))).isNotEmpty()
        } catch (_: Exception) {
            false
        } finally {
            runCatching { d.close() }
        }
    }

    private fun cropRect(bmp: Bitmap, r: Rect): Bitmap? = runCatching {
        val l = r.left.coerceIn(0, bmp.width - 1)
        val t = r.top.coerceIn(0, bmp.height - 1)
        val w = r.width().coerceIn(1, bmp.width - l)
        val h = r.height().coerceIn(1, bmp.height - t)
        Bitmap.createBitmap(bmp, l, t, w, h)
    }.getOrNull()
}
