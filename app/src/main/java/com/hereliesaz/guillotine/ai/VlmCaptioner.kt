package com.hereliesaz.guillotine.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

/**
 * On-device vision-language captioning via MediaPipe `LlmInference` with the **vision modality** enabled
 * (a multimodal `.task`, e.g. Gemma-3n). This runs as a SEPARATE `LlmInference` instance from the text
 * assistant — the text `.task` path is byte-for-byte untouched; vision only activates here, through the
 * additive `GraphOptions`/`addImage` session API. On-device only — pixels never leave the device.
 */
object VlmCaptioner {

    private var path: String? = null
    private var instance: LlmInference? = null

    /** Load (or reuse) the vision-enabled engine for [modelPath]; switching paths closes the previous. */
    @Synchronized
    private fun engine(context: Context, modelPath: String): LlmInference {
        instance?.let { if (path == modelPath) return it }
        runCatching { instance?.close() }
        instance = LlmInference.createFromOptions(
            context.applicationContext,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setMaxNumImages(1)
                .build(),
        )
        path = modelPath
        return instance!!
    }

    /**
     * Answer [prompt] about [frame] using the multimodal model at [modelPath]. A fresh session is opened
     * per call (so images don't accumulate) with vision enabled; returns the model's response text.
     */
    @Synchronized
    fun describe(context: Context, modelPath: String, frame: Bitmap, prompt: String): String {
        val llm = engine(context, modelPath)
        val session = LlmInferenceSession.createFromOptions(
            llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(10)
                .setTemperature(0.4f)
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build(),
        )
        return try {
            session.addQueryChunk(prompt)
            session.addImage(BitmapImageBuilder(frame).build())
            session.generateResponse().orEmpty().trim()
        } finally {
            runCatching { session.close() }
        }
    }
}
