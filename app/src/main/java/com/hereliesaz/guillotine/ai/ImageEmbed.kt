package com.hereliesaz.guillotine.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable

/**
 * On-device image embedder (MediaPipe + bundled MobileNet-V3) for "is this the same object?" matching.
 * Used by reference cuts: embed the crop of the object the user pointed at, then compare it against the
 * same-class detections in every frame by cosine similarity. Loads a model bundled in assets (offline);
 * degrades to "unavailable" if it can't load so callers fall back to plain class matching.
 */
class ImageEmbed(context: Context) : Closeable {

    private val embedder: ImageEmbedder? = runCatching {
        ImageEmbedder.createFromOptions(
            context,
            ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.IMAGE)
                .setL2Normalize(true)
                .setQuantize(false)
                .build(),
        )
    }.getOrNull()

    val available: Boolean get() = embedder != null

    /** Feature embedding of [bitmap]; null on failure. */
    fun embed(bitmap: Bitmap): Embedding? {
        val e = embedder ?: return null
        return runCatching {
            e.embed(BitmapImageBuilder(bitmap).build()).embeddingResult().embeddings().firstOrNull()
        }.getOrNull()
    }

    /** Cosine similarity in [-1, 1] (≈1 = same object); 0 on failure. */
    fun similarity(a: Embedding, b: Embedding): Double =
        runCatching { ImageEmbedder.cosineSimilarity(a, b) }.getOrDefault(0.0)

    override fun close() {
        runCatching { embedder?.close() }
    }

    private companion object {
        const val MODEL_ASSET = "mobilenet_v3_small.tflite"
    }
}
