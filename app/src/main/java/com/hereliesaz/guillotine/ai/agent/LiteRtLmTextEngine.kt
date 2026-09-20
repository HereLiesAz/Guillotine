package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Text-only adapter for modern `.litertlm` assistant models.
 *
 * MediaPipe LlmInference remains in place for legacy `.task` models and its vision session API.
 * Newer community models target LiteRT-LM directly, so forcing them through the deprecated MediaPipe
 * runtime leaves performance/features on the table and can reject models that require newer metadata.
 *
 * One engine is cached across turns. We try GPU first (the preferred mobile path for the current
 * quantized catalog) and transparently fall back to CPU on devices/drivers where OpenCL isn't usable.
 */
object LiteRtLmTextEngine {
    private val mutex = Mutex()
    private var path: String? = null
    private var engine: Engine? = null
    private var backendLabel: String = ""

    suspend fun prepare(context: Context, modelPath: String): String = mutex.withLock {
        ensureEngineLocked(context.applicationContext, modelPath)
        backendLabel
    }

    suspend fun generate(context: Context, modelPath: String, prompt: String): String =
        mutex.withLock {
            val active = ensureEngineLocked(context.applicationContext, modelPath)
            val out = StringBuilder()
            active.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).collect { chunk -> out.append(chunk) }
            }
            out.toString().trim()
        }

    private suspend fun ensureEngineLocked(context: Context, modelPath: String): Engine {
        engine?.let { if (path == modelPath) return it }

        runCatching { engine?.close() }
        engine = null
        path = null
        backendLabel = ""

        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val gpuAttempt = runCatching {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    maxNumTokens = 1024,
                    cacheDir = context.cacheDir.absolutePath,
                ),
            ).also { candidate ->
                try {
                    candidate.initialize()
                } catch (t: Throwable) {
                    runCatching { candidate.close() }
                    throw t
                }
            }
        }

        val chosen = gpuAttempt.getOrElse {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(
                        threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
                    ),
                    maxNumTokens = 1024,
                    cacheDir = context.cacheDir.absolutePath,
                ),
            ).also { candidate ->
                try {
                    candidate.initialize()
                } catch (t: Throwable) {
                    runCatching { candidate.close() }
                    throw t
                }
            }
        }

        engine = chosen
        path = modelPath
        backendLabel = if (gpuAttempt.isSuccess) "LiteRT-LM GPU" else "LiteRT-LM CPU"
        return chosen
    }
}
