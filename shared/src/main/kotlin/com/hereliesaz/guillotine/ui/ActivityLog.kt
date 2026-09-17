package com.hereliesaz.guillotine.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide activity feed surfaced in the bottom sheet: the AI assistant's chat output, the
 * currently-running process, its progress, and any errors — one integrated log instead of the
 * several scattered status strips that used to show pieces of it. Any subsystem (the agent,
 * on-device analysis, export) posts here; ActivityLogSheet observes [entries].
 *
 * It is a plain singleton (no DI) because the producers live in different ViewModels / callbacks
 * that don't otherwise share a scope, and the log is inherently app-global.
 */
object ActivityLog {
    enum class Level { USER, CHAT, INFO, PROGRESS, SUCCESS, ERROR }

    data class Entry(
        val id: Long,
        val level: Level,
        val text: String,
        /** Wall-clock time is kept with every line so copied diagnostics preserve when a stall occurred. */
        val timestampMs: Long,
    )

    /**
     * Keep enough history for a useful bug report. Two hundred lines was easy to exhaust during a
     * multi-step AI edit and made the activity sheet look like a partial log rather than the log.
     */
    private const val MAX_ENTRIES = 1_000

    private val ids = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun post(level: Level, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _entries.update { cur ->
            // Drop an exact consecutive duplicate (e.g. a progress stage re-reported) so the
            // feed doesn't stutter, then cap to the newest MAX_ENTRIES.
            val last = cur.lastOrNull()
            if (last != null && last.level == level && last.text == t) return@update cur
            // takeLast returns a fresh, independent list (subList would return a view that
            // pins the parent list alive).
            (cur + Entry(ids.incrementAndGet(), level, t, System.currentTimeMillis()))
                .takeLast(MAX_ENTRIES)
        }
    }

    fun user(text: String) = post(Level.USER, text)
    fun chat(text: String) = post(Level.CHAT, text)
    fun info(text: String) = post(Level.INFO, text)
    fun progress(text: String) = post(Level.PROGRESS, text)
    fun success(text: String) = post(Level.SUCCESS, text)
    fun error(text: String) = post(Level.ERROR, text)

    fun clear() {
        _entries.value = emptyList()
    }
}
