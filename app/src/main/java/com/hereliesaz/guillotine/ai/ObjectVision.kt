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
                // categoryName() is a platform type and can be null/blank — guard before lowercasing.
                .mapNotNull { it.categoryName()?.takeIf(String::isNotBlank)?.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    override fun close() {
        runCatching { detector?.close() }
    }

    companion object {
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.4f
        private const val MAX_RESULTS = 25

        /** The 80 COCO labels this model emits (used to know when the labeler fallback is redundant). */
        val COCO_LABELS: Set<String> = setOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush",
        )

        /** True if [term] is reliably handled by the COCO detector (so the labeler fallback adds nothing). */
        fun coversTerm(term: String): Boolean =
            COCO_LABELS.any { it.contains(term) || term.contains(it) }
    }
}
