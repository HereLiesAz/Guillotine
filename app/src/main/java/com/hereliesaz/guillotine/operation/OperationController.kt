package com.hereliesaz.guillotine.operation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Pause the running operation — only if it is pausable. Setting the flag for a non-pausable op
     * (Export) would park a checkpoint the UI offers no Resume for.
     */
    fun pause() {
        _state.update { s ->
            if (s != null && s.pausable) {
                paused = true
                s.copy(paused = true)
            } else {
                s
            }
        }
    }

    fun resume() {
        paused = false
        _state.update { s -> s?.copy(paused = false) }
    }

    fun cancel() {
        cancelled = true
        paused = false
        job?.cancel()
        _state.update { s -> s?.copy(paused = false) }
    }

    /** Progress reporting + cooperative pause/cancel surface handed to a running operation. */
    class Sink internal constructor() {
        /** Update the notification / UI. [progress] is 0..1 or null for indeterminate. */
        fun report(progress: Float?, label: String? = null) {
            _state.update { s -> s?.copy(progress = progress?.coerceIn(0f, 1f), label = label ?: s.label) }
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
     * [block] runs off the main thread; exactly one of [onComplete] / [onError] / [onCancel] then
     * runs on the main thread (so callers can always clear their busy state), followed by
     * [onFinished]. Keep these callbacks free of Activity references where possible — they live as
     * long as the operation does, which can outlive the Activity.
     */
    fun start(
        context: Context,
        kind: OperationKind,
        label: String,
        pausable: Boolean,
        onError: (Throwable) -> Unit = {},
        onCancel: () -> Unit = {},
        onFinished: () -> Unit = {},
        onComplete: () -> Unit = {},
        block: suspend (Sink) -> Unit,
    ): Boolean {
        val app = context.applicationContext
        return startWith(
            startForeground = { startService(app) },
            callbackDispatcher = Dispatchers.Main,
            kind = kind, label = label, pausable = pausable,
            onError = onError, onCancel = onCancel, onFinished = onFinished, onComplete = onComplete,
            block = block,
        )
    }

    /** Context-free core of [start]; [startForeground] and [callbackDispatcher] are injectable for tests. */
    internal fun startWith(
        startForeground: () -> Unit,
        callbackDispatcher: CoroutineDispatcher,
        kind: OperationKind,
        label: String,
        pausable: Boolean,
        onError: (Throwable) -> Unit = {},
        onCancel: () -> Unit = {},
        onFinished: () -> Unit = {},
        onComplete: () -> Unit = {},
        block: suspend (Sink) -> Unit,
    ): Boolean {
        // Atomic busy-check + slot-claim. StateFlow.compareAndSet returns false if the current
        // value isn't null (already claimed) — two near-simultaneous start() calls used to both
        // pass the plain `_state.value != null` check before either called begin(), so both
        // proceeded and clobbered each other's state.
        if (!_state.compareAndSet(expect = null, update = OperationState(kind, label, progress = null, paused = false, pausable = pausable))) {
            return false
        }
        begin(startForeground)
        val sink = Sink()
        job = scope.launch {
            // Terminal callbacks touch Compose/UI state — run them on the main thread. NonCancellable:
            // after job.cancel() a plain withContext would itself throw and the callback would never
            // run, leaving the caller's UI stuck "busy" (the old behaviour).
            val terminal = callbackDispatcher + NonCancellable
            val outcome: () -> Unit = try {
                block(sink)
                onComplete
            } catch (_: CancellationException) {
                onCancel
            } catch (e: Throwable) {
                { onError(e) }
            }
            try {
                withContext(terminal) {
                    try { outcome() } finally { onFinished() }
                }
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
        // Same atomic slot-claim as start(). check() throws if another op already owns the slot.
        check(
            _state.compareAndSet(
                expect = null,
                update = OperationState(kind, label, progress = null, paused = false, pausable = pausable),
            ),
        ) { "Another operation is already running." }
        begin { startService(context.applicationContext) }
        job = null // blocking; cancel via the `cancelled` flag observed in checkpointBlocking
        try {
            return block(Sink())
        } finally {
            end()
        }
    }

    /** Called after the caller claimed the slot via compareAndSet (so the state is already set). */
    private fun begin(startForeground: () -> Unit) {
        paused = false
        cancelled = false
        // Starting a foreground service from the background throws on API 31+ (e.g. an external MCP
        // tool triggering generative removal while the app is backgrounded). Don't crash — the
        // operation still runs, just without the foreground notification.
        runCatching { startForeground() }
    }

    private fun startService(app: Context) {
        ContextCompat.startForegroundService(app, Intent(app, OperationService::class.java))
    }

    private fun end() {
        job = null
        paused = false
        cancelled = false
        _state.value = null // OperationService observes null and stops itself
    }
}
