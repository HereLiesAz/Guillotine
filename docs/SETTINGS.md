# Guillotine Settings

Everything Guillotine can be configured with lives in one place: the **Settings** screen (the gear in
the app menu). This is the complete reference for every control it holds.

**Two invariants hold throughout Settings:**

- **Stored on-device.** Every setting is persisted locally. API keys are held in
  [`ApiKeyStore`](../app/src/main/java/com/hereliesaz/guillotine/ai/ApiKeyStore.kt) using Jetpack
  Security (`EncryptedSharedPreferences` + a Keystore-backed master key) — **encrypted at rest on the
  device**. Every key field states "Stored encrypted on this device" right below it.
- **Nothing is uploaded.** Your video and audio never leave the device. Analysis runs on-device; only
  *text* (your prompt, the tool descriptions a cloud AI must see, and the on-device vision's text
  results) is ever sent — your raw frames or audio never are. A key only travels in the direct
  provider request you initiate.

No setting is required to use the app: the default provider is **on-device vision**, which needs no key
and works offline.

The screen has a title bar (**Settings** + a **✕** close button), four tabs, and a single **Save**
button pinned at the bottom that commits the whole screen at once. The tabs, in UI order, are:

1. [AI Analyzer](#1-ai-analyzer)
2. [Generation](#2-generation)
3. [Transcription](#3-transcription)
4. [Advanced](#4-advanced)

A field-by-field map of the persisted [`AiSettings`](../shared/src/main/kotlin/com/hereliesaz/guillotine/ai/AiTypes.kt)
model is in [§5, Settings field reference](#5-aisettings-field-reference).

Related docs: [PROVIDERS.md](PROVIDERS.md) (cloud AI providers & keys) · [MODELS.md](MODELS.md)
(on-device model downloads & paths) · [TOOLS.md](TOOLS.md) (what the AI can do) ·
[MANUAL.md](MANUAL.md) (using the editor) · [PLUGINS.md](PLUGINS.md) (the MCP surface) ·
[ECOSYSTEM.md](ECOSYSTEM.md) (LUTs, shaders, filters).

---

## Reusable controls

Two control patterns repeat across the screen — described once here, referenced below.

### Model picker

Most on-device model fields are paired with a **model picker**: a curated list of recommended models,
each showing its label, download size, and license. Per row the action is one of:

- **Download** / **Resume** — fetch the model straight to the device (Wi-Fi recommended). Shows a
  progress bar with percent; **Cancel** stops it, leaving a resumable partial.
- **✓ Use** — adopt an already-installed model (applies on **Save**).
- **In use** — the currently selected model for that field.
- **Get ↗** — for **gated** models, opens the source for a free Hugging Face sign-in; you then paste
  the downloaded path into the field above.
- **Remove** — delete a downloaded model from disk (bundled models can't be removed).

A freshly finished download is auto-selected for that field. Groups that offer a Gemma model show a
"Built with Gemma — Gemma Terms of Use ↗" notice. See [MODELS.md](MODELS.md) for the full catalog.

### Model-path field

Every model picker sits under a plain text field holding the **absolute path** to the chosen model
(a `.tflite`/`.task`/`.onnx` file, or an extracted model *directory*, as noted per field). Typing a
path directly, or using a picker's **✓ Use**, both write this field. **Blank** means the feature is
off or falls back to a bundled default, as documented per control.

---

## 1. AI Analyzer

> "Analysis always runs on-device — your video never leaves the device. Pick the AI that drives the
> editor below." — the tab's own intro

This tab picks the "brain" that drives the editor and configures the on-device models it can call.

### Analyzer provider

A radio list — pick exactly one. This sets [`AiSettings.provider`](#5-aisettings-field-reference).

| Option (label) | Needs key? | Notes |
| --- | --- | --- |
| **Local (free, on-device)** | No | On-device silence cut. Works offline. |
| **On-device vision (free)** | No | On-device faces & objects — keep/remove by what's on screen. **Default.** |
| **Gemini** | Yes | Google. Drives the editor via tools (no video sent). |
| **OpenAI** | Yes | Drives the editor via tools. |
| **Anthropic** | Yes | Drives the editor via tools. |
| **OpenRouter** | Yes | One key, many models. |
| **Groq** | Yes | Fast. Drives the editor via tools. |
| **xAI (Grok)** | Yes | Drives the editor via tools. |
| **Mistral** | Yes | Drives the editor via tools. |

- **Type:** single-select radio list · **Default:** *On-device vision*.
- The two on-device options need no further config. Selecting any key-based provider reveals the two
  controls below.

### Provider API key *(shown only for a key-based provider)*

- **Type:** text field · **Default:** empty · **Stored:** encrypted on-device.
- Writes [`AiSettings.keys`](#5-aisettings-field-reference) for the selected provider. A "Get a …
  API key ↗" link opens that provider's key page. See [PROVIDERS.md](PROVIDERS.md).

### Model *(shown only for a key-based provider)*

- **Type:** dropdown (model picker), populated live from the provider using your key.
- **Allowed values:** **Default** (clears the override) or any model id from the provider's live list.
- **Default:** *Default* — i.e. the provider's built-in recommended model. Writes
  [`AiSettings.models`](#5-aisettings-field-reference) for the selected provider; blank = provider
  default. Per-provider default model ids are listed in [PROVIDERS.md](PROVIDERS.md).

### Frame-analysis cache

- **Type:** slider · **Range:** `0`–`32768` frames in ~`1024`-frame steps (33 stops) ·
  **Default:** `4096` · reads **Off** at `0`.
- How many per-frame on-device vision results to keep in memory so rescanning the same clip with a
  different prompt is near-instant. Higher = more scans stay fast but a bit more memory; `0` disables
  the cache. Writes [`AiSettings.frameAnalysisCacheSize`](#5-aisettings-field-reference).

### AI assistant — on-device model (optional)

- **Type:** model-path field (`.task`) + [model picker](#model-picker) ("Assistant models").
- Run the assistant fully offline with **no key** from a downloaded `.task` LLM. Blank = the assistant
  uses the selected provider's key above instead. Writes
  [`AiSettings.agentModelPath`](#5-aisettings-field-reference).

### Recognition model — for "teach a specific thing" (optional)

- **Type:** model-path field (`.tflite`) + [model picker](#model-picker) ("Recognition models").
- A stronger MediaPipe-compatible image embedder sharpens instance matching ("is this the same
  thing?"). **Blank = the bundled MobileNet-V3-small.** Writes
  [`AiSettings.idEmbedModelPath`](#5-aisettings-field-reference).

### Face model — for identifying a specific person (optional)

- **Type:** model-path field (`.tflite`) + [model picker](#model-picker) ("Face models").
- When set, teaching a person uses face recognition (ML Kit face detect + this model). **Blank = fall
  back to the general recognition model.** Writes
  [`AiSettings.faceEmbedModelPath`](#5-aisettings-field-reference).

### Image effects — on-device TFLite models (optional)

Four independent model-path fields (`.tflite`), each with its own [model picker](#model-picker). They
populate the [`AiSettings.effectModelPaths`](#5-aisettings-field-reference) map under fixed keys:

| Field | Map key | Purpose |
| --- | --- | --- |
| Super-resolution model path | `superres` | Upscale a frame (e.g. ESRGAN). |
| Style-transfer model path | `style` | Stylize a frame (e.g. Magenta). |
| Depth model path | `depth` | Depth map (e.g. MiDaS). |
| Low-light enhance model path | `lowlight` | Brighten a dark frame. |

Each is **optional**, **default empty**. The assistant's `apply_image_effect` tool runs the matching
model on the current frame ("upscale / stylize / depth this frame").

### Audio highlights — on-device YAMNet (optional)

- **Type:** model-path field (`.tflite`) + [model picker](#model-picker) ("Audio-event models").
- Enables "find the highlights / best moments" by detecting applause, cheering, laughter, music and
  crowd noise. **Blank = feature off.** Writes
  [`AiSettings.audioEventModelPath`](#5-aisettings-field-reference).

### Speech (ASR) — offline transcription (optional)

- **Type:** model-path field (sherpa-onnx ASR model **directory**) + [model picker](#model-picker)
  ("ASR models").
- Enables "transcribe this accurately" via offline Whisper (sherpa-onnx). **Blank = feature off.**
  Writes [`AiSettings.asrModelPath`](#5-aisettings-field-reference).
  *(Distinct from the [Transcription tab](#3-transcription)'s Vosk field.)*

### Speech (TTS) — offline voiceover (optional)

- **Type:** model-path field (sherpa-onnx TTS voice **directory**) + [model picker](#model-picker)
  ("TTS voices").
- Enables "add a voiceover saying …" via offline neural TTS (sherpa-onnx). **Blank = feature off.**
  Writes [`AiSettings.ttsModelPath`](#5-aisettings-field-reference).

### Frame captioning (VLM) — multimodal model (optional)

- **Type:** model-path field (`.task`) + [model picker](#model-picker) ("VLM models").
- Enables "describe / understand this frame" in rich language (Gemma-3n vision). Gated — free Hugging
  Face sign-in, then paste the path. **Blank = feature off.** Writes
  [`AiSettings.vlmModelPath`](#5-aisettings-field-reference).

### Speaker diarization — who spoke when (optional, needs both models)

Two model-path fields, each with a [model picker](#model-picker). Diarization only works when **both**
are set:

- **Segmentation model directory (pyannote)** — writes
  [`AiSettings.diarizeSegModelPath`](#5-aisettings-field-reference).
- **Speaker-embedding model (`.onnx`)** — writes
  [`AiSettings.diarizeEmbedModelPath`](#5-aisettings-field-reference).

Enables "who speaks when?". Both **default empty**.

### Stem separation — vocals / instrumental (optional)

- **Type:** model-path field (Spleeter model **directory**, ONNX) + [model picker](#model-picker)
  ("Stem models").
- Enables "separate the stems / isolate the vocals" (Spleeter). Heavy — best on a capable device and
  moderate clip lengths. **Blank = feature off.** Writes
  [`AiSettings.stemModelPath`](#5-aisettings-field-reference).

### Noise reduction — clean up voice audio (optional)

- **Type:** model-path field (`.onnx`) + [model picker](#model-picker) ("Denoiser models").
- Enables "remove background noise / clean up the audio" — strips hiss, hum and background noise from
  voice (GTCRN). Fast, on-device. **Blank = feature off.** Writes
  [`AiSettings.denoiseModelPath`](#5-aisettings-field-reference).

### FFmpeg / Frei0r filters — bake a `-vf` graph (advanced)

- **Type:** text field (path to an `ffmpeg` executable) · **Default:** empty.
- Enables "apply the ffmpeg filter …" — bakes a standard FFmpeg `-vf` filtergraph (and Frei0r plugins
  via `frei0r=name:params`) onto a clip. Needs an `ffmpeg` binary; heavy, desktop-first. **Blank =
  feature off.** Writes [`AiSettings.ffmpegPath`](#5-aisettings-field-reference). See
  [ECOSYSTEM.md §4](ECOSYSTEM.md).

---

## 2. Generation

Bring your own AI keys to generate **images, video, and music**. Configure as many providers as you
like — only the categories and providers you set up are offered when generating. This tab is three
sections (**Image**, **Video**, **Music**), each a list of collapsible provider cards.

**Every provider card has the same controls:**

- **Expand / collapse** (`+` / `–`) and a small **dot** that turns red once the provider is
  *configured* (keyless, or a key entered).
- **API key** field — text, encrypted, empty by default. Keyless providers instead read "Free — no key
  needed." Writes [`AiSettings.genKeys`](#5-aisettings-field-reference) (except Leonardo — see below).
- **Model** field — free-text model id, with an inline "Options:" hint listing suggested model names.
  **Default:** the provider's first/default model (blank uses it). Writes
  [`AiSettings.genModels`](#5-aisettings-field-reference).
- **Wrapper base URL** field — **only** on Suno and Udio (their music wrappers). Writes
  [`AiSettings.genExtras`](#5-aisettings-field-reference).
- A **disclaimer** line where relevant, and a **"Get a … key ↗"** link.

Providers offered per category (enum order):

**Image**

| Provider | Needs key? | Notes |
| --- | --- | --- |
| Pollinations (free) | No | Keyless, instant. |
| Leonardo.ai | Yes | Key reuses the legacy `leonardoKey` field (see note). |
| OpenAI Images | Yes | |
| Stability AI (image) | Yes | |
| Black Forest Labs (FLUX) | Yes | |
| Google Imagen | Yes | Same key can drive the editor as Gemini. |
| Ideogram | Yes | |
| Recraft | Yes | |
| fal.ai (aggregator) | Yes | Enter the fal model id as the model. |
| Replicate (aggregator) | Yes | Enter the `owner/name` model as the model. |

**Video**

| Provider | Needs key? | Notes |
| --- | --- | --- |
| Guillotine (free) | No | Keyless; short, low-res clips on a shared free GPU. |
| Runway | Yes | |
| Luma Dream Machine | Yes | |
| Google Veo | Yes | Via a Gemini key. |
| MiniMax / Hailuo | Yes | |
| OpenAI Sora | Yes | |
| Kling | Yes | |
| Pika | Yes | |
| Stability AI (video) | Yes | |
| fal.ai (aggregator) | Yes | |
| Replicate (aggregator) | Yes | |

**Music**

| Provider | Needs key? | Notes |
| --- | --- | --- |
| ElevenLabs | Yes | |
| Stability Audio | Yes | |
| Google Lyria | Yes | Via a Gemini key. |
| MusicGen (Replicate) | Yes | |
| Mubert | Yes | |
| Beatoven.ai | Yes | |
| Loudly | Yes | |
| Cassette | Yes | |
| Suno (via wrapper) | Yes | No official API — needs a wrapper key **and** base URL; disclaimed. |
| Udio (via wrapper) | Yes | No official API — needs a wrapper key **and** base URL; disclaimed. |
| fal.ai (aggregator) | Yes | |
| Replicate (aggregator) | Yes | |

> **Leonardo note.** Leonardo's key field is bound to the legacy top-level
> [`AiSettings.leonardoKey`](#5-aisettings-field-reference) so existing users don't re-enter it; its
> companion [`AiSettings.leonardoModel`](#5-aisettings-field-reference) is chosen from the
> **Generate image** dialog, not this screen.

Full per-provider key URLs and model catalogs are in [PROVIDERS.md](PROVIDERS.md).

---

## 3. Transcription

A single control for the built-in transcription path (separate from the AI Analyzer tab's optional
sherpa-onnx ASR).

### On-device speech model path (Vosk)

- **Type:** text field (path to a **Vosk** model folder) · **Default:** empty.
- Set a Vosk model folder for offline transcription; **blank uses OpenAI Whisper.** A "Download a Vosk
  model ↗" link opens the Vosk model list. Writes
  [`AiSettings.speechModelPath`](#5-aisettings-field-reference).

---

## 4. Advanced

Developer / power-user options. These persist to their own stores (noted per control), **not** to
`AiSettings` — except Backup & Restore, which reads and writes the whole `AiSettings` bundle.

### Crash reporting

- **Crash relay URL** — text field, empty by default. Point it at your deployed crash-relay endpoint
  (`tools/crash-relay`) to auto-file issues. Saved via `CrashConfig` on **Save**.

### MCP access token (external AI tools)

- **Token** — **read-only** text field showing the bearer token for the embedded MCP server.
- **Copy** — copies the token to the clipboard.
- **Regenerate** — mints a new token immediately (revoking any tool still using the old one).
- Send it as `Authorization: Bearer <token>` when POSTing to `/mcp` on port `6274`. Backed by
  `McpAuth`. See [PLUGINS.md](PLUGINS.md).

### Encrypted cloud relay (optional)

Reach the on-device editor remotely via a Cloudflare Worker without port-forwarding. Saved via
`McpRelayConfig` on **Save**.

- **Reach the editor via Cloudflare** — checkbox, off by default (`RelayConfig.enabled`).
- **Worker URL** — text field, e.g. `wss://…workers.dev/relay`.
- **Worker access key** — text field, optional.
- Traffic is end-to-end encrypted; Cloudflare only relays ciphertext. Deploy `tools/mcp-relay` and run
  the local proxy with the same MCP token.

### Backup & Restore

- **Export settings** — writes a JSON file (via the system file picker; default name
  `guillotine-settings.json`) containing **all AI settings and user-defined tools**.
- **Import settings** — reads such a file and **overwrites** current settings.

Backup/restore is handled by
[`SettingsBackup`](../app/src/main/java/com/hereliesaz/guillotine/data/SettingsBackup.kt) and runs the
moment you pick a file (independent of the **Save** button). The export is a **plain JSON** file — it
includes your keys in the clear, so store it somewhere safe. `genDefaults` and user tool packs travel
in the bundle; a bundle missing `frameAnalysisCacheSize` restores it to `200`.

---

## 5. `AiSettings` field reference

Every persisted field of
[`AiSettings`](../shared/src/main/kotlin/com/hereliesaz/guillotine/ai/AiTypes.kt), its type, default,
and the control that sets it.

| Field | Type | Default | Controls / meaning | Set by |
| --- | --- | --- | --- | --- |
| `provider` | `AiProviderType` | `MLKIT` (On-device vision) | Which AI drives the editor/analyzer | §1 Analyzer provider |
| `keys` | `Map<AiProviderType, String>` | `{}` | Per-provider API keys (encrypted) | §1 Provider API key |
| `models` | `Map<AiProviderType, String>` | `{}` | Per-provider analyzer model; blank = provider default | §1 Model |
| `leonardoKey` | `String` | `""` | Legacy Leonardo image key (reused by Generation → Image → Leonardo) | §2 Image → Leonardo key |
| `leonardoModel` | `String` | `LeonardoDefaultModel` (Leonardo Phoenix 1.0) | Legacy Leonardo model id | **Generate image** dialog (not this screen) |
| `speechModelPath` | `String` | `""` | Vosk model folder; blank = OpenAI Whisper | §3 Transcription |
| `agentModelPath` | `String` | `""` | On-device assistant LLM (`.task`); blank = use provider key | §1 AI assistant |
| `frameAnalysisCacheSize` | `Int` | `4096` | Per-frame vision cache size; `0` = off (range 0–32768) | §1 Frame-analysis cache slider |
| `idEmbedModelPath` | `String` | `""` | Recognition embedder (`.tflite`); blank = bundled MobileNet-V3-small | §1 Recognition model |
| `faceEmbedModelPath` | `String` | `""` | Face embedder (`.tflite`); blank = general recognition model | §1 Face model |
| `effectModelPaths` | `Map<String, String>` | `{}` | Image-effect models keyed `superres`/`style`/`depth`/`lowlight` | §1 Image effects (4 fields) |
| `audioEventModelPath` | `String` | `""` | YAMNet audio-event (`.tflite`) for highlights; blank = off | §1 Audio highlights |
| `asrModelPath` | `String` | `""` | sherpa-onnx ASR model **dir**; blank = off | §1 Speech (ASR) |
| `ttsModelPath` | `String` | `""` | sherpa-onnx TTS voice **dir**; blank = off | §1 Speech (TTS) |
| `vlmModelPath` | `String` | `""` | Multimodal VLM (`.task`); blank = off | §1 Frame captioning |
| `diarizeSegModelPath` | `String` | `""` | pyannote segmentation **dir**; blank = off | §1 Speaker diarization |
| `diarizeEmbedModelPath` | `String` | `""` | Speaker-embedding (`.onnx`); blank = off | §1 Speaker diarization |
| `stemModelPath` | `String` | `""` | Spleeter model **dir** (ONNX); blank = off | §1 Stem separation |
| `denoiseModelPath` | `String` | `""` | GTCRN denoiser (`.onnx`); blank = off | §1 Noise reduction |
| `ffmpegPath` | `String` | `""` | `ffmpeg` executable path; blank = off | §1 FFmpeg / Frei0r filters |
| `genKeys` | `Map<GenProviderType, String>` | `{}` | Per-generation-provider keys (encrypted) | §2 provider key fields |
| `genModels` | `Map<GenProviderType, String>` | `{}` | Per-generation-provider model; blank = provider default | §2 provider model fields |
| `genExtras` | `Map<GenProviderType, String>` | `{}` | Provider extra (Suno/Udio wrapper base URL) | §2 Music → Suno/Udio URL |
| `genDefaults` | `Map<GenKind, GenProviderType>` | `{}` | Remembered preferred provider per category | Not set here — remembered when generating |

Notes:

- **Map fields** persist only non-empty entries; on **Save**, values are trimmed and blanks dropped.
- `frameAnalysisCacheSize` is clamped to `0`–`32768` on load. The `4096` default is
  `FrameAnalysisCache.DEFAULT_MAX_ENTRIES`.
- Model-path fields marked **dir** expect an extracted model *directory*; the rest expect a single
  file of the noted extension.
