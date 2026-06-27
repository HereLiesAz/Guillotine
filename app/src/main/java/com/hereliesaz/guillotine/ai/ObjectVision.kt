package com.hereliesaz.guillotine.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable

/**
 * On-device COCO object detector (MediaPipe + bundled EfficientDet-Lite0). Unlike whole-image
 * labeling, this returns bounding-box detections, so it reliably flags objects that aren't the
 * dominant thing in frame — a phone in someone's hand, a car at the edge, etc.
 *
 * Loads a model bundled in app assets (offline, no key/download). If the model or native libs
 * can't load (older device), it degrades to "unavailable" so callers fall back to image labeling
 * instead of crashing the analysis.
 */
class ObjectVision(context: Context) : Closeable {

    private val detector: ObjectDetector? = runCatching {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .build(),
        )
    }.getOrNull()

    /** Whether the detector loaded; callers can skip object detection cleanly when false. */
    val available: Boolean get() = detector != null

    /** Lower-cased COCO category names detected in [bitmap] (e.g. "cell phone", "car"); empty on failure. */
    fun labels(bitmap: Bitmap): Set<String> {
        val d = detector ?: return emptySet()
        return runCatching {
            d.detect(BitmapImageBuilder(bitmap).build())
                .detections()
                .flatMap { it.categories() }
                .map { it.categoryName().lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    override fun close() {
        runCatching { detector?.close() }
    }

    private companion object {
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val SCORE_THRESHOLD = 0.4f
        const val MAX_RESULTS = 25
    }
}
