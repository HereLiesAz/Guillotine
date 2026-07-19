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
import com.hereliesaz.guillotine.desktop.media.DesktopDiarizer
import com.hereliesaz.guillotine.desktop.media.DesktopFaceBlur
import com.hereliesaz.guillotine.desktop.media.DesktopFaceDetector
import com.hereliesaz.guillotine.desktop.media.DesktopImageEffect
import com.hereliesaz.guillotine.desktop.media.DesktopImageEmbedder
import com.hereliesaz.guillotine.desktop.media.DesktopFfmpegFilter
import com.hereliesaz.guillotine.desktop.media.DesktopImageLabeler
import com.hereliesaz.guillotine.desktop.media.DesktopInpaint
import com.hereliesaz.guillotine.desktop.media.DesktopTts
import com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder
import com.hereliesaz.guillotine.desktop.media.DesktopMediaImport
import com.hereliesaz.guillotine.desktop.media.DesktopVoskTranscriber
import com.hereliesaz.guillotine.desktop.media.DesktopYamnet
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.editor.FACE_BLUR_PREFIX
import com.hereliesaz.guillotine.model.LearnedConcept
import com.hereliesaz.guillotine.model.MediaItem
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
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
        put(toolDefinition("analyze_clip", "Prompt-driven cut analysis ON-DEVICE: label the clip's frames and keep the parts matching its prompt (set_prompt first), cutting the rest. Label-based (needs the footage-search model).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition("analyze_clip_with_reference", "Analyze using reference frame (not yet available on desktop).",
            objSchema("clip_id" to stringProp(), required = listOf("clip_id"))))
        put(toolDefinition(
            "remove_object_generative",
            "Remove the salient subject from the clip's current frame ON-DEVICE and add the inpainted " +
                "result as an image clip. Masks the subject with the segmentation model and fills it with " +
                "a LaMa inpaint model (both installed via .azp). Desktop stays fully on-device (no cloud), " +
                "so it removes the segmented subject rather than an arbitrary named object.",
            objSchema("clip_id" to stringProp("The clip whose current frame to clean"), required = listOf("clip_id")),
        ))
        put(toolDefinition("describe_current_frame", "Describe the video frame at the playhead using on-device image labels (needs the footage-search model).", emptySchema()))
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
            "Synthesize speech from text ON-DEVICE (offline neural TTS: a VITS/Piper .onnx voice run " +
                "through ONNX Runtime) and add it to the timeline as an audio clip. Requires a TTS voice " +
                ".azp installed; if none is set it returns an error naming the setting — relay it.",
            objSchema(
                "text" to stringProp("The words to speak"),
                "speed" to numberProp("Speaking rate (default 1.0; <1 slower, >1 faster)"),
                required = listOf("text"),
            ),
        ))
        put(toolDefinition(
            "diarize_clip",
            "Speaker diarization ON-DEVICE (who spoke when): energy VAD + an ONNX speaker-embedding model " +
                "+ clustering. Requires the diarization .azp (speaker-embedding model); if it isn't set it " +
                "returns an error naming the setting — relay it.",
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
            "set_frame_step",
            "Frame decimation — keep only every Nth frame of a clip for a choppy/stutter/strobe look, LIVE " +
                "and on-device (no ffmpeg, no baking, no new clip). step=2 removes every other frame, step=3 " +
                "keeps one of every three, step=1 turns it off. The clip stays the SAME length and its audio " +
                "is untouched, so it stays in sync — this is the go-to for \"cut/remove every other frame\", " +
                "\"make it choppy/stuttery\", \"low-frame-rate look\". Quantizes against the project frame rate. " +
                "(For a baked-to-a-new-clip version, apply_ffmpeg_filter with \"framestep=N\" does the same.)",
            objSchema(
                "clip_id" to stringProp("The clip to decimate"),
                "step" to intProp("Keep 1 of every N frames. 2 = every other frame; 1 = off."),
                required = listOf("clip_id", "step"),
            ),
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
                "The ESCAPE HATCH for effects with no dedicated tool: AUTHOR the `-vf` graph yourself from the " +
                "user's plain-English request. Frame decimation/stutter — \"cut/remove every other frame\", " +
                "\"drop every 2nd frame\", \"make it choppy\" → \"framestep=2\" (every third → \"framestep=3\"); " +
                "\"choppy N-fps look\" → \"fps=8\"; \"frame-blend/motion trail\" → \"tmix=frames=3\". Also " +
                "\"run a frei0r plugin\", \"vintage/vhs/chromashift\", \"eq/curves/deband this\". `filter` is the " +
                "raw -vf graph, e.g. \"framestep=2\", \"hue=s=0, gblur=sigma=2\" or \"frei0r=cartoon\". Audio is " +
                "passed through unchanged, so prefer duration-preserving graphs (framestep/fps/tmix/eq) and " +
                "avoid setpts/trim, which change the video length and desync the audio. Runs in-process " +
                "via the bundled FFmpeg; this is a bake-to-new-clip step, not a live filter.",
            objSchema(
                "clip_id" to stringProp("The clip whose video to filter"),
                "filter" to stringProp("An FFmpeg -vf filtergraph you author from the request, e.g. framestep=2 for \"every other frame\" (Frei0r via frei0r=name:params)"),
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
        // ---- learned concepts (teach a specific thing by pointing at it) ----
        // list_concepts / delete_concept are pure data ops on the shared LearnedConcept store — REAL on
        // desktop. add_reference needs an on-device image/face embedder (ML Kit/TFLite) → honest stub.
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

        // ---- vision / face / image-model tools: defined for discoverability, honest stubs on desktop
        // (ML Kit / MediaPipe VLM / TFLite have no clean desktop-JVM equivalent). ----
        put(toolDefinition(
            "apply_image_effect",
            "Run an ON-DEVICE image model on the current preview frame and add the result as a new image " +
                "clip. effect = superres (upscale) | style (style transfer) | depth (depth map) | lowlight " +
                "(brighten a dark/low-light frame). Requires that effect's .onnx model installed via .azp; " +
                "if it isn't, returns an error naming the setting (relay it, don't retry).",
            objSchema(
                "effect" to stringProp("superres | style | depth | lowlight"),
                "clip_id" to stringProp("Optional clip; defaults to the video clip at the playhead"),
                required = listOf("effect"),
            ),
        ))
        put(toolDefinition(
            "blur_faces",
            "ON-DEVICE face anonymization: tracks the main face across the clip and drops a pre-blurred " +
                "patch that follows it on a track above (keyframed, so it renders in preview and export and " +
                "stays editable), for privacy. Use for \"blur the faces\", \"anonymize people\", \"hide " +
                "identities\", \"censor faces\". Pass enabled=false to remove it. Needs the face-detection model.",
            objSchema(
                "clip_id" to stringProp("The clip to blur faces on; defaults to the video clip at the playhead"),
                "enabled" to boolProp("Turn face-blur on (default true) or off"),
                required = emptyList(),
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
            "apply_transition",
            "Add a cross-dissolve TRANSITION between two clips ON-DEVICE. Use for \"add a crossfade/" +
                "dissolve between these\", \"put a transition here\". Overlaps the two clips on one track " +
                "by duration_sec (default 1) so the renderer's built-in crossfade blends them, in both " +
                "preview and export. type is accepted for API parity but desktop does an opacity " +
                "cross-dissolve (not per-style xfade wipes).",
            objSchema(
                "from_clip_id" to stringProp("The outgoing (first) clip"),
                "to_clip_id" to stringProp("The incoming (second) clip"),
                "type" to stringProp("xfade transition type (default fade)"),
                "duration_sec" to numberProp("Transition/overlap length in seconds (default 1)"),
                required = listOf("from_clip_id", "to_clip_id"),
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
        put(toolDefinition(
            "list_azp_plugins",
            "List installed azphalt `.azp` effect and kinetic-typography plugins. Returns plugin IDs, " +
                "names, and tags. Use this to discover advanced effects to apply.",
            emptySchema(),
        ))
        put(toolDefinition(
            "apply_azp_plugin",
            "Apply a `.azp` plugin (by its ID) to a clip. A kinetic-typography motion plugin applied to a " +
                "caption (TEXT clip) is baked into keyframes, so it animates in preview and export and " +
                "stays editable.",
            objSchema(
                "clip_id" to stringProp("The ID of the clip to apply the plugin to"),
                "plugin_id" to stringProp("The ID of the plugin (from list_azp_plugins)"),
                required = listOf("clip_id", "plugin_id"),
            ),
        ))
        put(toolDefinition(
            "clear_azp_plugin",
            "Remove an applied kinetic-typography preset (its baked keyframes) and any applied-plugin " +
                "marker from a clip. Hand-authored keyframes are kept.",
            objSchema(
                "clip_id" to stringProp("The ID of the clip to clear the plugin/preset from"),
                required = listOf("clip_id"),
            ),
        ))
        // Named one-call video effects (sharpen, film_grain, vhs, mirror, thermal, …), each a standard
        // FFmpeg -vf graph baked to a new clip. Shared registry so both platforms expose the same set.
        val videoFx = com.hereliesaz.guillotine.mcp.VideoFilterCatalog.toolDefinitions()
        for (i in 0 until videoFx.length()) put(videoFx.get(i))
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
        // ---- vision (ML Kit labeling / MediaPipe VLM) → honest stubs ----
        "analyze_clip" -> analyzeClipByPrompt(args.getString("clip_id"))
        "analyze_clip_with_reference" ->
            visionToolUnavailable(name, "an on-device vision/labeling model")
        "analyze_clip_with_concept" -> analyzeClipWithConcept(
            args.getString("clip_id"), args.getString("name"), args.optBoolean("keep_only", false),
        )
        "describe_current_frame" -> describeCurrentFrame()
        "caption_frame" -> captionFrameTool(args.optString("clip_id"))
        "find_highlights" -> findHighlights(
            args.getString("clip_id"),
            args.optDouble("threshold", 0.3).toFloat(),
            args.optBoolean("split", true),
        )
        "search_clips" -> searchClips(args.getString("query"))
        // ---- face / segmentation ----
        "blur_faces" -> blurFaces(
            args.optString("clip_id"),
            if (args.has("enabled")) args.getBoolean("enabled") else true,
        )
        "auto_reframe" -> autoReframe(args.getString("clip_id"), args.optDouble("zoom", 1.3).toFloat())
        "replace_background" -> replaceBackgroundTool(
            args.optString("clip_id"), args.optString("color"), args.optString("image_path"),
        )
        // ---- concept embedder / image models / inpaint → honest stubs ----
        "add_reference" -> addReference(
            args.getString("name"), args.optString("term"), args.optBoolean("negative", false),
        )
        "remove_object_generative" -> removeObjectGenerative(args.optString("clip_id"))
        "apply_bokeh" -> applyBokehTool(args.optString("clip_id"))
        "apply_image_effect" -> applyImageEffect(args.getString("effect"), args.optString("clip_id"))
        "denoise_clip" ->
            visionToolUnavailable(name, "the on-device GTCRN speech-denoiser model")
        "apply_transition" -> applyTransition(
            args.getString("from_clip_id"), args.getString("to_clip_id"), args.optDouble("duration_sec", 1.0).toFloat(),
        )
        "list_azp_plugins" -> listAzpPlugins()
        "apply_azp_plugin" -> applyAzpPlugin(args.getString("clip_id"), args.getString("plugin_id"))
        "clear_azp_plugin" -> clearAzpPlugin(args.getString("clip_id"))
        // ---- learned-concept data ops → REAL (shared LearnedConcept store) ----
        "list_concepts" -> listConcepts()
        "delete_concept" -> deleteConcept(args.getString("name"))
        "transcribe_clip" -> transcribeClip(args.getString("clip_id"))
        "animated_transcribe_clip" -> animatedTranscribeClip(args.getString("clip_id"))
        "transcribe_precise" -> transcribeClip(args.getString("clip_id"))
        "add_voiceover" -> addVoiceover(args.getString("text"), args.optDouble("speed", 1.0).toFloat())
        "diarize_clip" -> diarizeClip(args.getString("clip_id"), args.optInt("num_speakers", 0))
        "remove_fillers" -> removeFillers(args.getString("clip_id"))
        "sync_by_audio" -> syncByAudio(args.getString("reference_clip_id"), args.getString("clip_id"), args.optInt("max_offset_sec", 15))
        "create_user_tool" -> createUserTool(args.getString("name"), args.getString("description"))
        "list_user_tools" -> listUserTools()
        "delete_user_tool" -> deleteUserTool(args.getString("name"))
        "run_user_tool" -> runUserTool(args.getString("name"), args.getString("clip_id"))
        "start_recording" -> startRecording(args.getString("clip_id"))
        "stop_recording" -> stopRecording(args.getString("name"), args.optString("extra_instructions", ""))
        "discard_recording" -> discardRecording()
        "set_clip_filter" -> setClipFilter(args.getString("clip_id"), args.getString("property"), args.getDouble("value").toFloat())
        "set_frame_step" -> setFrameStep(args.getString("clip_id"), args.getInt("step"))
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
        in com.hereliesaz.guillotine.mcp.VideoFilterCatalog.names ->
            applyFfmpegFilter(args.getString("clip_id"), com.hereliesaz.guillotine.mcp.VideoFilterCatalog.graphFor(name, args))
                .apply { put("humanSummary", com.hereliesaz.guillotine.mcp.VideoFilterCatalog.summaryFor(name)) }
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

    /**
     * On-device image effect via an ONNX model (desktop analogue of Android's TFLite `apply_image_effect`).
     * effect ∈ {superres, style, depth, lowlight}; each resolves its own `.onnx` slot. Grabs the frame at
     * the playhead (or the given clip), runs the model, and adds the result as an image clip.
     */
    private fun applyImageEffect(effect: String, clipId: String): JSONObject {
        val key = effect.lowercase().trim()
        val path = ModelResolver.resolve("effect_$key")
        require(path.isNotBlank()) {
            "No on-device model set for \"$effect\". Install its .azp (superres/style/depth/lowlight) from the Azphalt Storefront."
        }
        val st = vm.uiState.value
        val now = st.currentTimeMs
        val clip = (if (clipId.isNotBlank()) st.document.clips.firstOrNull { it.id == clipId } else null)
            ?: com.hereliesaz.guillotine.model.TimelineMath.activeClip(st.document.clips, ClipType.VIDEO, now)
            ?: throw IllegalStateException("No video clip to apply the effect to — scrub onto one.")
        val media = st.document.mediaFor(clip) ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, sourceMs, maxPx = 1920) }
            ?: throw IllegalStateException("Could not read the current frame.")
        val out = DesktopImageEffect.run(frame, path)
            ?: throw IllegalStateException("The $key model produced no output — check the .onnx file.")
        val file = File(File(DesktopStorage.dataDir, "gen").apply { mkdirs() }, "effect_${System.currentTimeMillis()}.png")
        javax.imageio.ImageIO.write(out, "png", file)
        val probed = DesktopMediaImport.probe(file) ?: throw IllegalStateException("The effect output couldn't be read.")
        vm.addMedia(listOf(probed.copy(name = "$key: ${media.name}")))
        return ok().apply {
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Applied on-device $key and added the result as an image clip.")
        }
    }

    /**
     * On-device object removal (desktop keeps the on-device invariant — no cloud, unlike Android's
     * Leonardo path): mask the salient subject with the segmentation model and fill it with a LaMa-style
     * inpaint model, adding the cleaned frame as an image clip. Requires both models.
     */
    private fun removeObjectGenerative(clipId: String): JSONObject {
        val seg = ModelResolver.resolve("segModelPath")
        val inpaint = ModelResolver.resolve("inpaintModelPath")
        require(seg.isNotBlank()) { "Object removal needs a segmentation model. Install the selfie-segmentation .azp." }
        require(inpaint.isNotBlank()) { "Object removal needs an inpaint model. Install the LaMa inpaint .azp." }
        val clip = resolveClipOrPlayhead(clipId)
        val st = vm.uiState.value
        val media = st.document.mediaFor(clip) ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val now = st.currentTimeMs
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, sourceMs, maxPx = 1280) }
            ?: throw IllegalStateException("Could not read the current frame.")
        val out = DesktopInpaint.removeSubject(frame, seg, inpaint)
            ?: throw IllegalStateException("Inpainting produced no output — check the models.")
        val file = File(File(DesktopStorage.dataDir, "gen").apply { mkdirs() }, "inpaint_${System.currentTimeMillis()}.png")
        javax.imageio.ImageIO.write(out, "png", file)
        val probed = DesktopMediaImport.probe(file) ?: throw IllegalStateException("The inpainted frame couldn't be read.")
        vm.addMedia(listOf(probed.copy(name = "removed: ${media.name}")))
        return ok().apply {
            put("clipCount", vm.uiState.value.document.clips.size)
            put(
                "humanSummary",
                "Removed the segmented subject and added the inpainted frame as an image clip. " +
                    "(Desktop removes the salient subject on-device; it does not localize an arbitrary named object.)",
            )
        }
    }

    /**
     * On-device neural TTS via a VITS/Piper `.onnx` voice ([DesktopTts]) — desktop analogue of Android's
     * sherpa `add_voiceover`. Synthesizes the text to a WAV and adds it as an audio clip.
     */
    private fun addVoiceover(text: String, speed: Float): JSONObject {
        require(text.isNotBlank()) { "Give the voiceover some text to speak." }
        val model = ModelResolver.resolve("ttsModelPath")
        require(model.isNotBlank()) {
            "No TTS voice set. Install a VITS/Piper voice .azp from the Azphalt Storefront."
        }
        val out = File(File(DesktopStorage.dataDir, "gen").apply { mkdirs() }, "voiceover_${System.currentTimeMillis()}.wav")
        val result = DesktopTts.synthesize(model, text.trim(), out.absolutePath, speed.coerceIn(0.5f, 2.0f))
        val probed = DesktopMediaImport.probe(File(result.wavPath))
            ?: throw IllegalStateException("The synthesized voiceover couldn't be read.")
        val duration = result.durationMs.coerceAtLeast(500L)
        vm.addMedia(listOf(probed.copy(name = "VO: ${text.trim().take(40)}")))
        return ok().apply {
            put("durationMs", duration)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Added a ${msFmt(duration)} voiceover to the timeline.")
        }
    }

    /**
     * On-device speaker diarization ([DesktopDiarizer]: energy VAD + ONNX speaker embeddings + clustering)
     * — desktop analogue of Android's sherpa `diarize_clip`. Returns speaker turns mapped to timeline ms.
     */
    private fun diarizeClip(clipId: String, numSpeakers: Int): JSONObject {
        val embed = ModelResolver.resolve("diarizeEmbedModelPath")
        require(embed.isNotBlank()) {
            "Speaker diarization needs a speaker-embedding model. Install the diarization .azp from the Azphalt Storefront."
        }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, 16_000) }
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to diarize.")
        val turns = DesktopDiarizer.diarize(embed, pcm.samples, numSpeakers)
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
        return ok().apply {
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

    /**
     * Honest stub for the vision / face / segmentation / image-model / inpaint tools that depend on
     * ML Kit, MediaPipe, or TFLite — all Android-only with no clean desktop-JVM equivalent. Never fakes
     * success: returns a clear error naming what's missing, mirroring the sherpa speech stubs.
     */
    private fun visionToolUnavailable(tool: String, needs: String) = JSONObject().apply {
        val msg = "$tool needs $needs. This heavy model is not bundled with Guillotine by default. " +
            "Please open the Azphalt Storefront and install the `.azp` package for this model, which will download it to your local registry."
        put("error", msg)
        put("humanSummary", msg)
    }

    /**
     * Footage search ("find all clips with a dog") via the on-device ONNX image labeler — the desktop
     * analogue of Android's ML Kit `search_clips`. Samples 4 frames across each video clip, labels each
     * through [DesktopImageLabeler], and matches the labels against [query] (substring either way, as on
     * Android). Requires the classifier set in Settings → AI Analyzer → Footage search; relays its error
     * (missing model / missing labels file) rather than faking a result.
     */
    private fun searchClips(query: String): JSONObject {
        val q = query.trim().lowercase()
        require(q.isNotBlank()) { "Give something to search for, e.g. \"dog\"." }
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("labelModelPath")
        require(model.isNotBlank()) {
            "No image-labeling model set. Add an ONNX classifier in Settings → AI Analyzer → Footage search."
        }
        require(File(model).isFile) { "The image-labeling model file does not exist at: $model" }
        val doc = vm.uiState.value.document
        val videoClips = doc.clips.filter { it.type == ClipType.VIDEO }
        val matches = JSONArray()
        var found = 0
        for (clip in videoClips) {
            val media = doc.mediaFor(clip) ?: continue
            var bestLabel: String? = null
            var bestScore = 0f
            var bestTimeline = clip.startTimeMs
            // Sample 4 frames across the clip's source range (matches the Android cadence).
            for (k in 1..4) {
                val sourceMs = clip.trimStartMs + clip.durationMs * k / 5
                val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, sourceMs) } ?: continue
                val labels = DesktopImageLabeler.labels(model, frame)
                val hit = labels.firstOrNull {
                    val t = it.text.lowercase()
                    t.contains(q) || q.contains(t)
                }
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
        return ok().apply {
            put("matchCount", found)
            put("matches", matches)
            put(
                "humanSummary",
                if (found == 0) "No clips matched \"$query\"." else "Found $found clip(s) matching \"$query\".",
            )
        }
    }

    /**
     * Audio-event highlight detection ("find the best moments") via the on-device ONNX YAMNet model —
     * the desktop analogue of Android's TFLite `find_highlights`. Decodes the clip's audio to 16 kHz
     * mono, scans it in ~1 s windows through [DesktopYamnet], merges consecutive highlight windows
     * (≤1.5 s gap) into ranges, and (when [split]) cuts the clip at each range boundary so every best
     * moment becomes its own piece. Requires the YAMNet model set in Settings → AI Analyzer → Audio
     * highlights; relays its error rather than faking a result.
     */
    private fun findHighlights(clipId: String, threshold: Float, split: Boolean): JSONObject {
        val path = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("audioEventModelPath")
        require(path.isNotBlank()) {
            "No audio-event model set. Add a YAMNet .onnx in Settings → AI Analyzer → Audio highlights."
        }
        require(File(path).isFile) { "The audio-event model file does not exist at: $path" }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, DesktopYamnet.SAMPLE_RATE) }
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to analyze.")
        // decodePcmMono decodes the WHOLE media, so drop hits outside the clip's trimmed source window
        // (else a trimmed-out highlight maps to a zero-length range at the clip boundary).
        val frameMs = DesktopYamnet.FRAME * 1000L / DesktopYamnet.SAMPLE_RATE
        val srcStart = clip.trimStartMs
        val srcEnd = clip.trimStartMs + clip.durationMs
        val hits = DesktopYamnet.scanHighlights(path, pcm.samples, threshold.coerceIn(0.05f, 0.95f))
            .filter { it.startMs < srcEnd && it.startMs + frameMs > srcStart }
        if (hits.isEmpty()) {
            return ok().apply {
                put("highlightCount", 0)
                put("humanSummary", "No highlight-worthy audio events found (try a lower threshold).")
            }
        }
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

    /**
     * Vertical auto-reframe ("follow the subject") via the on-device ONNX face detector — the desktop
     * analogue of Android's ML Kit `auto_reframe`. Punches in by [zoom] and pans OFFSET_X keyframes so
     * the largest face stays centred as it moves. Samples ~60 frames, finds the main face's centre X
     * per frame ([DesktopFaceDetector]), smooths (3-tap moving average), and maps each to a pan offset
     * bounded so the punched-in crop never leaves the frame. Requires the face-detection model in
     * Settings → AI Analyzer → Face detection; relays its error rather than faking a result.
     */
    private fun autoReframe(clipId: String, zoom: Float): JSONObject {
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("faceDetectModelPath")
        require(model.isNotBlank()) {
            "No face-detection model set. Add an ONNX face detector in Settings → AI Analyzer → Face detection."
        }
        require(File(model).isFile) { "The face-detection model file does not exist at: $model" }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val z = zoom.coerceIn(1.05f, 3f)
        val durationMs = clip.durationMs
        require(durationMs > 0) { "Clip has no duration to reframe." }

        val step = maxOf(300L, durationMs / 60) // ~every 0.3s, capped ~60 samples
        // Max pan (normalized) that keeps the punched-in crop inside the frame.
        val maxPan = ((z - 1f) / (2f * z)).coerceIn(0f, 0.5f)
        val samples = ArrayList<Pair<Long, Float>>() // relMs -> centerX (0..1)
        var srcMs = clip.trimStartMs
        val endSrc = clip.trimStartMs + durationMs
        while (srcMs <= endSrc) {
            val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, srcMs) }
            if (frame != null) {
                DesktopFaceDetector.largestFaceCenterX(model, frame)?.let { cx ->
                    samples += (srcMs - clip.trimStartMs) to cx
                }
            }
            srcMs += step
        }
        check(samples.isNotEmpty()) { "No faces found to follow — auto-reframe needs a face in the shot." }

        // Smooth the centers (moving average of 3) to avoid jitter, then map to a pan offset.
        val ease = CubicBezier()
        val points = samples.mapIndexed { i, (relMs, _) ->
            val lo = maxOf(0, i - 1); val hi = minOf(samples.size - 1, i + 1)
            val avgCx = (lo..hi).map { samples[it].second }.average().toFloat()
            // Subject right of center → pan the image left (negative offset) to recenter. OFFSET_X is a
            // fraction of frame width (desktop render: image centre = canvasW/2 + ox*canvasW), so pan by
            // the face's own offset from centre, bounded to the in-frame limit.
            val offsetX = (0.5f - avgCx).coerceIn(-maxPan, maxPan)
            Triple(relMs, offsetX, ease)
        }
        vm.updateClip(clipId) { it.copy(scale = z) }
        vm.insertKeyframes(clipId, KeyframeProperty.OFFSET_X, points)
        return ok().apply {
            put("keyframes", points.size)
            put("humanSummary", "Auto-reframed: punched in ${z}× and panned across ${points.size} points to follow the face.")
        }
    }

    /**
     * Describe the video frame at the playhead using the on-device ONNX image labeler — the desktop
     * analogue of Android's `describe_current_frame`. Android runs an object detector; desktop reuses
     * [DesktopImageLabeler] (the footage-search classifier) to name the frame's most likely contents.
     * Honest about being label-based, not box-level object detection. Requires the footage-search model.
     */
    private fun describeCurrentFrame(): JSONObject {
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("labelModelPath")
        require(model.isNotBlank()) {
            "No image-labeling model set. Add an ONNX classifier in Settings → AI Analyzer → Footage search."
        }
        require(File(model).isFile) { "The image-labeling model file does not exist at: $model" }
        val st = vm.uiState.value
        val now = st.currentTimeMs
        val clip = com.hereliesaz.guillotine.model.TimelineMath.activeClip(st.document.clips, ClipType.VIDEO, now)
            ?: return JSONObject().put("error", "No video clip at the playhead — scrub onto one.")
        val media = st.document.mediaFor(clip)
            ?: return JSONObject().put("error", "Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, sourceMs) }
            ?: return JSONObject().put("error", "Could not extract the current preview frame.")
        val labels = DesktopImageLabeler.labels(model, frame, topK = 5)
        val topDesc = labels.take(3).joinToString(", ") { "${it.text} (${(it.confidence * 100).toInt()}%)" }
        return ok().apply {
            put("clipId", clip.id)
            put("labels", JSONArray().apply {
                labels.forEach { put(JSONObject().put("label", it.text).put("confidence", (it.confidence * 100).toInt())) }
            })
            put(
                "humanSummary",
                if (topDesc.isBlank()) "Couldn't recognize the frame's contents." else "This frame looks like: $topDesc.",
            )
        }
    }

    /**
     * On-device privacy face-blur via tracking (the desktop approach): track the largest face across
     * the clip, generate a pre-blurred patch of it, and drop that patch on a new track ABOVE the clip
     * with OFFSET_X / OFFSET_Y / SCALE keyframes that follow the face ([DesktopFaceBlur] +
     * [EditorViewModel.addFaceBlurOverlay]). Rides the keyframe system, so it renders in preview and
     * export and stays editable. `enabled=false` removes the overlay. Requires the face-detection model.
     */
    private fun blurFaces(clipId: String, enabled: Boolean): JSONObject {
        val clip = resolveClipOrPlayhead(clipId)
        if (!enabled) {
            vm.removeFaceBlurOverlay(clip.id)
            return ok().apply { put("humanSummary", "Removed face blur from clip ${clip.id}.") }
        }
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("faceDetectModelPath")
        require(model.isNotBlank()) {
            "No face-detection model set. Add an ONNX face detector in Settings → AI Analyzer → Face detection."
        }
        require(File(model).isFile) { "The face-detection model file does not exist at: $model" }
        val media = vm.uiState.value.document.mediaFor(clip)
            ?: throw IllegalArgumentException("No media for clip: ${clip.id}")
        val track = DesktopFaceBlur.build(model, media.uri, clip, File(DesktopStorage.dataDir, "faceblur"))
            ?: throw IllegalStateException("No faces found to blur — auto-blur needs a face in the shot.")
        val probed = DesktopMediaImport.probe(track.patch)
            ?: throw IllegalStateException("Couldn't load the generated blur patch.")
        val patchMedia = probed.copy(name = "$FACE_BLUR_PREFIX${clip.id}", durationMs = clip.durationMs)
        val overlayId = vm.addFaceBlurOverlay(clip.id, patchMedia, track.offsetX, track.offsetY, track.scale)
        return ok().apply {
            put("overlayClipId", overlayId ?: JSONObject.NULL)
            put("keyframes", track.offsetX.size)
            put(
                "humanSummary",
                "Tracked the face across ${track.offsetX.size} points and added a blur patch that follows it on a track above.",
            )
        }
    }

    /**
     * Teach a visual thing by pointing it out (few-shot, on-device) — the desktop analogue of Android's
     * `add_reference`. Embeds the frame at the playhead via [DesktopImageEmbedder] and stores the
     * L2-normalized vector as a positive (or, with [negative], a hard-negative) example of the concept
     * [name] in the shared [LearnedConcept] store. Desktop embeds the WHOLE frame (Android detects
     * per-object first); honest about being frame-level. Requires the concept-embedding model.
     */
    private fun addReference(name: String, term: String, negative: Boolean): JSONObject {
        require(name.isNotBlank()) { "Give the thing a name, e.g. \"Rex\"." }
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("idEmbedModelPath")
        require(model.isNotBlank()) {
            "No concept-embedding model set. Add an ONNX embedder in Settings → AI Analyzer → Concept matching."
        }
        require(File(model).isFile) { "The concept-embedding model file does not exist at: $model" }
        val st = vm.uiState.value
        val now = st.currentTimeMs
        val clip = com.hereliesaz.guillotine.model.TimelineMath.activeClip(st.document.clips, ClipType.VIDEO, now)
            ?: throw IllegalStateException("No video clip at the playhead to capture from — scrub to the thing first.")
        val media = st.document.mediaFor(clip) ?: throw IllegalStateException("Media missing for clip ${clip.id}.")
        val sourceMs = com.hereliesaz.guillotine.model.TimelineMath.sourceTimeMs(clip, now).coerceAtLeast(0L)
        val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, sourceMs) }
            ?: throw IllegalStateException("Could not read the current frame.")
        val vec = DesktopImageEmbedder.embed(model, frame).toList()
        val terms = if (term.isBlank()) emptyList() else listOf(term.trim().lowercase())
        val key = name.trim()
        val concepts = DesktopLearnedConceptStore.load()
        val existing = concepts.firstOrNull { it.name.equals(key, ignoreCase = true) }
        val updated = when {
            existing != null && negative -> existing.copy(negatives = existing.negatives + listOf(vec), terms = (existing.terms + terms).distinct())
            existing != null -> existing.copy(examples = existing.examples + listOf(vec), terms = (existing.terms + terms).distinct())
            negative -> LearnedConcept(key, terms, emptyList(), listOf(vec))
            else -> LearnedConcept(key, terms, listOf(vec))
        }
        DesktopLearnedConceptStore.save(concepts.filterNot { it.name.equals(key, ignoreCase = true) } + updated)
        return ok().apply {
            put("name", updated.name)
            put("exampleCount", updated.exampleCount)
            put("negativeCount", updated.negativeCount)
            put(
                "humanSummary",
                if (negative) "Learned a non-example for \"${updated.name}\" (${updated.negativeCount} total)."
                else "Learned \"${updated.name}\" (${updated.exampleCount} example(s)).",
            )
        }
    }

    /**
     * Find/keep a learned thing in a clip — the desktop analogue of `analyze_clip_with_concept`. Samples
     * the clip, embeds each window ([DesktopImageEmbedder]), and matches it to the concept's positive
     * examples (a window matches when its best positive cosine clears a threshold and beats every
     * negative). With [keepOnly] the non-matching windows are cut; otherwise the matching windows are
     * cut. Cuts are applied for real via [EditorViewModel.applyCuts]. Requires the embedding model.
     */
    private fun analyzeClipWithConcept(clipId: String, name: String, keepOnly: Boolean): JSONObject {
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val concept = DesktopLearnedConceptStore.get(name)
            ?: throw IllegalStateException("No learned thing called \"$name\". Point it out first with add_reference.")
        require(concept.examples.isNotEmpty()) { "\"$name\" has no examples yet — point it out with add_reference." }
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("idEmbedModelPath")
        require(model.isNotBlank()) {
            "No concept-embedding model set. Add an ONNX embedder in Settings → AI Analyzer → Concept matching."
        }
        require(File(model).isFile) { "The concept-embedding model file does not exist at: $model" }
        val positives = concept.examples.map { it.toFloatArray() }
        val negatives = concept.negatives.map { it.toFloatArray() }
        val dur = clip.durationMs
        require(dur > 0) { "Clip has no duration to analyze." }
        val step = maxOf(500L, dur / 40)
        val threshold = 0.55f
        // Build REMOVE segments (source ms) directly, merging contiguous windows.
        val edits = ArrayList<EditSegment>()
        var srcMs = clip.trimStartMs
        val endSrc = clip.trimStartMs + dur
        while (srcMs < endSrc) {
            val winEnd = minOf(srcMs + step, endSrc)
            val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, srcMs) }
            val match = if (frame == null) false else {
                val e = DesktopImageEmbedder.embed(model, frame)
                val maxPos = positives.maxOf { DesktopImageEmbedder.cosine(e, it) }
                val maxNeg = if (negatives.isEmpty()) -1f else negatives.maxOf { DesktopImageEmbedder.cosine(e, it) }
                maxPos >= threshold && maxPos > maxNeg
            }
            val remove = if (keepOnly) !match else match
            if (remove) {
                val last = edits.lastOrNull()
                if (last != null && srcMs <= last.endMs) {
                    edits[edits.size - 1] = last.copy(endMs = maxOf(last.endMs, winEnd))
                } else {
                    edits += EditSegment(srcMs, winEnd, com.hereliesaz.guillotine.model.EditAction.REMOVE, if (keepOnly) "not \"$name\"" else "\"$name\"")
                }
            }
            srcMs = winEnd
        }
        if (edits.isEmpty()) {
            return ok().apply {
                put("humanSummary", if (keepOnly) "Every part matched \"$name\" — nothing removed." else "No part matched \"$name\" — nothing removed.")
            }
        }
        vm.applyCuts(clipId, edits)
        val removedMs = edits.sumOf { it.endMs - it.startMs }
        val n = vm.uiState.value.document.clips.size
        return ok().apply {
            put("segmentsRemoved", edits.size)
            put("clipCount", n)
            put(
                "humanSummary",
                "${if (keepOnly) "Kept only" else "Removed"} \"$name\": cut ${edits.size} range(s) (${msFmt(removedMs)}). Timeline now $n clip(s).",
            )
        }
    }

    /**
     * Cross-dissolve transition: overlap [toClipId] onto the end of [fromClipId] on the same track so
     * the desktop renderer's built-in crossfade blends them (it already fades two overlapping clips).
     * Real — no FFmpeg bake. `type` is ignored (desktop does an opacity cross-dissolve).
     */
    // ---- azphalt plugin tools (parity with app McpTools) ----

    private fun listAzpPlugins(): JSONObject {
        val baseDir = File(DesktopStorage.dataDir, "extensions")
        val pluginsList = JSONArray()
        if (baseDir.exists()) {
            baseDir.listFiles { _, name -> name.endsWith(".azp") }?.forEach { azpFile ->
                try {
                    val manifest = com.hereliesaz.guillotine.azphalt.AzpPackage.load(azpFile.readBytes()).manifest
                    val id = manifest.id
                    val tagsList = manifest.assets.firstOrNull()?.tags ?: emptyList()
                    val tags = JSONArray().also { arr -> tagsList.forEach { arr.put(it) } }
                    val cat = when {
                        id.contains("vegas") -> "vegas-inspired"
                        id.contains("scenery") -> "layer-effects-scenery"
                        id.contains("smart") -> "kinetic-typography-smart"
                        id.contains("typography") || id.contains("type") || tagsList.contains("text") -> "kinetic-typography"
                        else -> "layer-effects"
                    }
                    pluginsList.put(JSONObject().apply {
                        put("id", id); put("name", manifest.name); put("tags", tags); put("category", cat)
                    })
                } catch (_: Exception) {
                    // Skip invalid packages.
                }
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
        val baseDir = File(DesktopStorage.dataDir, "extensions")
        // Track whether an installed .azp actually matches pluginId, so applying an unknown plugin fails
        // loudly instead of silently stamping the clip with an id that resolves to nothing.
        var pluginExists = false
        val motionBytes: ByteArray? = baseDir.listFiles { _, name -> name.endsWith(".azp") }
            ?.firstNotNullOfOrNull { f ->
                runCatching {
                    val bytes = f.readBytes()
                    val plan = com.hereliesaz.guillotine.azphalt.AzpMotionInstaller.plan(bytes, emptySet())
                    if (plan.loaded.manifest.id != pluginId) return@runCatching null
                    pluginExists = true
                    val motion = plan.motions.firstOrNull() ?: return@runCatching null
                    com.hereliesaz.guillotine.azphalt.AzpMotionInstaller.bundledBytes(plan, motion)
                }.getOrNull()
            }
        if (!pluginExists) throw IllegalArgumentException("Plugin $pluginId not found in the extensions directory.")
        if (motionBytes != null && clip.type == com.hereliesaz.guillotine.model.ClipType.TEXT) {
            vm.applyCaptionMotion(clipId, motionBytes, pluginId)
            return ok().apply {
                put("humanSummary", "Applied kinetic-typography preset $pluginId to caption $clipId (baked keyframes).")
            }
        }
        vm.updateClip(clipId) { c -> c.copy(azpPluginId = pluginId) }
        return ok().apply { put("humanSummary", "Applied plugin $pluginId to clip $clipId.") }
    }

    private fun clearAzpPlugin(clipId: String): JSONObject {
        vm.uiState.value.document.clips.find { it.id == clipId }
            ?: throw IllegalArgumentException("Clip $clipId not found.")
        vm.clearCaptionMotion(clipId)
        return ok().apply { put("humanSummary", "Cleared the applied plugin/preset from clip $clipId.") }
    }

    private fun applyTransition(fromClipId: String, toClipId: String, durationSec: Float): JSONObject {
        val doc = vm.uiState.value.document
        val from = doc.clips.firstOrNull { it.id == fromClipId }
            ?: throw IllegalArgumentException("Clip not found: $fromClipId")
        val to = doc.clips.firstOrNull { it.id == toClipId }
            ?: throw IllegalArgumentException("Clip not found: $toClipId")
        val durMs = (durationSec.coerceIn(0.1f, 5f) * 1000f).toLong().coerceAtMost(minOf(from.durationMs, to.durationMs))
        val newStart = (from.endTimeMs - durMs).coerceAtLeast(0)
        vm.updateClip(toClipId) { it.copy(startTimeMs = newStart, trackId = from.trackId) }
        return ok().apply {
            put("overlapMs", durMs)
            put("humanSummary", "Cross-dissolve: overlapped the clips by ${msFmt(durMs)} so they blend on-device.")
        }
    }

    /** Remove filler words ("um", "uh", …) using on-device Vosk word timings + real cuts. */
    private fun removeFillers(clipId: String): JSONObject {
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("speechModelPath")
        require(model.isNotBlank()) { "No on-device speech model set. Set a Vosk model in Settings → Transcription." }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val pcm = runBlocking { DesktopMediaDecoder.decodePcmMono(media.uri, 16_000) }
            ?: throw IllegalStateException("No audio track in \"${media.name}\" to analyze.")
        val fillers = setOf("um", "umm", "uh", "uhh", "erm", "er", "ah", "hmm", "mmm", "like")
        val srcStart = clip.trimStartMs
        val srcEnd = clip.trimStartMs + clip.durationMs
        val words = DesktopVoskTranscriber.transcribe(model, pcm.samples)
            .flatMap { it.words }
            .filter { it.word.trim().lowercase().trim('.', ',', '?', '!') in fillers }
            .filter { it.startMs in srcStart until srcEnd }
            .sortedBy { it.startMs }
        if (words.isEmpty()) return ok().apply { put("humanSummary", "No filler words found.") }
        val edits = ArrayList<EditSegment>()
        for (w in words) {
            val last = edits.lastOrNull()
            if (last != null && w.startMs <= last.endMs + 150) {
                edits[edits.size - 1] = last.copy(endMs = maxOf(last.endMs, w.endMs))
            } else {
                edits += EditSegment(w.startMs, w.endMs, com.hereliesaz.guillotine.model.EditAction.REMOVE, "filler: ${w.word}")
            }
        }
        vm.applyCuts(clipId, edits)
        val removedMs = edits.sumOf { it.endMs - it.startMs }
        return ok().apply {
            put("removed", words.size)
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Removed ${words.size} filler word(s) (${msFmt(removedMs)}).")
        }
    }

    /**
     * Prompt-driven analysis via the on-device labeler — desktop's take on `analyze_clip`. Labels
     * sampled windows and keeps the ones whose labels match the clip's prompt, cutting the rest (only
     * when at least one window matches, so a total miss never nukes the clip). Label-based, not a VLM.
     */
    private fun analyzeClipByPrompt(clipId: String): JSONObject {
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("labelModelPath")
        require(model.isNotBlank()) { "No image-labeling model set. Add an ONNX classifier in Settings → AI Analyzer → Footage search." }
        require(File(model).isFile) { "The image-labeling model file does not exist at: $model" }
        val doc = vm.uiState.value.document
        val clip = doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
        require(clip.prompt.isNotBlank()) { "Clip has no prompt. Use set_prompt first, e.g. \"the parts with a dog\"." }
        val media = doc.mediaFor(clip) ?: throw IllegalArgumentException("No media for clip: $clipId")
        val promptWords = clip.prompt.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val dur = clip.durationMs
        require(dur > 0) { "Clip has no duration to analyze." }
        val step = maxOf(500L, dur / 40)
        data class Win(val start: Long, val end: Long, val match: Boolean)
        val wins = ArrayList<Win>()
        var srcMs = clip.trimStartMs
        val endSrc = clip.trimStartMs + dur
        while (srcMs < endSrc) {
            val winEnd = minOf(srcMs + step, endSrc)
            val frame = runBlocking { DesktopMediaDecoder.grabFrame(media.uri, srcMs) }
            val match = frame != null && DesktopImageLabeler.labels(model, frame, topK = 5).any { lbl ->
                val lw = lbl.text.lowercase()
                promptWords.any { pw -> pw in lw } || lw.split(Regex("[^a-z0-9]+")).any { it.length > 2 && it in promptWords }
            }
            wins += Win(srcMs, winEnd, match)
            srcMs = winEnd
        }
        if (wins.none { it.match }) {
            return ok().apply { put("humanSummary", "Nothing matched \"${clip.prompt}\" — left the clip unchanged.") }
        }
        val edits = ArrayList<EditSegment>()
        for (wn in wins.filter { !it.match }) {
            val last = edits.lastOrNull()
            if (last != null && wn.start <= last.endMs) {
                edits[edits.size - 1] = last.copy(endMs = maxOf(last.endMs, wn.end))
            } else {
                edits += EditSegment(wn.start, wn.end, com.hereliesaz.guillotine.model.EditAction.REMOVE, "not \"${clip.prompt}\"")
            }
        }
        if (edits.isEmpty()) return ok().apply { put("humanSummary", "Every part matched — nothing to cut.") }
        vm.applyCuts(clipId, edits)
        val removedMs = edits.sumOf { it.endMs - it.startMs }
        val n = vm.uiState.value.document.clips.size
        return ok().apply {
            put("segmentsRemoved", edits.size)
            put("clipCount", n)
            put("humanSummary", "Kept the parts matching \"${clip.prompt}\": cut ${edits.size} range(s) (${msFmt(removedMs)}). Timeline now $n clip(s).")
        }
    }

    /** Caption the playhead frame. Desktop has no on-device VLM, so this is the honest label-based
     *  description (same as describe_current_frame); [clipId] is accepted for API parity. */
    private fun captionFrameTool(clipId: String): JSONObject = describeCurrentFrame()

    /**
     * Replace a clip's background: matte the subject via the on-device segmentation model (on export)
     * and drop a solid colour or an image on a NEW track behind it ([EditorViewModel.replaceBackground]).
     * Requires the subject-segmentation model (Settings → AI Analyzer → Background removal), else the
     * matte can't apply. The matte is applied in the export render; the preview shows the un-matted clip.
     */
    private fun replaceBackgroundTool(clipId: String, color: String, imagePath: String): JSONObject {
        require(com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("segModelPath").isNotBlank()) {
            "No subject-segmentation model set. Add an ONNX segmenter in Settings → AI Analyzer → Background removal."
        }
        val clip = resolveClipOrPlayhead(clipId)
        val bg: MediaItem = when {
            imagePath.isNotBlank() -> {
                val f = File(imagePath)
                require(f.isFile) { "Background image not found: $imagePath" }
                DesktopMediaImport.probe(f)?.copy(durationMs = clip.durationMs)
                    ?: throw IllegalStateException("Couldn't load background image: $imagePath")
            }
            color.isNotBlank() -> {
                val file = makeSolidColorImage(parseColor(color), File(DesktopStorage.dataDir, "bg"))
                DesktopMediaImport.probe(file)?.copy(name = "bg: $color", durationMs = clip.durationMs)
                    ?: throw IllegalStateException("Couldn't create the background colour image.")
            }
            else -> throw IllegalArgumentException("Give a background color (e.g. #202020) or an image_path.")
        }
        vm.replaceBackground(clip.id, bg)
        return ok().apply {
            put("clipCount", vm.uiState.value.document.clips.size)
            put("humanSummary", "Replaced the background behind clip ${clip.id} — the subject is matted on export.")
        }
    }

    /**
     * Portrait bokeh: keep the segmented subject sharp and blur the background. Sets the clip's [bokeh]
     * flag; the export render composites a blurred background behind the matted subject
     * ([DesktopSegmenter.portraitBlur]). Requires the subject-segmentation model. (Desktop uses subject
     * segmentation, not a depth model — so it's a portrait blur, not true depth-of-field.)
     */
    private fun applyBokehTool(clipId: String): JSONObject {
        require(com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("segModelPath").isNotBlank()) {
            "No subject-segmentation model set. Add an ONNX segmenter in Settings → AI Analyzer → Background removal."
        }
        val clip = resolveClipOrPlayhead(clipId)
        vm.updateClipFilters(clip.id) { it.copy(bokeh = true) }
        return ok().apply {
            put("humanSummary", "Portrait bokeh on clip ${clip.id} — subject sharp, background blurred (on export).")
        }
    }

    /** Parse "#RRGGBB" / "0xRRGGBB" / a few colour names into a packed RGB int (defaults to black). */
    private fun parseColor(spec: String): Int {
        val s = spec.trim().lowercase()
        mapOf(
            "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x00AA00,
            "blue" to 0x0000FF, "gray" to 0x808080, "grey" to 0x808080, "yellow" to 0xFFFF00,
            "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
        )[s]?.let { return it }
        val hex = s.removePrefix("#").removePrefix("0x")
        return runCatching { hex.toInt(16) and 0xFFFFFF }.getOrDefault(0x000000)
    }

    /** Write a small solid-colour PNG (uniform, so any size fills the frame) and return the file. */
    private fun makeSolidColorImage(rgb: Int, outDir: File): File {
        outDir.mkdirs()
        val img = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(rgb)
        g.fillRect(0, 0, 320, 180)
        g.dispose()
        val file = File(outDir, "bg_${Integer.toHexString(rgb)}.png")
        ImageIO.write(img, "png", file)
        return file
    }

    // ---- learned concepts: pure data ops on the shared LearnedConcept store (REAL on desktop) ----

    private fun listConcepts(): JSONObject {
        val concepts = DesktopLearnedConceptStore.load()
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
        DesktopLearnedConceptStore.remove(name)
        return ok().apply { put("humanSummary", "Forgot \"$name\".") }
    }

    // ---- offline speech: transcription (Vosk) + honest stubs ----------------

    /**
     * Honest stub for the sherpa-onnx-backed speech tools that have no clean JVM distribution yet
     * (transcribe_precise / add_voiceover / diarize_clip / remove_fillers). Never fakes success.
     */
    private fun speechToolUnavailable(tool: String, needs: String) = JSONObject().apply {
        val msg = "$tool needs $needs. This heavy model is not bundled with Guillotine by default. " +
            "Please open the Azphalt Storefront and install the `.azp` package for this model, which will download it to your local registry."
        put("error", msg)
        put("humanSummary", msg)
    }

    /** Decode a clip's audio to 16 kHz mono, transcribe with the Vosk model, and add timed captions. */
    private fun transcribeClip(clipId: String): JSONObject {
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("speechModelPath")
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
        val model = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("speechModelPath")
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
        // No window had ≥1s of overlap → never scored. Fail loudly instead of silently "syncing" at 0.
        check(bestScore != -Double.MAX_VALUE) {
            "Could not find a reliable audio match between the clips within the search window."
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

    private fun setFrameStep(clipId: String, step: Int): JSONObject {
        val doc = vm.uiState.value.document
        doc.clips.firstOrNull { it.id == clipId } ?: throw IllegalArgumentException("Clip not found: $clipId")
        val n = step.coerceAtLeast(1)
        vm.updateClipFilters(clipId) { f -> f.copy(frameStep = n) }
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        val summary = if (n <= 1) "Turned off frame decimation on clip $clipId (every frame plays)."
        else "Keeping every $n$suffix frame on clip $clipId (choppy look, same length)."
        return ok().apply { put("humanSummary", summary) }
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
        // Desktop renders shaders through Skia (GLSL→SkSL); confirm THIS shader actually compiles so the
        // report is honest — some advanced GLSL doesn't translate and would silently no-op.
        val rendered = com.hereliesaz.guillotine.desktop.media.DesktopShaderPass.canRender(file.absolutePath)
        return ok().apply {
            put("shaderRendered", rendered)
            put(
                "humanSummary",
                "Recorded shader ${file.name}" +
                    (if (overrides.isNotEmpty()) " with ${overrides.size} param(s)" else "") +
                    " on clip ${clip.id}. " +
                    if (rendered) {
                        "It renders in the desktop preview and export via Skia."
                    } else {
                        "Note: this shader couldn't be translated to Skia's SkSL, so it is saved on the " +
                            "clip but will NOT be visible in the desktop preview or export."
                    },
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
        val dir = com.hereliesaz.guillotine.desktop.platform.ModelResolver.resolve("stemModelPath")
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
