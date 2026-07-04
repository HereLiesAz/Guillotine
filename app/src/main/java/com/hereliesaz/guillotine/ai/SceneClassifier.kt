package com.hereliesaz.guillotine.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import java.io.Closeable

/**
 * On-device scene classifier (MediaPipe + bundled EfficientNet-Lite0 on ImageNet). Covers ~1000
 * fine-grained categories ("golden retriever", "sports car", "acoustic guitar") vs ML Kit's
 * ~400 generic labels ("indoor", "product", "room"). Returns results as [FrameAnalysisCache.SceneLabel]
 * with pre-lowercased text for hot-path matching.
 *
 * Degrades to "unavailable" if the model can't load so callers fall back to ML Kit.
 */
class SceneClassifier(context: Context) : Closeable {

    private val classifier: ImageClassifier? = runCatching {
        ImageClassifier.createFromOptions(
            context,
            ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .build(),
        )
    }.getOrNull()

    val available: Boolean get() = classifier != null

    fun classify(bitmap: Bitmap): List<FrameAnalysisCache.SceneLabel> {
        val c = classifier ?: return emptyList()
        return runCatching {
            c.classify(BitmapImageBuilder(bitmap).build())
                .classificationResult()
                .classifications()
                .flatMap { it.categories() }
                .mapNotNull { cat ->
                    val name = cat.categoryName()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    FrameAnalysisCache.SceneLabel(name, name.lowercase(), cat.score())
                }
        }.getOrDefault(emptyList())
    }

    override fun close() {
        runCatching { classifier?.close() }
    }

    private companion object {
        const val MODEL_ASSET = "efficientnet_lite0.tflite"
        const val SCORE_THRESHOLD = 0.15f
        const val MAX_RESULTS = 15
    }
}
