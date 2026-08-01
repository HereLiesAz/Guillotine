package com.hereliesaz.guillotine.azphalt

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Builds the inventory a host sends a store app so its buttons can read *Open* or *Update* instead of
 * *Get* on things the user already has — azphalt `spec/state-reporting.md` § 3.1.
 *
 * Only the host knows this. Acquisition and installation are separate events in this architecture: a
 * store app hands over bytes and has no idea whether they were verified, installed, or thrown away. A
 * store that can't tell "you have this" from "you could have this" is a catalogue, not a store.
 *
 * **What this deliberately does not carry** (§ 2 makes these normative): no device, advertising,
 * install or user identifier; no other application's name; nothing the user made — no file names,
 * paths, or project content. Package id, version, state and a timestamp, and nothing else. Two reports
 * from the same device are not linkable, because there is nothing in them to link.
 */
object AzpStateReport {

    /** Intent extra carrying the inventory JSON on a browse request (`spec/state-reporting.md` § 3.1). */
    const val EXTRA_INVENTORY = "azphalt.extra.INVENTORY"

    /**
     * The spec's Binder-safety caps. An `Intent` crosses a shared, fixed-size transaction buffer, and an
     * oversized extra fails as `TransactionTooLargeException` **in the caller** — us. So the document is
     * bounded before it is ever attached, not hopefully.
     */
    const val MAX_ENTRIES = 500
    const val MAX_BYTES = 256 * 1024

    /** One package's state. Fields are exactly § 1's entry shape. */
    data class Entry(
        val id: String,
        val version: String,
        /** § 1 vocabulary. Guillotine only ever reports `active` — see [stateFor]. */
        val state: String,
        /** ISO-8601 instant the state was entered, or null to omit. */
        val at: String?,
    )

    /**
     * Guillotine's state for an installed package is always `active`.
     *
     * The § 1 vocabulary distinguishes `installed` (present but switched off) from `active` (available
     * right now), and Guillotine has no concept of a disabled extension — anything in the extensions
     * directory is offered to the editor. Reporting `installed` would describe a toggle that doesn't
     * exist. `downloaded` and `failed` exist for hosts that hold bytes they haven't committed to; this
     * install path either lands a package or refuses it, so neither state is ever reached.
     */
    private fun stateFor(): String = "active"

    /**
     * Read the installed packages in [extensionsDir] scoped to [hostAppId] and return § 1 entries,
     * newest-installed first so that truncation at [MAX_ENTRIES] drops the stalest rather than an
     * arbitrary slice. Blocking I/O — call off the main thread. Unreadable packages are skipped.
     */
    fun entries(extensionsDir: File, hostAppId: String): List<Entry> {
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".azp") } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching {
                    val manifest = AzpPackage.read(f.readBytes()).manifest
                    if (!manifest.targetsApp(hostAppId)) return@runCatching null
                    Entry(
                        id = manifest.id,
                        version = manifest.version,
                        state = stateFor(),
                        at = runCatching { Instant.ofEpochMilli(f.lastModified()).toString() }.getOrNull(),
                    )
                }.getOrNull()
            }
            .take(MAX_ENTRIES)
    }

    /** Serialise [entries] as the § 1 document, `{"entries":[…]}`. */
    fun toJson(entries: List<Entry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("version", e.version)
                    put("state", e.state)
                    e.at?.let { put("at", it) }
                },
            )
        }
        return JSONObject().put("entries", arr).toString()
    }

    /**
     * The inventory document for [extensionsDir], or **null** when there is nothing to send.
     *
     * Null rather than an empty document on purpose: a host that reports nothing is explicitly
     * conforming (§ 3.2), and attaching `{"entries":[]}` would spend a Binder budget to say nothing.
     *
     * If the document still exceeds [MAX_BYTES] after the entry cap — a pathological set of very long
     * ids — entries are dropped from the end until it fits, because failing the whole browse request
     * with a `TransactionTooLargeException` would be a far worse outcome than a shorter list.
     */
    fun inventoryJson(extensionsDir: File, hostAppId: String): String? {
        var list = entries(extensionsDir, hostAppId)
        if (list.isEmpty()) return null
        var json = toJson(list)
        while (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES && list.size > 1) {
            list = list.dropLast(1)
            json = toJson(list)
        }
        return if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) null else json
    }
}
