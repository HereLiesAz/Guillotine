package com.hereliesaz.guillotine.mcp

import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.ClipType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Core timeline-manipulation MCP tools shared by both platforms. Each is a thin wrapper over an existing
 * [EditorViewModel] operation, so the model gains the "verbs" it was missing — moving/positioning clips,
 * seeking the playhead, trimming, adding titles, managing tracks, undo/redo — without any platform-specific
 * logic. Both surfaces append [toolDefinitions] and route matching calls through [call] with their `vm`.
 */
object TimelineTools {

    private fun boolProp(desc: String): JSONObject =
        JSONObject().put("type", "boolean").apply { if (desc.isNotEmpty()) put("description", desc) }

    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } })
            .put("required", JSONArray(required))

    fun toolDefinitions(): JSONArray = JSONArray().apply {
        put(toolDefinition("seek",
            "Move the playhead to a timeline position (ms). Use this to INSPECT or act on a different moment " +
                "— every frame tool (describe_current_frame, caption_frame, apply_image_effect, …) operates at " +
                "the playhead, so seek there first to look around a clip.",
            schema("time_ms" to intProp("Timeline position in milliseconds."), required = listOf("time_ms"))))
        put(toolDefinition("undo", "Undo the last edit.", schema()))
        put(toolDefinition("redo", "Redo the last undone edit.", schema()))
        put(toolDefinition("move_clip",
            "Move a clip to a new start time (ms) and optionally to another track. Repositions on the timeline; " +
                "does not trim.",
            schema(
                "clip_id" to stringProp("The clip to move."),
                "start_ms" to intProp("New start time on the timeline, in ms."),
                "track_id" to stringProp("Target track id (omit to keep the current track)."),
                required = listOf("clip_id", "start_ms"))))
        put(toolDefinition("trim_clip",
            "Trim a clip's edges by a delta in ms (positive shortens, negative lengthens). start_delta_ms moves " +
                "the in-point, end_delta_ms moves the out-point. Length changes; the clip is not moved.",
            schema(
                "clip_id" to stringProp("The clip to trim."),
                "start_delta_ms" to intProp("Shift the in-point by this many ms (default 0)."),
                "end_delta_ms" to intProp("Shift the out-point by this many ms (default 0)."),
                required = listOf("clip_id"))))
        put(toolDefinition("add_text",
            "Add a title/caption TEXT clip with the given words. Defaults to the top video track at the playhead " +
                "for 3s. Use for lower-thirds, titles, captions the user dictates (not transcription).",
            schema(
                "text" to stringProp("The words to show."),
                "track_id" to stringProp("Track to place it on (omit for the top video track)."),
                "start_ms" to intProp("Start time in ms (omit for the current playhead)."),
                "duration_ms" to intProp("Duration in ms (default 3000)."),
                required = listOf("text"))))
        put(toolDefinition("add_track",
            "Add a new empty track. type = video | audio (text lives on video tracks). Returns the new track id.",
            schema("type" to stringProp("video | audio"), required = listOf("type"))))
        put(toolDefinition("set_track",
            "Set a track's settings (absolute). Any omitted field is left unchanged.",
            schema(
                "track_id" to stringProp("The track to change."),
                "muted" to boolProp("Mute/unmute the track's audio."),
                "disabled" to boolProp("Hide/disable the whole track."),
                "volume" to numberProp("Track volume (0..2, 1 = unchanged)."),
                "opacity" to numberProp("Track opacity (0..1)."),
                required = listOf("track_id"))))
        put(toolDefinition("transform_clip",
            "Set a clip's crop/placement transform (absolute): scale (1 = fit), normalized offset from centre " +
                "(fraction of the frame), and rotation in degrees. Omitted fields are unchanged.",
            schema(
                "clip_id" to stringProp("The clip to transform."),
                "scale" to numberProp("Uniform scale (1 = original; >1 punches in)."),
                "offset_x" to numberProp("Horizontal offset from centre, fraction of width (-1..1)."),
                "offset_y" to numberProp("Vertical offset from centre, fraction of height (-1..1)."),
                "rotation" to numberProp("Rotation in degrees (clockwise)."),
                required = listOf("clip_id"))))
    }

    val names: Set<String> = setOf(
        "seek", "undo", "redo", "move_clip", "trim_clip", "add_text", "add_track", "set_track", "transform_clip",
    )

    fun call(vm: EditorViewModel, name: String, args: JSONObject): JSONObject = when (name) {
        "seek" -> {
            val ms = args.getLong("time_ms").coerceAtLeast(0L)
            vm.seekTo(ms)
            ok("Moved the playhead to ${ms}ms.")
        }
        "undo" -> { vm.undo(); ok("Undid the last edit.") }
        "redo" -> { vm.redo(); ok("Redid the last edit.") }
        "move_clip" -> {
            val clip = clipOrThrow(vm, args.getString("clip_id"))
            val startMs = args.getLong("start_ms").coerceAtLeast(0L)
            val track = args.optString("track_id").ifBlank { clip.trackId }
            vm.moveClip(clip.id, track, startMs)
            ok("Moved the clip to ${startMs}ms on $track.")
        }
        "trim_clip" -> {
            val clip = clipOrThrow(vm, args.getString("clip_id"))
            val startDelta = args.optLong("start_delta_ms", 0L)
            val endDelta = args.optLong("end_delta_ms", 0L)
            if (startDelta != 0L) vm.trimClipStart(clip.id, startDelta)
            if (endDelta != 0L) vm.trimClipEnd(clip.id, endDelta)
            ok("Trimmed the clip (start $startDelta ms, end $endDelta ms).")
        }
        "add_text" -> {
            val doc = vm.uiState.value.document
            val track = args.optString("track_id").ifBlank {
                doc.videoTracks.firstOrNull() ?: run { vm.addTrack(ClipType.VIDEO); vm.uiState.value.document.videoTracks.first() }
            }
            val startMs = if (args.has("start_ms")) args.getLong("start_ms").coerceAtLeast(0L) else vm.uiState.value.currentTimeMs
            val durationMs = args.optLong("duration_ms", 3_000L)
            val id = vm.addTextClip(track, args.getString("text"), startMs, durationMs)
            ok("Added a text clip on $track at ${startMs}ms.").put("clipId", id)
        }
        "add_track" -> {
            val type = if (args.getString("type").equals("audio", ignoreCase = true)) ClipType.AUDIO else ClipType.VIDEO
            vm.addTrack(type)
            val doc = vm.uiState.value.document
            val newId = (if (type == ClipType.AUDIO) doc.audioTracks else doc.videoTracks).lastOrNull().orEmpty()
            ok("Added a ${type.name.lowercase()} track.").put("trackId", newId)
        }
        "set_track" -> {
            val trackId = args.getString("track_id")
            vm.updateTrackSettings(trackId) { ts ->
                var out = ts
                if (args.has("muted")) out = out.copy(muted = args.getBoolean("muted"))
                if (args.has("disabled")) out = out.copy(disabled = args.getBoolean("disabled"))
                if (args.has("volume")) out = out.copy(volume = args.getDouble("volume").toFloat())
                if (args.has("opacity")) out = out.copy(opacity = args.getDouble("opacity").toFloat())
                out
            }
            ok("Updated track $trackId.")
        }
        "transform_clip" -> {
            val clip = clipOrThrow(vm, args.getString("clip_id"))
            vm.updateClip(clip.id) { c ->
                c.copy(
                    scale = if (args.has("scale")) args.getDouble("scale").toFloat().coerceAtLeast(0f) else c.scale,
                    offsetX = if (args.has("offset_x")) args.getDouble("offset_x").toFloat() else c.offsetX,
                    offsetY = if (args.has("offset_y")) args.getDouble("offset_y").toFloat() else c.offsetY,
                    rotation = if (args.has("rotation")) args.getDouble("rotation").toFloat() else c.rotation,
                )
            }
            ok("Transformed the clip.")
        }
        else -> throw IllegalArgumentException("Unknown timeline tool: $name")
    }

    private fun clipOrThrow(vm: EditorViewModel, clipId: String) =
        vm.uiState.value.document.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")

    private fun ok(summary: String): JSONObject = JSONObject().put("ok", true).put("humanSummary", summary)
}
