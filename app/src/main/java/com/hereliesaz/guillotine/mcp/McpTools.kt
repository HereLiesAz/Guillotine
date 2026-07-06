package com.hereliesaz.guillotine.mcp

import android.content.Context
import android.net.Uri
import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.Analysis
import com.hereliesaz.guillotine.ai.BeatAnalyzer
import com.hereliesaz.guillotine.ai.FaceEmbed
import com.hereliesaz.guillotine.ai.MlKitProvider
import com.hereliesaz.guillotine.ai.PcmDecoder
import com.hereliesaz.guillotine.ai.SherpaAsr
import com.hereliesaz.guillotine.ai.SherpaTts
import com.hereliesaz.guillotine.ai.tflite.TfliteImageModel
import com.hereliesaz.guillotine.ai.tflite.YamnetClassifier
import com.hereliesaz.guillotine.ai.gen.GenController
import com.hereliesaz.guillotine.ai.gen.GenKind
import com.hereliesaz.guillotine.ai.gen.GenProviderType
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.CubicBezier
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.newId
import com.hereliesaz.guillotine.data.LearnedConceptStore
import com.hereliesaz.guillotine.operation.OperationController
import com.hereliesaz.guillotine.operation.OperationKind
import com.hereliesaz.guillotine.ui.ActivityLog
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
) : McpToolsSurface {

    // ---- tool definitions ---------------------------------------------------

    override fun definitions(): JSONArray = JSONArray().apply {
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

        // ---- teach a specific thing by pointing at it (few-shot, on-device) ----
        put(toolDefinition(
            "add_reference",
            "Teach the app a SPECIFIC thing by pointing at it in the CURRENT preview frame: captures an " +
                "on-device fingerprint of what's there and adds it as an example of a named concept. Call " +
                "it once per frame the user points the thing out in (\"this is my dog Rex\", \"here he is " +
                "again\") — more examples = more robust recognition. Pass `term` (the kind of thing, e.g. " +
                "\"dog\") if the user said it, to help pick the right object in the frame. Set " +
                "negative=true for a NON-example (\"this frame does NOT have Rex\", \"that's a different " +
                "dog\") — it fingerprints the same-kind look-alikes so recognition can reject them.",
            objSchema(
                "name" to stringProp("Short name for the thing, e.g. \"Rex\""),
                "term" to stringProp("Optional kind of object, e.g. \"dog\", \"mug\""),
                "negative" to JSONObject().apply {
                    put("type", "boolean"); put("description", "True if this frame does NOT contain the thing (a look-alike to reject)")
                },
                required = listOf("name"),
            ),
        ))
        put(toolDefinition(
            "list_concepts",
            "List the things the user has taught by pointing them out (learned concepts): name + how many " +
                "examples each has.",
            emptySchema(),
        ))
        put(toolDefinition(
            "delete_concept",
            "Forget a learned thing by name.",
            objSchema("name" to stringProp(), required = listOf("name")),
        ))
        put(toolDefinition(
            "analyze_clip_with_concept",
            "Keep or cut a clip by a LEARNED thing (taught via add_reference): finds frames containing that " +
                "specific instance and cuts for real. keep_only=true keeps ONLY the frames with it (removes " +
                "the rest — \"keep only shots with Rex\"); keep_only=false removes the frames with it (\"cut " +
                "everything with Rex\"). Prefer this over analyze_clip when the user taught the thing by " +
                "pointing at it.",
            objSchema(
                "clip_id" to stringProp(), "name" to stringProp("The learned thing's name"),
                "keep_only" to JSONObject().apply {
                    put("type", "boolean"); put("description", "Keep only frames with it (else remove them)")
                },
                required = listOf("clip_id", "name"),
            ),
        ))

        // ---- on-device image effects (TFLite) ----
        put(toolDefinition(
            "apply_image_effect",
            "Run an ON-DEVICE image model on the current preview frame and add the result as a new image " +
                "clip. effect = superres (upscale) | style (style transfer) | depth (depth map). Requires " +
                "that effect's .tflite model to be set in Settings → AI Analyzer → Image effects; if it " +
                "isn't, returns an error naming the setting (relay it, don't retry).",
            objSchema(
                "effect" to stringProp("superres | style | depth"),
                "clip_id" to stringProp("Optional clip; defaults to the video clip at the playhead"),
                required = listOf("effect"),
            ),
        ))

        // ---- audio-event highlights (YAMNet, on-device) ----
        put(toolDefinition(
            "find_highlights",
            "Scan a clip's AUDIO on-device (YAMNet) for exciting moments — applause, cheering, laughter, " +
                "music, screaming, a roaring crowd — and return them as timestamped ranges. By default it " +
                "also splits the clip at each highlight boundary so every best-moment becomes its own clip " +
                "(pass split=false to only report). Use for \"find the best moments / highlights\", " +
                "\"make a highlight reel\", \"where does the crowd cheer?\". Requires the YAMNet model set " +
                "in Settings → AI Analyzer → Audio highlights; if it isn't, returns an error naming the " +
                "setting (relay it, don't retry).",
            objSchema(
                "clip_id" to stringProp("The clip whose audio to scan"),
                "threshold" to numberProp("Detection confidence 0–1 (default 0.3; lower finds more)"),
                "split" to boolProp("Split the clip at highlight boundaries (default true)"),
                required = listOf("clip_id"),
            ),
        ))

        // ---- shot / scene detection (on-device, no model) ----
        put(toolDefinition(
            "detect_scenes",
            "Detect visual scene/shot cuts in a clip on-device (colour-histogram content difference — no " +
                "model or key needed) and, by default, split the clip at each cut so every shot is its own " +
                "piece. Use for \"detect scenes\", \"split into shots\", \"auto-chapter this\", \"cut at " +
                "every scene change\". sensitivity 0–1 (higher finds more cuts). Pass split=false to only " +
                "report the cut timestamps.",
            objSchema(
                "clip_id" to stringProp("The clip to scan"),
                "sensitivity" to numberProp("0–1, higher = more cuts (default 0.5)"),
                "split" to boolProp("Split the clip at each scene cut (default true)"),
                required = listOf("clip_id"),
            ),
        ))

        // ---- offline speech (sherpa-onnx ASR + TTS) ----
        put(toolDefinition(
            "transcribe_precise",
            "Transcribe a clip's audio ON-DEVICE with the offline Whisper (sherpa-onnx) model and return " +
                "the transcript text. More accurate than transcribe_clip's lightweight recognizer; use it " +
                "for \"what is said in this clip?\", \"transcribe this accurately\", or to get text for " +
                "summaries / voice commands. Requires the ASR model in Settings → AI Analyzer → Speech " +
                "(ASR); if it isn't set it returns an error naming the setting — relay it, don't retry. " +
                "(transcribe_clip still handles adding timed on-screen captions.)",
            objSchema(
                "clip_id" to stringProp("The clip whose audio to transcribe"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "add_voiceover",
            "Synthesize speech from text ON-DEVICE (offline neural TTS via sherpa-onnx) and add it to the " +
                "timeline as an audio clip. Use for \"add a voiceover saying …\", \"narrate this\", " +
                "\"read this out\". Requires the TTS voice in Settings → AI Analyzer → Speech (TTS); if it " +
                "isn't set it returns an error naming the setting — relay it, don't retry.",
            objSchema(
                "text" to stringProp("The words to speak"),
                "speed" to numberProp("Speaking rate (default 1.0; <1 slower, >1 faster)"),
                required = listOf("text"),
            ),
        ))

        // ---- generative media (cloud, BYO key; key-gated at call time) ----
        put(toolDefinition(
            "generate_image",
            "Generate a NEW image from a text prompt using the user's configured image provider and add " +
                "it to the timeline as an image clip. Optionally pass provider/model to pick among " +
                "configured providers. If no image provider is configured it returns an error telling " +
                "the user to add a key in Settings — relay that, don't retry.",
            objSchema(
                "prompt" to stringProp("What to generate"),
                "provider" to stringProp("Optional provider id (e.g. OPENAI_IMAGE, BFL_FLUX, FAL)"),
                "model" to stringProp("Optional model id"),
                required = listOf("prompt"),
            ),
        ))
        put(toolDefinition(
            "generate_video",
            "Generate a NEW video clip from a text prompt (cloud, BYO key) and add it to the timeline. " +
                "Async — may take a while. Optional provider/model/duration_sec.",
            objSchema(
                "prompt" to stringProp(), "provider" to stringProp(), "model" to stringProp(),
                "duration_sec" to intProp("Requested length in seconds"),
                required = listOf("prompt"),
            ),
        ))
        put(toolDefinition(
            "generate_music",
            "Generate NEW music (or a sound effect) from a text prompt (cloud, BYO key) and add it to " +
                "the timeline as an audio clip. Describe mood/genre/length in the prompt. Optional " +
                "provider/model/duration_sec.",
            objSchema(
                "prompt" to stringProp(), "provider" to stringProp(), "model" to stringProp(),
                "duration_sec" to intProp("Requested length in seconds"),
                required = listOf("prompt"),
            ),
        ))

        // ---- rhythm / edit-to-the-beat ----
        put(toolDefinition(
            "get_beat_map",
            "Analyze an AUDIO clip ON-DEVICE and return its tempo (bpm) plus beat, downbeat, and onset " +
                "timestamps (in source ms). Call this before cut_to_beats / apply_on_beat.",
            objSchema("audio_clip_id" to stringProp("The audio/music clip to analyze"), required = listOf("audio_clip_id")),
        ))
        put(toolDefinition(
            "cut_to_beats",
            "Split a VIDEO clip at the beats of an AUDIO clip so it cuts in time with the music (all " +
                "footage is kept — nothing deleted). mode = beats | downbeats | onsets (downbeats is a " +
                "good punchy default). every_n keeps only every Nth point (e.g. 2 = every other beat).",
            objSchema(
                "video_clip_id" to stringProp(), "audio_clip_id" to stringProp(),
                "mode" to stringProp("beats | downbeats | onsets"),
                "every_n" to intProp("Keep every Nth point (default 1)"),
                required = listOf("video_clip_id", "audio_clip_id"),
            ),
        ))
        put(toolDefinition(
            "apply_on_beat",
            "Add on-beat motion to a VIDEO clip synced to an AUDIO clip: effect = zoom (scale punch-in) " +
                "| flash (brightness pop) | shake (position jitter), placed as keyframes on each beat/" +
                "downbeat/onset. Great combined with cut_to_beats for a music-video feel.",
            objSchema(
                "video_clip_id" to stringProp(), "audio_clip_id" to stringProp(),
                "effect" to stringProp("zoom | flash | shake"),
                "mode" to stringProp("beats | downbeats | onsets"),
                required = listOf("video_clip_id", "audio_clip_id", "effect"),
            ),
        ))
        put(toolDefinition(
            "align_clips_to_beats",
            "Snap the START of every clip on a track to the nearest beat of an AUDIO clip — assemble a " +
                "montage locked to the music. mode = beats | downbeats | onsets.",
            objSchema(
                "track_id" to stringProp(), "audio_clip_id" to stringProp(),
                "mode" to stringProp("beats | downbeats | onsets"),
                required = listOf("track_id", "audio_clip_id"),
            ),
        ))
    }

    // ---- tool dispatch ------------------------------------------------------

    override fun call(name: String, args: JSONObject): JSONObject = when (name) {
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
        "apply_image_effect" -> applyImageEffect(args.getString("effect"), args.optString("clip_id"))
        "add_reference" -> addReference(args.getString("name"), args.optString("term"), args.optBoolean("negative", false))
        "list_concepts" -> listConcepts()
        "delete_concept" -> deleteConcept(args.getString("name"))
        "analyze_clip_with_concept" -> analyzeClipWithConcept(args.getString("clip_id"), args.getString("name"), args.optBoolean("keep_only", false))
        "generate_image" -> generateMedia(GenKind.IMAGE, args.getString("prompt"), args.optString("provider"), args.optString("model"), null)
        "generate_video" -> generateMedia(GenKind.VIDEO, args.getString("prompt"), args.optString("provider"), args.optString("model"), args.optInt("duration_sec", 8))
        "generate_music" -> generateMedia(GenKind.MUSIC, args.getString("prompt"), args.optString("provider"), args.optString("model"), args.optInt("duration_sec", 8))
        "get_beat_map" -> getBeatMap(args.getString("audio_clip_id"))
        "cut_to_beats" -> cutToBeats(args.getString("video_clip_id"), args.getString("audio_clip_id"), args.optString("mode", "downbeats"), args.optInt("every_n", 1))
        "apply_on_beat" -> applyOnBeat(args.getString("video_clip_id"), args.getString("audio_clip_id"), args.getString("effect"), args.optString("mode", "downbeats"))
        "align_clips_to_beats" -> alignClipsToBeats(args.getString("track_id"), args.getString("audio_clip_id"), args.optString("mode", "beats"))
        "find_highlights" -> findHighlights(args.getString("clip_id"), args.optDouble("threshold", 0.3).toFloat(), args.optBoolean("split", true))
        "detect_scenes" -> detectScenes(args.getString("clip_id"), args.optDouble("sensitivity", 0.5).toFloat(), args.optBoolean("split", true))
        "transcribe_precise" -> transcribePrecise(args.getString("clip_id"))
        "add_voiceover" -> addVoiceover(args.getString("text"), args.optDouble("speed", 1.0).toFloat())
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    // ---- resource definitions -----------------------------------------------

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
            Analysis.run(
                context, settingsProvider(), Uri.parse(media.uri), media.kind, clip.prompt, clip.durationMs,
                onProgress = { p -> p.finding?.let { ActivityLog.info(it) } },
            )
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
        val removed = edits.filter { it.action == EditAction.REMOVE }
        val removedMs = removed.sumOf { it.endMs - it.startMs }
        val keptCount = edits.count { it.action == EditAction.KEEP }
        val removedCount = removed.size
        // Surface the exact actions (and why) in the activity feed, not just to the agent.
        if (removed.isEmpty()) {
            ActivityLog.info("No matching frames — nothing to cut.")
        } else {
            ActivityLog.info("Cutting $removedCount region(s) (${msFmt(removedMs)}):")
            removed.take(12).forEach {
                ActivityLog.info("  · ${msFmt(it.startMs)}–${msFmt(it.endMs)} — ${it.reason}")
            }
            if (removed.size > 12) ActivityLog.info("  · …and ${removed.size - 12} more")
        }
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
                embedModelPath = settingsProvider().idEmbedModelPath,
                onProgress = { p -> p.finding?.let { ActivityLog.info(it) } },
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
                            val rects = boxes.map { android.graphics.RectF(it.left, it.top, it.right, it.bottom) }
                            val mask = com.hereliesaz.guillotine.ai.InpaintMask.fromBoxes(frame.width, frame.height, rects)
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

    // ---- generative media ---------------------------------------------------

    private fun generateMedia(
        kind: GenKind,
        prompt: String,
        provider: String,
        model: String,
        durationSec: Int?,
    ): JSONObject {
        val settings = settingsProvider()
        require(prompt.isNotBlank()) { "Enter a prompt to generate." }
        val providerType = provider.takeIf { it.isNotBlank() }
            ?.let { runCatching { GenProviderType.valueOf(it.uppercase()) }.getOrNull() }
        val label = when (kind) { GenKind.IMAGE -> "image"; GenKind.VIDEO -> "video"; GenKind.MUSIC -> "music" }
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Generating $label…", pausable = true,
        ) { sink ->
            val item = runBlocking {
                GenController.generate(
                    context, settings, kind, prompt,
                    providerOverride = providerType,
                    modelOverride = model.takeIf { it.isNotBlank() },
                    durationSec = durationSec ?: if (kind == GenKind.IMAGE) 0 else 8,
                    onProgress = { p -> if (p != null) sink.report(p, "Generating $label…") },
                    checkpoint = { sink.checkpointBlocking() },
                )
            }
            vm.addMedia(listOf(item))
            ok().apply {
                put("mediaKind", item.kind.name)
                put("durationMs", item.durationMs)
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Generated a $label and added it to the timeline.")
            }
        }
    }

    // ---- rhythm / edit-to-the-beat ------------------------------------------

    private fun getBeatMap(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val map = runBlocking { BeatAnalyzer.analyze(context, Uri.parse(media.uri)) }
        return JSONObject().apply {
            put("ok", true); put("clipId", clipId); put("bpm", map.bpm)
            put("beatCount", map.beatsMs.size)
            put("beatsMs", JSONArray(map.beatsMs))
            put("downbeatsMs", JSONArray(map.downbeatsMs))
            put("onsetCount", map.onsetsMs.size)
            put(
                "humanSummary",
                "Analyzed rhythm: ~${map.bpm.toInt()} BPM, ${map.beatsMs.size} beats, ${map.downbeatsMs.size} downbeats.",
            )
        }
    }

    /** Map an audio clip's beat times (source ms) to timeline positions, keeping only those on-clip. */
    private fun beatTimelinePositions(
        audioClip: TimelineClip,
        beatMap: com.hereliesaz.guillotine.model.BeatMap,
        mode: String,
        everyN: Int,
    ): List<Long> {
        val src = beatMap.points(mode)
        val picked = if (everyN > 1) src.filterIndexed { i, _ -> i % everyN == 0 } else src
        return picked
            .map { audioClip.startTimeMs + (it - audioClip.trimStartMs) }
            .filter { it in audioClip.startTimeMs..audioClip.endTimeMs }
    }

    private fun cutToBeats(videoClipId: String, audioClipId: String, mode: String, everyN: Int): JSONObject {
        val doc = vm.uiState.value.document
        val video = doc.clips.firstOrNull { it.id == videoClipId }
            ?: throw IllegalArgumentException("Video clip not found: $videoClipId")
        val audio = doc.clips.firstOrNull { it.id == audioClipId }
            ?: throw IllegalArgumentException("Audio clip not found: $audioClipId")
        val media = doc.mediaFor(audio) ?: throw IllegalArgumentException("No media for audio clip: $audioClipId")
        val map = runBlocking { BeatAnalyzer.analyze(context, Uri.parse(media.uri)) }
        val cuts = beatTimelinePositions(audio, map, mode, everyN.coerceAtLeast(1))
            .filter { it > video.startTimeMs && it < video.endTimeMs }
        if (cuts.isEmpty()) {
            return JSONObject().apply {
                put("ok", true); put("cuts", 0)
                put("humanSummary", "No $mode fell within the video clip — nothing cut.")
            }
        }
        vm.splitClipAt(videoClipId, cuts)
        val n = vm.uiState.value.document.clips.size
        return ok().apply {
            put("bpm", map.bpm); put("cuts", cuts.size); put("clipCount", n)
            put("humanSummary", "Cut the video on ${cuts.size} $mode (~${map.bpm.toInt()} BPM). Timeline now $n clip(s).")
        }
    }

    private fun applyOnBeat(videoClipId: String, audioClipId: String, effect: String, mode: String): JSONObject {
        val doc = vm.uiState.value.document
        val video = doc.clips.firstOrNull { it.id == videoClipId }
            ?: throw IllegalArgumentException("Video clip not found: $videoClipId")
        val audio = doc.clips.firstOrNull { it.id == audioClipId }
            ?: throw IllegalArgumentException("Audio clip not found: $audioClipId")
        val media = doc.mediaFor(audio) ?: throw IllegalArgumentException("No media for audio clip: $audioClipId")
        val map = runBlocking { BeatAnalyzer.analyze(context, Uri.parse(media.uri)) }
        val positions = beatTimelinePositions(audio, map, mode, 1)
            .filter { it in video.startTimeMs..video.endTimeMs }
        if (positions.isEmpty()) {
            return JSONObject().apply {
                put("ok", true); put("beats", 0)
                put("humanSummary", "No $mode within the clip — nothing applied.")
            }
        }
        val half = 90L
        val ease = CubicBezier()
        val (property, peak, baseline) = when (effect.lowercase().trim()) {
            "flash", "brightness" -> Triple(KeyframeProperty.BRIGHTNESS, 1.6f, 1.0f)
            "shake", "jitter" -> Triple(KeyframeProperty.OFFSET_X, 0.04f, 0.0f)
            else -> Triple(KeyframeProperty.SCALE, 1.12f, 1.0f) // zoom
        }
        val points = mutableListOf<Triple<Long, Float, CubicBezier>>()
        positions.forEach { pos ->
            val rel = pos - video.startTimeMs
            points += Triple(rel - half, baseline, ease)
            points += Triple(rel, peak, ease)
            points += Triple(rel + half, baseline, ease)
        }
        vm.insertKeyframes(videoClipId, property, points)
        return ok().apply {
            put("beats", positions.size)
            put("humanSummary", "Added $effect on ${positions.size} $mode.")
        }
    }

    private fun alignClipsToBeats(trackId: String, audioClipId: String, mode: String): JSONObject {
        val doc = vm.uiState.value.document
        val audio = doc.clips.firstOrNull { it.id == audioClipId }
            ?: throw IllegalArgumentException("Audio clip not found: $audioClipId")
        val media = doc.mediaFor(audio) ?: throw IllegalArgumentException("No media for audio clip: $audioClipId")
        val map = runBlocking { BeatAnalyzer.analyze(context, Uri.parse(media.uri)) }
        val beats = beatTimelinePositions(audio, map, mode, 1)
        if (beats.isEmpty()) {
            return JSONObject().apply {
                put("ok", true); put("moved", 0)
                put("humanSummary", "No $mode found — nothing aligned.")
            }
        }
        val clips = doc.clips.filter { it.trackId == trackId }.sortedBy { it.startTimeMs }
        var moved = 0
        clips.forEach { c ->
            val nearest = beats.minByOrNull { kotlin.math.abs(it - c.startTimeMs) } ?: return@forEach
            if (nearest != c.startTimeMs) {
                vm.updateClip(c.id) { it.copy(startTimeMs = nearest) }
                moved++
            }
        }
        return ok().apply {
            put("moved", moved)
            put("humanSummary", "Snapped $moved clip(s) on $trackId to the nearest $mode.")
        }
    }

    // ---- audio-event highlights (YAMNet, on-device) -------------------------

    /**
     * Scan a clip's audio with on-device YAMNet and locate "exciting" moments (applause, cheering,
     * laughter, music, screaming, crowd). Merges nearby detections into ranges and, when [split], cuts
     * the clip at each highlight boundary so every best-moment becomes its own piece the user can keep.
     */
    private fun findHighlights(clipId: String, threshold: Float, split: Boolean): JSONObject {
        val path = settingsProvider().audioEventModelPath
        require(path.isNotBlank()) {
            "No audio-event model set. Download YAMNet in Settings → AI Analyzer → Audio highlights."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), YamnetClassifier.SAMPLE_RATE)
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to analyze.")
        val samples = YamnetClassifier.resampleTo16k(pcm.samples, pcm.sampleRate)
        val hits = YamnetClassifier(path).use { m ->
            require(m.available) { "The audio-event model failed to load — check the .tflite file." }
            m.scanHighlights(samples, threshold.coerceIn(0.05f, 0.95f))
        }
        if (hits.isEmpty()) {
            return JSONObject().apply {
                put("ok", true); put("highlightCount", 0)
                put("humanSummary", "No highlight-worthy audio events found (try a lower threshold).")
            }
        }
        // Merge consecutive detections (<=1.5 s gap) into ranges, tracking each range's dominant label.
        val frameMs = YamnetClassifier.FRAME * 1000L / YamnetClassifier.SAMPLE_RATE
        class Range(val startMs: Long, var endMs: Long, val labels: MutableMap<String, Int>)
        val ranges = ArrayList<Range>()
        for (h in hits.sortedBy { it.startMs }) {
            val last = ranges.lastOrNull()
            if (last != null && h.startMs - last.endMs <= 1500L) {
                last.endMs = h.startMs + frameMs
                last.labels[h.label] = (last.labels[h.label] ?: 0) + 1
            } else {
                ranges += Range(h.startMs, h.startMs + frameMs, mutableMapOf(h.label to 1))
            }
        }
        // Source ms → timeline ms, clamped to the clip's placement.
        fun toTimeline(srcMs: Long) = (clip.startTimeMs + (srcMs - clip.trimStartMs))
            .coerceIn(clip.startTimeMs, clip.endTimeMs)
        val arr = JSONArray()
        val boundaries = sortedSetOf<Long>()
        ranges.forEach { r ->
            val s = toTimeline(r.startMs); val e = toTimeline(r.endMs)
            val label = r.labels.maxByOrNull { it.value }?.key ?: "event"
            arr.put(JSONObject().apply { put("startMs", s); put("endMs", e); put("event", label) })
            if (s > clip.startTimeMs && s < clip.endTimeMs) boundaries += s
            if (e > clip.startTimeMs && e < clip.endTimeMs) boundaries += e
        }
        var splitInfo = ""
        if (split && boundaries.isNotEmpty()) {
            vm.splitClipAt(clipId, boundaries.toList())
            splitInfo = " Split at ${boundaries.size} boundaries so each best moment is its own clip."
        }
        val topLabels = ranges.flatMap { it.labels.keys }.distinct().take(4).joinToString(", ")
        return ok().apply {
            put("highlightCount", ranges.size)
            put("highlights", arr)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Found ${ranges.size} highlight moment(s) — $topLabels.$splitInfo")
        }
    }

    // ---- shot / scene detection (on-device, no model) -----------------------

    /**
     * Detect visual scene/shot cuts in a clip by sampling frames and comparing colour histograms
     * (a classic content-difference cut detector — no model needed). Where consecutive frames differ
     * more than [sensitivity] allows, that's a cut. When [split], the clip is cut at each boundary so
     * every shot becomes its own piece (auto-chaptering / scene segmentation).
     */
    private fun detectScenes(clipId: String, sensitivity: Float, split: Boolean): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val durationMs = clip.durationMs
        require(durationMs > 0) { "Clip has no duration to scan." }
        // Sample ~every 0.4 s, but cap total samples (~600) so a long clip stays fast.
        val sampleMs = maxOf(400L, durationMs / 600L)
        // Higher sensitivity → lower distance threshold → more cuts. L1 distance over a normalized
        // 64-bin RGB histogram ranges 0..2; hard cuts are typically ~0.7–1.5.
        val threshold = (1.15f - 0.7f * sensitivity.coerceIn(0f, 1f))
        val boundaries = sortedSetOf<Long>()
        var found = 0
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(media.uri))
            var prev: FloatArray? = null
            var srcMs = clip.trimStartMs
            val endSrc = clip.trimStartMs + durationMs
            while (srcMs <= endSrc) {
                // OPTION_CLOSEST (not _SYNC): we sample densely (~0.4s), so keyframe-only decoding would
                // return the same keyframe repeatedly and fire false cuts at keyframe boundaries. On API
                // 27+ pull a 32x32 frame straight from the decoder (much faster — we only histogram it).
                val bmp = runCatching {
                    val tUs = srcMs * 1000
                    if (android.os.Build.VERSION.SDK_INT >= 27)
                        r.getScaledFrameAtTime(tUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST, 32, 32)
                    else
                        r.getFrameAtTime(tUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()
                if (bmp != null) {
                    val hist = try { sceneHistogram(bmp) } finally { bmp.recycle() }
                    val p = prev
                    if (p != null && l1(p, hist) >= threshold) {
                        val tl = clip.startTimeMs + (srcMs - clip.trimStartMs)
                        if (tl > clip.startTimeMs && tl < clip.endTimeMs) { boundaries += tl; found++ }
                    }
                    prev = hist
                }
                srcMs += sampleMs
            }
        } finally {
            runCatching { r.release() }
        }
        var splitInfo = ""
        if (split && boundaries.isNotEmpty()) {
            vm.splitClipAt(clipId, boundaries.toList())
            splitInfo = " Split into ${boundaries.size + 1} shots."
        }
        return ok().apply {
            put("sceneCuts", found)
            put("cutsMs", JSONArray(boundaries.toList()))
            put("clipCount", vm.uiState.value.document.clips.size)
            put(
                "humanSummary",
                if (found == 0) "No distinct scene cuts detected (try higher sensitivity)."
                else "Detected $found scene cut(s).$splitInfo",
            )
        }
    }

    /** Normalized 64-bin (4×4×4) RGB colour histogram of a small downscale of [bmp]. */
    private fun sceneHistogram(bmp: android.graphics.Bitmap): FloatArray {
        val n = 32
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        if (small != bmp) small.recycle()
        val hist = FloatArray(64)
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val bin = (r shr 6) * 16 + (g shr 6) * 4 + (b shr 6)
            hist[bin] += 1f
        }
        val total = (n * n).toFloat()
        for (i in hist.indices) hist[i] /= total
        return hist
    }

    /** L1 (sum of absolute differences) distance between two same-length histograms. */
    private fun l1(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += kotlin.math.abs(a[i] - b[i])
        return s
    }

    // ---- offline speech (sherpa-onnx ASR + TTS) -----------------------------

    /** Transcribe a clip's audio with the offline sherpa-onnx Whisper model; returns the text. */
    private fun transcribePrecise(clipId: String): JSONObject {
        val dir = settingsProvider().asrModelPath
        require(dir.isNotBlank()) {
            "No ASR model set. Download Whisper in Settings → AI Analyzer → Speech (ASR)."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), YamnetClassifier.SAMPLE_RATE)
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to transcribe.")
        val samples = YamnetClassifier.resampleTo16k(pcm.samples, pcm.sampleRate)
        val text = SherpaAsr.transcribe(dir, samples).trim()
        return ok().apply {
            put("text", text)
            put(
                "humanSummary",
                if (text.isEmpty()) "Transcribed the clip — no speech detected."
                else "Transcribed the clip: \"${text.take(120)}${if (text.length > 120) "…" else ""}\"",
            )
        }
    }

    /** Synthesize [text] to speech with the offline sherpa-onnx voice and add it as an audio clip. */
    private fun addVoiceover(text: String, speed: Float): JSONObject {
        require(text.isNotBlank()) { "Give the voiceover some text to speak." }
        val dir = settingsProvider().ttsModelPath
        require(dir.isNotBlank()) {
            "No TTS voice set. Download a voice in Settings → AI Analyzer → Speech (TTS)."
        }
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Synthesizing voiceover…", pausable = false,
        ) { _ ->
            val out = java.io.File(context.cacheDir, "voiceover_${System.currentTimeMillis()}.wav")
            val result = SherpaTts.synthesize(dir, text.trim(), out.absolutePath, speed.coerceIn(0.5f, 2.0f))
            val duration = result.durationMs.coerceAtLeast(500L)
            vm.addMedia(
                listOf(
                    MediaItem(newId(), Uri.fromFile(out).toString(), "VO: ${text.trim().take(40)}", MediaKind.AUDIO, duration),
                ),
            )
            ok().apply {
                put("durationMs", duration)
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Added a ${msFmt(duration)} voiceover to the timeline.")
            }
        }
    }

    // ---- on-device image effects (TFLite) ----------------------------------

    private fun applyImageEffect(effect: String, clipId: String): JSONObject {
        val settings = settingsProvider()
        val key = effect.lowercase().trim()
        val path = settings.effectModelPaths[key].orEmpty()
        require(path.isNotBlank()) {
            "No on-device model set for \"$effect\". Add its .tflite path in Settings → AI Analyzer → Image effects."
        }
        val st = vm.uiState.value
        val now = st.currentTimeMs
        val clip = (if (clipId.isNotBlank()) st.document.clips.firstOrNull { it.id == clipId } else null)
            ?: com.hereliesaz.guillotine.model.TimelineMath.activeClip(
                st.document.clips, com.hereliesaz.guillotine.model.ClipType.VIDEO, now,
            )
            ?: throw IllegalStateException("No video clip to apply the effect to — scrub onto one.")
        val media = st.document.mediaFor(clip)
            ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = grabFrame(Uri.parse(media.uri), sourceMs)
            ?: throw IllegalStateException("Could not read the current frame.")
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Applying $key…", pausable = true,
        ) { _ ->
            val out = try {
                TfliteImageModel(path).use { it.run(frame) }
            } finally {
                frame.recycle()
            } ?: throw IllegalStateException("The $key model produced no output — check the .tflite file.")
            val uri = saveBitmap(out)
            out.recycle()
            vm.addMedia(listOf(MediaItem(newId(), uri, "$key: ${media.name}", MediaKind.IMAGE, 5_000)))
            ok().apply {
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Applied on-device $key and added the result as an image clip.")
            }
        }
    }

    private fun saveBitmap(bmp: android.graphics.Bitmap): String {
        val f = java.io.File(context.cacheDir, "effect_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f).toString()
    }

    // ---- learned concepts (teach a specific thing by pointing at it) --------

    private fun addReference(name: String, term: String, negative: Boolean): JSONObject {
        require(name.isNotBlank()) { "Give the thing a name, e.g. \"Rex\"." }
        val settings = settingsProvider()
        val st = vm.uiState.value
        val now = st.currentTimeMs
        val clip = com.hereliesaz.guillotine.model.TimelineMath.activeClip(
            st.document.clips, com.hereliesaz.guillotine.model.ClipType.VIDEO, now,
        ) ?: throw IllegalStateException("No video clip at the playhead to capture from. Scrub to the thing first.")
        val media = st.document.mediaFor(clip)
            ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = grabFrame(Uri.parse(media.uri), sourceMs)
            ?: throw IllegalStateException("Could not read the current frame.")
        // A person concept (by name/term, or if the existing concept is one, or a face is present) uses
        // the face-recognition route; otherwise the general object embedder.
        val existing = LearnedConceptStore.get(context, name)
        val isFace = existing?.isFace == true || isPersonWord(name) || isPersonWord(term) ||
            (term.isBlank() && FaceEmbed.hasFace(context, frame))
        val prov = MlKitProvider()
        val terms = if (term.isBlank()) emptyList() else listOf(term.trim().lowercase())
        val concept = try {
            if (negative) {
                val vecs = prov.captureNegativeEmbeddings(
                    context, frame, term.ifBlank { null }, isFace, settings.idEmbedModelPath, settings.faceEmbedModelPath,
                )
                if (vecs.isEmpty()) throw IllegalStateException(
                    "Nothing to learn as a non-example here — scrub to a frame that shows a look-alike.",
                )
                LearnedConceptStore.addNegatives(context, name, terms, vecs, isFace)
            } else {
                val vec = prov.captureReferenceEmbedding(
                    context, frame, term.ifBlank { null }, isFace, settings.idEmbedModelPath, settings.faceEmbedModelPath,
                ) ?: throw IllegalStateException(
                    "Couldn't capture a fingerprint here — the on-device embedder is unavailable or there was nothing to capture.",
                )
                LearnedConceptStore.addExample(context, name, terms, vec, isFace)
            }
        } finally {
            frame.recycle()
        }
        return ok().apply {
            put("name", concept.name)
            put("exampleCount", concept.exampleCount); put("negativeCount", concept.negativeCount)
            put("isFace", concept.isFace)
            put(
                "humanSummary",
                if (negative) {
                    "Noted a non-example for \"${concept.name}\" (${concept.negativeCount} now) — helps reject look-alikes."
                } else {
                    "Learned an example of \"${concept.name}\" (${concept.exampleCount} example(s) so far). " +
                        "Point it out in another frame to make it more reliable, then keep/cut by it."
                },
            )
        }
    }

    private fun isPersonWord(s: String): Boolean {
        val p = s.lowercase()
        return listOf("face", "person", "people", "someone", "somebody", "man", "woman", "boy", "girl", "guy", "kid", "child")
            .any { p.contains(it) }
    }

    private fun listConcepts(): JSONObject {
        val concepts = LearnedConceptStore.load(context)
        return JSONObject().apply {
            put("ok", true)
            put("concepts", JSONArray().apply {
                concepts.forEach {
                    put(JSONObject().apply {
                        put("name", it.name); put("exampleCount", it.exampleCount)
                        if (it.terms.isNotEmpty()) put("terms", JSONArray(it.terms))
                    })
                }
            })
            put(
                "humanSummary",
                if (concepts.isEmpty()) "Nothing learned yet — point something out with add_reference."
                else "Learned things: " + concepts.joinToString { "${it.name} (${it.exampleCount})" } + ".",
            )
        }
    }

    private fun deleteConcept(name: String): JSONObject {
        LearnedConceptStore.remove(context, name)
        return ok().apply { put("humanSummary", "Forgot \"$name\".") }
    }

    private fun analyzeClipWithConcept(clipId: String, name: String, keepOnly: Boolean): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val concept = LearnedConceptStore.get(context, name)
            ?: throw IllegalStateException("No learned thing called \"$name\". Point it out first with add_reference.")
        require(concept.examples.isNotEmpty()) { "\"$name\" has no examples yet — point it out with add_reference." }
        val settings = settingsProvider()
        val examples = concept.examples.map { it.toFloatArray() }
        val negatives = concept.negatives.map { it.toFloatArray() }
        val edits = runBlocking {
            MlKitProvider().analyzeWithConcept(
                context, Uri.parse(media.uri), media.kind, clip.durationMs,
                examples, negatives, concept.terms, concept.isFace, keepMatches = keepOnly,
                embedModelPath = settings.idEmbedModelPath, faceModelPath = settings.faceEmbedModelPath,
                onProgress = { p -> p.finding?.let { ActivityLog.info(it) } },
            )
        }
        return analyzeResult(clipId, edits)
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
