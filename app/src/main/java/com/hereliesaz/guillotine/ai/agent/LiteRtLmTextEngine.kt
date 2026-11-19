package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex

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

    suspend fun prepare(context: Context, modelPath: String): String {
        mutex.lock()
        return try {
            ensureEngineLocked(context.applicationContext, modelPath)
            backendLabel
        } finally {
            mutex.unlock()
        }
    }

    suspend fun generate(context: Context, modelPath: String, prompt: String): String {
        mutex.lock()
        return try {
            val active = ensureEngineLocked(context.applicationContext, modelPath)
            val out = StringBuilder()
            active.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).collect { chunk -> out.append(chunk) }
            }
            out.toString().trim()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Close the cached engine and free its (large, native) model memory. Non-blocking: if a
     * generation currently holds the engine it is left alone and false is returned — callers are
     * memory-pressure / settings hooks that can't wait, and the next turn reloads lazily anyway.
     * [unlessPath]: keep the engine if it's already loaded for exactly this model.
     */
    fun release(unlessPath: String? = null): Boolean {
        if (!mutex.tryLock()) return false
        try {
            if (engine == null || (unlessPath != null && path == unlessPath)) return true
            runCatching { engine?.close() }
            engine = null
            path = null
            backendLabel = ""
            return true
        } finally {
            mutex.unlock()
        }
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

        // runCatching would also swallow cancellation; let it propagate instead of falling back to CPU.
        gpuAttempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
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
