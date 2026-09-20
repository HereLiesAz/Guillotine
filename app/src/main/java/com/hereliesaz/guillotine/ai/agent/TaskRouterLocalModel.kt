package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dedicated routing role for the bundled tiny LLM.
 *
 * It never edits, never calls MCP tools, and never produces user-facing answers. It only reads a
 * compact capability catalog and returns a small JSON delegation decision. We reuse the bundled
 * 135M weights instead of shipping another 167 MB copy, but this engine/context is isolated from
 * Prompt Coach and the editor brain.
 */
object TaskRouterLocalModel {
    private val mutex = Mutex()

    suspend fun route(context: Context, prompt: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val modelPath = runCatching {
                BundledModelExtractor.ensureExtracted(context.applicationContext)
            }.getOrNull() ?: return@withLock null

            try {
                val llm = LlmInference.createFromOptions(
                    context.applicationContext,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(192)
                        .build(),
                )
                try {
                    llm.generateResponse(prompt.take(7_500))
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
