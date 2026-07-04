package com.hereliesaz.guillotine.mcp

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.Analysis
import com.hereliesaz.guillotine.ai.MlKitProvider
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.newId
import com.hereliesaz.guillotine.operation.OperationController
import com.hereliesaz.guillotine.operation.OperationKind
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP tool and resource implementations. Reads from / writes to the [vm] ViewModel.
 * Executed on NanoHTTPD's IO thread; ViewModel updates are thread-safe.
 */
class McpTools(
    private val context: Context,
    private val vm: EditorViewModel,
    private val settingsProvider: () -> AiSettings,
) {

    // ---- tool definitions ---------------------------------------------------

    fun definitions(): JSONArray = JSONArray().apply {
        put(toolDefinition("get_timeline", "Get the current timeline state: all clips, tracks, and timing.",
            emptySchema()))
        put(toolDefinition("get_clip", "Get details for a specific clip by ID.",
            objSchema("clip_id" to stringProp("The clip ID"), required = listOf("clip_id"))))
        put(toolDefinition("set_prompt", "Set the AI analysis prompt for a clip.",
            objSchema(
                "clip_id" to stringProp(), "prompt" to stringProp(),
                required = listOf("clip_id", "prompt"),
            )))
        put(toolDefinition("analyze_clip",
            "Run on-device vision on a clip using its current prompt AND cut it for real: matching ranges " +
                "are found, then the clip is split into its kept pieces and the removed ranges are deleted " +
                "with the timeline closing up (no black gaps). For \"cut/remove the frames with X\" the " +
                "matched ranges are removed; for \"keep only X\" the non-matching ranges are removed. Use " +
                "remove_object_generative instead when the clip must stay the SAME length.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("select_clip", "Select a clip by ID (empty string to clear).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))

        // ---- real timeline edits (the app's actual split/delete/ripple operations) ----
        put(toolDefinition("split_clip", "Split a clip into two at a timeline position (ms).",
            objSchema("clip_id" to stringProp(), "at_ms" to intProp("Timeline position in ms"),
                required = listOf("clip_id", "at_ms"))))
        put(toolDefinition("segment_clip", "Split a clip into separate clips at every keep/remove edit boundary (keeps all pieces).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("delete_clip", "Delete a clip (and its linked audio / group) from the timeline.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("ripple_delete_range", "Cut a timeline span [start_ms, end_ms) out of every track and close the gap.",
            objSchema("start_ms" to intProp(), "end_ms" to intProp(), required = listOf("start_ms", "end_ms"))))
        put(toolDefinition(
            "analyze_clip_with_reference",
            "Like analyze_clip (finds matches AND cuts the clip for real), but uses the clip's CURRENT " +
                "playhead frame as a visual reference to find that specific object across the clip. Use when " +
                "the user points at the current frame (e.g. \"this is my phone\"). Set the clip's prompt to " +
                "the object first.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id")),
        ))
        put(toolDefinition(
            "remove_object_generative",
            "Remove an object by GENERATING replacement frames (cloud, BYO Leonardo key) so the clip stays " +
                "the SAME length: the object's segments become inpainted image clips grouped with the " +
                "original pieces. Use when the user wants the object gone but the video kept natural / the " +
                "same length (NOT cut shorter). Set the clip's prompt to the object first.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id")),
        ))
        put(toolDefinition(
            "describe_current_frame",
            "Get an on-device vision description of what's in the current preview frame (the video clip " +
                "at the playhead). Returns detected objects (label, confidence, pixel bounding box), the " +
                "clip's id, and the source-media timestamp. The raw frame stays on the device — only the " +
                "resulting text goes to you. Use whenever the user references 'this frame', 'what's on " +
                "screen', 'the current thing', or otherwise points at the current preview.",
            emptySchema(),
        ))
        put(toolDefinition(
            "create_user_tool",
            "Create a named editing method the user can invoke later by name. The description should " +
                "be step-by-step instructions for what to do to a clip (using the other tools). If a " +
                "tool with the same name exists it is overwritten.",
            objSchema(
                "name" to stringProp("Short name for the method (e.g. \"comedy zoom\")"),
                "description" to stringProp("Step-by-step instructions the agent should follow when this tool is invoked"),
                required = listOf("name", "description"),
            ),
        ))
        put(toolDefinition(
            "list_user_tools",
            "List all user-defined editing methods (tools). Returns name + description for each.",
            emptySchema(),
        ))
        put(toolDefinition(
            "delete_user_tool",
            "Delete a user-defined editing method by name.",
            objSchema("name" to stringProp("Name of the tool to delete"), required = listOf("name")),
        ))
        put(toolDefinition(
            "run_user_tool",
            "Run a user-defined editing method on a clip. Returns the method's step-by-step " +
                "instructions — execute them using the other tools on the given clip.",
            objSchema(
                "name" to stringProp("Name of the user tool to run"),
                "clip_id" to stringProp("The clip to apply the method to"),
                required = listOf("name", "clip_id"),
            ),
        ))
        put(toolDefinition(
            "start_recording",
            "Start recording the user's editing actions on a clip. While recording, every edit " +
                "operation (split, trim, delete, keyframe, filter change, etc.) is captured. " +
                "Stop with stop_recording to save the recorded actions as a user-defined tool.",
            objSchema("clip_id" to stringProp("The clip to record actions on"), required = listOf("clip_id")),
        ))
        put(toolDefinition(
            "stop_recording",
            "Stop recording and save the captured actions as a user-defined tool. Returns the " +
                "recorded steps so the user can review them. The user can add caveats or " +
                "clarifications via create_user_tool afterward.",
            objSchema(
                "name" to stringProp("Name for the new tool (e.g. \"dramatic zoom cut\")"),
                "extra_instructions" to stringProp("Optional caveats or generalizations to append (e.g. \"adapt timings to clip length\")"),
                required = listOf("name"),
            ),
        ))
        put(toolDefinition(
            "discard_recording",
            "Discard the current recording without saving.",
            emptySchema(),
        ))
        put(toolDefinition(
            "transcribe_clip",
            "Transcribe a clip's audio (on-device Vosk or cloud Whisper) and add timed caption text " +
                "clips to the timeline, grouped with the source clip. Each caption appears and disappears " +
                "in sync with the spoken words.",
            objSchema("clip_id" to stringProp("The clip to transcribe"), required = listOf("clip_id")),
        ))
        put(toolDefinition(
            "animated_transcribe_clip",
            "Transcribe a clip's audio and create ANIMATED per-syllable captions: each word is split " +
                "into syllables placed on separate video tracks so they all appear simultaneously, with " +
                "SCALE keyframes that ramp each syllable from small to large as it is spoken — a " +
                "\"grow as said\" kinetic typography effect. Use when the user asks for animated, " +
                "kinetic, per-word, or per-syllable text/captions.",
            objSchema("clip_id" to stringProp("The clip to transcribe"), required = listOf("clip_id")),
        ))
    }

    // ---- tool dispatch ------------------------------------------------------

    fun call(name: String, args: JSONObject): JSONObject = when (name) {
        "get_timeline" -> getTimeline()
        "get_clip" -> getClip(args.getString("clip_id"))
        "set_prompt" -> setPrompt(args.getString("clip_id"), args.getString("prompt"))
        "analyze_clip" -> analyzeClip(args.getString("clip_id"))
        "select_clip" -> selectClip(args.getString("clip_id"))
        "split_clip" -> splitClipTool(args.getString("clip_id"), args.getLong("at_ms"))
        "segment_clip" -> segmentClipTool(args.getString("clip_id"))
        "delete_clip" -> deleteClipTool(args.getString("clip_id"))
        "ripple_delete_range" -> rippleDeleteRangeTool(args.getLong("start_ms"), args.getLong("end_ms"))
        "analyze_clip_with_reference" -> analyzeClipWithReference(args.getString("clip_id"))
        "remove_object_generative" -> removeObjectGenerative(args.getString("clip_id"))
        "describe_current_frame" -> describeCurrentFrame()
        "transcribe_clip" -> transcribeClip(args.getString("clip_id"))
        "animated_transcribe_clip" -> animatedTranscribeClip(args.getString("clip_id"))
        "create_user_tool" -> createUserTool(args.getString("name"), args.getString("description"))
        "list_user_tools" -> listUserTools()
        "delete_user_tool" -> deleteUserTool(args.getString("name"))
        "run_user_tool" -> runUserTool(args.getString("name"), args.getString("clip_id"))
        "start_recording" -> startRecording(args.getString("clip_id"))
        "stop_recording" -> stopRecording(args.getString("name"), args.optString("extra_instructions", ""))
        "discard_recording" -> discardRecording()
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    // ---- resource definitions -----------------------------------------------

    fun resourceDefinitions(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("uri", "guillotine://timeline"); put("name", "Timeline")
            put("description", "Current editor timeline state"); put("mimeType", "application/json")
        })
        put(JSONObject().apply {
            put("uri", "guillotine://clips"); put("name", "Clips")
            put("description", "List of all clips"); put("mimeType", "application/json")
        })
    }

    fun readResource(uri: String): JSONObject = when (uri) {
        "guillotine://timeline" -> getTimeline()
        "guillotine://clips" -> JSONObject().apply {
            put("clips", JSONArray().apply { vm.uiState.value.document.clips.forEach { put(clipJson(it)) } })
        }
        else -> throw IllegalArgumentException("Unknown resource: $uri")
    }

    // ---- tool implementations -----------------------------------------------

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
            put(
                "humanSummary",
                "Read timeline: ${doc.clips.size} clip(s), ${msFmt(doc.totalDurationMs)} total, playhead ${msFmt(now)}.",
            )
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
            put(
                "humanSummary",
                buildString {
                    append("Read clip \"${media?.name ?: clip.id.take(6)}\": ${msFmt(clip.durationMs)}")
                    if (clip.edits.isNotEmpty()) append(", ${clip.edits.size} edit(s)")
                    if (clip.prompt.isNotBlank()) append(", prompt \"${clip.prompt.take(60)}\"")
                    append(".")
                },
            )
        }
    }

    private fun setPrompt(clipId: String, prompt: String): JSONObject {
        vm.updateClip(clipId) { it.copy(prompt = prompt) }
        return JSONObject().apply {
            put("ok", true); put("clipId", clipId); put("prompt", prompt)
            put("humanSummary", "Set clip prompt to \"${prompt.take(80)}\".")
        }
    }

    private fun analyzeClip(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip)
            ?: throw IllegalArgumentException("No media for clip: $clipId")
        require(clip.prompt.isNotBlank()) { "Clip has no prompt. Use set_prompt first." }
        val edits = runBlocking {
            Analysis.run(context, settingsProvider(), Uri.parse(media.uri), media.kind, clip.prompt, clip.durationMs)
        }
        return analyzeResult(clipId, edits)
    }

    /**
     * Apply an analysis result as a REAL cut — split the clip into its kept pieces and delete the matched
     * (removed) ranges, rippling the timeline closed, exactly like the user doing it by hand with the
     * scissors + trash. The ranges are passed straight into [EditorViewModel.applyCuts] so the split +
     * delete is one atomic step that NEVER leaves "scripting" REMOVE marks on the timeline (which only
     * skipped/blacked the frames instead of removing them). The AI has no mark-only path — this is it.
     */
    private fun analyzeResult(clipId: String, edits: List<EditSegment>): JSONObject {
        val cutApplied = edits.any { it.action == EditAction.REMOVE }
        val removedMs = edits.filter { it.action == EditAction.REMOVE }.sumOf { it.endMs - it.startMs }
        val keptCount = edits.count { it.action == EditAction.KEEP }
        val removedCount = edits.count { it.action == EditAction.REMOVE }
        if (cutApplied) vm.applyCuts(clipId, edits)
        val newClipCount = vm.uiState.value.document.clips.size
        val newTotal = vm.uiState.value.document.totalDurationMs
        return JSONObject().apply {
            put("ok", true); put("clipId", clipId); put("segmentsFound", edits.size)
            put("segments", segmentsJson(edits))
            put("cutApplied", cutApplied)
            put("clipCount", newClipCount)
            put("totalDurationMs", newTotal)
            put(
                "humanSummary",
                when {
                    edits.isEmpty() -> "No frames matched — nothing to cut."
                    !cutApplied -> "Matched $keptCount kept range(s); no cuts needed."
                    else ->
                        "Cut $removedCount range(s) (${msFmt(removedMs)}), kept $keptCount. " +
                            "Timeline now $newClipCount clip(s), ${msFmt(newTotal)} total."
                },
            )
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
        return JSONObject().apply { put("ok", true); put("humanSummary", summary) }
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
            put("humanSummary", "Segmented clip at every edit boundary (all pieces kept). Timeline now $n clip(s).")
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
            put(
                "humanSummary",
                "Rippled out ${msFmt(startMs)}–${msFmt(endMs)} (${msFmt(endMs - startMs)}) across all tracks. " +
                    "Timeline now ${msFmt(total)}.",
            )
        }
    }

    private fun analyzeClipWithReference(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip)
            ?: throw IllegalArgumentException("No media for clip: $clipId")
        require(clip.prompt.isNotBlank()) { "Set the clip's prompt to the target object first (use set_prompt)." }
        // The frame the user scrubbed to: timeline playhead -> this clip's source time.
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath
            .sourceTimeMs(clip, vm.uiState.value.currentTimeMs).coerceAtLeast(0)
        val reference = grabFrame(Uri.parse(media.uri), sourceMs)
            ?: throw IllegalStateException("Could not read the current frame for reference matching.")
        val edits = runBlocking {
            MlKitProvider().analyzeWithReference(
                context, Uri.parse(media.uri), media.kind, clip.prompt, clip.durationMs, reference,
            )
        }
        reference.recycle()
        return analyzeResult(clipId, edits)
    }

    private fun removeObjectGenerative(clipId: String): JSONObject {
        val settings = settingsProvider()
        val key = settings.leonardoKey
        require(key.isNotBlank()) { "Add your Leonardo API key in Settings to generate replacements." }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip)
            ?: throw IllegalArgumentException("No media for clip: $clipId")
        require(clip.prompt.isNotBlank()) { "Set the clip's prompt to the object to remove first (use set_prompt)." }

        // Run in the background via the foreground service: a progress notification, pausable, cancellable.
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Removing ${clip.prompt}…", pausable = true,
        ) { sink ->
            // 1. Object's segments, on-device (the REMOVE ranges), via the normal on-device analyzer.
            // Force a REMOVE intent ("remove <object>") so the analyzer marks the object-PRESENT
            // ranges as REMOVE. Passing the bare object name is parsed as a "keep only <object>"
            // intent, which inverts it — the object's ranges become KEEP and this filter would then
            // pick the object-absent gaps, so detection below never finds the object to inpaint.
            val removes = runBlocking {
                Analysis.run(
                    context, settings, Uri.parse(media.uri), media.kind, "remove ${clip.prompt}", clip.durationMs,
                    checkpoint = sink::checkpointBlocking,
                )
            }.filter { it.action == EditAction.REMOVE }
            if (removes.isEmpty()) {
                JSONObject().apply {
                    put("ok", true); put("replaced", 0); put("note", "No matching object to remove.")
                    put("humanSummary", "No \"${clip.prompt}\" found in the clip — nothing generated.")
                }
            } else {
                // 2. For each segment: on-device mask from a representative frame -> cloud inpaint -> media.
                // Hoist ObjectVision out of the segment loop — before, we `new`d + `use`d it per
                // segment, reloading the EfficientDet .tflite model each time. Once per run is
                // enough; the model owns no per-segment state.
                val replacements = com.hereliesaz.guillotine.ai.ObjectVision(context).use { ov ->
                    runBlocking {
                        removes.mapIndexedNotNull { idx, seg ->
                            sink.checkpointBlocking()
                            sink.report(idx.toFloat() / removes.size, "Removing ${clip.prompt}… ${idx + 1}/${removes.size}")
                            val frame = grabFrame(Uri.parse(media.uri), (seg.startMs + seg.endMs) / 2)
                                ?: return@mapIndexedNotNull null
                            try {
                                val boxes = ov.detect(frame).filter { matchesPrompt(clip.prompt, it.label) }.map { it.box }
                                if (boxes.isEmpty()) return@mapIndexedNotNull null
                            val mask = com.hereliesaz.guillotine.ai.InpaintMask.fromBoxes(frame.width, frame.height, boxes)
                            try {
                                val uri = runCatching {
                                    com.hereliesaz.guillotine.ai.ImageGen.Leonardo.inpaint(
                                        context, key, settings.leonardoModel, frame, mask,
                                        "remove the ${clip.prompt}, clean natural background, photorealistic",
                                    )
                                }.getOrNull()
                                uri?.let {
                                    val relStart = (seg.startMs - clip.trimStartMs).coerceIn(0, clip.durationMs)
                                    val relEnd = (seg.endMs - clip.trimStartMs).coerceIn(0, clip.durationMs)
                                    EditorViewModel.Replacement(
                                        relStart, relEnd,
                                        MediaItem(newId(), it.toString(), "inpaint", MediaKind.IMAGE, relEnd - relStart),
                                    )
                                }
                            } finally {
                                mask.recycle()
                            }
                        } finally {
                            frame.recycle()
                        }
                    }
                    }
                }
                if (replacements.isEmpty()) throw IllegalStateException("Generation produced no usable replacements.")
                vm.replaceSegmentsWithGenerated(clipId, replacements)
                val replacedMs = replacements.sumOf { (it.relEndMs - it.relStartMs).coerceAtLeast(0L) }
                JSONObject().apply {
                    put("ok", true); put("replaced", replacements.size)
                    put("clipCount", vm.uiState.value.document.clips.size)
                    put("totalDurationMs", vm.uiState.value.document.totalDurationMs)
                    put(
                        "humanSummary",
                        "Erased \"${clip.prompt}\" from ${replacements.size} segment(s) (${msFmt(replacedMs)}) " +
                            "with inpainted replacements — clip length unchanged.",
                    )
                }
            }
        }
    }

    private fun matchesPrompt(prompt: String, label: String): Boolean {
        val p = prompt.lowercase()
        return label in p || p.contains(label) ||
            label.split(" ").any { it.length > 2 && p.contains(it) } ||
            p.split(" ").any { it.length > 2 && label.contains(it) }
    }

    /**
     * On-device vision on the video clip at the playhead. The raw bitmap is decoded, run through
     * [ObjectVision] (COCO-labelled detection with bounding boxes), and recycled here — only the
     * resulting text descriptions leave this method, so the AI can "reference" the current preview
     * frame (know what's in it, where it is on screen) without any pixel data going to the cloud.
     */
    private fun describeCurrentFrame(): JSONObject {
        val st = vm.uiState.value
        val now = st.currentTimeMs
        val clip = com.hereliesaz.guillotine.model.TimelineMath.activeClip(
            st.document.clips,
            com.hereliesaz.guillotine.model.ClipType.VIDEO,
            now,
        ) ?: return JSONObject().put("error", "No video clip at the current playhead.")
        val media = st.document.mediaFor(clip)
            ?: return JSONObject().put("error", "Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = grabFrame(Uri.parse(media.uri), sourceMs)
            ?: return JSONObject().put("error", "Could not extract the current preview frame.")
        try {
            val detections = com.hereliesaz.guillotine.ai.ObjectVision(context).use { ov -> ov.detect(frame) }
            val sorted = detections.sortedByDescending { it.score }
            val topDesc = sorted.take(3).joinToString(", ") { "${it.label} (${(it.score * 100).toInt()}%)" }
            return JSONObject().apply {
                put("clipId", clip.id)
                put("timelineMs", now)
                put("sourceMs", sourceMs)
                put("frameWidth", frame.width)
                put("frameHeight", frame.height)
                put("objects", JSONArray().apply {
                    sorted.forEach { d ->
                        put(JSONObject().apply {
                            put("label", d.label)
                            put("confidence", (d.score * 100).toInt())
                            put("box", JSONObject().apply {
                                put("x", d.box.left.toInt())
                                put("y", d.box.top.toInt())
                                put("width", d.box.width().toInt())
                                put("height", d.box.height().toInt())
                            })
                        })
                    }
                })
                put(
                    "humanSummary",
                    if (sorted.isEmpty()) "Scanned the current frame — no recognisable objects."
                    else "Scanned the current frame — ${sorted.size} object(s): $topDesc" +
                        if (sorted.size > 3) ", …" else ".",
                )
            }
        } finally {
            runCatching { frame.recycle() }
        }
    }

    private fun createUserTool(name: String, description: String): JSONObject {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        require(description.isNotBlank()) { "Tool description must not be blank." }
        val tool = com.hereliesaz.guillotine.data.UserTool(
            id = com.hereliesaz.guillotine.model.newId(),
            name = name.trim(),
            description = description.trim(),
        )
        com.hereliesaz.guillotine.data.UserToolStore.add(context, tool)
        return ok().apply {
            put("name", tool.name)
            put("humanSummary", "Saved editing method \"${tool.name}\" — invoke it with run_user_tool.")
        }
    }

    private fun listUserTools(): JSONObject {
        val tools = com.hereliesaz.guillotine.data.UserToolStore.load(context)
        return JSONObject().apply {
            put("tools", JSONArray().apply {
                tools.forEach { t ->
                    put(JSONObject().apply { put("name", t.name); put("description", t.description) })
                }
            })
            put("count", tools.size)
            put(
                "humanSummary",
                if (tools.isEmpty()) "No user-defined tools saved yet."
                else "Found ${tools.size} user tool(s): ${tools.joinToString { "\"${it.name}\"" }}.",
            )
        }
    }

    private fun deleteUserTool(name: String): JSONObject {
        com.hereliesaz.guillotine.data.UserToolStore.remove(context, name.trim())
        return ok().apply { put("humanSummary", "Deleted user tool \"$name\".") }
    }

    private fun runUserTool(name: String, clipId: String): JSONObject {
        val tools = com.hereliesaz.guillotine.data.UserToolStore.load(context)
        val tool = tools.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("No user tool named \"$name\". Use list_user_tools to see available ones.")
        val doc = vm.uiState.value.document
        doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        return JSONObject().apply {
            put("ok", true)
            put("clipId", clipId)
            put("toolName", tool.name)
            put("instructions", tool.description)
            put(
                "humanSummary",
                "Running \"${tool.name}\" on clip — follow the instructions using the other tools.",
            )
        }
    }

    private fun startRecording(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        doc.clips.firstOrNull { it.id == clipId }
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
        val tool = com.hereliesaz.guillotine.data.UserTool(
            id = com.hereliesaz.guillotine.model.newId(),
            name = name.trim(),
            description = fullDesc,
        )
        com.hereliesaz.guillotine.data.UserToolStore.add(context, tool)
        return JSONObject().apply {
            put("ok", true)
            put("name", tool.name)
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

    private fun transcribeClip(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip)
            ?: throw IllegalArgumentException("No media for clip: $clipId")
        val cues = runBlocking {
            com.hereliesaz.guillotine.ai.Transcription.transcribe(
                context, settingsProvider(), Uri.parse(media.uri),
            )
        }
        if (cues.isEmpty()) return ok().apply {
            put("captions", 0)
            put("humanSummary", "Transcribed clip — no speech detected.")
        }
        vm.addTextClipsFromTranscript(clipId, cues)
        val n = vm.uiState.value.document.clips.count { it.type == com.hereliesaz.guillotine.model.ClipType.TEXT }
        return ok().apply {
            put("captions", cues.size)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Transcribed and added ${cues.size} caption(s) ($n text clips total).")
        }
    }

    private fun animatedTranscribeClip(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip)
            ?: throw IllegalArgumentException("No media for clip: $clipId")
        val cues = runBlocking {
            com.hereliesaz.guillotine.ai.Transcription.transcribe(
                context, settingsProvider(), Uri.parse(media.uri),
            )
        }
        val wordCues = cues.flatMap { it.words }
        if (wordCues.isEmpty()) return ok().apply {
            put("words", 0)
            put("humanSummary", "Transcribed clip — no per-word timing available for animation.")
        }
        vm.addAnimatedCaptionsFromTranscript(clipId, wordCues)
        val n = vm.uiState.value.document.clips.count { it.type == com.hereliesaz.guillotine.model.ClipType.TEXT }
        val tracks = vm.uiState.value.document.videoTracks.size
        return ok().apply {
            put("words", wordCues.size)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("videoTracks", tracks)
            put(
                "humanSummary",
                "Created animated captions for ${wordCues.size} word(s) — syllables on $tracks video track(s) " +
                    "with per-syllable scale keyframes ($n text clips total).",
            )
        }
    }

    private fun grabFrame(uri: Uri, atMs: Long): android.graphics.Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.getFrameAtTime(atMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun ok() = JSONObject().put("ok", true)

    /** Human duration: seconds under a minute (`4.3s`), M:SS otherwise (`1:04`). Used in humanSummary. */
    private fun msFmt(ms: Long): String {
        val abs = ms.coerceAtLeast(0L)
        return if (abs < 60_000L) String.format(java.util.Locale.US, "%.1fs", abs / 1000.0)
        else String.format(java.util.Locale.US, "%d:%02d", abs / 60_000L, (abs % 60_000L) / 1000L)
    }

    // ---- helpers -------------------------------------------------------------

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
