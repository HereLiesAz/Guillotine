# Guillotine — On-Device Model Catalog

Guillotine's AI runs **on the device first**. Every model in this catalog downloads to your phone
and executes locally — the assistant brain, the recognizers, speech, and the image/audio effect
models all run offline. The invariant holds here as everywhere in Guillotine: **your video and audio
never leave the device.** The only thing that ever goes to the network is the *download of the model
weights themselves* (from Hugging Face and a couple of public model hosts), and — for gated Google
Gemma weights — a re-hosted, sign-in-free copy in the project's own namespace (see
[The Gemma mirror](#the-gemma-mirror)).

Models are grouped by **runtime category**. Each category plugs into a specific on-device engine and
is wired to a specific capability of the AI editor. The engines in use today are **MediaPipe LLM
Inference / LiteRT-LM** (`.task` / `.litertlm`), **MediaPipe** vision tasks and raw **TFLite**
interpreters (`.tflite`), **sherpa-onnx** offline speech (`.tar.bz2` bundles and single `.onnx`
files), and **ONNX Runtime** (Spleeter stem separation).

You manage everything from **Settings → the Model Manager** (see [SETTINGS.md](SETTINGS.md)). Each
category has a text field for a model *path* plus a picker listing the recommended models, where each
row offers **Download**, **Resume**, **Cancel**, **✓ Use**, **In use**, or **Remove** (gated repos
show **Get ↗** instead — a link out). For how these models power specific assistant commands
(`caption_frame`, `transcribe_precise`, `separate_stems`, `find_highlights`, …), see
[TOOLS.md](TOOLS.md); for the day-to-day workflow, see [MANUAL.md](MANUAL.md); for the *cloud*
bring-your-own-key generation providers (a separate, opt-in system), see [PROVIDERS.md](PROVIDERS.md).

> Source of truth: `shared/src/main/kotlin/com/hereliesaz/guillotine/ai/agent/OnDeviceModels.kt`
> (the `ModelCategory` enum and the `RECOMMENDED_*_MODELS` catalogs). Sizes, filenames, and URLs
> below are taken verbatim from that file.

---

## How models are stored, downloaded, and installed

- **Where they live.** Downloads land in app-specific external storage
  (`getExternalFilesDir(...)`) — **no storage permission is needed**, and uninstalling the app
  removes them. Each category gets its own sub-directory: `llm-models`, `recognition-models`,
  `face-models`, and otherwise `<category>-models` (e.g. `depth-models`, `asr-models`, `vlm-models`,
  `stem-models`).
- **One-tap download.** For an ungated model the picker downloads straight to the device in the
  background (it keeps running if you close the Settings sheet). A **free-space check** runs first,
  and archives reserve extra headroom for extraction.
- **Resumable.** Interrupted downloads keep a `.part` file and resume via HTTP Range — the row shows
  **Resume** and how far along it is. **Remove** deletes both the finished file and any partial.
- **Single-file vs. archive.** Single-file models (`.task`, `.litertlm`, `.tflite`, `.onnx`) are
  verified by an exact byte-size match (or, when the exact size isn't known, by being non-empty) and
  then wired directly. **sherpa-onnx / Spleeter bundles are `.tar.bz2` archives** — they extract into
  a per-model directory (the top-level folder is stripped), and "Use" wires that *directory* path;
  install is confirmed by a marker file inside it.
- **The bundled starter.** One model — **SmolLM 135M Instruct (q8)** — ships *inside the APK* and is
  extracted to `llm-models` on first launch, so the assistant works offline out of the box with no
  download. It cannot be removed.
- **Picking one.** Tapping **✓ Use** sets that category's `…ModelPath` setting to the installed path;
  a freshly finished download is auto-adopted for its own category. These paths are persisted and
  travel in the Settings backup bundle.

### The Gemma mirror

Google's Gemma LiteRT models (`.task`) are normally **gated** — they require accepting the Gemma
license and signing in to Hugging Face before download. To keep the one-tap flow working, the
`.github/workflows/mirror-models.yml` GitHub Action **re-hosts the Gemma weights into public,
un-gated repositories in the project's own `HereLiesAz` Hugging Face namespace**. It runs entirely on
a GitHub runner (using a maintainer's Gemma-accepted `HF_TOKEN`); nothing passes through a user's
machine. The mirror currently covers:

| Upstream (gated) | Mirrored to (public) | Used by |
|---|---|---|
| `google/gemma-3n-E2B-it-litert-preview` | `HereLiesAz/gemma-3n-e2b-it-litertlm` | VLM — Gemma 3n E2B |
| `google/gemma-3n-E4B-it-litert-preview` | `HereLiesAz/gemma-3n-e4b-it-litertlm` | VLM — Gemma 3n E4B |
| `litert-community/Gemma3-1B-IT` | `HereLiesAz/gemma3-1b-it` | Assistant — Gemma 3 1B |

Because of the mirror, **every model in the current catalog is un-gated (`gated = false`) and
downloads with no sign-in.** The app still carries a *gated link-out* mechanism (a **Get ↗** button
to the Hugging Face repo, after which you paste the path) for any future gated entry, but no shipped
recommended model needs it today. All Gemma-derived models remain **subject to the
[Gemma Terms of Use](https://ai.google.dev/gemma/terms)**, and the Model Manager shows a "Built with
Gemma" notice next to them.

### Download-source shorthand used in the tables

- **Hugging Face** rows download via `https://huggingface.co/<repo>/resolve/main/<file>?download=true`
  — the tables list the `<repo>`.
- **sherpa-onnx** rows (ASR, TTS, diarization, stems, denoise) download from the
  `k2-fsa/sherpa-onnx` GitHub **Releases**.
- A few rows come straight from a vendor host (Google MediaPipe storage, TF-Hub, a GitHub raw file);
  those name the host inline.

---

## Category overview

Fifteen `ModelCategory` values exist. Thirteen have a working runtime **and** a recommended-model
catalog today; **STYLE** is exposed as a custom-path slot only (no recommended download); the enum
still tags **STYLE** and **STEM** as "reserved," but **STEM is in fact fully wired** with a
downloadable model — see [the note below](#a-note-on-reserved-categories).

| Category | Powers | Runtime · format | Settings path | Recommended models |
|---|---|---|---|---|
| `ASSISTANT_LLM` | The assistant "brain" that drives the editor offline | MediaPipe LlmInference · `.task` / `.litertlm` | `agentModelPath` | 7 |
| `RECOGNITION` | "Teach a specific thing" — is this the same object? | MediaPipe ImageEmbedder · `.tflite` | `idEmbedModelPath` | 2 |
| `FACE` | Identify a specific **person** | raw-TFLite FaceRecognizer · `.tflite` | `faceEmbedModelPath` | 1 |
| `DEPTH` | "Depth this frame" (bokeh, parallax) | TfliteImageModel · `.tflite` | `effectModelPaths["depth"]` | 1 |
| `SUPERRES` | "Upscale / enhance this frame" | TfliteImageModel · `.tflite` | `effectModelPaths["superres"]` | 1 |
| `LOWLIGHT` | "Brighten this dark frame" | TfliteImageModel · `.tflite` | `effectModelPaths["lowlight"]` | 1 |
| `STYLE` | Style transfer (custom path only) | TfliteImageModel · `.tflite` | `effectModelPaths["style"]` | 0 |
| `AUDIO_EVENT` | "Find the highlights / best moments" | raw-TFLite YAMNet · `.tflite` | `audioEventModelPath` | 1 |
| `ASR` | "Transcribe this accurately" (speech→text) | sherpa-onnx · `.tar.bz2` dir | `asrModelPath` | 1 |
| `TTS` | "Add a voiceover saying…" (text→speech) | sherpa-onnx · `.tar.bz2` dir | `ttsModelPath` | 1 |
| `VLM` | "Describe / understand this frame" | MediaPipe LlmInference + vision · `.task` | `vlmModelPath` | 2 |
| `DIARIZE_SEG` | "Who spoke when?" — segmentation half | sherpa-onnx pyannote · `.tar.bz2` dir | `diarizeSegModelPath` | 1 |
| `DIARIZE_EMBED` | "Who spoke when?" — embedding half | sherpa-onnx · `.onnx` | `diarizeEmbedModelPath` | 1 |
| `STEM` | "Separate the stems / isolate vocals" | ONNX Runtime (Spleeter) · `.tar.bz2` dir | `stemModelPath` | 1 |
| `DENOISE` | "Clean up the audio" (speech denoise) | sherpa-onnx GTCRN · `.onnx` | `denoiseModelPath` | 1 |

Total: **22 recommended models** across 14 catalogs.

---

## `ASSISTANT_LLM` — the assistant brain

MediaPipe `LlmInference` models (`.task`, or the newer `.litertlm`) that let the AI editor plan and
run edits **fully offline, with no API key**. If `agentModelPath` is blank the command bar falls back
to whichever cloud provider key you've configured (see [PROVIDERS.md](PROVIDERS.md)); set a local
model here to stay offline. The bundled SmolLM starter is always present.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| SmolLM 135M Instruct q8 (`smollm-135m-q8`) | Bundled starter. Instant startup, basic completion & simple tool calls; very limited reasoning. | 166 MB | `.task` | Apache-2.0 | **Bundled in APK** (no download) |
| Qwen2.5 0.5B Instruct q8 (`qwen2.5-0.5b-q8`) | Good reasoning for its size; handles tool calls and editing context. | 546 MB | `.task` | Apache-2.0 | HF `litert-community/Qwen2.5-0.5B-Instruct` |
| Qwen2.5 1.5B Instruct q8 (`qwen2.5-1.5b-q8`) | Strong reasoning & tool use; best quality/size balance. | 1.57 GB | `.task` | Apache-2.0 | HF `litert-community/Qwen2.5-1.5B-Instruct` |
| Phi-4 mini Instruct q8 (`phi4-mini-q8`) | Most capable on-device model; excellent reasoning. Needs a high-end device. | 3.94 GB | `.task` | MIT | HF `litert-community/Phi-4-mini-instruct` |
| Gemma 3 1B Instruct int4 (`gemma3-1b-int4`) | Compact & fast with good reasoning; smallest of the full-capability models. | 554 MB | `.task` | Gemma | HF `HereLiesAz/gemma3-1b-it` (mirrored) |
| DeepSeek-R1 Distill Qwen 1.5B q8 (`deepseek-r1-qwen-1.5b-q8`) | Strong step-by-step reasoning (distilled R1); good for multi-step edits. Verbose traces. | 1.86 GB | `.task` | MIT | HF `litert-community/DeepSeek-R1-Distill-Qwen-1.5B` |
| Qwen3 0.6B int4 (`qwen3-0.6b-int4`) | Small, fast, up-to-date lightweight default; ships as LiteRT-LM. | 497 MB | `.litertlm` | Apache-2.0 | HF `litert-community/Qwen3-0.6B` |

Notes: the `.litertlm` Qwen3 model loads directly on the current MediaPipe runtime (0.10.35). The
Gemma 3 1B entry is served from the [Gemma mirror](#the-gemma-mirror) (Gemma Terms of Use apply).

---

## `RECOGNITION` — "teach a specific thing"

MediaPipe **ImageEmbedder** `.tflite` models. The "point at a thing to teach it" feature embeds a
crop and matches by cosine similarity. Leaving `idEmbedModelPath` blank uses the **bundled
MobileNet-V3-small** default; the catalog lets you drop an explicit copy or a stronger embedder on
disk.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| MobileNet-V3 Large (`mobilenet-v3-large-embed`) | Stronger, still-fast general embedder — better "same thing?" matching than the default. | 10 MB | `.tflite` | Apache-2.0 | Google MediaPipe storage (`storage.googleapis.com/mediapipe-models/image_embedder/…`) |
| MobileNet-V3 Small (`mobilenet-v3-small-embed`) | The lightweight reference embedder (same family as the bundled default); an explicit on-disk copy. | 4 MB | `.tflite` | Apache-2.0 | Google MediaPipe storage (`…/image_embedder/…`) |

Compatibility: MediaPipe's ImageEmbedder loads any **CNN** `.tflite` with a **single image input and
`NormalizationOptions` metadata**. ViT-based embedders (DINOv2/CLIP) don't convert cleanly to TFLite
and are **not** compatible with this slot.

---

## `FACE` — identify a specific person

A face-embedding `.tflite` run through Guillotine's **raw-TFLite `FaceRecognizer`** (not MediaPipe):
detect a face → square-resize → `(x − 127.5) / 128` → embed → L2-normalize → cosine. When
`faceEmbedModelPath` is set, teaching a *person* concept routes here instead of the generic image
embedder ("keep only shots with X"); blank falls back to the recognition model.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| MobileFaceNet (`mobilefacenet-192`) | ArcFace-trained face embedder (112×112 → 192-d). Recognizes a specific person far better than a generic embedder. | 5 MB | `.tflite` | BSD-3-Clause | GitHub raw — `MCarlomagno/FaceRecognitionAuth` |

Compatibility: because it runs through the raw-TFLite path, a **plain face `.tflite` *without*
MediaPipe metadata** is exactly what's wanted. Best with aligned faces (a plain detected-face crop
works at somewhat lower accuracy). Weights: `sirius-ai/MobileFaceNet_TF` (Apache-2.0).

---

## `DEPTH` — "depth this frame"

Monocular depth-estimation `.tflite` (single image in → single depth map out), run through the
generic `TfliteImageModel`; the map is normalized to a visible greyscale image. Wired via
`effectModelPaths["depth"]`. Powers depth-of-field / bokeh and parallax looks.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| MiDaS-small (`midas-small-256-fp16`) | Per-pixel depth from a single 256×256 frame. Relative (not metric) depth; output upscaled to the frame. | 33 MB | `.tflite` | MIT / Apache-2.0 | HF `litert-community/MiDaS-small` |

---

## `SUPERRES` — "upscale / enhance this frame"

Super-resolution `.tflite` (single image in → larger image out) via `TfliteImageModel`. Wired via
`effectModelPaths["superres"]`.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| Real-ESRGAN ×4 general v3 (`real-esrgan-x4v3`) | 4× super-resolution on a 128×128 tile (→512×512). Great for sharpening a still or cropped frame; heavy for full video. | 3 MB | `.tflite` | BSD-3-Clause | HF `litert-community/real-esrgan-x4v3-litert` |

---

## `LOWLIGHT` — "brighten this dark frame"

Low-light enhancement `.tflite` (image→image) via `TfliteImageModel`, applied through
`apply_image_effect(effect="lowlight")`. Wired via `effectModelPaths["lowlight"]`.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| MIRNet (`mirnet-lowlight-dr`) | Brightens and denoises dark / low-light footage. Fixed 400×400 (frame resized in and back out); CPU-heavy per frame. | 37 MB | `.tflite` | Apache-2.0 | TF-Hub — `sayakpaul/lite-model/mirnet-fixed/dr/1` |

---

## `STYLE` — style transfer (custom path only)

Wired via `effectModelPaths["style"]`, but **has no recommended download.** The common style-transfer
`.tflite` files are the two-input Magenta arbitrary-stylization pair (predict + transform), which do
**not** fit the single-image-in / single-image-out `TfliteImageModel` runtime. You can still point the
style path at a compatible **single-input** model of your own.

---

## `AUDIO_EVENT` — "find the highlights / best moments"

The standard TF-Hub **YAMNet** classification export: a fixed 15600-sample (0.975 s @ 16 kHz) waveform
in → `[1, 521]` AudioSet class scores out, run frame-by-frame to locate exciting moments (applause,
cheering, laughter, music, screaming, crowd). Wired via `audioEventModelPath`.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| YAMNet (`yamnet-classification`) | Detects 521 audio events on-device to power highlight / best-moment detection. Tags sound, not beats; ~1 s time resolution. | 4 MB | `.tflite` | Apache-2.0 | HF `thelou1s/yamnet` |

---

## `ASR` — offline transcription (speech → text)

sherpa-onnx offline ASR bundles, downloaded as a `.tar.bz2` and extracted into a per-model directory;
`asrModelPath` points at that directory. Enables "transcribe this accurately."

| Model (`id`) | Purpose | Size (approx) | Format | License | Source |
|---|---|---|---|---|---|
| Whisper tiny.en (`sherpa-whisper-tiny-en`) | Word-level **English** transcription (OpenAI Whisper tiny) — sharper captions than the lightweight default recognizer. | ~113 MB | `.tar.bz2` bundle | MIT | sherpa-onnx Releases (`asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2`) |

Install marker: `tiny.en-encoder.int8.onnx`.

---

## `TTS` — offline voiceover (text → speech)

sherpa-onnx offline TTS voices, `.tar.bz2` bundles extracted to a directory; `ttsModelPath` points at
it. Enables "add a voiceover saying…".

| Model (`id`) | Purpose | Size (approx) | Format | License | Source |
|---|---|---|---|---|---|
| Piper — Amy, US English (`sherpa-piper-en-us-amy-low`) | Offline neural TTS (Piper VITS) for voiceover / narration. One English voice. | ~30 MB | `.tar.bz2` bundle | MIT (verify the voice's dataset license before commercial use) | sherpa-onnx Releases (`tts-models/vits-piper-en_US-amy-low.tar.bz2`) |

Install marker: `en_US-amy-low.onnx`.

---

## `VLM` — "describe / understand this frame"

Multimodal Gemma-3n `.task` models (MediaPipe `LlmInference` + vision modality) for rich frame
captioning. Wired via `vlmModelPath`. Both are served un-gated from the
[Gemma mirror](#the-gemma-mirror) (Gemma Terms of Use apply; a high-end device is recommended).

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| Gemma 3n E2B vision (`gemma-3n-e2b-it`) | Natively multimodal — looks at a frame and describes it in rich language. | 3.14 GB | `.task` | Gemma | HF `HereLiesAz/gemma-3n-e2b-it-litertlm` (mirrored) |
| Gemma 3n E4B vision (`gemma-3n-e4b-it`) | The larger, more capable Gemma-3n — sharper, more detailed descriptions. | 4.41 GB | `.task` | Gemma | HF `HereLiesAz/gemma-3n-e4b-it-litertlm` (mirrored) |

> The Model Manager's VLM helper text still mentions a "gated — sign in free" step; in practice these
> mirrored entries download in one tap like the rest.

---

## `DIARIZE_SEG` — "who spoke when?" (segmentation half)

sherpa-onnx pyannote **segmentation** model (`.tar.bz2` → directory), which detects speaker *turns*.
Wired via `diarizeSegModelPath`. **Diarization needs both halves** — pair it with a
`DIARIZE_EMBED` model.

| Model (`id`) | Purpose | Size (approx) | Format | License | Source |
|---|---|---|---|---|---|
| Pyannote segmentation 3.0 (`sherpa-pyannote-seg-3-0`) | Detects who is speaking when (speaker turns). Half of diarization. | ~6 MB | `.tar.bz2` bundle | MIT (converted pyannote weights) | sherpa-onnx Releases (`speaker-segmentation-models/…`) |

Install marker: `model.onnx`.

---

## `DIARIZE_EMBED` — "who spoke when?" (embedding half)

sherpa-onnx **speaker-embedding** `.onnx` (single file). Turns each speaker turn into a voiceprint so
turns can be grouped by speaker. Wired via `diarizeEmbedModelPath`. Pair with a `DIARIZE_SEG` model.

| Model (`id`) | Purpose | Size (approx) | Format | License | Source |
|---|---|---|---|---|---|
| 3D-Speaker ERes2Net (`3dspeaker-eres2net-base-16k`) | Speaker embeddings (voiceprints) — the other half of diarization. | ~26 MB | `.onnx` | Apache-2.0 | sherpa-onnx Releases (`speaker-recongition-models/…`) |

Notes: the exact byte size isn't published, so install is verified by "downloaded and non-empty"
rather than an exact-size match. The upstream release tag is spelled **`recongition`** (a typo kept
verbatim so the URL resolves).

---

## `STEM` — "separate the stems / isolate vocals"

Source-separation model run via **ONNX Runtime** — Deezer **Spleeter** 2-stem (`.tar.bz2` →
directory). Wired via `stemModelPath`. Splits a song into a vocals track and an instrumental
(accompaniment) track for remixes, karaoke, or isolating either part.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| Spleeter 2-stem (`spleeter-2stems`) | Real ML stem splitting into vocals + accompaniment. Heavy (hundreds of MB of RAM) — best on a capable device and moderate clip lengths. | 71 MB | `.tar.bz2` bundle | MIT (Deezer Spleeter) | sherpa-onnx Releases (`source-separation-models/…`) |

Install marker: `vocals.onnx`.

---

## `DENOISE` — "clean up the audio"

sherpa-onnx **GTCRN** offline speech-denoiser (`.onnx`, single file). Strips hiss, hum, and background
noise from voice; wired via `denoiseModelPath`.

| Model (`id`) | Purpose | Size | Format | License | Source |
|---|---|---|---|---|---|
| GTCRN (`gtcrn-denoise`) | On-device speech noise reduction. Outputs 16 kHz; tuned for speech, not music. | ~0.5 MB | `.onnx` | MIT | sherpa-onnx Releases (`speech-enhancement-models/gtcrn_simple.onnx`) |

> This model is ~535 KB; the in-app size badge rounds sub-megabyte models down to "0 MB."

---

## A note on reserved categories

The `ModelCategory` enum tags `STYLE` and `STEM` as *"reserved for upcoming runtimes (not yet shown
in the picker)."* That comment is accurate for **STYLE** (no recommended download; custom single-input
path only) but **stale for STEM** — STEM has a live ONNX Runtime runtime, a recommended Spleeter
download, and a full Model Manager section today. Documented above as active.

---

## Custom models & compatibility, at a glance

Every category accepts a **user-supplied model path** through its setting (see
[SETTINGS.md](SETTINGS.md)). Point the field at a compatible local file or extracted directory. The
constraints below come from the runtime each category uses:

| Category | Setting | Accepts | Compatibility constraint |
|---|---|---|---|
| `ASSISTANT_LLM` | `agentModelPath` | `.task` / `.litertlm` file | MediaPipe `LlmInference` (LiteRT-LM). |
| `RECOGNITION` | `idEmbedModelPath` | `.tflite` file | MediaPipe ImageEmbedder: **CNN, single image input, `NormalizationOptions` metadata**. ViTs (DINOv2/CLIP) not supported. |
| `FACE` | `faceEmbedModelPath` | `.tflite` file | Raw-TFLite FaceRecognizer: a **plain face embedder without MediaPipe metadata**; 112×112 input, ArcFace-style normalization. |
| `DEPTH` | `effectModelPaths["depth"]` | `.tflite` file | Single-image-in / single-image-out (`TfliteImageModel`). |
| `SUPERRES` | `effectModelPaths["superres"]` | `.tflite` file | Single-image-in / single-image-out. |
| `LOWLIGHT` | `effectModelPaths["lowlight"]` | `.tflite` file | Single-image-in / single-image-out. |
| `STYLE` | `effectModelPaths["style"]` | `.tflite` file | Single-image-in / single-image-out — **single-input only** (two-input Magenta pairs are rejected). No recommended download. |
| `AUDIO_EVENT` | `audioEventModelPath` | `.tflite` file | YAMNet-shaped: 15600-sample waveform in → `[1, 521]` out. |
| `ASR` | `asrModelPath` | extracted **directory** | sherpa-onnx offline ASR bundle layout. |
| `TTS` | `ttsModelPath` | extracted **directory** | sherpa-onnx offline TTS voice bundle layout. |
| `VLM` | `vlmModelPath` | `.task` file | MediaPipe `LlmInference` **with vision modality**. |
| `DIARIZE_SEG` | `diarizeSegModelPath` | extracted **directory** | sherpa-onnx pyannote segmentation bundle. Needs a `DIARIZE_EMBED` model too. |
| `DIARIZE_EMBED` | `diarizeEmbedModelPath` | `.onnx` file | sherpa-onnx speaker-embedding model. Needs a `DIARIZE_SEG` model too. |
| `STEM` | `stemModelPath` | extracted **directory** | Spleeter ONNX model, run via ONNX Runtime. |
| `DENOISE` | `denoiseModelPath` | `.onnx` file | sherpa-onnx `OfflineSpeechDenoiser` (GTCRN). |

Leaving a path blank uses the bundled default (recognition) or turns the corresponding feature off;
`agentModelPath` blank falls back to a configured cloud provider key.

---

## See also

- **[SETTINGS.md](SETTINGS.md)** — the Model Manager UI and every `…ModelPath` setting.
- **[TOOLS.md](TOOLS.md)** — the assistant commands each model category powers
  (`caption_frame`, `transcribe_precise`, `add_voiceover`, `diarize_clip`, `separate_stems`,
  `find_highlights`, `apply_image_effect`, …).
- **[PROVIDERS.md](PROVIDERS.md)** — the separate cloud **generation** providers (bring-your-own-key).
- **[MANUAL.md](MANUAL.md)** — everyday editing workflow.
- **[AI_ROADMAP.md](AI_ROADMAP.md)** — on-device model candidates and where the AI features are headed.
- **[ECOSYSTEM.md](ECOSYSTEM.md)** — LUTs, shaders, MCP plugins, and other bring-your-own formats.
