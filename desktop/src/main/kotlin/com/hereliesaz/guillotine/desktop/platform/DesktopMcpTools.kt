package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.mcp.intProp
import com.hereliesaz.guillotine.mcp.stringProp
import com.hereliesaz.guillotine.mcp.toolDefinition
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.newId
import org.json.JSONArray
import org.json.JSONObject

class DesktopMcpTools(
    private val vm: EditorViewModel,
) : McpToolsSurface {

    override fun definitions(): JSONArray = JSONArray().apply {
        put(toolDefinition("get_timeline", "Get the current timeline state: all clips, tracks, and timing.", emptySchema()))
        put(toolDefinition("get_clip", "Get details for a specific clip by ID.",
            objSchema("clip_id" to stringProp("The clip ID"), required = listOf("clip_id"))))
        put(toolDefinition("set_prompt", "Set the AI analysis prompt for a clip.",
            objSchema("clip_id" to stringProp(), "prompt" to stringProp(), required = listOf("clip_id", "prompt"))))
        put(toolDefinition("select_clip", "Select a clip by ID (empty string to clear).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("split_clip", "Split a clip into two at a timeline position (ms).",
            objSchema("clip_id" to stringProp(), "at_ms" to intProp("Timeline position in ms"), required = listOf("clip_id", "at_ms"))))
        put(toolDefinition("segment_clip", "Split a clip into separate clips at every keep/remove edit boundary (keeps all pieces).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("delete_clip", "Delete a clip (and its linked audio / group) from the timeline.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("ripple_delete_range", "Cut a timeline span [start_ms, end_ms) out of every track and close the gap.",
            objSchema("start_ms" to intProp(), "end_ms" to intProp(), required = listOf("start_ms", "end_ms"))))

        // Media-dependent tools: defined so the schema is discoverable, but return stubs on desktop.
        put(toolDefinition("analyze_clip", "Run vision analysis on a clip (not yet available on desktop).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("analyze_clip_with_reference", "Analyze using reference frame (not yet available on desktop).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("remove_object_generative", "Remove object with inpainting (not yet available on desktop).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("describe_current_frame", "Describe the current preview frame (not yet available on desktop).", emptySchema()))
        put(toolDefinition("transcribe_clip", "Transcribe a clip's audio (not yet available on desktop).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("animated_transcribe_clip", "Animated per-syllable captions (not yet available on desktop).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))

        // User tools — fully functional on desktop.
        put(toolDefinition("create_user_tool", "Create a named editing method the user can invoke later by name.",
            objSchema("name" to stringProp("Short name"), "description" to stringProp("Step-by-step instructions"), required = listOf("name", "description"))))
        put(toolDefinition("list_user_tools", "List all user-defined editing methods.", emptySchema()))
        put(toolDefinition("delete_user_tool", "Delete a user-defined editing method by name.",
            objSchema("name" to stringProp("Name of the tool to delete"), required = listOf("name"))))
        put(toolDefinition("run_user_tool", "Run a user-defined editing method on a clip.",
            objSchema("name" to stringProp(), "clip_id" to stringProp(), required = listOf("name", "clip_id"))))
        put(toolDefinition("start_recording", "Start recording the user's editing actions on a clip.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("stop_recording", "Stop recording and save as a user-defined tool.",
            objSchema("name" to stringProp(), "extra_instructions" to stringProp(), required = listOf("name"))))
        put(toolDefinition("discard_recording", "Discard the current recording without saving.", emptySchema()))
    }

    override fun call(name: String, args: JSONObject): JSONObject = when (name) {
        "get_timeline" -> getTimeline()
        "get_clip" -> getClip(args.getString("clip_id"))
        "set_prompt" -> setPrompt(args.getString("clip_id"), args.getString("prompt"))
        "select_clip" -> selectClip(args.getString("clip_id"))
        "split_clip" -> splitClipTool(args.getString("clip_id"), args.getLong("at_ms"))
        "segment_clip" -> segmentClipTool(args.getString("clip_id"))
        "delete_clip" -> deleteClipTool(args.getString("clip_id"))
        "ripple_delete_range" -> rippleDeleteRangeTool(args.getLong("start_ms"), args.getLong("end_ms"))
        "analyze_clip", "analyze_clip_with_reference", "remove_object_generative",
        "describe_current_frame", "transcribe_clip", "animated_transcribe_clip" -> notAvailable(name)
        "create_user_tool" -> createUserTool(args.getString("name"), args.getString("description"))
        "list_user_tools" -> listUserTools()
        "delete_user_tool" -> deleteUserTool(args.getString("name"))
        "run_user_tool" -> runUserTool(args.getString("name"), args.getString("clip_id"))
        "start_recording" -> startRecording(args.getString("clip_id"))
        "stop_recording" -> stopRecording(args.getString("name"), args.optString("extra_instructions", ""))
        "discard_recording" -> discardRecording()
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    override fun resourceDefinitions(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("uri", "guillotine://timeline"); put("name", "Timeline")
            put("description", "Current editor timeline state"); put("mimeType", "application/json")
        })
        put(JSONObject().apply {
            put("uri", "guillotine://clips"); put("name", "Clips")
            put("description", "List of all clips"); put("mimeType", "application/json")
        })
    }

    override fun readResource(uri: String): JSONObject = when (uri) {
        "guillotine://timeline" -> getTimeline()
        "guillotine://clips" -> JSONObject().apply {
            put("clips", JSONArray().apply { vm.uiState.value.document.clips.forEach { put(clipJson(it)) } })
        }
        else -> throw IllegalArgumentException("Unknown resource: $uri")
    }

    private fun notAvailable(tool: String) = JSONObject().apply {
        put("error", "$tool is not yet available on desktop (requires media pipeline).")
        put("humanSummary", "$tool is not yet available on desktop.")
    }

    private fun getTimeline(): JSONObject {
        val doc = vm.uiState.value.document
        val now = vm.uiState.value.currentTimeMs
        return JSONObject().apply {
            put("name", doc.name)
            put("totalDurationMs", doc.totalDurationMs)
            put("currentTimeMs", now)
            put("videoTracks", JSONArray(doc.videoTracks))
            put("audioTracks", JSONArray(doc.audioTracks))
            put("clipCount", doc.clips.size)
            put("clips", JSONArray().apply { doc.clips.forEach { put(clipJson(it)) } })
            put("humanSummary", "Read timeline: ${doc.clips.size} clip(s), ${msFmt(doc.totalDurationMs)} total, playhead ${msFmt(now)}.")
        }
    }

    private fun getClip(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip)
        return clipJson(clip).apply {
            if (media != null) {
                put("mediaName", media.name); put("mediaKind", media.kind.name); put("mediaUri", media.uri)
            }
            put("humanSummary", buildString {
                append("Read clip \"${media?.name ?: clip.id.take(6)}\": ${msFmt(clip.durationMs)}")
                if (clip.edits.isNotEmpty()) append(", ${clip.edits.size} edit(s)")
                if (clip.prompt.isNotBlank()) append(", prompt \"${clip.prompt.take(60)}\"")
                append(".")
            })
        }
    }

    private fun setPrompt(clipId: String, prompt: String): JSONObject {
        vm.updateClip(clipId) { it.copy(prompt = prompt) }
        return ok().apply {
            put("clipId", clipId); put("prompt", prompt)
            put("humanSummary", "Set clip prompt to \"${prompt.take(80)}\".")
        }
    }

    private fun selectClip(clipId: String): JSONObject {
        vm.selectClip(clipId.ifBlank { null })
        val summary = if (clipId.isBlank()) "Cleared selection." else run {
            val doc = vm.uiState.value.document
            val cl = doc.clips.firstOrNull { it.id == clipId }
            val name = cl?.let { doc.mediaFor(it)?.name } ?: clipId.take(6)
            "Selected clip \"$name\"."
        }
        return ok().apply { put("humanSummary", summary) }
    }

    private fun splitClipTool(clipId: String, atMs: Long): JSONObject {
        vm.splitClip(clipId, atMs)
        val n = vm.uiState.value.document.clips.size
        return ok().apply {
            put("clipCount", n)
            put("humanSummary", "Split clip at ${msFmt(atMs)}. Timeline now $n clip(s).")
        }
    }

    private fun segmentClipTool(clipId: String): JSONObject {
        vm.segmentClip(clipId)
        val n = vm.uiState.value.document.clips.size
        return ok().apply {
            put("clipCount", n)
            put("humanSummary", "Segmented clip at every edit boundary. Timeline now $n clip(s).")
        }
    }

    private fun deleteClipTool(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val name = doc.clips.firstOrNull { it.id == clipId }?.let { doc.mediaFor(it)?.name } ?: clipId.take(6)
        vm.deleteClip(clipId)
        val n = vm.uiState.value.document.clips.size
        return ok().apply {
            put("clipCount", n)
            put("humanSummary", "Deleted clip \"$name\". $n clip(s) remain.")
        }
    }

    private fun rippleDeleteRangeTool(startMs: Long, endMs: Long): JSONObject {
        vm.rippleDeleteRange(startMs, endMs)
        val total = vm.uiState.value.document.totalDurationMs
        return ok().apply {
            put("totalDurationMs", total)
            put("humanSummary", "Rippled out ${msFmt(startMs)}–${msFmt(endMs)} (${msFmt(endMs - startMs)}) across all tracks. Timeline now ${msFmt(total)}.")
        }
    }

    private fun createUserTool(name: String, description: String): JSONObject {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        require(description.isNotBlank()) { "Tool description must not be blank." }
        val tool = UserTool(id = newId(), name = name.trim(), description = description.trim())
        DesktopUserToolStore.add(tool)
        return ok().apply {
            put("name", tool.name)
            put("humanSummary", "Saved editing method \"${tool.name}\" — invoke it with run_user_tool.")
        }
    }

    private fun listUserTools(): JSONObject {
        val tools = DesktopUserToolStore.load()
        return JSONObject().apply {
            put("tools", JSONArray().apply {
                tools.forEach { t -> put(JSONObject().apply { put("name", t.name); put("description", t.description) }) }
            })
            put("count", tools.size)
            put("humanSummary",
                if (tools.isEmpty()) "No user-defined tools saved yet."
                else "Found ${tools.size} user tool(s): ${tools.joinToString { "\"${it.name}\"" }}.")
        }
    }

    private fun deleteUserTool(name: String): JSONObject {
        DesktopUserToolStore.remove(name.trim())
        return ok().apply { put("humanSummary", "Deleted user tool \"$name\".") }
    }

    private fun runUserTool(name: String, clipId: String): JSONObject {
        val tools = DesktopUserToolStore.load()
        val tool = tools.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("No user tool named \"$name\". Use list_user_tools to see available ones.")
        vm.uiState.value.document.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        return JSONObject().apply {
            put("ok", true); put("clipId", clipId); put("toolName", tool.name)
            put("instructions", tool.description)
            put("humanSummary", "Running \"${tool.name}\" on clip — follow the instructions using the other tools.")
        }
    }

    private fun startRecording(clipId: String): JSONObject {
        vm.uiState.value.document.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        if (vm.actionRecorder.isRecording) throw IllegalStateException("Already recording. Stop or discard first.")
        vm.actionRecorder.start(clipId)
        return ok().apply {
            put("clipId", clipId)
            put("humanSummary", "Recording started — editing actions on this clip will be captured.")
        }
    }

    private fun stopRecording(name: String, extraInstructions: String): JSONObject {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        if (!vm.actionRecorder.isRecording) throw IllegalStateException("Not currently recording.")
        val actions = vm.actionRecorder.stop()
        if (actions.isEmpty()) throw IllegalStateException("No actions were recorded.")
        val autoDesc = vm.actionRecorder.toDescription()
        val fullDesc = if (extraInstructions.isBlank()) autoDesc else "$autoDesc\n\nNotes: $extraInstructions"
        val tool = UserTool(id = newId(), name = name.trim(), description = fullDesc)
        DesktopUserToolStore.add(tool)
        return JSONObject().apply {
            put("ok", true); put("name", tool.name)
            put("stepsRecorded", actions.size)
            put("steps", vm.actionRecorder.toJson())
            put("description", fullDesc)
            put("humanSummary", "Saved \"${tool.name}\" with ${actions.size} recorded step(s).")
        }
    }

    private fun discardRecording(): JSONObject {
        vm.actionRecorder.discard()
        return ok().apply { put("humanSummary", "Recording discarded.") }
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun msFmt(ms: Long): String {
        val abs = ms.coerceAtLeast(0L)
        return if (abs < 60_000L) String.format(java.util.Locale.US, "%.1fs", abs / 1000.0)
        else String.format(java.util.Locale.US, "%d:%02d", abs / 60_000L, (abs % 60_000L) / 1000L)
    }

    private fun clipJson(clip: com.hereliesaz.guillotine.model.TimelineClip) = JSONObject().apply {
        put("id", clip.id); put("type", clip.type.name); put("trackId", clip.trackId)
        put("startTimeMs", clip.startTimeMs); put("trimStartMs", clip.trimStartMs)
        put("durationMs", clip.durationMs); put("prompt", clip.prompt)
        put("isAnalyzing", clip.isAnalyzing); put("editCount", clip.edits.size)
        if (clip.edits.isNotEmpty()) put("edits", segmentsJson(clip.edits))
    }

    private fun segmentsJson(edits: List<EditSegment>) = JSONArray().apply {
        edits.forEach { e ->
            put(JSONObject().apply {
                put("startMs", e.startMs); put("endMs", e.endMs)
                put("action", e.action.name.lowercase()); put("reason", e.reason)
            })
        }
    }

    private fun emptySchema() = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }

    private fun objSchema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
}
