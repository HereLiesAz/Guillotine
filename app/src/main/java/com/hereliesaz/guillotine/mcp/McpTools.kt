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
import com.hereliesaz.guillotine.ai.SherpaDiarizer
import com.hereliesaz.guillotine.ai.SherpaTts
import com.hereliesaz.guillotine.ai.VlmCaptioner
import com.hereliesaz.guillotine.ai.VocalIsolator
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
                "clip. effect = superres (upscale) | style (style transfer) | depth (depth map) | lowlight " +
                "(brighten a dark/low-light frame). Requires that effect's .tflite model to be set in " +
                "Settings → AI Analyzer → Image effects; if it isn't, returns an error naming the setting " +
                "(relay it, don't retry).",
            objSchema(
                "effect" to stringProp("superres | style | depth | lowlight"),
                "clip_id" to stringProp("Optional clip; defaults to the video clip at the playhead"),
                required = listOf("effect"),
            ),
        ))
        put(toolDefinition(
            "auto_color",
            "Auto color-correct a clip ON-DEVICE (no model): analyze a frame and nudge exposure, contrast, " +
                "and saturation toward a balanced look, applied as the clip's filters. Use for \"auto color\", " +
                "\"fix the exposure/levels\", \"balance this shot\".",
            objSchema(
                "clip_id" to stringProp("Optional clip; defaults to the video clip at the playhead"),
                required = emptyList(),
            ),
        ))
        put(toolDefinition(
            "match_color",
            "Shot-match ON-DEVICE: set the TARGET clip's exposure/contrast/saturation to match the SOURCE " +
                "clip's look, so two shots cut together consistently. Use for \"match this shot to that one\".",
            objSchema(
                "source_clip_id" to stringProp("The clip whose look to match"),
                "target_clip_id" to stringProp("The clip to adjust"),
                required = listOf("source_clip_id", "target_clip_id"),
            ),
        ))
        put(toolDefinition(
            "blur_faces",
            "Toggle ON-DEVICE face anonymization on a clip (ML Kit face detection, no key): every detected " +
                "face is blurred in both preview and export, for privacy. Use for \"blur the faces\", " +
                "\"anonymize people\", \"hide identities\", \"censor faces\". Pass enabled=false to turn it " +
                "back off. Applies to video and image clips.",
            objSchema(
                "clip_id" to stringProp("The clip to blur faces on; defaults to the video clip at the playhead"),
                "enabled" to boolProp("Turn face-blur on (default true) or off"),
                required = emptyList(),
            ),
        ))
        put(toolDefinition(
            "apply_lut",
            "Apply a `.cube` 3D LUT color grade to a clip ON-DEVICE — the standard color-grade format " +
                "exported by DaVinci Resolve / Photoshop and shared in free LUT packs. It grades in both " +
                "preview and export. Use for \"apply this LUT\", \"grade with a .cube\", \"give it a " +
                "cinematic/teal-orange look via a LUT\". `path` is a filesystem path to a .cube file " +
                "(usually one the user picked). clear_lut removes it.",
            objSchema(
                "clip_id" to stringProp("The clip to grade; defaults to the video clip at the playhead"),
                "path" to stringProp("Filesystem path to a .cube 3D LUT file"),
                required = listOf("path"),
            ),
        ))
        put(toolDefinition(
            "clear_lut",
            "Remove the `.cube` LUT color grade from a clip (undo apply_lut).",
            objSchema(
                "clip_id" to stringProp("The clip to clear; defaults to the video clip at the playhead"),
                required = emptyList(),
            ),
        ))
        put(toolDefinition(
            "apply_shader",
            "Apply a custom GLSL shader effect to a clip ON-DEVICE — a standard **ISF** (`.isf`) shader or " +
                "a raw `.fs`/`.glsl` fragment. It runs on every frame in preview and export. Use for " +
                "\"apply this ISF shader\", \"add a glitch/CRT/kaleidoscope shader\", \"run this .fs on the " +
                "clip\". Only single-pass, single-image shaders are supported (multi-pass, feedback, audio, " +
                "and two-input transition shaders are rejected). `path` is a filesystem path (usually a file " +
                "the user picked). clear_shader removes it.",
            objSchema(
                "clip_id" to stringProp("The clip to affect; defaults to the video clip at the playhead"),
                "path" to stringProp("Filesystem path to an .isf / .fs / .glsl fragment shader"),
                "params" to objSchema(required = emptyList()).apply {
                    put("description", "Optional {name: value} overrides for the shader's scalar inputs (see list_shader_params).")
                },
                required = listOf("path"),
            ),
        ))
        put(toolDefinition(
            "clear_shader",
            "Remove the custom GLSL/ISF shader effect from a clip (undo apply_shader).",
            objSchema(
                "clip_id" to stringProp("The clip to clear; defaults to the video clip at the playhead"),
                required = emptyList(),
            ),
        ))
        put(toolDefinition(
            "list_shader_params",
            "List an ISF/GLSL shader's adjustable scalar inputs (name, type, default, min, max) so you " +
                "know what to pass to apply_shader's `params`. `path` is a shader file.",
            objSchema(
                "path" to stringProp("Filesystem path to an .isf / .fs / .glsl shader"),
                required = listOf("path"),
            ),
        ))
        put(toolDefinition(
            "replace_background",
            "Replace a clip's background ON-DEVICE with no green screen (ML Kit subject matte): segment " +
                "the subject and composite it over a new background — a solid color or an image — placed " +
                "on a new track behind. Use for \"replace the background\", \"put me on a red/blue " +
                "background\", \"change the backdrop\", \"green-screen me onto this image\". Provide color " +
                "(hex like #1e90ff or a name like \"blue\") OR image_path; defaults to black. For a " +
                "generated backdrop, generate an image first, then pass its path.",
            objSchema(
                "clip_id" to stringProp("The subject clip; defaults to the video clip at the playhead"),
                "color" to stringProp("Background color (hex #RRGGBB or a name). Ignored if image_path is set."),
                "image_path" to stringProp("Filesystem path to a background image (overrides color)"),
                required = emptyList(),
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
        put(toolDefinition(
            "remove_vocals",
            "Remove the lead vocals from a clip's audio ON-DEVICE (no model/key) and add the resulting " +
                "instrumental as a new audio clip — for karaoke / backing tracks. Use for \"remove the " +
                "vocals\", \"make a karaoke / instrumental version\", \"strip the singing\". Uses stereo " +
                "center-channel cancellation, so it needs a STEREO track (returns an error on mono); it's " +
                "a lightweight instrumental extractor, not a full multi-stem split.",
            objSchema(
                "clip_id" to stringProp("The clip whose audio to process"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "set_export_preset",
            "Set the project's output aspect ratio for a platform ON-DEVICE. Use for \"make it vertical " +
                "for TikTok/Reels/Shorts\", \"square for Instagram\", \"16:9 for YouTube\", \"back to " +
                "original\". preset = tiktok | reels | shorts | vertical (all 9:16), square | instagram " +
                "(1:1), youtube | landscape (16:9), or original.",
            objSchema(
                "preset" to stringProp("Platform/aspect: tiktok/reels/shorts/vertical, square, youtube/landscape, original"),
                required = listOf("preset"),
            ),
        ))
        put(toolDefinition(
            "assemble_music_video",
            "Assemble the clips on a video track into a montage cut to the beat ON-DEVICE: analyze the " +
                "audio clip's beat grid and trim each clip on the track to span one beat interval, butting " +
                "them together on the downbeats. Use for \"make a music video from these clips\", \"cut " +
                "this montage to the beat\", \"one clip per bar\". mode = beats | downbeats | onsets; " +
                "beats_per_clip sets how many beats each clip holds (default 1).",
            objSchema(
                "track_id" to stringProp("The video track whose clips to assemble (e.g. V1)"),
                "audio_clip_id" to stringProp("The music clip that provides the beat grid"),
                "mode" to stringProp("beats | downbeats | onsets (default downbeats)"),
                "beats_per_clip" to intProp("Beats each clip spans (default 1)"),
                required = listOf("track_id", "audio_clip_id"),
            ),
        ))
        put(toolDefinition(
            "normalize_levels",
            "Even out the loudness of the timeline's audio clips ON-DEVICE (no key): measures each audio " +
                "clip's level and sets its volume so they all sit at a consistent perceived loudness. Use " +
                "for \"normalize the audio levels\", \"even out the volume\", \"level-match the clips\". " +
                "(Simple RMS level matching; for a platform loudness target use normalize_loudness.)",
            emptySchema(),
        ))
        put(toolDefinition(
            "normalize_loudness",
            "Normalize each audio clip to a platform LOUDNESS target ON-DEVICE (no key), using ITU-R " +
                "BS.1770 K-weighted LUFS. Use for \"normalize to -14 LUFS\", \"match YouTube/Spotify " +
                "loudness\", \"make it broadcast loudness\". target_lufs defaults to -14 (YouTube/Spotify); " +
                "-16 is Apple/podcasts, -23 is EBU R128 broadcast. (Ungated integrated LUFS — a good " +
                "loudness match, not a certified meter.)",
            objSchema(
                "target_lufs" to numberProp("Target loudness in LUFS (default -14)"),
            ),
        ))
        put(toolDefinition(
            "apply_bokeh",
            "Add a depth-of-field / portrait \"bokeh\" blur to the current frame ON-DEVICE: runs the depth " +
                "model, keeps the near subject sharp and blurs the far background, and adds the result as " +
                "an image clip. Use for \"blur the background\", \"portrait mode\", \"add bokeh / depth of " +
                "field\", \"cinematic blur\". strength scales the blur (default 1.0). Requires the depth " +
                "model in Settings → AI Analyzer → Image effects (depth); relay its error if unset.",
            objSchema(
                "clip_id" to stringProp("Optional clip; defaults to the video clip at the playhead"),
                "strength" to numberProp("Blur strength (default 1.0; higher = more blur)"),
            ),
        ))
        put(toolDefinition(
            "separate_stems",
            "Split a clip's music into VOCALS and ACCOMPANIMENT (instrumental) tracks ON-DEVICE (Spleeter " +
                "via ONNX, no key) and add both as audio clips — true ML stem separation for remixes, " +
                "karaoke, or isolating either part. Use for \"separate the stems\", \"split vocals and " +
                "instrumental\", \"isolate the vocals\", \"give me the acapella / instrumental\". (For a " +
                "quick stereo karaoke without a model, use remove_vocals.) Requires the Spleeter model in " +
                "Settings → AI Analyzer → Stem separation; heavy — best on moderate clip lengths. Relay " +
                "its error if the model isn't set.",
            objSchema(
                "clip_id" to stringProp("The clip whose audio to separate"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "denoise_clip",
            "Remove background noise from a clip's VOICE audio ON-DEVICE (GTCRN speech denoiser, no key) and " +
                "add the cleaned track as a new audio clip. Strips hiss, hum, air-conditioner drone, and " +
                "general background noise while keeping speech. Use for \"remove background noise\", \"clean " +
                "up the audio\", \"denoise this\", \"isolate the voice\", \"reduce the hiss\". Requires the " +
                "denoiser model in Settings → AI Analyzer → Noise reduction; relay its error if unset.",
            objSchema(
                "clip_id" to stringProp("The clip whose voice audio to denoise"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "apply_ffmpeg_filter",
            "Bake a standard **FFmpeg `-vf` filtergraph** onto a clip ON-DEVICE and add the result as a new " +
                "clip — the whole FFmpeg filter ecosystem, and **Frei0r** plugins via `frei0r=<name>:<params>`. " +
                "Use for \"apply the ffmpeg filter <graph>\", \"run a frei0r plugin\", \"add a vintage/vhs/" +
                "chromashift filter\", \"eq/curves/deband this\". `filter` is the raw -vf graph, e.g. " +
                "\"hue=s=0, gblur=sigma=2\" or \"frei0r=cartoon\". Requires an ffmpeg executable set in " +
                "Settings → AI Analyzer → FFmpeg filters (desktop-first; relay its error if unset). This is " +
                "a bake-to-new-clip step, not a live filter.",
            objSchema(
                "clip_id" to stringProp("The clip whose video to filter"),
                "filter" to stringProp("An FFmpeg -vf filtergraph (Frei0r via frei0r=name:params)"),
                required = listOf("clip_id", "filter"),
            ),
        ))
        put(toolDefinition(
            "apply_transition",
            "Create a GL-style TRANSITION between two clips ON-DEVICE (FFmpeg `xfade`) and add the combined " +
                "result as a new clip. Use for \"add a crossfade/dissolve between these\", \"wipe from this " +
                "to that\", \"put a transition here\". type is any xfade transition: fade, fadeblack, " +
                "fadewhite, wipeleft/right/up/down, slideleft/right/up/down, circleopen, circleclose, " +
                "dissolve, pixelize, radial, smoothleft, distance, and more (default fade). duration_sec " +
                "is the overlap (default 1). Requires an ffmpeg executable (Settings → AI Analyzer → FFmpeg " +
                "filters); relay its error if unset. Bake-to-new-clip, desktop-first.",
            objSchema(
                "from_clip_id" to stringProp("The outgoing (first) clip"),
                "to_clip_id" to stringProp("The incoming (second) clip"),
                "type" to stringProp("xfade transition type (default fade)"),
                "duration_sec" to numberProp("Transition/overlap length in seconds (default 1)"),
                required = listOf("from_clip_id", "to_clip_id"),
            ),
        ))
        put(toolDefinition(
            "diarize_clip",
            "Speaker diarization ON-DEVICE (no key): work out WHO spoke WHEN in a clip's audio and return " +
                "the speaker turns (speaker index + time range). Use for \"who speaks when?\", \"label the " +
                "speakers\", \"split by speaker\", \"how many people are talking?\", or as the basis for " +
                "podcast multicam switching. Optionally pass num_speakers if you know the count (else it's " +
                "inferred). Requires BOTH diarization models in Settings → AI Analyzer → Speaker " +
                "diarization; relay its error if unset.",
            objSchema(
                "clip_id" to stringProp("The clip whose audio to diarize"),
                "num_speakers" to intProp("Known speaker count (0 = infer automatically)"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "remove_fillers",
            "Remove filler words (\"um\", \"uh\", \"er\", \"hmm\") from a clip ON-DEVICE using the offline " +
                "Whisper (sherpa-onnx) word timings, ripple-deleting each filler so the timeline closes " +
                "up. Use for \"remove the ums\", \"cut the filler words\", \"clean up the ums and uhs\". " +
                "Requires the ASR model in Settings → AI Analyzer → Speech (ASR); relay its error if unset. " +
                "Timings are approximate — review the result.",
            objSchema(
                "clip_id" to stringProp("The clip to de-filler"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "sync_by_audio",
            "Sync two clips by their audio ON-DEVICE (no key): cross-correlates the two audio tracks to " +
                "find the time offset and moves the second clip so its audio lines up with the reference " +
                "(multicam / dual-recording sync). Use for \"sync these two clips by audio\", \"line up " +
                "the multicam angles\", \"match the second camera to the audio recorder\". Both clips need " +
                "audio of the same moment.",
            objSchema(
                "reference_clip_id" to stringProp("The clip to keep fixed (the reference)"),
                "clip_id" to stringProp("The clip to move so its audio aligns to the reference"),
                "max_offset_sec" to intProp("Max search offset in seconds (default 15)"),
                required = listOf("reference_clip_id", "clip_id"),
            ),
        ))
        put(toolDefinition(
            "auto_reframe",
            "Auto-reframe a clip to keep the subject centered ON-DEVICE (no key): detects the main face " +
                "across the clip and pans a punched-in crop to follow it — the classic \"make it work " +
                "vertically / follow the speaker\" reframe. Use for \"auto-reframe this\", \"keep the " +
                "subject centered\", \"follow the face\", \"reframe for vertical/Reels\". zoom is the " +
                "punch-in (default 1.3). Sets the clip's scale and writes OFFSET_X keyframes that track " +
                "the face; needs faces in the footage (returns an error if none are found).",
            objSchema(
                "clip_id" to stringProp("The clip to reframe"),
                "zoom" to numberProp("Punch-in scale (default 1.3; more = tighter, more room to pan)"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "search_clips",
            "Find which video clips contain something ON-DEVICE (no key): samples each clip's frames and " +
                "matches them against [query] using on-device image labels (~400 common things/scenes — " +
                "e.g. dog, car, beach, sunset, food, crowd). Use for \"find the clips with a dog\", " +
                "\"which shots have a sunset?\", \"where's the beach footage?\". Returns the matching " +
                "clips with the matched label and a timestamp; it does not edit anything.",
            objSchema(
                "query" to stringProp("What to look for, e.g. \"dog\" or \"sunset\""),
                required = listOf("query"),
            ),
        ))
        put(toolDefinition(
            "auto_duck",
            "Auto-duck (sidechain) a music clip under a voice/speech clip ON-DEVICE (no model/key): detect " +
                "where the voice is talking and dip the music's VOLUME there with smooth ramps, restoring " +
                "it in the gaps. Use for \"duck the music under the voiceover\", \"lower the music when " +
                "someone's talking\", \"sidechain the music to the narration\". amount is the ducked level " +
                "(0–1, default 0.3 = −10 dB-ish).",
            objSchema(
                "music_clip_id" to stringProp("The music clip to duck"),
                "voice_clip_id" to stringProp("The voice/speech clip that triggers the ducking"),
                "amount" to numberProp("Ducked music level 0–1 (default 0.3; lower = quieter under speech)"),
                required = listOf("music_clip_id", "voice_clip_id"),
            ),
        ))
        put(toolDefinition(
            "caption_frame",
            "Describe a frame in rich natural language using the ON-DEVICE multimodal VLM (Gemma-3n). " +
                "Prefer this over describe_current_frame when the user wants a real description / " +
                "understanding of the scene (\"what's happening in this frame?\", \"describe this shot\", " +
                "\"what is this a picture of?\") rather than just a list of detected objects. Optionally " +
                "pass a specific question as prompt. Requires the VLM model in Settings → AI Analyzer → " +
                "Frame captioning (VLM); if it isn't set it returns an error naming the setting — relay " +
                "it, don't retry (you can still fall back to describe_current_frame).",
            objSchema(
                "clip_id" to stringProp("Optional clip; defaults to the video clip at the playhead"),
                "prompt" to stringProp("Optional question about the frame (default: describe it)"),
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
        put(toolDefinition(
            "set_clip_filter", "Sets static values for a clip filter (e.g., brightness = 1.2, speed = 2.0).",
            objSchema(
                "clip_id" to stringProp(), "property" to stringProp("e.g. brightness, speed"), "value" to numberProp(),
                required = listOf("clip_id", "property", "value")
            )
        ))
        put(toolDefinition(
            "add_keyframe", "Adds a keyframe for a specific KeyframeProperty at a specific time in the clip.",
            objSchema(
                "clip_id" to stringProp(), "property" to stringProp(), "time_ms" to intProp(), "value" to numberProp(),
                required = listOf("clip_id", "property", "time_ms", "value")
            )
        ))
        put(toolDefinition(
            "clear_keyframes", "Removes all keyframes for a specified property on a clip.",
            objSchema(
                "clip_id" to stringProp(), "property" to stringProp(),
                required = listOf("clip_id", "property")
            )
        ))
        put(toolDefinition(
            "list_azp_plugins",
            "List all available azphalt `.azp` effect and kinetic typography plugins installed in the " +
                "extensions directories. Returns the plugin IDs, names, and tags. Use this to discover " +
                "available advanced effects to apply.",
            emptySchema()
        ))
        put(toolDefinition(
            "apply_azp_plugin",
            "Apply a specific `.azp` plugin (by its ID) to a clip on the timeline. A kinetic-typography " +
                "motion plugin applied to a caption (TEXT clip) is baked into keyframes, so it animates " +
                "in preview and export and stays editable.",
            objSchema(
                "clip_id" to stringProp("The ID of the clip to apply the plugin to"),
                "plugin_id" to stringProp("The ID of the plugin (from list_azp_plugins)"),
                required = listOf("clip_id", "plugin_id")
            )
        ))
        put(toolDefinition(
            "clear_azp_plugin",
            "Remove an applied kinetic-typography preset (its baked keyframes) and any applied-plugin " +
                "marker from a clip. Hand-authored keyframes are kept.",
            objSchema(
                "clip_id" to stringProp("The ID of the clip to clear the plugin/preset from"),
                required = listOf("clip_id")
            )
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
        "auto_color" -> autoColor(args.optString("clip_id"))
        "match_color" -> matchColor(args.getString("source_clip_id"), args.getString("target_clip_id"))
        "blur_faces" -> blurFaces(args.optString("clip_id"), if (args.has("enabled")) args.getBoolean("enabled") else true)
        "apply_lut" -> applyLut(args.optString("clip_id"), args.getString("path"))
        "clear_lut" -> clearLut(args.optString("clip_id"))
        "apply_shader" -> applyShader(args.optString("clip_id"), args.getString("path"), args.optJSONObject("params"))
        "clear_shader" -> clearShader(args.optString("clip_id"))
        "list_shader_params" -> listShaderParams(args.getString("path"))
        "replace_background" -> replaceBackgroundTool(args.optString("clip_id"), args.optString("color"), args.optString("image_path"))
        "apply_bokeh" -> applyBokeh(args.optString("clip_id"), args.optDouble("strength", 1.0).toFloat())
        "normalize_loudness" -> normalizeLoudness(args.optDouble("target_lufs", -14.0))
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
        "remove_vocals" -> removeVocals(args.getString("clip_id"))
        "caption_frame" -> captionFrame(args.optString("clip_id"), args.optString("prompt"))
        "auto_duck" -> autoDuck(args.getString("music_clip_id"), args.getString("voice_clip_id"), args.optDouble("amount", 0.3).toFloat())
        "search_clips" -> searchClips(args.getString("query"))
        "auto_reframe" -> autoReframe(args.getString("clip_id"), args.optDouble("zoom", 1.3).toFloat())
        "sync_by_audio" -> syncByAudio(args.getString("reference_clip_id"), args.getString("clip_id"), args.optInt("max_offset_sec", 15))
        "set_export_preset" -> setExportPreset(args.getString("preset"))
        "assemble_music_video" -> assembleMusicVideo(args.getString("track_id"), args.getString("audio_clip_id"), args.optString("mode", "downbeats"), args.optInt("beats_per_clip", 1))
        "normalize_levels" -> normalizeLevels()
        "remove_fillers" -> removeFillers(args.getString("clip_id"))
        "diarize_clip" -> diarizeClip(args.getString("clip_id"), args.optInt("num_speakers", 0))
        "separate_stems" -> separateStems(args.getString("clip_id"))
        "denoise_clip" -> denoiseClip(args.getString("clip_id"))
        "apply_ffmpeg_filter" -> applyFfmpegFilter(args.getString("clip_id"), args.getString("filter"))
        "apply_transition" -> applyTransition(args.getString("from_clip_id"), args.getString("to_clip_id"), args.optString("type", "fade"), args.optDouble("duration_sec", 1.0).toFloat())
        "set_clip_filter" -> setClipFilter(args.getString("clip_id"), args.getString("property"), args.getDouble("value").toFloat())
        "add_keyframe" -> addKeyframe(args.getString("clip_id"), args.getString("property"), args.getLong("time_ms"), args.getDouble("value").toFloat())
        "clear_keyframes" -> clearKeyframes(args.getString("clip_id"), args.getString("property"))
        "list_azp_plugins" -> listAzpPlugins()
        "apply_azp_plugin" -> applyAzpPlugin(args.getString("clip_id"), args.getString("plugin_id"))
        "clear_azp_plugin" -> clearAzpPlugin(args.getString("clip_id"))
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
                onProgress = { p -> 
                    p.finding?.let { ActivityLog.info(it) } 
                    p.currentMs?.let { ms -> vm.seekTo(clip.startTimeMs + ms) }
                },
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
                embedModelPath = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "idEmbedModelPath"),
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
                    onProgress = { p -> 
                        p.currentMs?.let { ms -> vm.seekTo(clip.startTimeMs + ms) }
                    },
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

    /** Decode a clip's audio to mono PCM on-device, then run the shared beat/tempo analyzer. */
    private fun beatMapFor(uri: Uri): com.hereliesaz.guillotine.model.BeatMap {
        val pcm = PcmDecoder.decode(context, uri)
            ?: return com.hereliesaz.guillotine.model.BeatMap(0f, emptyList(), emptyList(), emptyList())
        return BeatAnalyzer.analyze(pcm.samples, pcm.sampleRate)
    }

    private fun getBeatMap(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val map = beatMapFor(Uri.parse(media.uri))
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
        val map = beatMapFor(Uri.parse(media.uri))
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
        val map = beatMapFor(Uri.parse(media.uri))
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
        val map = beatMapFor(Uri.parse(media.uri))
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
        val path = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "audioEventModelPath")
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
        val dir = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "asrModelPath")
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
        val dir = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "ttsModelPath")
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

    /** Remove center-panned vocals (karaoke) from a clip's stereo audio and add the instrumental. */
    private fun removeVocals(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Removing vocals…", pausable = false,
        ) { _ ->
            val out = java.io.File(context.cacheDir, "instrumental_${System.currentTimeMillis()}.wav")
            val stereo = com.hereliesaz.guillotine.ai.StereoPcmDecoder.decode(context, Uri.parse(media.uri))
            val duration = (if (stereo == null || stereo.channels < 2) null
                else VocalIsolator.removeVocals(stereo.left, stereo.right, stereo.sampleRate, out.absolutePath))
                ?: throw IllegalStateException(
                    "Couldn't remove vocals — the clip needs a stereo audio track (center-channel " +
                        "cancellation can't work on mono).",
                )
            vm.addMedia(
                listOf(
                    MediaItem(newId(), Uri.fromFile(out).toString(), "Instrumental: ${media.name}", MediaKind.AUDIO, duration),
                ),
            )
            ok().apply {
                put("durationMs", duration)
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Added a ${msFmt(duration)} vocals-removed (instrumental) track.")
            }
        }
    }

    /**
     * Auto-duck (sidechain) [musicClipId] under the speech in [voiceClipId]: find where the voice is
     * talking (RMS energy gate) and dip the music's VOLUME to [amount] there with short ramps, back to
     * 1.0 in the gaps — all on-device, no model. Writes VOLUME keyframes on the music clip.
     */
    private fun autoDuck(musicClipId: String, voiceClipId: String, amount: Float): JSONObject {
        val doc = vm.uiState.value.document
        val music = doc.clips.firstOrNull { it.id == musicClipId }
            ?: throw IllegalArgumentException("Music clip not found: $musicClipId")
        val voice = doc.clips.firstOrNull { it.id == voiceClipId }
            ?: throw IllegalArgumentException("Voice clip not found: $voiceClipId")
        val vmedia = doc.mediaFor(voice) ?: throw IllegalArgumentException("No media for voice clip: $voiceClipId")
        val pcm = PcmDecoder.decode(context, Uri.parse(vmedia.uri), 8_000)
            ?: throw IllegalStateException("No audio track in \"${vmedia.name}\" to detect speech.")
        val regions = speechRegions(pcm) // source ms within the voice media
        if (regions.isEmpty()) {
            return JSONObject().apply {
                put("ok", true); put("duckedRegions", 0)
                put("humanSummary", "No speech detected in the voice clip — nothing to duck under.")
            }
        }
        // Map each voice-source region to music-clip-relative ms, keeping only the on-clip overlap.
        val duck = amount.coerceIn(0f, 1f)
        val ramp = 120L
        val ease = CubicBezier()
        val points = mutableListOf<Triple<Long, Float, CubicBezier>>()
        var applied = 0
        for ((s, e) in regions) {
            val tlStart = voice.startTimeMs + (s - voice.trimStartMs)
            val tlEnd = voice.startTimeMs + (e - voice.trimStartMs)
            val relStart = (tlStart - music.startTimeMs)
            val relEnd = (tlEnd - music.startTimeMs)
            if (relEnd <= 0 || relStart >= music.durationMs) continue // no overlap with the music clip
            val rs = relStart.coerceIn(0, music.durationMs)
            val re = relEnd.coerceIn(0, music.durationMs)
            points += Triple((rs - ramp).coerceAtLeast(0), 1f, ease)
            points += Triple(rs, duck, ease)
            points += Triple(re, duck, ease)
            points += Triple((re + ramp).coerceAtMost(music.durationMs), 1f, ease)
            applied++
        }
        if (applied == 0) {
            return JSONObject().apply {
                put("ok", true); put("duckedRegions", 0)
                put("humanSummary", "The speech doesn't overlap the music clip — nothing ducked.")
            }
        }
        vm.insertKeyframes(musicClipId, KeyframeProperty.VOLUME, points)
        return ok().apply {
            put("duckedRegions", applied)
            put("humanSummary", "Ducked the music to ${(duck * 100).toInt()}% under $applied speech section(s).")
        }
    }

    /**
     * Detect speech regions in [pcm] via a short-window RMS energy gate with an adaptive threshold.
     * Returns [start,end] pairs in source ms, merging gaps < 250 ms and dropping blips < 150 ms.
     */
    private fun speechRegions(pcm: com.hereliesaz.guillotine.ai.PcmAudio): List<Pair<Long, Long>> {
        val rate = pcm.sampleRate
        if (rate <= 0 || pcm.samples.isEmpty()) return emptyList()
        val win = (rate * 0.04).toInt().coerceAtLeast(1) // 40 ms windows
        val n = pcm.samples.size / win
        if (n == 0) return emptyList()
        val rms = FloatArray(n)
        var peak = 0f
        for (i in 0 until n) {
            var sum = 0.0
            val base = i * win
            for (j in 0 until win) { val v = pcm.samples[base + j]; sum += v * v }
            rms[i] = kotlin.math.sqrt(sum / win).toFloat()
            if (rms[i] > peak) peak = rms[i]
        }
        val threshold = maxOf(0.02f, peak * 0.2f)
        fun winMs(w: Int) = w.toLong() * win * 1000L / rate
        val raw = ArrayList<Pair<Long, Long>>()
        var i = 0
        while (i < n) {
            if (rms[i] >= threshold) {
                var j = i
                while (j < n && rms[j] >= threshold) j++
                raw += winMs(i) to winMs(j)
                i = j
            } else i++
        }
        // Merge close regions, then drop too-short ones.
        val merged = ArrayList<Pair<Long, Long>>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.second <= 250L) merged[merged.size - 1] = last.first to r.second
            else merged += r
        }
        return merged.filter { it.second - it.first >= 150L }
    }

    /**
     * Auto-reframe [clipId] to follow the main face: sample frames, find the largest face's horizontal
     * position, punch in by [zoom], and write OFFSET_X keyframes that pan to keep the subject centered.
     */
    private fun autoReframe(clipId: String, zoom: Float): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val z = zoom.coerceIn(1.05f, 3f)
        val durationMs = clip.durationMs
        require(durationMs > 0) { "Clip has no duration to reframe." }
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Auto-reframing…", pausable = false,
        ) { _ ->
            val uri = Uri.parse(media.uri)
            val step = maxOf(300L, durationMs / 60) // ~every 0.3s, capped ~60 samples
            // Max pan (normalized) that keeps the punched-in crop inside the frame.
            val maxPan = ((z - 1f) / (2f * z)).coerceIn(0f, 0.5f)
            val samples = ArrayList<Pair<Long, Float>>() // relMs -> centerX (0..1)
            var srcMs = clip.trimStartMs
            val endSrc = clip.trimStartMs + durationMs
            while (srcMs <= endSrc) {
                val frame = grabFrame(uri, srcMs)
                if (frame != null) {
                    val cx = try { com.hereliesaz.guillotine.ai.FaceEmbed.largestFaceCenterX(context, frame) }
                        finally { frame.recycle() }
                    if (cx != null) samples += (srcMs - clip.trimStartMs) to cx
                }
                srcMs += step
            }
            if (samples.isEmpty()) {
                throw IllegalStateException("No faces found to follow — auto-reframe needs a face in the shot.")
            }
            // Smooth the centers (moving average of 3) to avoid jitter, then map to a pan offset.
            val ease = CubicBezier()
            val points = samples.mapIndexed { i, (relMs, _) ->
                val lo = maxOf(0, i - 1); val hi = minOf(samples.size - 1, i + 1)
                val avgCx = (lo..hi).map { samples[it].second }.average().toFloat()
                // Subject right of center → pan the image left (negative offset) to recenter.
                val offsetX = ((0.5f - avgCx) * 2f * maxPan).coerceIn(-maxPan, maxPan)
                Triple(relMs, offsetX, ease)
            }
            vm.updateClip(clipId) { it.copy(scale = z) }
            vm.insertKeyframes(clipId, KeyframeProperty.OFFSET_X, points)
            ok().apply {
                put("keyframes", points.size)
                put("humanSummary", "Auto-reframed: punched in ${z}× and panned across ${points.size} points to follow the face.")
            }
        }
    }

    /** Envelope sample rate for audio-sync cross-correlation (100 Hz = one RMS point per 10 ms). */
    private val ENV_RATE = 100

    /**
     * Sync [clipId] to [refClipId] by audio: build an RMS envelope of each clip's audio, cross-correlate
     * them over ±[maxOffsetSec], and move [clipId]'s start so the matching audio lines up on the timeline.
     */
    private fun syncByAudio(refClipId: String, clipId: String, maxOffsetSec: Int): JSONObject {
        val doc = vm.uiState.value.document
        val ref = doc.clips.firstOrNull { it.id == refClipId }
            ?: throw IllegalArgumentException("Reference clip not found: $refClipId")
        val mov = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val envA = audioEnvelope(doc, ref) ?: throw IllegalStateException("No audio in the reference clip to sync on.")
        val envB = audioEnvelope(doc, mov) ?: throw IllegalStateException("No audio in \"$clipId\" to sync on.")
        val maxLag = (maxOffsetSec.coerceIn(1, 120) * ENV_RATE)
        // Find the lag L (in env samples) maximizing Σ envA[k] * envB[k+L]. envA[k] ≈ envB[k+L] means the
        // same event is at index k in A and k+L in B, so B should start L*10ms earlier than A.
        var bestLag = 0
        var bestScore = -Double.MAX_VALUE
        for (lag in -maxLag..maxLag) {
            var sum = 0.0
            var count = 0
            var k = maxOf(0, -lag)
            val kEnd = minOf(envA.size, envB.size - lag)
            while (k < kEnd) { sum += envA[k] * envB[k + lag]; count++; k++ }
            if (count > ENV_RATE) { // need at least ~1s of overlap to trust a score
                val score = sum / count
                if (score > bestScore) { bestScore = score; bestLag = lag }
            }
        }
        val offsetMs = bestLag.toLong() * (1000L / ENV_RATE)
        val newStart = (ref.startTimeMs - offsetMs).coerceAtLeast(0L)
        vm.updateClip(clipId) { it.copy(startTimeMs = newStart) }
        return ok().apply {
            put("offsetMs", offsetMs)
            put("newStartMs", newStart)
            put("humanSummary", "Synced by audio: moved the clip to ${msFmt(newStart)} (offset ${offsetMs}ms) so its audio matches the reference.")
        }
    }

    /** Normalized (zero-mean, unit-std) RMS envelope of a clip's audio at [ENV_RATE], or null if none. */
    private fun audioEnvelope(doc: com.hereliesaz.guillotine.model.Document, clip: TimelineClip): FloatArray? {
        val media = doc.mediaFor(clip) ?: return null
        val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), 4_000) ?: return null
        if (pcm.sampleRate <= 0 || pcm.samples.isEmpty()) return null
        val win = (pcm.sampleRate / ENV_RATE).coerceAtLeast(1)
        val n = pcm.samples.size / win
        if (n < ENV_RATE) return null
        val env = FloatArray(n)
        for (i in 0 until n) {
            var s = 0.0
            val base = i * win
            for (j in 0 until win) { val v = pcm.samples[base + j]; s += v * v }
            env[i] = kotlin.math.sqrt(s / win).toFloat()
        }
        val mean = env.average().toFloat()
        var varSum = 0.0
        for (v in env) varSum += (v - mean) * (v - mean)
        val std = kotlin.math.sqrt(varSum / env.size).toFloat().coerceAtLeast(1e-6f)
        for (i in env.indices) env[i] = (env[i] - mean) / std
        return env
    }

    /** Set the project's output aspect ratio from a platform [preset]. */
    private fun setExportPreset(preset: String): JSONObject {
        val p = preset.lowercase().trim()
        val aspect = when {
            listOf("tiktok", "reels", "reel", "shorts", "short", "vertical", "9:16", "story", "stories", "portrait").any { p.contains(it) } ->
                com.hereliesaz.guillotine.model.AspectRatio.RATIO_9_16
            listOf("square", "1:1", "instagram post", "feed").any { p.contains(it) } ->
                com.hereliesaz.guillotine.model.AspectRatio.RATIO_1_1
            listOf("youtube", "landscape", "16:9", "wide", "tv", "horizontal").any { p.contains(it) } ->
                com.hereliesaz.guillotine.model.AspectRatio.RATIO_16_9
            listOf("original", "source", "native").any { p.contains(it) } ->
                com.hereliesaz.guillotine.model.AspectRatio.ORIGINAL
            else -> throw IllegalArgumentException(
                "Unknown preset \"$preset\". Try tiktok/reels/shorts (9:16), square (1:1), youtube (16:9), or original.",
            )
        }
        val cur = vm.uiState.value.document.settings
        vm.setGlobalSettings(cur.copy(aspectRatio = aspect))
        val label = aspect.name.removePrefix("RATIO_").replace('_', ':')
        return ok().apply {
            put("aspectRatio", aspect.name)
            put("humanSummary", "Set the project to $label${if (aspect.name == "ORIGINAL") "" else " ($preset)"}.")
        }
    }

    /**
     * Trim each clip on [trackId] to span one beat interval of [audioClipId]'s grid, butting them
     * together on the beats — a montage cut to the music. [beatsPerClip] controls the interval length.
     */
    private fun assembleMusicVideo(trackId: String, audioClipId: String, mode: String, beatsPerClip: Int): JSONObject {
        val doc = vm.uiState.value.document
        val audio = doc.clips.firstOrNull { it.id == audioClipId }
            ?: throw IllegalArgumentException("Audio clip not found: $audioClipId")
        val media = doc.mediaFor(audio) ?: throw IllegalArgumentException("No media for audio clip: $audioClipId")
        val map = beatMapFor(Uri.parse(media.uri))
        val every = beatsPerClip.coerceAtLeast(1)
        // Beat times → timeline positions (on the audio clip), thinned to every Nth beat.
        val grid = map.points(mode)
            .map { audio.startTimeMs + (it - audio.trimStartMs) }
            .filter { it in audio.startTimeMs..audio.endTimeMs }
            .sorted()
            .filterIndexed { i, _ -> i % every == 0 }
        if (grid.size < 2) {
            return JSONObject().apply {
                put("ok", true); put("assembled", 0)
                put("humanSummary", "Not enough $mode in the music to build a montage.")
            }
        }
        val clips = doc.clips
            .filter { it.trackId == trackId && it.type == com.hereliesaz.guillotine.model.ClipType.VIDEO }
            .sortedBy { it.startTimeMs }
        if (clips.isEmpty()) throw IllegalStateException("No video clips on track $trackId to assemble.")
        var assembled = 0
        for (i in clips.indices) {
            if (i + 1 >= grid.size) break // ran out of beat intervals
            val start = grid[i]
            val interval = grid[i + 1] - start
            val clip = clips[i]
            val avail = ((doc.mediaFor(clip)?.durationMs ?: interval) - clip.trimStartMs).coerceAtLeast(1)
            val dur = interval.coerceAtMost(avail)
            vm.updateClip(clip.id) { it.copy(startTimeMs = start, durationMs = dur) }
            assembled++
        }
        return ok().apply {
            put("assembled", assembled); put("bpm", map.bpm)
            put("humanSummary", "Assembled $assembled clip(s) on $trackId to the beat (~${map.bpm.toInt()} BPM, every $every $mode).")
        }
    }

    /** Even out audio-clip loudness: measure each clip's RMS and set its volume toward a shared target. */
    private fun normalizeLevels(): JSONObject {
        val doc = vm.uiState.value.document
        val audioClips = doc.clips.filter {
            val kind = doc.mediaFor(it)?.kind
            kind == MediaKind.AUDIO || kind == MediaKind.VIDEO
        }
        val rmsByClip = HashMap<String, Float>()
        for (clip in audioClips) {
            val media = doc.mediaFor(clip) ?: continue
            val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), 8_000) ?: continue
            if (pcm.samples.isEmpty()) continue
            var sum = 0.0
            for (v in pcm.samples) sum += v * v
            val rms = kotlin.math.sqrt(sum / pcm.samples.size).toFloat()
            if (rms > 1e-4f) rmsByClip[clip.id] = rms
        }
        if (rmsByClip.size < 1) {
            return JSONObject().apply {
                put("ok", true); put("normalized", 0)
                put("humanSummary", "No measurable audio to normalize.")
            }
        }
        // Target = median RMS across clips (robust to one very loud/quiet clip).
        val target = rmsByClip.values.sorted().let { it[it.size / 2] }
        var normalized = 0
        for ((id, rms) in rmsByClip) {
            val gain = (target / rms).coerceIn(0.1f, 4f)
            vm.updateClipFilters(id) { it.copy(volume = gain.coerceIn(0f, 2f)) }
            normalized++
        }
        return ok().apply {
            put("normalized", normalized)
            put("humanSummary", "Level-matched $normalized audio clip(s) to a consistent loudness.")
        }
    }

    /** Separate a clip's music into vocals + accompaniment via on-device Spleeter (ONNX); add both. */
    private fun separateStems(clipId: String): JSONObject {
        val dir = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "stemModelPath")
        require(dir.isNotBlank()) {
            "No stem model set. Download Spleeter in Settings → AI Analyzer → Stem separation."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Separating stems…", pausable = false,
        ) { _ ->
            val stereo = com.hereliesaz.guillotine.ai.StereoPcmDecoder.decode(context, Uri.parse(media.uri))
                ?: throw IllegalStateException("Couldn't separate — the clip needs decodable audio.")
            val stems = com.hereliesaz.guillotine.ai.Spleeter.separate(
                stereo.left, stereo.right, stereo.sampleRate, dir, java.io.File(context.cacheDir, "stems"),
            ) ?: throw IllegalStateException("Couldn't separate — the clip needs decodable audio.")
            vm.addMedia(
                listOf(
                    MediaItem(newId(), Uri.fromFile(java.io.File(stems.vocalsWav)).toString(), "vocals: ${media.name}", MediaKind.AUDIO, stems.durationMs),
                    MediaItem(newId(), Uri.fromFile(java.io.File(stems.accompanimentWav)).toString(), "instrumental: ${media.name}", MediaKind.AUDIO, stems.durationMs),
                ),
            )
            ok().apply {
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Separated into vocals + accompaniment and added both as audio clips.")
            }
        }
    }

    /** Denoise a clip's voice audio (GTCRN) and add the cleaned track as a new audio clip. */
    private fun denoiseClip(clipId: String): JSONObject {
        val model = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "denoiseModelPath")
        require(model.isNotBlank()) {
            "No denoiser model set. Download one in Settings → AI Analyzer → Noise reduction."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), YamnetClassifier.SAMPLE_RATE)
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to denoise.")
        val samples = YamnetClassifier.resampleTo16k(pcm.samples, pcm.sampleRate)
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Removing noise…", pausable = false,
        ) { _ ->
            val outWav = java.io.File(context.cacheDir, "denoise/${newId()}.wav").apply { parentFile?.mkdirs() }
            val durationMs = com.hereliesaz.guillotine.ai.SherpaDenoiser.denoiseToWav(
                model, samples, 16_000, outWav.absolutePath,
            )
            require(durationMs > 0) { "Denoiser produced no audio." }
            vm.addMedia(
                listOf(
                    MediaItem(
                        newId(), Uri.fromFile(outWav).toString(), "clean: ${media.name}",
                        MediaKind.AUDIO, durationMs,
                    ),
                ),
            )
            ok().apply {
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Removed background noise and added the cleaned voice as an audio clip.")
            }
        }
    }

    /** Bake an FFmpeg/Frei0r `-vf` filtergraph onto a clip's video, adding the result as a new clip. */
    private fun applyFfmpegFilter(clipId: String, filterGraph: String): JSONObject {
        val ffmpeg = settingsProvider().ffmpegPath
        require(ffmpeg.isNotBlank()) {
            "No ffmpeg set. Set an ffmpeg executable in Settings → AI Analyzer → FFmpeg filters."
        }
        require(filterGraph.isNotBlank()) { "Provide an FFmpeg -vf filtergraph." }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Applying FFmpeg filter…", pausable = false,
        ) { _ ->
            val baked = com.hereliesaz.guillotine.media.FfmpegFilter.apply(
                context, media.uri, filterGraph, ffmpeg, java.io.File(context.cacheDir, "ffmpeg"),
            )
            vm.addMedia(
                listOf(
                    MediaItem(
                        newId(), Uri.fromFile(baked.file).toString(), "ffmpeg: ${media.name}",
                        MediaKind.VIDEO, baked.durationMs, baked.hasAudio,
                    ),
                ),
            )
            ok().apply {
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Applied the FFmpeg filter and added the result as a new clip.")
            }
        }
    }

    /** Build a GL-style transition between two clips via FFmpeg xfade, adding the result as a new clip. */
    private fun applyTransition(fromClipId: String, toClipId: String, type: String, durationSec: Float): JSONObject {
        val ffmpeg = settingsProvider().ffmpegPath
        require(ffmpeg.isNotBlank()) {
            "No ffmpeg set. Set an ffmpeg executable in Settings → AI Analyzer → FFmpeg filters."
        }
        val doc = vm.uiState.value.document
        val from = doc.clips.firstOrNull { it.id == fromClipId }
            ?: throw IllegalArgumentException("Clip not found: $fromClipId")
        val to = doc.clips.firstOrNull { it.id == toClipId }
            ?: throw IllegalArgumentException("Clip not found: $toClipId")
        val fromMedia = doc.mediaFor(from) ?: throw IllegalArgumentException("No media for clip: $fromClipId")
        val toMedia = doc.mediaFor(to) ?: throw IllegalArgumentException("No media for clip: $toClipId")
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Building transition…", pausable = false,
        ) { _ ->
            val baked = com.hereliesaz.guillotine.media.FfmpegFilter.xfade(
                context,
                com.hereliesaz.guillotine.media.FfmpegFilter.Segment(fromMedia.uri, from.trimStartMs / 1000f, from.durationMs / 1000f),
                com.hereliesaz.guillotine.media.FfmpegFilter.Segment(toMedia.uri, to.trimStartMs / 1000f, to.durationMs / 1000f),
                type, durationSec, ffmpeg, java.io.File(context.cacheDir, "ffmpeg"),
            )
            vm.addMedia(
                listOf(
                    MediaItem(
                        newId(), Uri.fromFile(baked.file).toString(),
                        "transition: ${fromMedia.name} → ${toMedia.name}",
                        MediaKind.VIDEO, baked.durationMs, baked.hasAudio,
                    ),
                ),
            )
            ok().apply {
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Made a '$type' transition between the two clips and added it as a new clip.")
            }
        }
    }

    /** Diarize a clip's audio into speaker turns (who spoke when), mapped to timeline ms. */
    private fun diarizeClip(clipId: String, numSpeakers: Int): JSONObject {
        val settings = settingsProvider()
        val seg = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "diarizeSegModelPath")
        val embed = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "diarizeEmbedModelPath")
        require(seg.isNotBlank() && embed.isNotBlank()) {
            "Speaker diarization needs both models. Set them in Settings → AI Analyzer → Speaker diarization."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), YamnetClassifier.SAMPLE_RATE)
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to diarize.")
        val samples = YamnetClassifier.resampleTo16k(pcm.samples, pcm.sampleRate)
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Identifying speakers…", pausable = false,
        ) { _ ->
            val turns = SherpaDiarizer.diarize(seg, embed, samples, numSpeakers)
            val arr = JSONArray()
            val speakers = HashSet<Int>()
            for (t in turns) {
                speakers += t.speaker
                arr.put(JSONObject().apply {
                    put("speaker", t.speaker)
                    put("startMs", clip.startTimeMs + (t.startMs - clip.trimStartMs))
                    put("endMs", clip.startTimeMs + (t.endMs - clip.trimStartMs))
                })
            }
            ok().apply {
                put("speakerCount", speakers.size)
                put("turnCount", turns.size)
                put("turns", arr)
                put(
                    "humanSummary",
                    if (turns.isEmpty()) "No distinct speakers detected."
                    else "Found ${speakers.size} speaker(s) across ${turns.size} turn(s).",
                )
            }
        }
    }

    /** Filler words this removes (lowercased, punctuation-stripped word match). */
    private val FILLER_WORDS = setOf("um", "uh", "er", "erm", "hmm", "mm", "umm", "uhh", "uhm", "ah")

    /**
     * Remove filler words from a clip using offline Whisper word timings, ripple-deleting each filler's
     * timeline range (latest-first so earlier ranges stay valid). Timings are approximate.
     */
    private fun removeFillers(clipId: String): JSONObject {
        val dir = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "asrModelPath")
        require(dir.isNotBlank()) {
            "No ASR model set. Download Whisper in Settings → AI Analyzer → Speech (ASR)."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), YamnetClassifier.SAMPLE_RATE)
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to de-filler.")
        val samples = YamnetClassifier.resampleTo16k(pcm.samples, pcm.sampleRate)
        val words = SherpaAsr.transcribeWords(dir, samples)
        // Filler word source ranges → timeline ranges within this clip.
        val ranges = words
            .filter { it.text.lowercase().trim().trim('.', ',', '!', '?', ';', ':') in FILLER_WORDS }
            .map { w ->
                val tlStart = clip.startTimeMs + (w.startMs - clip.trimStartMs)
                val tlEnd = clip.startTimeMs + (w.endMs - clip.trimStartMs)
                tlStart.coerceIn(clip.startTimeMs, clip.endTimeMs) to tlEnd.coerceIn(clip.startTimeMs, clip.endTimeMs)
            }
            .filter { it.second - it.first >= 40L }
            .sortedByDescending { it.first } // delete latest-first so earlier ranges don't shift
        if (ranges.isEmpty()) {
            return JSONObject().apply {
                put("ok", true); put("removed", 0)
                put("humanSummary", "No filler words detected in the clip.")
            }
        }
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Removing filler words…", pausable = false,
        ) { _ ->
            var removed = 0
            for ((s, e) in ranges) { vm.rippleDeleteRange(s, e); removed++ }
            ok().apply {
                put("removed", removed)
                put("humanSummary", "Removed $removed filler word(s) (um/uh/…) and closed the gaps.")
            }
        }
    }

    /**
     * Find video clips whose sampled frames match [query] using on-device image labels (~400 common
     * things/scenes). Samples up to 4 frames per clip; returns each matching clip with the matched label
     * and the timeline ms of the best-scoring hit. Read-only — edits nothing.
     */
    private fun searchClips(query: String): JSONObject {
        val q = query.trim().lowercase()
        require(q.isNotBlank()) { "Give something to search for, e.g. \"dog\"." }
        val doc = vm.uiState.value.document
        val videoClips = doc.clips.filter { it.type == com.hereliesaz.guillotine.model.ClipType.VIDEO }
        val matches = JSONArray()
        var found = 0
        com.hereliesaz.guillotine.ai.SceneClassifier(context).use { classifier ->
            for (clip in videoClips) {
                val media = doc.mediaFor(clip) ?: continue
                val uri = Uri.parse(media.uri)
                var bestLabel: String? = null
                var bestScore = 0f
                var bestTimeline = clip.startTimeMs
                // Sample 4 frames across the clip's source range.
                for (k in 1..4) {
                    val sourceMs = clip.trimStartMs + clip.durationMs * k / 5
                    val frame = grabFrame(uri, sourceMs) ?: continue
                    val labels = try { classifier.classify(frame) } finally { frame.recycle() }
                    val hit = labels.firstOrNull { it.lowerText.contains(q) || q.contains(it.lowerText) }
                    if (hit != null && hit.confidence > bestScore) {
                        bestScore = hit.confidence
                        bestLabel = hit.text
                        bestTimeline = clip.startTimeMs + (sourceMs - clip.trimStartMs)
                    }
                }
                if (bestLabel != null) {
                    found++
                    matches.put(JSONObject().apply {
                        put("clipId", clip.id)
                        put("name", media.name)
                        put("label", bestLabel)
                        put("confidence", (bestScore * 100).toInt())
                        put("timelineMs", bestTimeline)
                    })
                }
            }
        }
        return ok().apply {
            put("matchCount", found)
            put("matches", matches)
            put(
                "humanSummary",
                if (found == 0) "No clips matched \"$query\"."
                else "Found $found clip(s) matching \"$query\".",
            )
        }
    }

    /** Describe the current (or [clipId]) frame in natural language with the on-device multimodal VLM. */
    private fun captionFrame(clipId: String, prompt: String): JSONObject {
        val path = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "vlmModelPath")
        require(path.isNotBlank()) {
            "No VLM model set. Add a multimodal .task in Settings → AI Analyzer → Frame captioning (VLM)."
        }
        val st = vm.uiState.value
        val now = st.currentTimeMs
        // An explicit clip_id must resolve (like every other tool); blank = the video clip at the playhead.
        val clip = if (clipId.isNotBlank()) {
            st.document.clips.firstOrNull { it.id == clipId }
                ?: throw IllegalArgumentException("Clip not found: $clipId")
        } else {
            com.hereliesaz.guillotine.model.TimelineMath.activeClip(
                st.document.clips, com.hereliesaz.guillotine.model.ClipType.VIDEO, now,
            ) ?: throw IllegalStateException("No video clip at the playhead — scrub onto one.")
        }
        val media = st.document.mediaFor(clip)
            ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = grabFrame(Uri.parse(media.uri), sourceMs)
            ?: throw IllegalStateException("Could not read the current frame.")
        val question = prompt.ifBlank { "Describe what is happening in this image in detail." }
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Looking at the frame…", pausable = false,
        ) { _ ->
            val text = try {
                VlmCaptioner.describe(context, path, frame, question)
            } finally {
                frame.recycle()
            }
            ok().apply {
                put("caption", text)
                put("humanSummary", if (text.isBlank()) "The VLM returned no description." else text)
            }
        }
    }

    // ---- on-device image effects (TFLite) ----------------------------------

    private fun applyImageEffect(effect: String, clipId: String): JSONObject {
        val settings = settingsProvider()
        val key = effect.lowercase().trim()
        val path = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "effect_" + key)
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

    // ---- auto color-correct / shot-match (on-device DSP, no model) -----------

    /** Tone of a frame (all 0..1): mean luminance, contrast spread (p95−p5), mean HSV saturation. */
    private data class Tone(val lum: Float, val spread: Float, val sat: Float)

    /** Sample a frame at low res and summarise its tone (for auto-color and shot-match). */
    private fun toneStats(bmp: android.graphics.Bitmap): Tone {
        val n = 64
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        if (small !== bmp) small.recycle()
        val lums = FloatArray(px.size)
        var sumL = 0f
        var sumS = 0f
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            lums[i] = 0.299f * r + 0.587f * g + 0.114f * b
            sumL += lums[i]
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            sumS += if (mx > 1e-4f) (mx - mn) / mx else 0f
        }
        lums.sort()
        val p5 = lums[(lums.size * 0.05f).toInt().coerceIn(0, lums.size - 1)]
        val p95 = lums[(lums.size * 0.95f).toInt().coerceIn(0, lums.size - 1)]
        return Tone(sumL / px.size, (p95 - p5).coerceIn(0.01f, 1f), sumS / px.size)
    }

    /** Tone of the frame at a clip's mid-point. */
    private fun clipTone(clip: com.hereliesaz.guillotine.model.TimelineClip): Tone {
        val media = vm.uiState.value.document.mediaFor(clip)
            ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val at = com.hereliesaz.guillotine.model.TimelineMath
            .sourceTimeMs(clip, clip.startTimeMs + clip.durationMs / 2).coerceAtLeast(0L)
        val frame = grabFrame(Uri.parse(media.uri), at)
            ?: throw IllegalStateException("Could not read a frame from clip ${clip.id}.")
        return try { toneStats(frame) } finally { frame.recycle() }
    }

    /** Auto color-correct: nudge exposure/contrast/saturation toward a balanced look, via the clip filters. */
    private fun autoColor(clipId: String): JSONObject {
        val st = vm.uiState.value
        val clip = (if (clipId.isNotBlank()) st.document.clips.firstOrNull { it.id == clipId } else null)
            ?: com.hereliesaz.guillotine.model.TimelineMath.activeClip(
                st.document.clips, com.hereliesaz.guillotine.model.ClipType.VIDEO, st.currentTimeMs,
            )
            ?: throw IllegalStateException("No video clip to color-correct — scrub onto one or pass clip_id.")
        val t = clipTone(clip)
        val brightness = (0.5f / t.lum.coerceAtLeast(0.02f)).coerceIn(0.6f, 1.8f)
        val contrast = (0.7f / t.spread).coerceIn(0.85f, 1.6f)
        val saturation = if (t.sat < 0.28f) 1.18f else 1f
        vm.updateClipFilters(clip.id) { it.copy(brightness = brightness, contrast = contrast, saturation = saturation) }
        return ok().apply {
            put("brightness", brightness.toDouble())
            put("contrast", contrast.toDouble())
            put("saturation", saturation.toDouble())
            put(
                "humanSummary",
                "Auto color-corrected: exposure ×%.2f, contrast ×%.2f%s.".format(
                    brightness, contrast,
                    if (saturation != 1f) ", saturation ×%.2f".format(saturation) else "",
                ),
            )
        }
    }

    /** Apply a `.cube` 3D LUT color grade to a clip (path validated + parseable). */
    private fun applyLut(clipId: String, path: String): JSONObject {
        require(path.isNotBlank()) { "Provide the path to a .cube LUT file." }
        val file = java.io.File(path)
        require(file.isFile) { "No .cube file at: $path" }
        // Parse up front so a bad file fails here with a clear message rather than silently doing nothing.
        runCatching { com.hereliesaz.guillotine.media.CubeLut.parse(file.readText()) }
            .onFailure { throw IllegalArgumentException("Not a valid 3D .cube LUT: ${it.message}") }
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(lutPath = file.absolutePath) }
        return ok().apply { put("humanSummary", "Applied LUT ${file.name} to clip ${clip.id}.") }
    }

    /** Replace a clip's background: matte the subject and composite it over a solid color or image. */
    private fun replaceBackgroundTool(clipId: String, color: String, imagePath: String): JSONObject {
        val fg = resolveClipOrPlayhead(clipId)
        val bg: MediaItem = if (imagePath.isNotBlank()) {
            val f = java.io.File(imagePath)
            require(f.isFile) { "No image at: $imagePath" }
            MediaItem(newId(), Uri.fromFile(f).toString(), "bg: ${f.name}", MediaKind.IMAGE, fg.durationMs)
        } else {
            val hex = color.ifBlank { "#000000" }
            val argb = runCatching { android.graphics.Color.parseColor(hex) }
                .getOrElse { throw IllegalArgumentException("Unrecognized color \"$hex\" — use hex like #1e90ff or a name like \"blue\".") }
            val file = solidColorPng(argb)
            MediaItem(newId(), Uri.fromFile(file).toString(), "bg: $hex", MediaKind.IMAGE, fg.durationMs)
        }
        vm.replaceBackground(fg.id, bg)
        return ok().apply {
            put(
                "humanSummary",
                "Matted the subject on clip ${fg.id} and put it over " +
                    (if (imagePath.isNotBlank()) "the image background." else "a ${color.ifBlank { "black" }} background."),
            )
        }
    }

    /** Write a small solid-[argb] PNG to the cache and return the file (a background fill). */
    private fun solidColorPng(argb: Int): java.io.File {
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        val dir = java.io.File(context.cacheDir, "bg").apply { mkdirs() }
        val file = java.io.File(dir, "${newId()}.png")
        return try {
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            file
        } finally {
            bmp.recycle()
        }
    }

    /** Remove a clip's `.cube` LUT grade. */
    private fun clearLut(clipId: String): JSONObject {
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(lutPath = "") }
        return ok().apply { put("humanSummary", "Removed the LUT from clip ${clip.id}.") }
    }

    /** Apply a GLSL/ISF shader effect to a clip, with optional scalar-input overrides. */
    private fun applyShader(clipId: String, path: String, params: JSONObject?): JSONObject {
        require(path.isNotBlank()) { "Provide the path to an .isf / .fs / .glsl shader file." }
        val file = java.io.File(path)
        require(file.isFile) { "No shader file at: $path" }
        // Parse up front so unsupported/malformed shaders fail here with a clear message.
        val program = runCatching { com.hereliesaz.guillotine.media.GlslShader.parse(file.readText()) }
            .getOrElse { throw IllegalArgumentException("Unsupported shader: ${it.message}") }
        // Only accept overrides for the shader's known scalar (single-value) uniforms.
        val scalar = program.uniforms.filter { it.values.size == 1 }.associateBy { it.name }
        val overrides = HashMap<String, Float>()
        params?.keys()?.forEach { k ->
            scalar[k]?.let { u -> overrides[k] = params.optDouble(k, u.values[0].toDouble()).toFloat() }
        }
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(shaderPath = file.absolutePath, shaderParams = overrides) }
        return ok().apply {
            put(
                "humanSummary",
                "Applied shader ${file.name}" +
                    (if (overrides.isNotEmpty()) " with ${overrides.size} param(s)" else "") +
                    " to clip ${clip.id}.",
            )
        }
    }

    /** Remove a clip's GLSL/ISF shader effect (and its param overrides). */
    private fun clearShader(clipId: String): JSONObject {
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(shaderPath = "", shaderParams = emptyMap()) }
        return ok().apply { put("humanSummary", "Removed the shader from clip ${clip.id}.") }
    }

    /** List a shader's adjustable scalar inputs (name, type, default, min, max). */
    private fun listShaderParams(path: String): JSONObject {
        val file = java.io.File(path)
        require(file.isFile) { "No shader file at: $path" }
        val program = runCatching { com.hereliesaz.guillotine.media.GlslShader.parse(file.readText()) }
            .getOrElse { throw IllegalArgumentException("Unsupported shader: ${it.message}") }
        val arr = JSONArray()
        program.uniforms.filter { it.values.size == 1 }.forEach { u ->
            arr.put(JSONObject().apply {
                put("name", u.name)
                put("type", u.type.name.lowercase())
                put("default", u.values[0].toDouble())
                put("min", u.min.toDouble())
                put("max", u.max.toDouble())
            })
        }
        return ok().apply {
            put("params", arr)
            put(
                "humanSummary",
                if (arr.length() == 0) "This shader has no adjustable scalar inputs." else "${arr.length()} adjustable input(s).",
            )
        }
    }

    /**
     * The clip [clipId], or the video clip under the playhead when blank. A non-blank id that doesn't
     * match throws (rather than silently editing the playhead clip, which risks the wrong clip on a
     * stale id). Throws too when blank and nothing is under the playhead.
     */
    private fun resolveClipOrPlayhead(clipId: String): com.hereliesaz.guillotine.model.TimelineClip {
        val st = vm.uiState.value
        if (clipId.isNotBlank()) {
            return st.document.clips.firstOrNull { it.id == clipId }
                ?: throw IllegalArgumentException("Clip not found: $clipId")
        }
        return com.hereliesaz.guillotine.model.TimelineMath.activeClip(
            st.document.clips, com.hereliesaz.guillotine.model.ClipType.VIDEO, st.currentTimeMs,
        ) ?: throw IllegalStateException("No clip — scrub onto one or pass clip_id.")
    }

    /** Toggle on-device face anonymization (blur every detected face) on a clip. */
    private fun blurFaces(clipId: String, enabled: Boolean): JSONObject {
        val st = vm.uiState.value
        val clip = (if (clipId.isNotBlank()) st.document.clips.firstOrNull { it.id == clipId } else null)
            ?: com.hereliesaz.guillotine.model.TimelineMath.activeClip(
                st.document.clips, com.hereliesaz.guillotine.model.ClipType.VIDEO, st.currentTimeMs,
            )
            ?: throw IllegalStateException("No clip to blur faces on — scrub onto one or pass clip_id.")
        vm.updateClipFilters(clip.id) { it.copy(blurFaces = enabled) }
        return ok().apply {
            put("humanSummary", if (enabled) "Blurring faces on clip ${clip.id}." else "Face blur off for clip ${clip.id}.")
        }
    }

    /** Shot-match: set the TARGET clip's tone filters to align its look with the SOURCE clip. */
    private fun matchColor(sourceClipId: String, targetClipId: String): JSONObject {
        val st = vm.uiState.value
        val src = st.document.clips.firstOrNull { it.id == sourceClipId }
            ?: throw IllegalArgumentException("Source clip not found: $sourceClipId")
        val tgt = st.document.clips.firstOrNull { it.id == targetClipId }
            ?: throw IllegalArgumentException("Target clip not found: $targetClipId")
        val s = clipTone(src)
        val d = clipTone(tgt)
        val brightness = (s.lum / d.lum.coerceAtLeast(0.02f)).coerceIn(0.4f, 2.5f)
        val contrast = (s.spread / d.spread).coerceIn(0.5f, 2f)
        val saturation = (s.sat / d.sat.coerceAtLeast(0.02f)).coerceIn(0.5f, 2f)
        vm.updateClipFilters(targetClipId) { it.copy(brightness = brightness, contrast = contrast, saturation = saturation) }
        return ok().apply {
            put(
                "humanSummary",
                "Matched the target clip's look to the source (exposure ×%.2f, contrast ×%.2f, saturation ×%.2f)."
                    .format(brightness, contrast, saturation),
            )
        }
    }

    /** Depth-of-field bokeh: run the depth model on the current frame, blur the far background, add it. */
    private fun applyBokeh(clipId: String, strength: Float): JSONObject {
        val settings = settingsProvider()
        val depthPath = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "effect_depth")
        require(depthPath.isNotBlank()) {
            "Bokeh needs the depth model. Set it in Settings → AI Analyzer → Image effects (depth)."
        }
        val st = vm.uiState.value
        val now = st.currentTimeMs
        val clip = (if (clipId.isNotBlank()) st.document.clips.firstOrNull { it.id == clipId } else null)
            ?: com.hereliesaz.guillotine.model.TimelineMath.activeClip(
                st.document.clips, com.hereliesaz.guillotine.model.ClipType.VIDEO, now,
            )
            ?: throw IllegalStateException("No video clip to apply bokeh to — scrub onto one.")
        val media = st.document.mediaFor(clip)
            ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = grabFrame(Uri.parse(media.uri), sourceMs)
            ?: throw IllegalStateException("Could not read the current frame.")
        return OperationController.runBlocking(
            context, OperationKind.GENERATE, "Applying bokeh…", pausable = true,
        ) { _ ->
            val depth = TfliteImageModel(depthPath).use { it.run(frame) }
                ?: run { frame.recycle(); throw IllegalStateException("The depth model produced no output — check the .tflite file.") }
            val radius = (14f * strength.coerceIn(0.2f, 4f)).toInt().coerceAtLeast(2)
            val out = com.hereliesaz.guillotine.ai.DepthBokeh.apply(frame, depth, radius)
            depth.recycle(); frame.recycle()
            val uri = saveBitmap(out)
            out.recycle()
            vm.addMedia(listOf(MediaItem(newId(), uri, "bokeh: ${media.name}", MediaKind.IMAGE, 5_000)))
            ok().apply {
                put("clipCount", vm.uiState.value.document.clips.size)
                put("humanSummary", "Added a depth-of-field (bokeh) frame — near subject sharp, background blurred.")
            }
        }
    }

    /** Normalize each audio clip to [targetLufs] using ITU-R BS.1770 K-weighted loudness. */
    private fun normalizeLoudness(targetLufs: Double): JSONObject {
        val doc = vm.uiState.value.document
        val clips = doc.clips.filter {
            val kind = doc.mediaFor(it)?.kind
            kind == MediaKind.AUDIO || kind == MediaKind.VIDEO
        }
        var normalized = 0
        for (clip in clips) {
            val media = doc.mediaFor(clip) ?: continue
            val pcm = PcmDecoder.decode(context, Uri.parse(media.uri), 16_000) ?: continue
            if (pcm.samples.isEmpty()) continue
            val lufs = com.hereliesaz.guillotine.ai.Loudness.measureLufs(pcm.samples, pcm.sampleRate)
            if (lufs <= -70.0) continue // silence
            val gain = com.hereliesaz.guillotine.ai.Loudness.gainToTarget(lufs, targetLufs)
            vm.updateClipFilters(clip.id) { it.copy(volume = gain.coerceIn(0f, 2f)) }
            normalized++
        }
        return ok().apply {
            put("normalized", normalized)
            put("targetLufs", targetLufs)
            put(
                "humanSummary",
                if (normalized == 0) "No measurable audio to normalize."
                else "Normalized $normalized audio clip(s) toward ${targetLufs.toInt()} LUFS.",
            )
        }
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
                    context, frame, term.ifBlank { null }, isFace, com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "idEmbedModelPath"), com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "faceEmbedModelPath"),
                )
                if (vecs.isEmpty()) throw IllegalStateException(
                    "Nothing to learn as a non-example here — scrub to a frame that shows a look-alike.",
                )
                LearnedConceptStore.addNegatives(context, name, terms, vecs, isFace)
            } else {
                val vec = prov.captureReferenceEmbedding(
                    context, frame, term.ifBlank { null }, isFace, com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "idEmbedModelPath"), com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "faceEmbedModelPath"),
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
                embedModelPath = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "idEmbedModelPath"), faceModelPath = com.hereliesaz.guillotine.platform.ModelResolver.resolve(context, "faceEmbedModelPath"),
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
    private fun setClipFilter(clipId: String, property: String, value: Float): JSONObject {
        val doc = vm.uiState.value.document
        doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
        
        val prop = try {
            com.hereliesaz.guillotine.model.KeyframeProperty.valueOf(property.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("Unknown property: $property")
        }
        
        vm.updateClipFilters(clipId) { f ->
            when (prop) {
                com.hereliesaz.guillotine.model.KeyframeProperty.BRIGHTNESS -> f.copy(brightness = value)
                com.hereliesaz.guillotine.model.KeyframeProperty.CONTRAST -> f.copy(contrast = value)
                com.hereliesaz.guillotine.model.KeyframeProperty.SATURATION -> f.copy(saturation = value)
                com.hereliesaz.guillotine.model.KeyframeProperty.HUE -> f.copy(hueRotate = value)
                com.hereliesaz.guillotine.model.KeyframeProperty.SEPIA -> f.copy(sepia = value)
                com.hereliesaz.guillotine.model.KeyframeProperty.SPEED -> f.copy(speed = value)
                com.hereliesaz.guillotine.model.KeyframeProperty.VOLUME -> f.copy(volume = value)
                com.hereliesaz.guillotine.model.KeyframeProperty.PAN -> f.copy(pan = value)
                else -> f
            }
        }
        
        if (prop in com.hereliesaz.guillotine.model.KeyframeProperty.TRANSFORM) {
            vm.updateClip(clipId) { c ->
                when (prop) {
                    com.hereliesaz.guillotine.model.KeyframeProperty.SCALE -> c.copy(scale = value)
                    com.hereliesaz.guillotine.model.KeyframeProperty.ROTATION -> c.copy(rotation = value)
                    com.hereliesaz.guillotine.model.KeyframeProperty.OFFSET_X -> c.copy(offsetX = value)
                    com.hereliesaz.guillotine.model.KeyframeProperty.OFFSET_Y -> c.copy(offsetY = value)
                    else -> c
                }
            }
        }

        return ok().apply { put("humanSummary", "Set $property to $value on clip $clipId.") }
    }

    private fun addKeyframe(clipId: String, property: String, timeMs: Long, value: Float): JSONObject {
        val doc = vm.uiState.value.document
        doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
            
        val prop = try {
            com.hereliesaz.guillotine.model.KeyframeProperty.valueOf(property.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("Unknown property: $property")
        }
        
        vm.updateClip(clipId) { c ->
            val newKf = com.hereliesaz.guillotine.model.Keyframe(
                id = com.hereliesaz.guillotine.model.newId(),
                timeMs = timeMs,
                value = value,
                property = prop
            )
            c.copy(keyframes = c.keyframes + newKf)
        }
        
        return ok().apply { put("humanSummary", "Added $property keyframe at ${timeMs}ms with value $value on clip $clipId.") }
    }

    private fun clearKeyframes(clipId: String, property: String): JSONObject {
        val doc = vm.uiState.value.document
        doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
            
        val prop = try {
            com.hereliesaz.guillotine.model.KeyframeProperty.valueOf(property.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("Unknown property: $property")
        }
        
        vm.updateClip(clipId) { c ->
            c.copy(keyframes = c.keyframes.filter { it.property != prop })
        }
        
        return ok().apply { put("humanSummary", "Cleared all $property keyframes on clip $clipId.") }
    }

    // ---- azphalt plugin tools ----

    private fun listAzpPlugins(): JSONObject {
        val baseDir = java.io.File(context.filesDir, "extensions")
        if (!baseDir.exists()) return ok().apply { put("plugins", JSONArray()) }
        
        val pluginsList = JSONArray()
        
        baseDir.listFiles { _, name -> name.endsWith(".azp") }?.forEach { azpFile ->
            try {
                val bytes = azpFile.readBytes()
                val loaded = com.hereliesaz.guillotine.azphalt.AzpPackage.load(bytes)
                val manifest = loaded.manifest
                
                val id = manifest.id
                val name = manifest.name
                
                val tagsList = manifest.assets.firstOrNull()?.tags ?: emptyList()
                val tags = JSONArray()
                tagsList.forEach { tags.put(it) }
                
                var cat = "layer-effects"
                if (id.contains("vegas")) cat = "vegas-inspired"
                else if (id.contains("scenery")) cat = "layer-effects-scenery"
                else if (id.contains("smart")) cat = "kinetic-typography-smart"
                else if (id.contains("typography") || id.contains("type") || tagsList.contains("text")) cat = "kinetic-typography"
                
                val pluginObj = JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("tags", tags)
                    put("category", cat)
                }
                pluginsList.put(pluginObj)
            } catch (e: Exception) {
                // Skip invalid
            }
        }
        
        return ok().apply {
            put("plugins", pluginsList)
            put("humanSummary", "Listed ${pluginsList.length()} available Azphalt plugins.")
        }
    }
    
    private fun applyAzpPlugin(clipId: String, pluginId: String): JSONObject {
        val clip = vm.uiState.value.document.clips.find { it.id == clipId }
            ?: throw IllegalArgumentException("Clip $clipId not found.")

        // Find the installed .azp whose manifest id matches, and try to pull a `motion` (kinetic
        // typography) asset out of it. A motion plugin is baked into real keyframes on the caption so it
        // renders in preview + export; anything else just records the applied-plugin id as before.
        val baseDir = java.io.File(context.filesDir, "extensions")
        val motionBytes: ByteArray? = baseDir.listFiles { _, name -> name.endsWith(".azp") }
            ?.firstNotNullOfOrNull { f ->
                runCatching {
                    val bytes = f.readBytes()
                    val plan = com.hereliesaz.guillotine.azphalt.AzpMotionInstaller.plan(bytes, emptySet())
                    if (plan.loaded.manifest.id != pluginId) return@runCatching null
                    val motion = plan.motions.firstOrNull() ?: return@runCatching null
                    com.hereliesaz.guillotine.azphalt.AzpMotionInstaller.bundledBytes(plan, motion)
                }.getOrNull()
            }

        if (motionBytes != null && clip.type == com.hereliesaz.guillotine.model.ClipType.TEXT) {
            vm.applyCaptionMotion(clipId, motionBytes, pluginId)
            return ok().apply {
                put("humanSummary", "Applied kinetic-typography preset $pluginId to caption $clipId (baked keyframes).")
            }
        }

        vm.updateClip(clipId) { c -> c.copy(azpPluginId = pluginId) }
        return ok().apply {
            put("humanSummary", "Applied plugin $pluginId to clip $clipId.")
        }
    }

    private fun clearAzpPlugin(clipId: String): JSONObject {
        vm.uiState.value.document.clips.find { it.id == clipId }
            ?: throw IllegalArgumentException("Clip $clipId not found.")
        vm.clearCaptionMotion(clipId)
        return ok().apply { put("humanSummary", "Cleared the applied plugin/preset from clip $clipId.") }
    }

}
