package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Isolated local completion used by Prompt Coach.
 *
 * It intentionally does NOT share [OnDeviceAgentBackend]'s engine cache: a coach completion may still
 * be finishing when the user presses Send, and closing/swapping the editor brain underneath a native
 * generation would race. The bundled 135M model is opened only for the fallback completion and closed
 * immediately afterwards. A mutex prevents rapid typing pauses from loading multiple copies at once.
 */
object PromptCoachLocalModel {
    private val mutex = Mutex()

    suspend fun complete(context: Context, modelPath: String, prompt: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val llm = LlmInference.createFromOptions(
                        context.applicationContext,
                        LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(modelPath)
                            .setMaxTokens(192)
                            .build(),
                    )
                    try {
                        llm.generateResponse(prompt.take(1_800))
                            .orEmpty()
                            .trim()
                            .takeIf { it.isNotBlank() }
                    } finally {
                        runCatching { llm.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
            }
        }
}
