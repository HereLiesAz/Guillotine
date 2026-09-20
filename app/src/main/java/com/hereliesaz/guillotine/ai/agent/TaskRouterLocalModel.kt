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

    suspend fun route(context: Context, prompt: String): String? =
        routeMany(context, listOf(prompt)).firstOrNull()

    /**
     * Route several catalog batches while loading the tiny router weights only once. The full MCP
     * catalog can therefore be covered without either a giant prompt or repeated model startup.
     */
    suspend fun routeMany(context: Context, prompts: List<String>): List<String?> =
        withContext(Dispatchers.IO) {
            if (prompts.isEmpty()) return@withContext emptyList()
            mutex.withLock {
                val modelPath = runCatching {
                    BundledModelExtractor.ensureExtracted(context.applicationContext)
                }.getOrNull() ?: return@withLock List(prompts.size) { null }

                try {
                    val llm = LlmInference.createFromOptions(
                        context.applicationContext,
                        LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(modelPath)
                            .setMaxTokens(192)
                            .build(),
                    )
                    try {
                        prompts.map { prompt ->
                            runCatching {
                                llm.generateResponse(prompt.take(7_500))
                                    .orEmpty()
                                    .trim()
                                    .takeIf { it.isNotBlank() }
                            }.getOrNull()
                        }
                    } finally {
                        runCatching { llm.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    List(prompts.size) { null }
                } catch (_: LinkageError) {
                    List(prompts.size) { null }
                }
            }
        }
}
