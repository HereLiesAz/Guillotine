package com.hereliesaz.guillotine.ai.vocab

/**
 * Persistence for the generated vocabulary expansion so the LLM pass runs at most once and survives
 * process restarts (instead of re-spending an API call each launch). Platform-implemented: Android writes
 * to `filesDir`, desktop to the app data dir. Both operations must be safe to call off the main thread and
 * must never throw — a missing/unreadable cache simply means "expand fresh".
 */
interface VocabularyCache {
    /** The cached expansion JSON, or null when absent/unreadable. */
    fun load(): String?

    /** Persist the expansion JSON (best-effort; failures are swallowed). */
    fun save(json: String)
}
