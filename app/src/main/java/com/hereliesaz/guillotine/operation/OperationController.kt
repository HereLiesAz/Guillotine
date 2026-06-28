package com.hereliesaz.guillotine.operation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

enum class OperationKind { ANALYZE, GENERATE, EXPORT }

/** Snapshot of the single in-flight long operation (null when idle). */
data class OperationState(
    val kind: OperationKind,
    val label: String,
    val progress: Float?,   // 0..1, or null = indeterminate
    val paused: Boolean,
    val pausable: Boolean,
)

/**
 * Single source of truth for the one long-running operation at a time (analysis, generative
 * removal, export). It hosts the work on a process-lifetime scope so it survives the Activity /
 * Compose scope, drives the foreground [OperationService] + its progress notification, and gives
 * operations a [Sink] for progress plus cooperative pause/cancel checkpoints (suspend & blocking).
 *
 * Scope chosen earlier with the user: only one operation runs at a time; pause/resume works while
 * the app process is alive (an OS kill drops the op — no cross-restart resume); Export is
 * cancel-only because Media3's Transformer can't pause an encode.
 */
object OperationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<OperationState?>(null)
    val state: StateFlow<OperationState?> = _state.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var cancelled = false
    @Volatile private var job: Job? = null

    val isBusy: Boolean get() = _state.value != null

    fun pause() {
        val s = _state.value ?: return
        if (!s.pausable) return
        paused = true
        _state.value = s.copy(paused = true)
    }

    fun resume() {
        paused = false
        _state.value?.let { _state.value = it.copy(paused = false) }
    }

    fun cancel() {
        cancelled = true
        paused = false
        job?.cancel()
        _state.value?.let { _state.value = it.copy(paused = false) }
    }

    /** Progress reporting + cooperative pause/cancel surface handed to a running operation. */
    class Sink internal constructor() {
        /** Update the notification / UI. [progress] is 0..1 or null for indeterminate. */
        fun report(progress: Float?, label: String? = null) {
            val s = _state.value ?: return
            _state.value = s.copy(progress = progress?.coerceIn(0f, 1f), label = label ?: s.label)
        }

        /** Suspend call sites: park while paused, throw on cancel. */
        suspend fun checkpoint() {
            while (paused && !cancelled) delay(120)
            if (cancelled) throw CancellationException("Operation cancelled")
            coroutineContext.ensureActive()
        }

        /** Blocking call sites (MCP runBlocking / tight frame loops): park while paused, throw on cancel. */
        fun checkpointBlocking() {
            while (paused && !cancelled) Thread.sleep(120)
            if (cancelled) throw CancellationException("Operation cancelled")
        }

        private val paused get() = OperationController.paused
        private val cancelled get() = OperationController.cancelled
        private val _state get() = OperationController._state
    }

    /**
     * Start a long operation on the controller's process scope (survives backgrounding while the
     * app is alive) and show the foreground notification. Returns false if another op is running.
     * Result handling lives inside [block] / [onComplete] / [onError]; they run off the main thread.
     */
    fun start(
        context: Context,
        kind: OperationKind,
        label: String,
        pausable: Boolean,
        onError: (Throwable) -> Unit = {},
        onComplete: () -> Unit = {},
        block: suspend (Sink) -> Unit,
    ): Boolean {
        if (_state.value != null) return false
        begin(context, kind, label, pausable)
        val sink = Sink()
        job = scope.launch {
            try {
                block(sink)
                onComplete()
            } catch (_: CancellationException) {
                // Cancelled by the user — no error surfaced.
            } catch (e: Throwable) {
                onError(e)
            } finally {
                end()
            }
        }
        return true
    }

    /**
     * Blocking variant for synchronous call sites (the MCP server's runBlocking tools). Runs [block]
     * on the calling thread, shows the notification, and supports pause/cancel via
     * [Sink.checkpointBlocking]. Throws if another operation is already running.
     */
    fun <T> runBlocking(
        context: Context,
        kind: OperationKind,
        label: String,
        pausable: Boolean,
        block: (Sink) -> T,
    ): T {
        check(_state.value == null) { "Another operation is already running." }
        begin(context, kind, label, pausable)
        job = null // blocking; cancel via the `cancelled` flag observed in checkpointBlocking
        try {
            return block(Sink())
        } finally {
            end()
        }
    }

    private fun begin(context: Context, kind: OperationKind, label: String, pausable: Boolean) {
        paused = false
        cancelled = false
        _state.value = OperationState(kind, label, progress = null, paused = false, pausable = pausable)
        val app = context.applicationContext
        ContextCompat.startForegroundService(app, Intent(app, OperationService::class.java))
    }

    private fun end() {
        job = null
        paused = false
        cancelled = false
        _state.value = null // OperationService observes null and stops itself
    }
}
