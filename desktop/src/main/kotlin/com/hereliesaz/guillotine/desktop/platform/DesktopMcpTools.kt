package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.BeatAnalyzer
import com.hereliesaz.guillotine.ai.Loudness
import com.hereliesaz.guillotine.ai.Spleeter
import com.hereliesaz.guillotine.ai.VocalIsolator
import com.hereliesaz.guillotine.ai.gen.AsyncJobPoller
import com.hereliesaz.guillotine.ai.gen.GenBackends
import com.hereliesaz.guillotine.ai.gen.GenKind
import com.hereliesaz.guillotine.ai.gen.GenProviderType
import com.hereliesaz.guillotine.ai.gen.GenRequest
import com.hereliesaz.guillotine.ai.gen.PollConfig
import com.hereliesaz.guillotine.ai.gen.meta
import com.hereliesaz.guillotine.desktop.media.DesktopFfmpegFilter
import com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder
import com.hereliesaz.guillotine.desktop.media.DesktopMediaImport
import com.hereliesaz.guillotine.desktop.media.DesktopVoskTranscriber
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.mcp.boolProp
import com.hereliesaz.guillotine.mcp.intProp
import com.hereliesaz.guillotine.mcp.numberProp
import com.hereliesaz.guillotine.mcp.stringProp
import com.hereliesaz.guillotine.mcp.toolDefinition
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.BeatMap
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.CubicBezier
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.newId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

class DesktopMcpTools(
    private val vm: EditorViewModel,
    private val settingsProvider: () -> AiSettings = { AiSettings() },
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
        put(toolDefinition(
            "transcribe_clip",
            "Transcribe a clip's audio ON-DEVICE (offline Vosk, no key/network) and add timed caption " +
                "text clips to the timeline, grouped with the source clip. Each caption appears and " +
                "disappears in sync with the spoken words. Requires a Vosk model in Settings → " +
                "Transcription; if it isn't set it returns an error naming the setting — relay it.",
            objSchema("clip_id" to stringProp("The clip to transcribe"), required = listOf("clip_id")),
        ))
        put(toolDefinition(
            "animated_transcribe_clip",
            "Transcribe a clip's audio ON-DEVICE (offline Vosk) and create ANIMATED per-syllable " +
                "captions: each word is split into syllables placed on separate video tracks so they all " +
                "appear simultaneously, with SCALE keyframes that ramp each syllable from small to large " +
                "as it is spoken — a \"grow as said\" kinetic typography effect. Use when the user asks " +
                "for animated, kinetic, per-word, or per-syllable text/captions. Requires a Vosk model in " +
                "Settings → Transcription; relay its error if unset.",
            objSchema("clip_id" to stringProp("The clip to transcribe"), required = listOf("clip_id")),
        ))
        put(toolDefinition(
            "transcribe_precise",
            "Transcribe a clip's audio ON-DEVICE with an offline Whisper (sherpa-onnx) model and return " +
                "the transcript text. NOTE: not yet available on desktop — sherpa-onnx has no clean JVM " +
                "distribution, so this returns an error pointing at transcribe_clip (Vosk) instead.",
            objSchema("clip_id" to stringProp("The clip whose audio to transcribe"), required = listOf("clip_id")),
        ))
        put(toolDefinition(
            "add_voiceover",
            "Synthesize speech from text ON-DEVICE (offline neural TTS via sherpa-onnx) and add it to the " +
                "timeline as an audio clip. NOTE: not yet available on desktop (no clean sherpa-onnx JVM " +
                "distribution); returns an error rather than faking it.",
            objSchema(
                "text" to stringProp("The words to speak"),
                "speed" to numberProp("Speaking rate (default 1.0; <1 slower, >1 faster)"),
                required = listOf("text"),
            ),
        ))
        put(toolDefinition(
            "diarize_clip",
            "Speaker diarization ON-DEVICE (who spoke when). NOTE: not yet available on desktop (no clean " +
                "sherpa-onnx JVM distribution); returns an error rather than faking it.",
            objSchema(
                "clip_id" to stringProp("The clip whose audio to diarize"),
                "num_speakers" to intProp("Known speaker count (0 = infer automatically)"),
                required = listOf("clip_id"),
            ),
        ))
        put(toolDefinition(
            "remove_fillers",
            "Remove filler words (\"um\", \"uh\", \"er\", \"hmm\") from a clip ON-DEVICE using offline " +
                "Whisper (sherpa-onnx) word timings. NOTE: not yet available on desktop (no clean " +
                "sherpa-onnx JVM distribution); returns an error rather than faking it.",
            objSchema("clip_id" to stringProp("The clip to de-filler"), required = listOf("clip_id")),
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

        // ---- color grading: auto-correct / shot-match / LUT / shader (on-device) ----
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
            "Record a custom GLSL shader effect (a standard **ISF** `.isf` shader or a raw `.fs`/`.glsl` " +
                "fragment) on a clip. Only single-pass, single-image shaders are accepted (multi-pass, " +
                "feedback, audio, and two-input transition shaders are rejected). `path` is a filesystem " +
                "path (usually a file the user picked). NOTE: on desktop the shader is saved on the clip " +
                "but the live GLSL renderer is not implemented yet, so it is NOT visible in preview or " +
                "export. clear_shader removes it.",
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

        // ---- output / export config (pure VM) ----
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

        // ---- rhythm / edit-to-the-beat (on-device) ----
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

        // ---- audio levelling (on-device DSP, no model) ----
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

        // ---- vocal / stem separation (on-device) ----
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

        // ---- FFmpeg filtergraph (in-process via bundled FFmpeg) ----
        put(toolDefinition(
            "apply_ffmpeg_filter",
            "Bake a standard **FFmpeg `-vf` filtergraph** onto a clip ON-DEVICE and add the result as a new " +
                "clip — the whole FFmpeg filter ecosystem, and **Frei0r** plugins via `frei0r=<name>:<params>`. " +
                "Use for \"apply the ffmpeg filter <graph>\", \"run a frei0r plugin\", \"add a vintage/vhs/" +
                "chromashift filter\", \"eq/curves/deband this\". `filter` is the raw -vf graph, e.g. " +
                "\"hue=s=0, gblur=sigma=2\" or \"frei0r=cartoon\". Runs in-process via the bundled FFmpeg; " +
                "this is a bake-to-new-clip step, not a live filter.",
            objSchema(
                "clip_id" to stringProp("The clip whose video to filter"),
                "filter" to stringProp("An FFmpeg -vf filtergraph (Frei0r via frei0r=name:params)"),
                required = listOf("clip_id", "filter"),
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
        // TODO(desktop): apply_transition (FFmpeg xfade between two clips) is not wired here. Android
        // shells an external ffmpeg with a two-input `xfade`+`acrossfade` filter_complex; a faithful
        // in-process port needs a two-input FFmpegFrameFilter with precise offset/PTS synchronization
        // that can't be verified in this environment, so it is intentionally left out rather than
        // shipped unverified. Single-input filtering (apply_ffmpeg_filter) IS wired.
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
        "describe_current_frame" -> notAvailable(name)
        "transcribe_clip" -> transcribeClip(args.getString("clip_id"))
        "animated_transcribe_clip" -> animatedTranscribeClip(args.getString("clip_id"))
        "transcribe_precise" -> speechToolUnavailable("transcribe_precise", "an offline Whisper (sherpa-onnx) ASR model")
        "add_voiceover" -> speechToolUnavailable("add_voiceover", "an offline neural TTS (sherpa-onnx) voice")
        "diarize_clip" -> speechToolUnavailable("diarize_clip", "the sherpa-onnx speaker-diarization models")
        "remove_fillers" -> speechToolUnavailable("remove_fillers", "an offline Whisper (sherpa-onnx) ASR model")
        "sync_by_audio" -> syncByAudio(args.getString("reference_clip_id"), args.getString("clip_id"), args.optInt("max_offset_sec", 15))
        "create_user_tool" -> createUserTool(args.getString("name"), args.getString("description"))
        "list_user_tools" -> listUserTools()
        "delete_user_tool" -> deleteUserTool(args.getString("name"))
        "run_user_tool" -> runUserTool(args.getString("name"), args.getString("clip_id"))
        "start_recording" -> startRecording(args.getString("clip_id"))
        "stop_recording" -> stopRecording(args.getString("name"), args.optString("extra_instructions", ""))
        "discard_recording" -> discardRecording()
        "set_clip_filter" -> setClipFilter(args.getString("clip_id"), args.getString("property"), args.getDouble("value").toFloat())
        "add_keyframe" -> addKeyframe(args.getString("clip_id"), args.getString("property"), args.getLong("time_ms"), args.getDouble("value").toFloat())
        "clear_keyframes" -> clearKeyframes(args.getString("clip_id"), args.getString("property"))
        "auto_color" -> autoColor(args.optString("clip_id"))
        "match_color" -> matchColor(args.getString("source_clip_id"), args.getString("target_clip_id"))
        "apply_lut" -> applyLut(args.optString("clip_id"), args.getString("path"))
        "clear_lut" -> clearLut(args.optString("clip_id"))
        "apply_shader" -> applyShader(args.optString("clip_id"), args.getString("path"), args.optJSONObject("params"))
        "clear_shader" -> clearShader(args.optString("clip_id"))
        "list_shader_params" -> listShaderParams(args.getString("path"))
        "set_export_preset" -> setExportPreset(args.getString("preset"))
        "get_beat_map" -> getBeatMap(args.getString("audio_clip_id"))
        "cut_to_beats" -> cutToBeats(args.getString("video_clip_id"), args.getString("audio_clip_id"), args.optString("mode", "downbeats"), args.optInt("every_n", 1))
        "apply_on_beat" -> applyOnBeat(args.getString("video_clip_id"), args.getString("audio_clip_id"), args.getString("effect"), args.optString("mode", "downbeats"))
        "align_clips_to_beats" -> alignClipsToBeats(args.getString("track_id"), args.getString("audio_clip_id"), args.optString("mode", "beats"))
        "assemble_music_video" -> assembleMusicVideo(args.getString("track_id"), args.getString("audio_clip_id"), args.optString("mode", "downbeats"), args.optInt("beats_per_clip", 1))
        "normalize_levels" -> normalizeLevels()
        "normalize_loudness" -> normalizeLoudness(args.optDouble("target_lufs", -14.0))
        "auto_duck" -> autoDuck(args.getString("music_clip_id"), args.getString("voice_clip_id"), args.optDouble("amount", 0.3).toFloat())
        "remove_vocals" -> removeVocals(args.getString("clip_id"))
        "separate_stems" -> separateStems(args.getString("clip_id"))
        "detect_scenes" -> detectScenes(args.getString("clip_id"), args.optDouble("sensitivity", 0.5).toFloat(), args.optBoolean("split", true))
        "apply_ffmpeg_filter" -> applyFfmpegFilter(args.getString("clip_id"), args.getString("filter"))
        "generate_image" -> generateMedia(GenKind.IMAGE, args.getString("prompt"), args.optString("provider"), args.optString("model"), null)
        "generate_video" -> generateMedia(GenKind.VIDEO, args.getString("prompt"), args.optString("provider"), args.optString("model"), args.optInt("duration_sec", 8))
        "generate_music" -> generateMedia(GenKind.MUSIC, args.getString("prompt"), args.optString("provider"), args.optString("model"), args.optInt("duration_sec", 8))
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

    // ---- offline speech: transcription (Vosk) + honest stubs ----------------

    /**
     * Honest stub for the sherpa-onnx-backed speech tools that have no clean JVM distribution yet
     * (transcribe_precise / add_voiceover / diarize_clip / remove_fillers). Never fakes success.
     */
    private fun speechToolUnavailable(tool: String, needs: String) = JSONObject().apply {
        // TODO(desktop): wire once sherpa-onnx ships a clean desktop-JVM artifact (bundled natives).
        // sherpa-onnx currently distributes JVM only as GitHub-release jars + separate per-platform
        // JNI natives (no Maven coordinate), so this can't be shipped reliably here.
        val msg = "$tool needs $needs, which isn't available on desktop yet " +
            "(sherpa-onnx has no on-device desktop model here). For transcription, use transcribe_clip, " +
            "which runs offline via a Vosk model set in Settings → Transcription."
        put("error", msg)
        put("humanSummary", msg)
    }

    /** Decode a clip's audio to 16 kHz mono, transcribe with the Vosk model, and add timed captions. */
    private fun transcribeClip(clipId: String): JSONObject {
        val model = settingsProvider().speechModelPath
        require(model.isNotBlank()) {
            "No on-device speech model set. Set a Vosk model in Settings → Transcription."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, 16_000) }
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to transcribe.")
        val cues = DesktopVoskTranscriber.transcribe(model, pcm.samples)
        if (cues.isEmpty()) return ok().apply {
            put("captions", 0)
            put("humanSummary", "Transcribed clip — no speech detected.")
        }
        vm.addTextClipsFromTranscript(clipId, cues)
        val n = vm.uiState.value.document.clips.count { it.type == ClipType.TEXT }
        return ok().apply {
            put("captions", cues.size)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Transcribed and added ${cues.size} caption(s) ($n text clips total).")
        }
    }

    /** Transcribe with Vosk and add ANIMATED per-syllable captions (grow-as-said kinetic typography). */
    private fun animatedTranscribeClip(clipId: String): JSONObject {
        val model = settingsProvider().speechModelPath
        require(model.isNotBlank()) {
            "No on-device speech model set. Set a Vosk model in Settings → Transcription."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, 16_000) }
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to transcribe.")
        val cues = DesktopVoskTranscriber.transcribe(model, pcm.samples)
        val wordCues = cues.flatMap { it.words }
        if (wordCues.isEmpty()) return ok().apply {
            put("words", 0)
            put("humanSummary", "Transcribed clip — no per-word timing available for animation.")
        }
        vm.addAnimatedCaptionsFromTranscript(clipId, wordCues)
        val n = vm.uiState.value.document.clips.count { it.type == ClipType.TEXT }
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

    // ---- audio sync (on-device cross-correlation, no model) -----------------

    private val ENV_RATE = 100

    /**
     * Sync [clipId] to [refClipId] by cross-correlating their audio envelopes and moving the clip so
     * its audio lines up with the reference. Mirrors Android's `sync_by_audio` exactly (10 ms env bins).
     */
    private fun syncByAudio(refClipId: String, clipId: String, maxOffsetSec: Int): JSONObject {
        val doc = vm.uiState.value.document
        val ref = doc.clips.firstOrNull { it.id == refClipId }
            ?: throw IllegalArgumentException("Reference clip not found: $refClipId")
        val mov = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val envA = audioEnvelope(ref) ?: throw IllegalStateException("No audio in the reference clip to sync on.")
        val envB = audioEnvelope(mov) ?: throw IllegalStateException("No audio in \"$clipId\" to sync on.")
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

    /** Normalized (zero-mean, unit-std) RMS envelope of [clip]'s audio at [ENV_RATE], or null if none. */
    private fun audioEnvelope(clip: TimelineClip): FloatArray? {
        val media = vm.uiState.value.document.mediaFor(clip) ?: return null
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, 4_000) } ?: return null
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

    // ---- color grading: auto-correct / shot-match / LUT / shader ------------

    /** Tone of a frame (all 0..1): mean luminance, contrast spread (p95−p5), mean HSV saturation. */
    private data class Tone(val lum: Float, val spread: Float, val sat: Float)

    /** Sample a frame at low res and summarise its tone (for auto-color and shot-match). Mirrors the
     *  Android `toneStats`, reading a [java.awt.image.BufferedImage] instead of a Bitmap. */
    private fun toneStats(image: java.awt.image.BufferedImage): Tone {
        val n = 64
        val small = java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = small.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(image, 0, 0, n, n, null)
        g.dispose()
        val px = IntArray(n * n)
        small.getRGB(0, 0, n, n, px, 0, n)
        val lums = FloatArray(px.size)
        var sumL = 0f
        var sumS = 0f
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val gc = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            lums[i] = 0.299f * r + 0.587f * gc + 0.114f * b
            sumL += lums[i]
            val mx = maxOf(r, gc, b); val mn = minOf(r, gc, b)
            sumS += if (mx > 1e-4f) (mx - mn) / mx else 0f
        }
        lums.sort()
        val p5 = lums[(lums.size * 0.05f).toInt().coerceIn(0, lums.size - 1)]
        val p95 = lums[(lums.size * 0.95f).toInt().coerceIn(0, lums.size - 1)]
        return Tone(sumL / px.size, (p95 - p5).coerceIn(0.01f, 1f), sumS / px.size)
    }

    /** Tone of the frame at a clip's mid-point (frame pulled in-process via JavaCV). */
    private fun clipTone(clip: TimelineClip): Tone {
        val media = vm.uiState.value.document.mediaFor(clip)
            ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val at = com.hereliesaz.guillotine.model.TimelineMath
            .sourceTimeMs(clip, clip.startTimeMs + clip.durationMs / 2).coerceAtLeast(0L)
        return runBlocking { DesktopMediaDecoder.grabFrame(media.uri, at) }
            ?.let { toneStats(it) }
            ?: throw IllegalStateException("Could not read a frame from clip ${clip.id}.")
    }

    /**
     * The clip [clipId], or the video clip under the playhead when blank. A non-blank id that doesn't
     * match throws (rather than silently editing the playhead clip on a stale id). Throws too when
     * blank and nothing is under the playhead. Mirrors Android's `resolveClipOrPlayhead`.
     */
    private fun resolveClipOrPlayhead(clipId: String): TimelineClip {
        val st = vm.uiState.value
        if (clipId.isNotBlank()) {
            return st.document.clips.firstOrNull { it.id == clipId }
                ?: throw IllegalArgumentException("Clip not found: $clipId")
        }
        return com.hereliesaz.guillotine.model.TimelineMath.activeClip(
            st.document.clips, ClipType.VIDEO, st.currentTimeMs,
        ) ?: throw IllegalStateException("No clip — scrub onto one or pass clip_id.")
    }

    /** Auto color-correct: nudge exposure/contrast/saturation toward a balanced look, via clip filters. */
    private fun autoColor(clipId: String): JSONObject {
        val st = vm.uiState.value
        val clip = (if (clipId.isNotBlank()) st.document.clips.firstOrNull { it.id == clipId } else null)
            ?: com.hereliesaz.guillotine.model.TimelineMath.activeClip(
                st.document.clips, ClipType.VIDEO, st.currentTimeMs,
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

    /** Apply a `.cube` 3D LUT color grade to a clip (path validated + parseable). Renders in preview + export. */
    private fun applyLut(clipId: String, path: String): JSONObject {
        require(path.isNotBlank()) { "Provide the path to a .cube LUT file." }
        val file = File(path)
        require(file.isFile) { "No .cube file at: $path" }
        // Parse up front so a bad file fails here with a clear message rather than silently doing nothing.
        runCatching { com.hereliesaz.guillotine.media.CubeLut.parse(file.readText()) }
            .onFailure { throw IllegalArgumentException("Not a valid 3D .cube LUT: ${it.message}") }
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(lutPath = file.absolutePath) }
        return ok().apply { put("humanSummary", "Applied LUT ${file.name} to clip ${clip.id}.") }
    }

    /** Remove a clip's `.cube` LUT grade. */
    private fun clearLut(clipId: String): JSONObject {
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(lutPath = "") }
        return ok().apply { put("humanSummary", "Removed the LUT from clip ${clip.id}.") }
    }

    /**
     * Record a GLSL/ISF shader (with optional scalar-input overrides) on a clip. The shader is validated
     * and stored on the clip's filters, BUT desktop has no live GLSL renderer yet, so it does NOT render
     * in preview or export — the humanSummary says so explicitly (don't claim it's visually applied).
     */
    private fun applyShader(clipId: String, path: String, params: JSONObject?): JSONObject {
        require(path.isNotBlank()) { "Provide the path to an .isf / .fs / .glsl shader file." }
        val file = File(path)
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
        // TODO(desktop): no GLSL/Skia render pass exists yet, so the shader is recorded but not rendered.
        return ok().apply {
            put("shaderRendered", false)
            put(
                "humanSummary",
                "Recorded shader ${file.name}" +
                    (if (overrides.isNotEmpty()) " with ${overrides.size} param(s)" else "") +
                    " on clip ${clip.id}. Note: the shader is saved on the clip, but the live GLSL render " +
                    "on desktop is still pending — it is NOT yet visible in preview or export.",
            )
        }
    }

    /** Remove a clip's GLSL/ISF shader effect (and its param overrides). */
    private fun clearShader(clipId: String): JSONObject {
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(shaderPath = "", shaderParams = emptyMap()) }
        return ok().apply { put("humanSummary", "Removed the shader from clip ${clip.id}.") }
    }

    /** List a shader's adjustable scalar inputs (name, type, default, min, max). No render needed. */
    private fun listShaderParams(path: String): JSONObject {
        val file = File(path)
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

    // ---- output / export config --------------------------------------------

    /** Set the project's output aspect ratio from a platform [preset]. */
    private fun setExportPreset(preset: String): JSONObject {
        val p = preset.lowercase().trim()
        val aspect = when {
            listOf("tiktok", "reels", "reel", "shorts", "short", "vertical", "9:16", "story", "stories", "portrait").any { p.contains(it) } ->
                AspectRatio.RATIO_9_16
            listOf("square", "1:1", "instagram post", "feed").any { p.contains(it) } ->
                AspectRatio.RATIO_1_1
            listOf("youtube", "landscape", "16:9", "wide", "tv", "horizontal").any { p.contains(it) } ->
                AspectRatio.RATIO_16_9
            listOf("original", "source", "native").any { p.contains(it) } ->
                AspectRatio.ORIGINAL
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

    // ---- rhythm / edit-to-the-beat -----------------------------------------

    /** Decode a clip's audio to mono PCM on-device (JavaCV), then run the shared beat/tempo analyzer. */
    private fun beatMapFor(uri: String): BeatMap {
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(uri) }
            ?: return BeatMap(0f, emptyList(), emptyList(), emptyList())
        return BeatAnalyzer.analyze(pcm.samples, pcm.sampleRate)
    }

    /** Map an audio clip's beat times (source ms) to timeline positions, keeping only those on-clip. */
    private fun beatTimelinePositions(
        audioClip: TimelineClip,
        beatMap: BeatMap,
        mode: String,
        everyN: Int,
    ): List<Long> {
        val src = beatMap.points(mode)
        val picked = if (everyN > 1) src.filterIndexed { i, _ -> i % everyN == 0 } else src
        return picked
            .map { audioClip.startTimeMs + (it - audioClip.trimStartMs) }
            .filter { it in audioClip.startTimeMs..audioClip.endTimeMs }
    }

    private fun getBeatMap(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val map = beatMapFor(media.uri)
        return JSONObject().apply {
            put("ok", true); put("clipId", clipId); put("bpm", map.bpm)
            put("beatCount", map.beatsMs.size)
            put("beatsMs", JSONArray(map.beatsMs))
            put("downbeatsMs", JSONArray(map.downbeatsMs))
            put("onsetCount", map.onsetsMs.size)
            put("humanSummary", "Analyzed rhythm: ~${map.bpm.toInt()} BPM, ${map.beatsMs.size} beats, ${map.downbeatsMs.size} downbeats.")
        }
    }

    private fun cutToBeats(videoClipId: String, audioClipId: String, mode: String, everyN: Int): JSONObject {
        val doc = vm.uiState.value.document
        val video = doc.clips.firstOrNull { it.id == videoClipId }
            ?: throw IllegalArgumentException("Video clip not found: $videoClipId")
        val audio = doc.clips.firstOrNull { it.id == audioClipId }
            ?: throw IllegalArgumentException("Audio clip not found: $audioClipId")
        val media = doc.mediaFor(audio) ?: throw IllegalArgumentException("No media for audio clip: $audioClipId")
        val map = beatMapFor(media.uri)
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
        val map = beatMapFor(media.uri)
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
        val map = beatMapFor(media.uri)
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

    /**
     * Trim each clip on [trackId] to span one beat interval of [audioClipId]'s grid, butting them
     * together on the beats — a montage cut to the music. [beatsPerClip] controls the interval length.
     */
    private fun assembleMusicVideo(trackId: String, audioClipId: String, mode: String, beatsPerClip: Int): JSONObject {
        val doc = vm.uiState.value.document
        val audio = doc.clips.firstOrNull { it.id == audioClipId }
            ?: throw IllegalArgumentException("Audio clip not found: $audioClipId")
        val media = doc.mediaFor(audio) ?: throw IllegalArgumentException("No media for audio clip: $audioClipId")
        val map = beatMapFor(media.uri)
        val every = beatsPerClip.coerceAtLeast(1)
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
            .filter { it.trackId == trackId && it.type == ClipType.VIDEO }
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

    // ---- audio levelling (on-device DSP) -----------------------------------

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
            val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, 8_000) } ?: continue
            if (pcm.samples.isEmpty()) continue
            var sum = 0.0
            for (v in pcm.samples) sum += v * v
            val rms = kotlin.math.sqrt(sum / pcm.samples.size).toFloat()
            if (rms > 1e-4f) rmsByClip[clip.id] = rms
        }
        if (rmsByClip.isEmpty()) {
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
            val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, 16_000) } ?: continue
            if (pcm.samples.isEmpty()) continue
            val lufs = Loudness.measureLufs(pcm.samples, pcm.sampleRate)
            if (lufs <= -70.0) continue // silence
            val gain = Loudness.gainToTarget(lufs, targetLufs)
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
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(vmedia.uri, 8_000) }
            ?: throw IllegalStateException("No audio track in \"${vmedia.name}\" to detect speech.")
        val regions = speechRegions(pcm.samples, pcm.sampleRate) // source ms within the voice media
        if (regions.isEmpty()) {
            return JSONObject().apply {
                put("ok", true); put("duckedRegions", 0)
                put("humanSummary", "No speech detected in the voice clip — nothing to duck under.")
            }
        }
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
     * Detect speech regions in mono [samples] at [rate] via a short-window RMS energy gate with an
     * adaptive threshold. Returns [start,end] pairs in source ms, merging gaps < 250 ms and dropping
     * blips < 150 ms.
     */
    private fun speechRegions(samples: FloatArray, rate: Int): List<Pair<Long, Long>> {
        if (rate <= 0 || samples.isEmpty()) return emptyList()
        val win = (rate * 0.04).toInt().coerceAtLeast(1) // 40 ms windows
        val n = samples.size / win
        if (n == 0) return emptyList()
        val rms = FloatArray(n)
        var peak = 0f
        for (i in 0 until n) {
            var sum = 0.0
            val base = i * win
            for (j in 0 until win) { val v = samples[base + j]; sum += v * v }
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

    // ---- vocal / stem separation (on-device) -------------------------------

    /**
     * Remove a clip's lead vocals via on-device stereo center-channel cancellation (no model) and add
     * the instrumental as a new audio clip. Mirrors Android's `remove_vocals`: needs a stereo track.
     */
    private fun removeVocals(clipId: String): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val stereo = runBlocking { DesktopMediaDecoder.decodePcmStereo(media.uri) }
        val outDir = File(DesktopStorage.dataDir, "stems").apply { mkdirs() }
        val out = File(outDir, "instrumental_${System.currentTimeMillis()}.wav")
        val duration = (if (stereo == null || stereo.channels < 2) null
            else VocalIsolator.removeVocals(stereo.left, stereo.right, stereo.sampleRate, out.absolutePath))
            ?: throw IllegalStateException(
                "Couldn't remove vocals — the clip needs a stereo audio track (center-channel " +
                    "cancellation can't work on mono).",
            )
        val probed = DesktopMediaImport.probe(out)
            ?: throw IllegalStateException("The instrumental couldn't be read.")
        vm.addMedia(listOf(probed.copy(name = "Instrumental: ${media.name}")))
        return ok().apply {
            put("durationMs", duration)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Added a ${msFmt(duration)} vocals-removed (instrumental) track.")
        }
    }

    /** Separate a clip's music into vocals + accompaniment via on-device Spleeter (ONNX); add both. */
    private fun separateStems(clipId: String): JSONObject {
        val dir = settingsProvider().stemModelPath
        require(dir.isNotBlank()) {
            "No stem model set. Download Spleeter in Settings → AI Analyzer → Stem separation."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val stereo = runBlocking { DesktopMediaDecoder.decodePcmStereo(media.uri) }
            ?: throw IllegalStateException("Couldn't separate — the clip needs decodable audio.")
        val stems = Spleeter.separate(
            stereo.left, stereo.right, stereo.sampleRate, dir, File(DesktopStorage.dataDir, "stems"),
        ) ?: throw IllegalStateException("Couldn't separate — the clip needs decodable audio.")
        val vocals = DesktopMediaImport.probe(File(stems.vocalsWav))
            ?: throw IllegalStateException("The separated vocals couldn't be read.")
        val instrumental = DesktopMediaImport.probe(File(stems.accompanimentWav))
            ?: throw IllegalStateException("The separated accompaniment couldn't be read.")
        vm.addMedia(
            listOf(
                vocals.copy(name = "vocals: ${media.name}"),
                instrumental.copy(name = "instrumental: ${media.name}"),
            ),
        )
        return ok().apply {
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Separated into vocals + accompaniment and added both as audio clips.")
        }
    }

    // ---- shot / scene detection --------------------------------------------

    /**
     * Detect visual scene/shot cuts in a clip by sampling frames and comparing colour histograms (a
     * classic content-difference detector — no model needed, frames pulled in-process via JavaCV). When
     * [split], the clip is cut at each boundary so every shot becomes its own piece.
     */
    private fun detectScenes(clipId: String, sensitivity: Float, split: Boolean): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val durationMs = clip.durationMs
        require(durationMs > 0) { "Clip has no duration to scan." }
        // Higher sensitivity → lower distance threshold → more cuts. L1 distance over a normalized
        // 64-bin RGB histogram ranges 0..2; hard cuts are typically ~0.7–1.5.
        val threshold = (1.15f - 0.7f * sensitivity.coerceIn(0f, 1f))
        val srcCuts = runBlocking {
            DesktopMediaDecoder.detectSceneCuts(media.uri, clip.trimStartMs, durationMs, threshold)
        }
        val boundaries = sortedSetOf<Long>()
        srcCuts.forEach { srcMs ->
            val tl = clip.startTimeMs + (srcMs - clip.trimStartMs)
            if (tl > clip.startTimeMs && tl < clip.endTimeMs) boundaries += tl
        }
        val found = boundaries.size
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

    // ---- FFmpeg filtergraph (in-process) -----------------------------------

    /** Bake an FFmpeg/Frei0r `-vf` filtergraph onto a clip's video in-process, adding a new clip. */
    private fun applyFfmpegFilter(clipId: String, filterGraph: String): JSONObject {
        require(filterGraph.isNotBlank()) { "Provide an FFmpeg -vf filtergraph." }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val outDir = File(DesktopStorage.dataDir, "ffmpeg")
        val baked = DesktopFfmpegFilter.apply(media.uri, filterGraph, outDir)
        val probed = DesktopMediaImport.probe(baked)
            ?: throw IllegalStateException("The filtered clip couldn't be read.")
        vm.addMedia(listOf(probed.copy(name = "ffmpeg: ${media.name}")))
        return ok().apply {
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Applied the FFmpeg filter and added the result as a new clip.")
        }
    }

    // ---- generative media (cloud, BYO key) ---------------------------------

    /**
     * Run a generation end-to-end via the shared backends and add the result to the timeline. Resolves
     * the provider (explicit pick, else the user's default for the category), drives [AsyncJobPoller],
     * saves the result locally via [DesktopGenSink], then imports it through the same path as user media.
     * The ONLY network-touching tool family on desktop.
     */
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
        val resolved = providerType?.takeIf { settings.genProviderAvailable(it) }
            ?: settings.defaultGenProvider(kind)
            ?: throw IllegalStateException(
                "No ${kind.name.lowercase()} generator is set up. Add a key for one in Settings → Generation.",
            )
        val modelId = model.takeIf { it.isNotBlank() } ?: settings.genModelFor(resolved)
        val dur = durationSec ?: if (kind == GenKind.IMAGE) 0 else 8
        val label = when (kind) { GenKind.IMAGE -> "image"; GenKind.VIDEO -> "video"; GenKind.MUSIC -> "music" }
        val req = GenRequest(
            kind = kind,
            provider = resolved,
            apiKey = settings.genKeyFor(resolved),
            model = modelId,
            prompt = prompt.trim(),
            durationSec = dur.coerceAtLeast(1),
            extra = mapOf("base_url" to settings.genExtraFor(resolved)),
        )
        val sink = DesktopGenSink()
        val cfg = PollConfig(
            maxAttempts = if (kind == GenKind.IMAGE) 90 else 300,
            intervalMs = 2_000,
            timeoutMessage = "${resolved.meta.label} timed out generating.",
        )
        val localUri = runBlocking {
            val result = AsyncJobPoller.run(GenBackends.jobFor(req, sink), cfg)
            // Result is either an already-local uri (byte-returning backends) or a remote url to download.
            if (result.startsWith("file:") || result.startsWith("content://")) result
            else sink.saveUrl(result, GenBackends.extFor(kind))
        }
        val file = File(URI(localUri))
        val probed = DesktopMediaImport.probe(file)
            ?: throw IllegalStateException("The generated $label couldn't be read.")
        val item = probed.copy(name = "${resolved.meta.label}: ${prompt.trim().take(24)}")
        vm.addMedia(listOf(item))
        return ok().apply {
            put("mediaKind", item.kind.name)
            put("durationMs", item.durationMs)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Generated a $label and added it to the timeline.")
        }
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
