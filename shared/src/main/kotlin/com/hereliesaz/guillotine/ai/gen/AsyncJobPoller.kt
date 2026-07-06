package com.hereliesaz.guillotine.ai.gen

import kotlinx.coroutines.delay

/** A generation request, provider-agnostic. Backends read what they need. */
data class GenRequest(
    val kind: GenKind,
    val provider: GenProviderType,
    val apiKey: String,
    val model: String,
    val prompt: String,
    /** Image pixel size (also used as a hint for some video providers). */
    val widthPx: Int = 1280,
    val heightPx: Int = 720,
    /** Requested length for video/music, in seconds. */
    val durationSec: Int = 5,
    /** Provider-specific extras (e.g. a wrapper base URL, aspect ratio, negative prompt). */
    val extra: Map<String, String> = emptyMap(),
)

/** One poll of an in-flight generation job. */
sealed interface JobStatus {
    /** Still running; [progress] is 0..1 when the provider reports it, else null. */
    data class Running(val progress: Float? = null) : JobStatus
    /**
     * Finished. [result] is either a remote URL to download or an already-local `file://` uri
     * (byte-returning backends save via [GenSink] and hand back the local uri).
     */
    data class Done(val result: String) : JobStatus
    data class Failed(val message: String) : JobStatus
}

data class PollConfig(
    val maxAttempts: Int = 120,
    val intervalMs: Long = 2_000,
    val timeoutMessage: String = "Generation timed out.",
)

/** Thrown on any generation failure so the existing operation/error plumbing can surface it. */
class GenException(message: String) : RuntimeException(message)

/**
 * One provider's generation job. [submit] kicks it off and returns an opaque handle (a job id, an
 * operation name, or — for inline/synchronous providers — a result marker). [poll] inspects that
 * handle. Inline providers do all the work in [submit] and return [JobStatus.Done] on the first
 * [poll]. The backend owns its own request bodies and JSON shapes.
 */
interface GenJob {
    suspend fun submit(): String
    suspend fun poll(handle: String): JobStatus
}

/**
 * Platform bridge for turning a generation result into a local file. Implemented in `:app`
 * (cache dir) and `:desktop` (temp dir). Kept out of the backends so they stay platform-free.
 */
interface GenSink {
    /** Download [url] and return a local `file://` uri. [ext] is the file extension without a dot. */
    suspend fun saveUrl(url: String, ext: String): String
    /** Write [bytes] to a local file and return its `file://` uri. */
    suspend fun saveBytes(bytes: ByteArray, ext: String): String
}

/**
 * Generalizes Leonardo's `repeat(90){ delay; poll }` loop into one reusable poller. Runs a [GenJob]
 * to completion, forwarding progress and cooperatively checkpointing (so `:app` can bridge pause/
 * cancel via `OperationController`). Returns the result url/uri from [JobStatus.Done], or throws
 * [GenException] on failure/timeout.
 */
object AsyncJobPoller {
    suspend fun run(
        job: GenJob,
        cfg: PollConfig = PollConfig(),
        onProgress: (Float?) -> Unit = {},
        checkpoint: suspend () -> Unit = {},
    ): String {
        val handle = job.submit()
        repeat(cfg.maxAttempts) {
            checkpoint()
            when (val s = job.poll(handle)) {
                is JobStatus.Done -> return s.result
                is JobStatus.Failed -> throw GenException(s.message)
                is JobStatus.Running -> {
                    onProgress(s.progress)
                    delay(cfg.intervalMs)
                }
            }
        }
        throw GenException(cfg.timeoutMessage)
    }
}
