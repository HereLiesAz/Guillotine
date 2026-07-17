package com.hereliesaz.guillotine.editor

import org.json.JSONArray
import org.json.JSONObject

data class RecordedAction(
    val tool: String,
    val params: Map<String, Any>,
    val description: String,
)

class ActionRecorder {
    // Guards all mutable state: MCP tool calls invoke record() on background threads while the UI reads
    // actions()/toJson(), so every access is synchronized to avoid a torn list / ConcurrentModification.
    private val lock = Any()
    private var _recording = false
    private var _targetClipId: String? = null
    private val _actions = mutableListOf<RecordedAction>()

    val isRecording: Boolean get() = synchronized(lock) { _recording }
    val targetClipId: String? get() = synchronized(lock) { _targetClipId }
    val actions: List<RecordedAction> get() = synchronized(lock) { _actions.toList() }

    fun start(clipId: String) = synchronized(lock) {
        _recording = true
        _targetClipId = clipId
        _actions.clear()
    }

    fun stop(): List<RecordedAction> = synchronized(lock) {
        _recording = false
        _actions.toList()
    }

    fun record(action: RecordedAction) = synchronized(lock) {
        if (_recording) _actions.add(action)
        Unit
    }

    fun discard() = synchronized(lock) {
        _recording = false
        _actions.clear()
        _targetClipId = null
    }

    fun toDescription(): String = buildString {
        actions.forEachIndexed { i, action ->
            append("${i + 1}. ${action.description}")
            if (action.params.isNotEmpty()) {
                val relevant = action.params.filter { it.key != "clip_id" }
                if (relevant.isNotEmpty()) {
                    append(" (${relevant.entries.joinToString { "${it.key}=${it.value}" }})")
                }
            }
            appendLine()
        }
    }.trimEnd()

    fun toJson(): JSONArray = JSONArray().apply {
        actions.forEach { action ->
            put(JSONObject().apply {
                put("tool", action.tool)
                put("description", action.description)
                action.params.forEach { (k, v) ->
                    when (v) {
                        is Long -> put(k, v)
                        is Int -> put(k, v)
                        is Double -> put(k, v)
                        is Float -> put(k, v.toDouble())
                        is Boolean -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            })
        }
    }
}
