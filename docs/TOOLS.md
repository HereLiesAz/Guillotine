# Guillotine AI Tool Reference

Every editing capability in Guillotine — cut, grade, transcribe, denoise, reframe, generate — is
exposed as an **MCP tool**. This is the single shared capability surface: the **in-app AI assistant**
and any **external AI** (through the embedded [MCP server](#the-mcp-server)) drive the editor through
the exact same tool catalog. There is no separate "AI-only" back door; the assistant is just a
client of these tools.

**On-device invariant.** Analysis runs **on the device** — vision, ASR/TTS, diarization, stem
separation, beat detection, scene detection, LUTs, shaders, and native filters all execute locally.
A controller LLM only ever exchanges **text**: it reads the timeline state and issues tool calls, and
gets back ids, parameters, and human-readable summaries. **Your video and audio frames never leave
the device** — the raw pixels stay local; only the resulting text is returned to the model. The
cloud-backed exceptions are the generation tools and one object-removal path, which are opt-in and
require your own provider key (see [Generation](#generation-cloud-byo-key) and
[`remove_object_generative`](#vision--recognition)).

This document lists **110 tools** across 12 categories, plus 2 read-only resources. It is
**manually maintained** (there is no codegen step tying it to the tool definitions), so it can drift
from the actual catalog — when in doubt, the live tool definitions are always the source of truth: an
MCP client can introspect the whole catalog at runtime with `tools/list`.

**See also:** [PLUGINS.md](PLUGINS.md) (the plugin protocol and how to connect a client),
[ECOSYSTEM.md](ECOSYSTEM.md) (LUT / shader / Frei0r / FFmpeg formats), [SETTINGS.md](SETTINGS.md)
(where model paths, keys, and the MCP token live), [MODELS.md](MODELS.md) (on-device model files),
[PROVIDERS.md](PROVIDERS.md) (cloud generation providers), and [MANUAL.md](MANUAL.md).

---

## The MCP server

Guillotine's editor is a standard **[Model Context Protocol](https://modelcontextprotocol.io)** server
(JSON-RPC 2.0). Point any MCP client — the in-app assistant, Claude Desktop, an SDK script, another
app — at it and it sees the tools below like any other MCP server.

| | |
| --- | --- |
| **Transport** | HTTP, JSON-RPC 2.0 |
| **Endpoint** | `POST /mcp` (bearer-gated) — JSON-RPC request/response |
| **Liveness** | `GET /health` (open, no token) → `{"status":"ok"}` |
| **Port** | `6274` (default; bound to loopback (`127.0.0.1`) only — a security fix that stops a bearer token from being sniffable off the LAN. Reach it from another device via the encrypted Cloudflare relay or an `adb`/USB port-forward, not by exposing the port directly) |
| **Protocol version** | `2024-11-05` |
| **Server info** | name `guillotine-editor`, version `1.0.0` |
| **Auth** | `Authorization: Bearer <token>` on every `/mcp` call |

### Authentication

The bearer token is shown in **Settings → Advanced** ("MCP access token"), with **Copy** and
**Regenerate** actions. It is generated on first use (24 random bytes, URL-safe Base64) and persisted
**encrypted on-device** (Keystore-backed `EncryptedSharedPreferences`, the same store as your API
keys). The server does a constant-time comparison and **fails closed** — if no token is configured, or
the header is missing/invalid, the call is rejected (`-32001 Unauthorized`). Regenerating the token
immediately revokes any tool still using the old one. See
[`McpAuth`](../app/src/main/java/com/hereliesaz/guillotine/mcp/McpAuth.kt) and
[`McpServer`](../shared/src/main/kotlin/com/hereliesaz/guillotine/mcp/McpServer.kt).

### JSON-RPC methods

| Method | Purpose |
| --- | --- |
| `initialize` | Handshake — returns `protocolVersion`, `capabilities` (`tools`, `resources`), and `serverInfo`. |
| `tools/list` | The full, self-describing tool catalog: each tool's `name`, `description`, and JSON `inputSchema`. |
| `tools/call` | Invoke a tool by `name` with `arguments`. Result is returned as a `content` text block containing the tool's JSON (usually including a `humanSummary`); failures set `isError: true` with the message. |
| `resources/list` | List the read-only resources below. |
| `resources/read` | Read a resource by `uri`. |

### Resources

Read-only views of editor state (`application/json`):

| URI | Name | Contents |
| --- | --- | --- |
| `guillotine://timeline` | Timeline | Current timeline state (same payload as `get_timeline`). |
| `guillotine://clips` | Clips | The list of all clips. |

### Connecting

```bash
# List the tools
curl -s http://<device-ip>:6274/mcp \
  -H "Authorization: Bearer $GUILLOTINE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# Call one
curl -s http://<device-ip>:6274/mcp \
  -H "Authorization: Bearer $GUILLOTINE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"apply_lut","arguments":{"path":"/data/data/com.hereliesaz.guillotine/files/luts/teal.cube"}}}'
```

`path`/`image_path` arguments (`apply_lut`, `replace_background`, …) are only accepted if they
canonicalize to somewhere the app already controls — its own `filesDir` (where a LUT picked via the
clip's "+ LUT (.cube)" button, or imported media, already lives — see `luts/`, `imported-models/`) or
`cacheDir`. A path outside that — `/sdcard/...`, another app's storage, anything reachable only
because the caller happened to type it — is rejected with a clear error rather than read.

To drive the editor from off-device without exposing the phone directly, deploy the Cloudflare relay
(`tools/mcp-relay`) and run the local proxy with the **same token** — **Settings → Advanced → Remote
relay**. The relay brokers only the same text JSON-RPC (end-to-end encrypted; Cloudflare relays
ciphertext only), never media. See [PLUGINS.md](PLUGINS.md) for the full protocol walkthrough.

> The Android app exposes this catalog via `McpTools`; the desktop build ships an equivalent MCP
> surface (`DesktopMcpTools`) over the same protocol. This reference documents the `McpTools` catalog.

### Argument types

Argument `type` values below are JSON-schema types straight from the definitions: `string`,
`integer` (whole number), `number` (float), `boolean`, and `object`. "Required" arguments must be
supplied; "optional" ones fall back to the listed default (or the noted behavior when there is no
literal default in code).

---

## Categories at a glance

| Category | Tools |
| --- | --- |
| [Timeline & editing](#timeline--editing) | 22 |
| [Vision & recognition](#vision--recognition) | 10 |
| [Transcription, captions & speech](#transcription-captions--speech) | 6 |
| [Audio](#audio) | 7 |
| [Color, LUT, shader, FFmpeg/Frei0r & transitions](#color-lut-shader-ffmpegfrei0r--transitions) | 9 |
| [Named video effects (FFmpeg bake)](#named-video-effects-ffmpeg-bake) | 30 |
| [Neural image effects & compositing](#neural-image-effects--compositing) | 4 |
| [Azphalt plugins](#azphalt-plugins) | 3 |
| [Scene, highlights, reframe & export](#scene-highlights-reframe--export) | 4 |
| [Beat-sync / rhythm](#beat-sync--rhythm) | 5 |
| [Generation (cloud, BYO key)](#generation-cloud-byo-key) | 3 |
| [User-defined tools & action recording](#user-defined-tools--action-recording) | 7 |
| **Total** | **110** |

---

## Timeline & editing

Read timeline state and perform the app's real split / delete / ripple operations, filters, and
keyframes. These are the same operations a user performs by hand.

### `get_timeline`
Get the current timeline state: all clips, tracks, and timing.

_No arguments._

### `get_clip`
Get details for a specific clip by ID.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip ID. |

### `set_prompt`
Set the AI analysis prompt for a clip.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to set the prompt on. |
| `prompt` | string | required | — | The analysis prompt text. |

### `select_clip`
Select a clip by ID (empty string clears the selection).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | Clip to select; empty string to clear. |

### `split_clip`
Split a clip into two at a timeline position (ms).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to split. |
| `at_ms` | integer | required | — | Timeline position in ms. |

### `segment_clip`
Split a clip into separate clips at every keep/remove edit boundary (keeps all pieces).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to segment. |

### `delete_clip`
Delete a clip (and its linked audio / group) from the timeline.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to delete. |

### `ripple_delete_range`
Cut a timeline span `[start_ms, end_ms)` out of every track and close the gap.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `start_ms` | integer | required | — | Range start (ms, inclusive). |
| `end_ms` | integer | required | — | Range end (ms, exclusive). |

### `set_clip_filter`
Set static values for a clip filter (e.g. brightness = 1.2, speed = 2.0).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to filter. |
| `property` | string | required | — | Property name, e.g. `brightness`, `speed`. |
| `value` | number | required | — | The value to set. |

### `lookup_vocabulary`
Resolve an editing word/phrase against the shared vocabulary graph. Returns the concept it maps to, its
synonyms, its opposite (antonym), the tool it routes to, and — for the exact phrase — whether the sense is
inverted (a negation like "less/reduce/remove" flips it to the opposite tool). The graph seeds a compact
lexicon and expands it programmatically, and it also feeds a synonym/antonym appendix into the agent prompt.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `term` | string | required | — | The word or phrase to look up (e.g. `crispy`, `less warm`). |

### `set_frame_step`
Frame decimation — keep only every Nth frame for a choppy/stutter look, live and on-device (no ffmpeg,
no baking). The clip stays the same length and its audio is untouched, so it stays in sync. Quantizes
against the project frame rate; desktop snaps the frame-grab source time, Android drops to `fps / step`
via a Media3 `FrameDropEffect`. `apply_ffmpeg_filter` with `framestep=N` is the baked-to-a-new-clip equivalent.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to decimate. |
| `step` | integer | required | — | Keep 1 of every N frames. 2 = every other frame; 1 = off. |

### `add_keyframe`
Add a keyframe for a specific `KeyframeProperty` at a specific time in the clip.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to animate. |
| `property` | string | required | — | The keyframe property. |
| `time_ms` | integer | required | — | Time within the clip (ms). |
| `value` | number | required | — | Keyframe value. |

### `clear_keyframes`
Remove all keyframes for a specified property on a clip.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip. |
| `property` | string | required | — | The property to clear. |

### Core timeline verbs

Thin wrappers over the editor's own operations (shared across platforms), giving the agent direct control
of the timeline rather than only analysis/effects.

| Tool | What it does | Key args |
| --- | --- | --- |
| `seek` | Move the playhead to a time (so the frame tools can inspect/act there) | `time_ms` |
| `move_clip` | Reposition a clip in time / to another track | `clip_id`, `start_ms`, `track_id?` |
| `trim_clip` | Shift a clip's in/out points by a delta | `clip_id`, `start_delta_ms?`, `end_delta_ms?` |
| `add_text` | Add a title/caption text clip | `text`, `track_id?`, `start_ms?`, `duration_ms?` |
| `add_track` | Add a video/audio track (returns its id) | `type` |
| `set_track` | Set track mute/disable/volume/opacity (absolute) | `track_id`, `muted?`, `disabled?`, `volume?`, `opacity?` |
| `transform_clip` | Set a clip's crop/scale/offset/rotation (absolute) | `clip_id`, `scale?`, `offset_x?`, `offset_y?`, `rotation?` |
| `undo` | Undo the last edit | — |
| `redo` | Redo the last undone edit | — |

`get_timeline` also now reports `globalSettings` (fps, aspect ratio, crop), per-track settings, and the
current `selectedClipIds`, so the agent can read project state instead of guessing.

---

## Vision & recognition

On-device vision: prompt-driven analysis that finds matching frames and cuts for real, frame
description, few-shot "point at it" concepts, object removal, and content search.

### `analyze_clip`
Run on-device vision on a clip using its current prompt **and cut it for real** — matching ranges are
found, the clip is split into its kept pieces, and removed ranges are deleted with the timeline
closing up (no black gaps). "Cut/remove the frames with X" removes matches; "keep only X" removes the
non-matches. Use [`remove_object_generative`](#vision--recognition) instead when the clip must stay the
same length.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to analyze and cut. |

### `analyze_clip_with_reference`
Like `analyze_clip` (finds matches **and** cuts for real), but uses the clip's current playhead frame
as a visual reference to find that specific object across the clip. Set the clip's prompt to the
object first.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to analyze against its playhead frame. |

### `remove_object_generative`
Remove an object by **generating** replacement frames so the clip stays the **same length**: the
object's segments become inpainted image clips grouped with the original pieces. Set the clip's prompt
to the object first. **Cloud / BYO key** — requires a Leonardo API key in Settings.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to process. |

### `describe_current_frame`
Get an on-device vision description of the current preview frame: detected objects (label, confidence,
pixel bounding box), the clip's id, and the source-media timestamp. The raw frame stays on the device
— only the resulting text is returned.

_No arguments._

### `caption_frame`
Describe a frame in rich natural language using the on-device multimodal VLM (Gemma-3n). Prefer this
over `describe_current_frame` when a real scene description is wanted rather than a list of detected
objects. Requires the VLM model in **Settings → AI Analyzer → Frame captioning (VLM)**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |
| `prompt` | string | optional | describe it | Optional question about the frame. |

### `add_reference`
Teach the app a **specific** thing by pointing at it in the current preview frame: captures an
on-device fingerprint and adds it as an example of a named concept. Call once per frame the user points
it out in (more examples = more robust). Set `negative=true` for a non-example (a same-kind look-alike
to reject).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `name` | string | required | — | Short name for the thing, e.g. "Rex". |
| `term` | string | optional | — | Optional kind of object, e.g. "dog", "mug", to help pick it in the frame. |
| `negative` | boolean | optional | `false` | True if this frame does **not** contain the thing (a look-alike to reject). |

### `list_concepts`
List the things taught by pointing them out (learned concepts): name + how many examples each has.

_No arguments._

### `delete_concept`
Forget a learned thing by name.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `name` | string | required | — | The concept name to forget. |

### `analyze_clip_with_concept`
Keep or cut a clip by a **learned** thing (taught via `add_reference`): finds frames containing that
specific instance and cuts for real.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to analyze. |
| `name` | string | required | — | The learned thing's name. |
| `keep_only` | boolean | optional | `false` | `true` keeps only frames with it (removes the rest); `false` removes the frames with it. |

### `search_clips`
Find which video clips contain something on-device (no key): samples each clip's frames and matches
them against `query` using on-device image labels (~400 common things/scenes). Returns matching clips
with the matched label and a timestamp; does not edit anything.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `query` | string | required | — | What to look for, e.g. "dog" or "sunset". |

---

## Transcription, captions & speech

ASR (on-device Vosk / cloud Whisper / offline sherpa-onnx Whisper), on-screen captions, filler-word
removal, TTS voiceover, and speaker diarization. Model-gated tools name the exact
**Settings → AI Analyzer** entry when a model is missing — relay the error, don't retry. See
[SETTINGS.md](SETTINGS.md) and [MODELS.md](MODELS.md).

### `transcribe_clip`
Transcribe a clip's audio (on-device Vosk or cloud Whisper) and add timed caption text clips to the
timeline, grouped with the source clip. Each caption appears/disappears in sync with the spoken words.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to transcribe. |

### `animated_transcribe_clip`
Transcribe a clip's audio and create **animated per-syllable** captions: each word is split into
syllables on separate video tracks that appear simultaneously, with SCALE keyframes that ramp each
syllable from small to large as it is spoken — a "grow as said" kinetic-typography effect.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to transcribe. |

### `transcribe_precise`
Transcribe a clip's audio **on-device** with the offline Whisper (sherpa-onnx) model and return the
transcript text — more accurate than `transcribe_clip`'s lightweight recognizer. Requires the ASR model
in **Settings → AI Analyzer → Speech (ASR)**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip whose audio to transcribe. |

### `remove_fillers`
Remove filler words ("um", "uh", "er", "hmm") on-device using the offline Whisper word timings,
ripple-deleting each filler so the timeline closes up. Requires the ASR model
(**Settings → AI Analyzer → Speech (ASR)**). Timings are approximate — review the result.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to de-filler. |

### `add_voiceover`
Synthesize speech from text **on-device** (offline neural TTS via sherpa-onnx) and add it as an audio
clip. Requires the TTS voice in **Settings → AI Analyzer → Speech (TTS)**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `text` | string | required | — | The words to speak. |
| `speed` | number | optional | `1.0` | Speaking rate (`<1` slower, `>1` faster). |

### `diarize_clip`
Speaker diarization **on-device** (no key): work out who spoke when in a clip's audio and return the
speaker turns (speaker index + time range). Requires **both** diarization models in
**Settings → AI Analyzer → Speaker diarization**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip whose audio to diarize. |
| `num_speakers` | integer | optional | `0` | Known speaker count; `0` infers automatically. |

---

## Audio

On-device audio processing: vocal removal, ML stem separation, denoise, loudness/level normalization,
music ducking, and audio-based multicam sync. All run locally with no key.

### `remove_vocals`
Remove the lead vocals from a clip's audio **on-device** (no model/key) and add the resulting
instrumental as a new audio clip — for karaoke / backing tracks. Uses stereo center-channel
cancellation, so it needs a **stereo** track (errors on mono); a lightweight extractor, not a full
multi-stem split.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip whose audio to process. |

### `separate_stems`
Split a clip's music into **vocals** and **accompaniment** tracks **on-device** (Spleeter via ONNX, no
key) and add both as audio clips — true ML stem separation. Requires the Spleeter model in
**Settings → AI Analyzer → Stem separation**; heavy — best on moderate clip lengths.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip whose audio to separate. |

### `denoise_clip`
Remove background noise from a clip's **voice** audio **on-device** (GTCRN speech denoiser, no key) and
add the cleaned track as a new audio clip. Strips hiss, hum, drone, and general background noise while
keeping speech. Requires the denoiser model in **Settings → AI Analyzer → Noise reduction**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip whose voice audio to denoise. |

### `normalize_levels`
Even out the loudness of the timeline's audio clips **on-device** (no key): measures each clip's level
and sets its volume so they all sit at a consistent perceived loudness. Simple RMS level matching (for
a platform loudness target use `normalize_loudness`).

_No arguments._

### `normalize_loudness`
Normalize each audio clip to a platform **loudness** target **on-device** (no key), using ITU-R
BS.1770 K-weighted LUFS. `-14` is YouTube/Spotify, `-16` Apple/podcasts, `-23` EBU R128 broadcast.
Ungated integrated LUFS — a good match, not a certified meter.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `target_lufs` | number | optional | `-14` | Target loudness in LUFS. |

### `auto_duck`
Auto-duck (sidechain) a music clip under a voice/speech clip **on-device** (no model/key): detect where
the voice is talking and dip the music's volume there with smooth ramps, restoring it in the gaps.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `music_clip_id` | string | required | — | The music clip to duck. |
| `voice_clip_id` | string | required | — | The voice/speech clip that triggers the ducking. |
| `amount` | number | optional | `0.3` | Ducked music level 0–1 (lower = quieter under speech; `0.3` ≈ −10 dB). |

### `sync_by_audio`
Sync two clips by their audio **on-device** (no key): cross-correlates the two audio tracks to find the
time offset and moves the second clip so its audio lines up with the reference (multicam /
dual-recording sync). Both clips need audio of the same moment.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `reference_clip_id` | string | required | — | The clip to keep fixed (the reference). |
| `clip_id` | string | required | — | The clip to move so its audio aligns to the reference. |
| `max_offset_sec` | integer | optional | `15` | Max search offset in seconds. |

---

## Color, LUT, shader, FFmpeg/Frei0r & transitions

Grade and effect the picture on-device: auto/shot-match color, `.cube` LUTs, ISF/GLSL shaders, FFmpeg
`-vf` filtergraphs (and Frei0r plugins), and `xfade` transitions. FFmpeg-based tools need an `ffmpeg`
executable set in **Settings → AI Analyzer → FFmpeg filters** (desktop-first). See
[ECOSYSTEM.md](ECOSYSTEM.md) for the formats.

### `auto_color`
Auto color-correct a clip **on-device** (no model): analyze a frame and nudge exposure, contrast, and
saturation toward a balanced look, applied as the clip's filters.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |

### `match_color`
Shot-match **on-device**: set the target clip's exposure/contrast/saturation to match the source clip's
look, so two shots cut together consistently.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `source_clip_id` | string | required | — | The clip whose look to match. |
| `target_clip_id` | string | required | — | The clip to adjust. |

### `apply_lut`
Apply a `.cube` 3D LUT color grade to a clip **on-device** — the standard format exported by
colour-grading and photo-editing tools. Grades in both preview and export.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |
| `path` | string | required | — | Filesystem path to a `.cube` 3D LUT file. |

### `clear_lut`
Remove the `.cube` LUT color grade from a clip (undo `apply_lut`).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |

### `apply_shader`
Apply a custom GLSL shader effect to a clip **on-device** — a standard ISF (`.isf`) shader or a raw
`.fs`/`.glsl` fragment. Runs on every frame in preview and export. Only single-pass, single-image
shaders are supported.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |
| `path` | string | required | — | Filesystem path to an `.isf` / `.fs` / `.glsl` fragment shader. |
| `params` | object | optional | — | Optional `{name: value}` overrides for the shader's scalar inputs (see `list_shader_params`). |

### `clear_shader`
Remove the custom GLSL/ISF shader effect from a clip (undo `apply_shader`).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |

### `list_shader_params`
List an ISF/GLSL shader's adjustable scalar inputs (name, type, default, min, max) so you know what to
pass to `apply_shader`'s `params`.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `path` | string | required | — | Filesystem path to an `.isf` / `.fs` / `.glsl` shader. |

### `apply_ffmpeg_filter`
Bake a standard FFmpeg `-vf` filtergraph onto a clip **on-device** and add the result as a new clip —
the whole FFmpeg filter ecosystem, and **Frei0r** plugins via `frei0r=<name>:<params>`. Requires an
`ffmpeg` executable (**Settings → AI Analyzer → FFmpeg filters**). Bake-to-new-clip, not a live filter.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip whose video to filter. |
| `filter` | string | required | — | An FFmpeg `-vf` filtergraph (Frei0r via `frei0r=name:params`). |

### `apply_transition`
Create a GL-style transition between two clips **on-device** (FFmpeg `xfade`) and add the combined
result as a new clip. `type` is any `xfade` transition (fade, wipe*, slide*, circleopen/close,
dissolve, pixelize, radial, smoothleft, distance, and more). Requires an `ffmpeg` executable.
Bake-to-new-clip, desktop-first.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `from_clip_id` | string | required | — | The outgoing (first) clip. |
| `to_clip_id` | string | required | — | The incoming (second) clip. |
| `type` | string | optional | `fade` | `xfade` transition type. |
| `duration_sec` | number | optional | `1` | Transition/overlap length in seconds. |

---

## Named video effects (FFmpeg bake)

One-call video looks, each backed by a standard FFmpeg `-vf` filtergraph and **baked to a new clip**. They
share the `apply_ffmpeg_filter` engine (desktop bakes in-process via the bundled FFmpeg; Android needs an
ffmpeg executable set in Settings → AI Analyzer → FFmpeg filters). Every effect is duration-preserving
(audio is copied through, so the clip length is unchanged). All take a required `clip_id`; the optional
tuning parameter (if any) is listed below.

| Tool | Effect | Optional param (default) |
| --- | --- | --- |
| `sharpen` | Sharpen a soft/blurry clip | `amount` (1.5) |
| `denoise_video` | Reduce video noise/grain | — |
| `deband` | Remove colour banding in gradients | — |
| `deflicker` | Remove flicker / brightness pulsing | — |
| `stabilize` | Stabilise shaky footage (one pass) | — |
| `lens_correction` | Fix barrel/fisheye distortion | `amount` (0.2) |
| `motion_trail` | Frame-blend into a motion trail / echo | `frames` (3) |
| `film_grain` | Add film grain / analog noise | `strength` (20) |
| `vignette` | Darken the edges | — |
| `vhs` | Retro VHS chroma-shift + noise | `strength` (5) |
| `chromatic_aberration` | RGB channel-split fringe | `amount` (4) |
| `glow` | Dreamy soft glow / bloom | `strength` (0.5) |
| `old_film` | Vintage curve + grain + vignette | — |
| `edge_detect` | Line-art / sketch outline | — |
| `pixelate` | Pixelate / mosaic | `size` (16) |
| `mirror` | Flip horizontally (left-right) | — |
| `flip_vertical` | Flip vertically (upside-down) | — |
| `rotate_180` | Rotate 180° | — |
| `grid_overlay` | Overlay an alignment grid | `size` (64) |
| `warm` | Warm the colour (orange/gold) | — |
| `cool` | Cool the colour (blue) | — |
| `cinematic` | Teal-and-orange grade | — |
| `increase_contrast` | Punchy contrast S-curve | — |
| `vintage` | Faded vintage colour curve | — |
| `cross_process` | Cross-process / lomo look | — |
| `darker` | Lower the exposure | — |
| `brighter` | Lift the exposure | — |
| `noir` | High-contrast B&W film-noir | — |
| `night_vision` | Green night-vision + noise | — |
| `thermal` | False-colour thermal / infrared | — |

---

## Neural image effects & compositing

On-device neural frame effects (TFLite image models) and matte-based compositing (ML Kit) — added as
new image clips or applied to preview + export. Model-gated tools name the
**Settings → AI Analyzer → Image effects** entry when a model is missing.

### `apply_image_effect`
Run an on-device image model on the current preview frame and add the result as a new image clip.
Requires the chosen effect's `.tflite` model in **Settings → AI Analyzer → Image effects**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `effect` | string | required | — | `superres` (upscale) \| `style` (style transfer) \| `depth` (depth map) \| `lowlight` (brighten a dark frame). |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |

### `apply_bokeh`
Add a depth-of-field / portrait "bokeh" blur to the current frame **on-device**: runs the depth model,
keeps the near subject sharp, blurs the far background, and adds the result as an image clip. Requires
the depth model in **Settings → AI Analyzer → Image effects (depth)**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |
| `strength` | number | optional | `1.0` | Blur strength (higher = more blur). |

### `blur_faces`
Toggle **on-device** face anonymization on a clip (ML Kit face detection, no key): every detected face
is blurred in both preview and export. Applies to video and image clips.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | Clip; defaults to the video clip at the playhead. |
| `enabled` | boolean | optional | `true` | Turn face-blur on (default) or off. |

### `replace_background`
Replace a clip's background **on-device** with no green screen (ML Kit subject matte): segment the
subject and composite it over a new background — a solid color or an image — on a new track behind.
Provide `color` **or** `image_path`; defaults to black.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | optional | playhead clip | The subject clip; defaults to the video clip at the playhead. |
| `color` | string | optional | black | Background color (hex `#RRGGBB` or a name). Ignored if `image_path` is set. |
| `image_path` | string | optional | — | Filesystem path to a background image (overrides `color`). |

---

## Azphalt plugins

Discover and apply installed **azphalt `.azp` packages** — third-party effect / kinetic-typography
extensions dropped into the app's extensions directories (see [PLUGINS.md](PLUGINS.md) for the
package format). This is the same apply path the Azphalt Store's own install flow uses
(`AzpPluginApplier`), so "installed from the store" and "applied via an MCP client" can't diverge on
what "applied" means.

### `list_azp_plugins`
List every installed `.azp` plugin: id, name, tags, and a coarse category (layer effect, scenery
effect, kinetic typography, "smart" kinetic typography) inferred from the package. Use this to
discover what's available before calling `apply_azp_plugin`.

_No arguments._

### `apply_azp_plugin`
Apply an installed plugin (by id, from `list_azp_plugins`) to a clip. A kinetic-typography motion
plugin applied to a caption (TEXT clip) is baked into real keyframes, so it animates in both preview
and export and stays editable afterward; other package kinds apply via whatever real render path they
support (shader, LUT, …). Errors — rather than reporting success — if the package has no apply path
for that clip yet.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to apply the plugin to. |
| `plugin_id` | string | required | — | The plugin id (from `list_azp_plugins`). |

### `clear_azp_plugin`
Remove an applied kinetic-typography preset (its baked keyframes) and the applied-plugin marker from a
clip. Hand-authored keyframes the user added on top are kept.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to clear the plugin/preset from. |

---

## Scene, highlights, reframe & export

Segment and repackage footage on-device: visual scene-cut detection, audio-highlight detection,
subject-following reframe, and the output aspect-ratio preset.

### `detect_scenes`
Detect visual scene/shot cuts in a clip **on-device** (colour-histogram content difference — no model
or key) and, by default, split the clip at each cut so every shot is its own piece.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to scan. |
| `sensitivity` | number | optional | `0.5` | 0–1, higher = more cuts. |
| `split` | boolean | optional | `true` | Split the clip at each scene cut; `false` only reports timestamps. |

### `find_highlights`
Scan a clip's **audio** on-device (YAMNet) for exciting moments — applause, cheering, laughter, music,
screaming, a roaring crowd — and return them as timestamped ranges. By default also splits the clip at
each highlight boundary. Requires the YAMNet model in **Settings → AI Analyzer → Audio highlights**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip whose audio to scan. |
| `threshold` | number | optional | `0.3` | Detection confidence 0–1 (lower finds more). |
| `split` | boolean | optional | `true` | Split the clip at highlight boundaries; `false` only reports. |

### `auto_reframe`
Auto-reframe a clip to keep the subject centered **on-device** (no key): detects the main face across
the clip and pans a punched-in crop to follow it — the classic "make it work vertically / follow the
speaker" reframe. Sets the clip's scale and writes OFFSET_X keyframes; needs faces in the footage
(errors if none are found).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to reframe. |
| `zoom` | number | optional | `1.3` | Punch-in scale (more = tighter, more room to pan). |

### `set_export_preset`
Set the project's output aspect ratio for a platform **on-device**.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `preset` | string | required | — | `tiktok`/`reels`/`shorts`/`vertical` (9:16), `square`/`instagram` (1:1), `youtube`/`landscape` (16:9), or `original`. |

---

## Beat-sync / rhythm

Analyze an audio clip's beat grid on-device and cut / animate / assemble video to it. `mode` is one of
`beats`, `downbeats`, or `onsets` throughout.

### `get_beat_map`
Analyze an **audio** clip **on-device** and return its tempo (bpm) plus beat, downbeat, and onset
timestamps (in source ms). Call before `cut_to_beats` / `apply_on_beat`.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `audio_clip_id` | string | required | — | The audio/music clip to analyze. |

### `cut_to_beats`
Split a **video** clip at the beats of an **audio** clip so it cuts in time with the music (all footage
is kept — nothing deleted).

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `video_clip_id` | string | required | — | The video clip to split. |
| `audio_clip_id` | string | required | — | The clip providing the beat grid. |
| `mode` | string | optional | `downbeats` | `beats` \| `downbeats` \| `onsets`. |
| `every_n` | integer | optional | `1` | Keep only every Nth point (e.g. 2 = every other beat). |

### `apply_on_beat`
Add on-beat motion to a **video** clip synced to an **audio** clip, placed as keyframes on each
beat/downbeat/onset. Great combined with `cut_to_beats`.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `video_clip_id` | string | required | — | The video clip to animate. |
| `audio_clip_id` | string | required | — | The clip providing the beat grid. |
| `effect` | string | required | — | `zoom` (scale punch-in) \| `flash` (brightness pop) \| `shake` (position jitter). |
| `mode` | string | optional | `downbeats` | `beats` \| `downbeats` \| `onsets`. |

### `align_clips_to_beats`
Snap the **start** of every clip on a track to the nearest beat of an **audio** clip — assemble a
montage locked to the music.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `track_id` | string | required | — | The track whose clips to snap. |
| `audio_clip_id` | string | required | — | The clip providing the beat grid. |
| `mode` | string | optional | `beats` | `beats` \| `downbeats` \| `onsets`. |

### `assemble_music_video`
Assemble the clips on a video track into a montage cut to the beat **on-device**: analyze the audio
clip's beat grid and trim each clip on the track to span one beat interval, butting them together on
the downbeats.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `track_id` | string | required | — | The video track whose clips to assemble (e.g. `V1`). |
| `audio_clip_id` | string | required | — | The music clip that provides the beat grid. |
| `mode` | string | optional | `downbeats` | `beats` \| `downbeats` \| `onsets`. |
| `beats_per_clip` | integer | optional | `1` | Beats each clip spans. |

---

## Generation (cloud, BYO key)

Generate new media from a text prompt using the user's configured provider. **Cloud, bring-your-own
key** — key-gated at call time; if no provider is configured the tool returns an error telling the
user to add a key in Settings (relay it, don't retry). Only the prompt text is sent to the provider —
your timeline media is not. See [PROVIDERS.md](PROVIDERS.md) and [MODELS.md](MODELS.md).

### `generate_image`
Generate a new image from a text prompt and add it to the timeline as an image clip.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `prompt` | string | required | — | What to generate. |
| `provider` | string | optional | — | Optional provider id (e.g. `OPENAI_IMAGE`, `BFL_FLUX`, `FAL`). |
| `model` | string | optional | — | Optional model id. |

### `generate_video`
Generate a new video clip from a text prompt and add it to the timeline. Async — may take a while.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `prompt` | string | required | — | What to generate. |
| `provider` | string | optional | — | Optional provider id. |
| `model` | string | optional | — | Optional model id. |
| `duration_sec` | integer | optional | `8` | Requested length in seconds. |

### `generate_music`
Generate new music (or a sound effect) from a text prompt and add it to the timeline as an audio clip.
Describe mood/genre/length in the prompt.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `prompt` | string | required | — | What to generate. |
| `provider` | string | optional | — | Optional provider id. |
| `model` | string | optional | — | Optional model id. |
| `duration_sec` | integer | optional | `8` | Requested length in seconds. |

---

## User-defined tools & action recording

Mint named macros over the other tools and persist them on-device (they travel in the settings backup
bundle — see [PLUGINS.md](PLUGINS.md)). Recording captures a user's real edits and saves them as a new
tool.

### `create_user_tool`
Create a named editing method the user can invoke later by name. The description should be step-by-step
instructions for what to do to a clip (using the other tools). Overwrites a tool with the same name.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `name` | string | required | — | Short name for the method (e.g. "comedy zoom"). |
| `description` | string | required | — | Step-by-step instructions the agent should follow when this tool is invoked. |

### `list_user_tools`
List all user-defined editing methods (tools). Returns name + description for each.

_No arguments._

### `delete_user_tool`
Delete a user-defined editing method by name.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `name` | string | required | — | Name of the tool to delete. |

### `run_user_tool`
Run a user-defined editing method on a clip. Returns the method's step-by-step instructions — execute
them using the other tools on the given clip.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `name` | string | required | — | Name of the user tool to run. |
| `clip_id` | string | required | — | The clip to apply the method to. |

### `start_recording`
Start recording the user's editing actions on a clip. While recording, every edit operation (split,
trim, delete, keyframe, filter change, etc.) is captured. Stop with `stop_recording` to save them as a
user-defined tool.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `clip_id` | string | required | — | The clip to record actions on. |

### `stop_recording`
Stop recording and save the captured actions as a user-defined tool. Returns the recorded steps for
review.

| Argument | Type | Req. | Default | Meaning |
| --- | --- | --- | --- | --- |
| `name` | string | required | — | Name for the new tool (e.g. "dramatic zoom cut"). |
| `extra_instructions` | string | optional | `""` | Optional caveats or generalizations to append (e.g. "adapt timings to clip length"). |

### `discard_recording`
Discard the current recording without saving.

_No arguments._

---

_This reference is **manually maintained** against the tool definitions in `McpTools`, `TimelineTools`,
and `VideoFilterCatalog` (there is no codegen step, so it can drift — TODO: generate it from the
definitions instead). The live `tools/list` catalog is always authoritative — an MCP client can
introspect every tool, description, and schema at runtime._
