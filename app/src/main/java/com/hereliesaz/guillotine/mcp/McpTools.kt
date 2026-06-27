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
        put(toolDefinition("analyze_clip", "Run AI analysis on a clip using its current prompt.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("apply_edits", "Apply keep/remove segments to a clip.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("clip_id", stringProp())
                put("segments", JSONObject().apply {
                    put("type", "array")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("startMs", JSONObject().put("type", "integer"))
                            put("endMs", JSONObject().put("type", "integer"))
                            put("action", JSONObject().apply {
                                put("type", "string"); put("enum", JSONArray().put("keep").put("remove"))
                            })
                            put("reason", JSONObject().put("type", "string"))
                        })
                        // startMs/endMs/action are mandatory per item (applyEdits throws without
                        // them); reason is optional. Declaring this lets clients send valid items.
                        put("required", JSONArray().put("startMs").put("endMs").put("action"))
                    })
                })
            })
            put("required", JSONArray().put("clip_id").put("segments"))
        }))
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
        put(toolDefinition(
            "apply_cuts",
            "Apply a clip's REMOVE edits for real: the kept ranges become separate, grouped clips and the " +
                "removed ranges are deleted with the timeline closing up (no black gaps). Call this after " +
                "analyze_clip/apply_edits to actually cut, instead of only marking.",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id")),
        ))
        put(toolDefinition("ripple_delete_range", "Cut a timeline span [start_ms, end_ms) out of every track and close the gap.",
            objSchema("start_ms" to intProp(), "end_ms" to intProp(), required = listOf("start_ms", "end_ms"))))
        put(toolDefinition(
            "analyze_clip_with_reference",
            "Like analyze_clip, but use the clip's CURRENT playhead frame as a visual reference to find " +
                "that specific object across the clip. Use when the user points at the current frame " +
                "(e.g. \"this is my phone\"). Set the clip's prompt to the object first.",
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
    }

    // ---- tool dispatch ------------------------------------------------------

    fun call(name: String, args: JSONObject): JSONObject = when (name) {
        "get_timeline" -> getTimeline()
        "get_clip" -> getClip(args.getString("clip_id"))
        "set_prompt" -> setPrompt(args.getString("clip_id"), args.getString("prompt"))
        "analyze_clip" -> analyzeClip(args.getString("clip_id"))
        "apply_edits" -> applyEdits(args.getString("clip_id"), args.getJSONArray("segments"))
        "select_clip" -> selectClip(args.getString("clip_id"))
        "split_clip" -> splitClipTool(args.getString("clip_id"), args.getLong("at_ms"))
        "segment_clip" -> segmentClipTool(args.getString("clip_id"))
        "delete_clip" -> deleteClipTool(args.getString("clip_id"))
        "apply_cuts" -> applyCutsTool(args.getString("clip_id"))
        "ripple_delete_range" -> rippleDeleteRangeTool(args.getLong("start_ms"), args.getLong("end_ms"))
        "analyze_clip_with_reference" -> analyzeClipWithReference(args.getString("clip_id"))
        "remove_object_generative" -> removeObjectGenerative(args.getString("clip_id"))
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
        return JSONObject().apply {
            put("name", doc.name)
            put("totalDurationMs", doc.totalDurationMs)
            put("currentTimeMs", vm.uiState.value.currentTimeMs)
            put("videoTracks", JSONArray(doc.videoTracks))
            put("audioTracks", JSONArray(doc.audioTracks))
            put("clipCount", doc.clips.size)
            put("clips", JSONArray().apply { doc.clips.forEach { put(clipJson(it)) } })
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
        }
    }

    private fun setPrompt(clipId: String, prompt: String): JSONObject {
        vm.updateClip(clipId) { it.copy(prompt = prompt) }
        return JSONObject().apply { put("ok", true); put("clipId", clipId); put("prompt", prompt) }
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
        vm.applyEdits(clipId, edits)
        return JSONObject().apply {
            put("ok", true); put("clipId", clipId); put("segmentsFound", edits.size)
            put("segments", segmentsJson(edits))
        }
    }

    private fun applyEdits(clipId: String, segments: JSONArray): JSONObject {
        val edits = buildList {
            for (i in 0 until segments.length()) {
                val s = segments.getJSONObject(i)
                add(EditSegment(
                    s.getLong("startMs"), s.getLong("endMs"),
                    if (s.getString("action") == "remove") EditAction.REMOVE else EditAction.KEEP,
                    s.optString("reason", ""),
                ))
            }
        }
        vm.applyEdits(clipId, edits)
        return JSONObject().apply { put("ok", true); put("segmentsApplied", edits.size) }
    }

    private fun selectClip(clipId: String): JSONObject {
        vm.selectClip(clipId.ifBlank { null })
        return JSONObject().apply { put("ok", true) }
    }

    private fun splitClipTool(clipId: String, atMs: Long): JSONObject {
        vm.splitClip(clipId, atMs)
        return ok().put("clipCount", vm.uiState.value.document.clips.size)
    }

    private fun segmentClipTool(clipId: String): JSONObject {
        vm.segmentClip(clipId)
        return ok().put("clipCount", vm.uiState.value.document.clips.size)
    }

    private fun deleteClipTool(clipId: String): JSONObject {
        vm.deleteClip(clipId)
        return ok().put("clipCount", vm.uiState.value.document.clips.size)
    }

    private fun applyCutsTool(clipId: String): JSONObject {
        vm.applyCuts(clipId)
        return ok()
            .put("clipCount", vm.uiState.value.document.clips.size)
            .put("totalDurationMs", vm.uiState.value.document.totalDurationMs)
    }

    private fun rippleDeleteRangeTool(startMs: Long, endMs: Long): JSONObject {
        vm.rippleDeleteRange(startMs, endMs)
        return ok().put("totalDurationMs", vm.uiState.value.document.totalDurationMs)
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
        vm.applyEdits(clipId, edits)
        return JSONObject().apply {
            put("ok", true); put("clipId", clipId); put("segmentsFound", edits.size)
            put("segments", segmentsJson(edits))
        }
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

        // 1. Object's segments, on-device (the REMOVE ranges), via the normal on-device analyzer.
        val removes = runBlocking {
            Analysis.run(context, settings, Uri.parse(media.uri), media.kind, clip.prompt, clip.durationMs)
        }.filter { it.action == EditAction.REMOVE }
        if (removes.isEmpty()) {
            return JSONObject().apply { put("ok", true); put("replaced", 0); put("note", "No matching object to remove.") }
        }

        // 2. For each segment: on-device mask from a representative frame -> cloud inpaint -> media.
        val replacements = runBlocking {
            removes.mapNotNull { seg ->
                val frame = grabFrame(Uri.parse(media.uri), (seg.startMs + seg.endMs) / 2) ?: return@mapNotNull null
                val boxes = com.hereliesaz.guillotine.ai.ObjectVision(context).use { ov ->
                    ov.detect(frame).filter { matchesPrompt(clip.prompt, it.label) }.map { it.box }
                }
                if (boxes.isEmpty()) { frame.recycle(); return@mapNotNull null }
                val mask = com.hereliesaz.guillotine.ai.InpaintMask.fromBoxes(frame.width, frame.height, boxes)
                val uri = runCatching {
                    com.hereliesaz.guillotine.ai.ImageGen.Leonardo.inpaint(
                        context, key, settings.leonardoModel, frame, mask,
                        "remove the ${clip.prompt}, clean natural background, photorealistic",
                    )
                }.getOrNull()
                frame.recycle(); mask.recycle()
                uri?.let {
                    val relStart = (seg.startMs - clip.trimStartMs).coerceIn(0, clip.durationMs)
                    val relEnd = (seg.endMs - clip.trimStartMs).coerceIn(0, clip.durationMs)
                    EditorViewModel.Replacement(
                        relStart, relEnd,
                        MediaItem(newId(), it.toString(), "inpaint", MediaKind.IMAGE, relEnd - relStart),
                    )
                }
            }
        }
        if (replacements.isEmpty()) throw IllegalStateException("Generation produced no usable replacements.")
        vm.replaceSegmentsWithGenerated(clipId, replacements)
        return JSONObject().apply {
            put("ok", true); put("replaced", replacements.size)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("totalDurationMs", vm.uiState.value.document.totalDurationMs)
        }
    }

    private fun matchesPrompt(prompt: String, label: String): Boolean {
        val p = prompt.lowercase()
        return label in p || p.contains(label) ||
            label.split(" ").any { it.length > 2 && p.contains(it) } ||
            p.split(" ").any { it.length > 2 && label.contains(it) }
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
