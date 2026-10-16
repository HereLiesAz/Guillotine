package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Small reusable wrapper over `ai.onnxruntime` for the desktop (JVM) build — the on-device inference
 * foundation shared by desktop ML tools, mirroring how `:app`'s [com.hereliesaz.guillotine.ai.Spleeter]
 * drives ORT (shared [OrtEnvironment], `createTensor(env, FloatBuffer, shape)`, run over
 * `inputNames`, read the output's `floatBuffer`). Sessions are heavy to build, so they're cached by
 * model path and reused across repeated tool calls; a model file replaced on disk is reloaded. Pure JVM; nothing leaves the machine.
 */
object DesktopOnnx {

    /** Single process-wide ORT environment, created lazily on first use. */
    val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** A cached session plus the model file's identity (mtime + size) when it was loaded. */
    private class Entry(val session: OrtSession, val stamp: Pair<Long, Long>)

    // Model path -> loaded session. Guarded for thread-safe get-or-create so concurrent tool calls
    // for the same model don't each build (and leak) a session.
    private val sessions = ConcurrentHashMap<String, Entry>()
    private val lock = Any()

    // Sessions replaced because their model file changed are closed after a grace period instead of
    // immediately, so an inference already running on one isn't pulled out from under it.
    private val retirer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "onnx-retire").apply { isDaemon = true }
    }
    private const val RETIRE_DELAY_SECONDS = 30L

    private fun stampOf(path: String): Pair<Long, Long> {
        val f = java.io.File(path)
        return f.lastModified() to f.length()
    }

    /**
     * The [OrtSession] for the model at [modelPath], loading and caching it on first request and
     * returning the cached instance on later calls — unless the file was replaced on disk (mtime or
     * size changed), in which case the stale session is retired and the new model loaded. Thread-safe.
     */
    fun session(modelPath: String, options: OrtSession.SessionOptions? = null): OrtSession {
        val stamp = stampOf(modelPath)
        sessions[modelPath]?.let { if (it.stamp == stamp) return it.session }
        synchronized(lock) {
            sessions[modelPath]?.let { existing ->
                if (existing.stamp == stamp) return existing.session
                sessions.remove(modelPath)
                retirer.schedule(Runnable { runCatching { existing.session.close() } }, RETIRE_DELAY_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            }
            val opts = options ?: OrtSession.SessionOptions()
            try {
                val created = environment.createSession(modelPath, opts)
                sessions[modelPath] = Entry(created, stamp)
                return created
            } finally {
                // SessionOptions holds native memory and is only needed during session init; close
                // one we allocated here. A caller-supplied options object is theirs to close.
                if (options == null) opts.close()
            }
        }
    }

    /**
     * Run a single-float-tensor-in / single-float-tensor-out model — the common case. Feeds [data]
     * (shaped [shape]) as [inputName] (defaults to the model's first declared input) and returns the
     * first output flattened to a [FloatArray]. Input tensor and result are closed before returning.
     */
    fun runSingle(
        session: OrtSession,
        data: FloatArray,
        shape: LongArray,
        inputName: String = session.inputNames.first(),
    ): FloatArray {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                return firstFloatOutput(result)
            }
        }
    }

    /**
     * Run a model with arbitrary named [inputs] (multi-input models). Returns the raw
     * [OrtSession.Result] — the CALLER owns it and must `close()` it (use `.use { }`), since it may
     * carry several outputs the caller needs to read. The [inputs] tensors are NOT closed here; the
     * caller closes those it created.
     */
    fun run(session: OrtSession, inputs: Map<String, OnnxTensor>): OrtSession.Result =
        session.run(inputs)

    /** Wrap [data]/[shape] as a float [OnnxTensor] on the shared environment. Caller closes it. */
    fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape)

    /** Flatten the first output of [result] to a [FloatArray]. */
    fun firstFloatOutput(result: OrtSession.Result): FloatArray {
        val out = result[0] as OnnxTensor
        val fb = out.floatBuffer
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** Close and evict the cached session for [modelPath] (if any). */
    fun evict(modelPath: String) {
        synchronized(lock) {
            sessions.remove(modelPath)?.let { runCatching { it.session.close() } }
        }
    }

    /** Close and evict every cached session. The shared environment is left intact for reuse. */
    fun close() {
        synchronized(lock) {
            sessions.values.forEach { runCatching { it.session.close() } }
            sessions.clear()
        }
    }
}
