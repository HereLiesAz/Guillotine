# Guillotine — AI Model & Capability Roadmap

This document surveys **on-device model candidates** worth adopting, the **cloud
generation providers** Guillotine now supports (bring-your-own key), and a **long
list of capability ideas** for where the app can go next. It is a planning
reference, not shipped behavior — feasibility notes assume a modern mid/high-end
Android phone (6–12 GB RAM). Verify each model's license before shipping; several
"open" models carry non-commercial or gated clauses.

Guillotine's invariant holds throughout: **your video never leaves the device.**
Everything in the "on-device" sections runs locally; cloud is only ever the
generation providers the user explicitly configures with their own key, or the
text-only "controller" LLMs that drive the editor over MCP without seeing pixels.

> **What has already shipped** is catalogued in the reference docs: the on-device model files in
> **[MODELS.md](MODELS.md)**, the cloud generation providers in **[PROVIDERS.md](PROVIDERS.md)**, and
> the assistant's tools in **[TOOLS.md](TOOLS.md)**. This roadmap marks shipped items inline
> (**shipped**) and keeps the rest as forward-looking candidates.

---

## 1. On-device model candidates (2025–2026)

The dominant on-device runtimes today are **MediaPipe LLM Inference / LiteRT-LM**
(Google AI Edge — consumes `.task` and the newer `.litertlm`), **ONNX Runtime
Mobile / GenAI**, and **ExecuTorch** (PyTorch on-device, Meta's Llama path).
Google's **AI Edge Gallery** app is a good compatibility oracle for what runs well.

### 1.1 LLM / agent brains (beyond the current SmolLM/Qwen/Phi/Gemma set)

| Model | Size | Task | License | Android feasibility |
|---|---|---|---|---|
| **Gemma 3n E2B / E4B** — **shipped** (vision, `caption_frame`) | ~2B / ~4B effective (MatFormer + PLE) | Multimodal LLM: text + **image + audio** in | Gemma (commercial OK) | **Top pick.** On-device frame captioning via MediaPipe LlmInference + vision modality. Official `.task` (gated, links out for the free Gemma sign-in). Audio-in remains future work. |
| **Gemma 3 1B** — **shipped** | 1B | Text LLM | Gemma | Excellent, very light; ships as an assistant `.task` (via the Gemma mirror). |
| **Llama 3.2 1B / 3B** | 1B / 3B | Text LLM | Llama 3.2 Community | Good; `.task` (MediaPipe) + ExecuTorch (Meta's official mobile path). Candidate — not yet in the catalog. |
| **Qwen2.5 0.5B / 1.5B · Qwen3 0.6B** — **shipped** | 0.5–1.5B | Text LLM | Apache-2.0 | In the on-device assistant catalog (Qwen2.5 as `.task`, Qwen3 as `.litertlm`). Qwen2.5-3B (Research License) is not offered. |
| **SmolLM2 360M / 1.7B** | 0.36 / 1.7B | Text LLM | Apache-2.0 | Very light; 1.7B is a good quality/size sweet spot (SmolLM 135M already bundled as the starter). |
| **Phi-3.5-mini / Phi-4-mini** — **shipped** (Phi-4-mini) | 3.8B | Reasoning LLM | MIT | Phi-4-mini ships in the assistant catalog; needs a high-end device (8 GB+). |
| **DeepSeek-R1-Distill-Qwen-1.5B** — **shipped** | 1.5B | Reasoning LLM | MIT | In the assistant catalog; strong step-by-step planning (verbose traces). |

**Update:** **Gemma 3n** (now the multimodal VLM behind `caption_frame`), **Qwen2.5 0.5B/1.5B**,
**Gemma 3 1B**, **Phi-4-mini**, **DeepSeek-R1-Distill-Qwen-1.5B**, and **Qwen3 0.6B** all ship in
`shared/.../ai/agent/OnDeviceModels.kt` — see **[MODELS.md](MODELS.md)** for the exact files, sizes,
and licenses. **SmolLM2-1.7B** remains a good quality/size candidate still worth adding.

### 1.2 Audio / music analysis (beat, tempo, key, onset, stems)

| Tool / model | Task | License | Android feasibility |
|---|---|---|---|
| **aubio** | Onset, beat, tempo/BPM, pitch | GPL-3.0 (copyleft) | C lib, cross-compiles to NDK. Lightweight, real-time. |
| **Essentia** | Beat tracking, BPM, key/scale, onset + **TempoCNN** TFLite | AGPL-3.0 / commercial | Official Android build; richest analysis toolkit. |
| **madmom / BeatNet** | SOTA DL beat/downbeat tracking | BSD / MIT | Python/PyTorch — export to ONNX/TFLite for on-device. Best offline accuracy. |
| **TensorFlow YAMNet** — **shipped** (one-tap download) | 521-class audio events (speech, music, applause…) | Apache-2.0, ~4 MB TFLite | Powers `find_highlights`: on-device best-moment / highlight-reel detection (applause, cheering, laughter, music, crowd). Runs via a raw TFLite `Interpreter`. |
| **Spotify BasicPitch** | Audio → MIDI / polyphonic pitch | Apache-2.0 | Ships TFLite/ONNX — on-device friendly. Melody/key extraction. |
| **Spleeter / Demucs** | Stem separation (vocals/drums/bass) | MIT | Spleeter → TFLite feasible; Demucs heavier (better as desktop/cloud). |

Guillotine's built-in `BeatAnalyzer` (see §-code) uses a **pure-Kotlin spectral-flux
onset + autocorrelation tempo** pipeline for a zero-dependency on-device beat grid,
upgradeable to a TFLite-exported BeatNet/TempoCNN or aubio/Essentia via NDK.

### 1.3 Vision

| Task | Model | License | Feasibility |
|---|---|---|---|
| Background / general matting | U²-Net, MODNet, BiRefNet (RMBG-1.4 = non-commercial) | Apache/MIT | Convert to TFLite; beyond ML Kit selfie. |
| Depth | **MiDaS-small** (~33 MB) — **shipped** (one-tap download) | MIT / Apache | Proven on Android → bokeh, parallax. Runs via the generic `TfliteImageModel`. |
| Super-resolution | **Real-ESRGAN ×4 v3** (3.5 MB) — **shipped** (one-tap download) | BSD-3 | Tile-by-tile (128²→512²); heavy for video, fine for stills. |
| Style transfer | Magenta Arbitrary Stylization TFLite | Apache-2.0 | Real-time capable, but the official model is **two-input** (content+style) so it doesn't fit the single-image `TfliteImageModel` — left as a bring-your-own-path option. |
| Optical flow | OpenCV (Farnebäck/DIS) or RAFT | BSD | Classical runs easily; RAFT heavy. |
| Shot detection | **Histogram diff (in-code)** — **shipped** (`detect_scenes`) or TransNetV2 | MIT | Cheap content-difference on-device; splits a clip into shots / chapters. |
| Captioning / VLM | **Gemma 3n** — **shipped** (`caption_frame`), SmolVLM 256M/500M, Moondream 0.5B, Florence-2 | Apache/MIT/Gemma | On-device multimodal frame captioning via MediaPipe LlmInference + vision modality (a separate instance, so the text `.task` path is untouched). |
| Auto-reframe | **face-follow (in-code, ML Kit)** — **shipped** (`auto_reframe`); AutoFlip + saliency for non-face subjects | Apache/MIT | Punch-in + OFFSET_X keyframes tracking the main face; saliency-based (non-face) tracking is future work. |

### 1.3b Recognition / ID embedding models ("is this the same specific thing?")

The "teach a thing by pointing at it" feature embeds a crop and matches by cosine. The
embedder is pluggable (Settings → recognition model); the default is MobileNet-V3-small.
MediaPipe ImageEmbedder loads any **CNN** TFLite with a single image input **and
`NormalizationOptions` metadata**; **ViTs (DINOv2/CLIP) don't convert cleanly to TFLite**
and need ONNX Runtime instead.

| Model | Size | Quality vs MobileNetV3 | License | Runtime |
|---|---|---|---|---|
| **MobileCLIP-S0** (image tower) | ~11 M, 512-d | Big instance-matching jump | Apple AMLR (non-permissive) | TFLite w/ metadata → **MediaPipe** ✅ |
| **EfficientNet-Lite0** | ~4.7 M | Modest, safe | Apache-2.0 | TFLite → MediaPipe ✅ |
| **OSNet** (re-ID) | ~2.2 M, 512-d | Instance-trained (person/vehicle) | MIT | TFLite w/ metadata → MediaPipe ✅ |
| **DINOv2 ViT-S/14** | ~22 M, 384-d | Best generic instance features | Apache-2.0 | **ONNX Runtime Mobile** ❌ (ViT) |

**Faces (person identity):** ML Kit face detect + a face embedder beats a generic one.
**MobileFaceNet — shipped** (one-tap download, BSD-3-Clause, 5.2 MB, 112×112 → 192-d
ArcFace) runs through a raw-TFLite `FaceRecognizer` (detect → square-resize → (x−127.5)/128
→ embed → L2 → cosine). Guillotine routes person concepts to it when a face model is
configured (Settings → face model), else falls back to the generic image embedder.
Follow-ups: 5-point landmark alignment for higher accuracy; **EdgeFace-XS** is more accurate
but ships under the Idiap research (non-commercial) license, so it's not offered.

### 1.4 Speech (ASR + TTS)

| Tool | Task | License | Notes |
|---|---|---|---|
| **whisper.cpp** | ASR (tiny 39M / base 74M / small 244M) | MIT | Runs on Android (NDK); real-time streaming demos. |
| **sherpa-onnx** — **shipped** (ASR + TTS) | ASR **+ TTS + VAD + diarization + source-sep** | Apache-2.0 | Best all-in-one offline speech engine. `transcribe_precise` (offline Whisper) + `add_voiceover` (offline neural TTS); models are one-tap `.tar.bz2` downloads. VAD/diarization/source-sep remain future work. |
| **Moonshine** | Fast English ASR | MIT | Lower latency than Whisper for English. |
| **Piper (VITS) / Kokoro-82M** | Offline neural TTS | MIT / Apache-2.0 | Voiceover/dubbing; run via sherpa-onnx. |

---

## 2. Cloud generation providers (bring-your-own key)

All are REST + Bearer / `x-api-key` and BYO-key friendly unless noted. Video and
music providers are **async (submit → poll → download)**; Guillotine's shared
`AsyncJobPoller` handles that uniformly. Generated media is downloaded to a local
file and imported as an ordinary timeline clip.

Two **keyless free** tiers need no account at all: **Pollinations** (image) and **Guillotine (free)**
(video — LTX-Video on a shared Hugging Face ZeroGPU Space). Only the prompt is ever sent. The full,
shipped provider matrix — enums, default model ids, and get-a-key links — is in
**[PROVIDERS.md](PROVIDERS.md)**.

### 2.1 Image
Pollinations (free, keyless) · Leonardo · OpenAI `gpt-image-1`/DALL·E 3 · Stability
(Stable Image / SD 3.5) · **Black Forest Labs FLUX** (+ FLUX.1 Kontext editing) ·
Google Imagen (Gemini key) · Ideogram (best text rendering) · Recraft (raster+vector).

### 2.2 Video (async)
**Guillotine (free)** (keyless — LTX-Video on Guillotine's Hugging Face ZeroGPU Space) · Runway (Gen-4) ·
Luma Dream Machine (Ray2/Ray3) · Google Veo 3.1 (Gemini key, native audio) · MiniMax/Hailuo · OpenAI
Sora · Kling · Pika · Stability image-to-video.

### 2.3 Music / audio
**ElevenLabs** (Eleven Music + Sound Effects + TTS — the most BYO-friendly) ·
Stability Audio (Stable Audio 2.0) · Google Lyria (Gemini key) · Meta MusicGen (via
Replicate) · Mubert (adaptive) · Beatoven · Loudly · Cassette.
**Caveat:** *Suno and Udio have no official public API* — only third-party
account-pooling wrappers (a ToS/reliability risk). Guillotine exposes them only via a
clearly-disclaimed "wrapper key" field.

### 2.4 Aggregators (one integration → many models)
**fal.ai** and **Replicate** each expose dozens of image/video/music models by
model-id and are the recommended path for Kling/Pika/Hailuo/Wan/Seedance and MusicGen.

---

## 3. Music-driven ("edit to the beat") editing — the pipeline

Standard pipeline used across beat-sync tools (the beat-sync features in mainstream consumer
editors, BeatSync-Engine, librosa/madmom scripts):

1. **Beat / onset / tempo analysis** → a beat grid (BPM, beats, downbeats, onsets).
2. **Structural analysis** (optional, what makes it feel *musical*) → sections,
   energy/RMS peaks, **drops**. Downbeats + drops = strong cut points.
3. **Cut-point derivation** → cuts on downbeats or every *N* beats (1/2/4-bar
   phrasing); emphasis cuts/transitions on onsets and drops; snap all edits to the
   grid.
4. **Clip selection & alignment** → shot-detect footage, score by motion/energy/
   saliency, trim/speed-ramp each clip to fill its beat interval, align action to
   strong beats.
5. **Transitions & polish** → on-beat cuts, flashes/zooms/whip-pans on drops.

Guillotine implements this on-device: `BeatAnalyzer` produces the grid, and the AI
editor composes it via the `get_beat_map` / `cut_to_beats` / `align_clips_to_beats`
/ `apply_on_beat` MCP tools — so open-ended prompts ("edit to the beat", "flash on
every snare", "one clip per bar") become concrete rhythm-locked edits.

---

## 4. Long idea list — what else Guillotine could offer

Each item is feasible on the current stack or a model listed above.

**Audio & music**
1. Beat-synced editing (shipped) + auto music-video assembly from a clip folder. **(shipped —
   `assemble_music_video` trims a track's clips to the beat grid)**
2. Stem separation (Spleeter/Demucs): "cut to the drums," karaoke, isolate/remove
   vocals, auto-duck music under the vocal stem. **(shipped — `separate_stems` runs
   Deezer Spleeter 2-stem via ONNX Runtime (STFT → model → ratio mask → iSTFT) into
   vocals + accompaniment; `remove_vocals` is the quick dep-free stereo karaoke.)**
3. Auto-ducking / sidechain: lower music under speech via VAD + RMS. **(shipped — `auto_duck`
   writes VOLUME keyframes on the music under detected speech, on-device, no model)**
4. AI soundtrack: generate a mood- and length-matched score; AI SFX timed to
   actions/transitions (ElevenLabs SFX). **(text-to-music / SFX generation shipped — `generate_music`,
   cloud BYO-key; auto-scoring timed to on-screen actions is the future part.)**
5. Loudness normalization to platform targets (−14 LUFS YouTube) on export. **(shipped —
   `normalize_loudness` uses BS.1770 K-weighted LUFS; `normalize_levels` is the quick RMS match)**
6. Noise reduction / de-reverb / voice isolation on-device (sherpa-onnx). **(shipped — `denoise_clip`
   via the on-device GTCRN speech denoiser; de-reverb and full voice-isolation remain future work.)**
7. Multicam sync by audio-waveform correlation **(shipped — `sync_by_audio`)**; filler-word
   ("um") removal **(shipped — `remove_fillers` via offline Whisper word timings)**.

**Speech & text**
8. Better ASR (whisper.cpp/sherpa-onnx) — multilingual, word-level captions. **(shipped —
   `transcribe_precise` via offline Whisper/sherpa-onnx)**
9. Offline TTS voiceover / dubbing (Piper/Kokoro), voice-clone dub (ElevenLabs);
   translate + burn foreign subtitles on-device. **(voiceover shipped — `add_voiceover` via
   offline Piper/sherpa-onnx)**
10. Speaker diarization → auto podcast multicam switching between speakers. **(shipped —
    `diarize_clip` via sherpa-onnx pyannote segmentation + speaker embedding)**
11. Voice-command editing — speak the instruction; ASR feeds the agent. **(shipped — a mic button
    on the assistant field records on-device, transcribes via offline Whisper, and drops the text into
    the prompt for review/send; needs the ASR model configured)**

**Vision & generation**
12. Smart auto-reframe landscape→9:16/1:1 following the subject (AutoFlip). **(shipped —
    `auto_reframe` punches in and pans OFFSET_X keyframes to follow the main face)**
13. Depth effects: portrait/bokeh blur, 2.5D parallax "3D Ken Burns," fake dolly. **(bokeh shipped —
    `apply_bokeh` blurs the depth-far background; animated 2.5D parallax is a follow-up)**
14. Super-resolution upscale of old/low-res footage and stills.
15. Style transfer / AI looks; on-device auto color-correct & shot-match; LUTs. **(shipped —
    `auto_color` (on-device auto color-correct), `match_color` (shot-match), and `.cube` LUTs (see
    [ECOSYSTEM.md](ECOSYSTEM.md)); ML style-transfer looks remain future — the STYLE model slot ships
    with no bundled model.)**
16. Background replace without green screen (matting) + generated backgrounds. **(shipped —
    the `removeBackground` subject matte composites over lower tracks, and `replace_background`
    mattes the subject and drops a chosen background (solid color or image) on a new track behind;
    pass a generated image's path for an AI backdrop. General (non-selfie) matting models remain a
    future upgrade.)**
17. Face tools: auto-blur faces (privacy), face-tracking reframe, "keep only shots
    with person X." **(shipped — auto-blur via `blur_faces` (on-device ML Kit face detection, blurred
    in both preview and export); "keep only person X" by teaching a person from their face across
    frames (`add_reference`, routed to the face-ID embedder) then
    `analyze_clip_with_concept(keep_only=true)`, with negatives ("that's a different person") to sharpen
    it. Non-face (saliency) auto-reframe remains future work.)**
18. Text-to-video B-roll to fill gaps; image-gen titles/thumbnails/lower-thirds;
    generate a thumbnail from the best frame. **(free T2V shipped — the keyless
    "Guillotine (free)" video provider calls our own Hugging Face Space (`hf-space/`,
    LTX-Video on ZeroGPU; deploy via the *Deploy T2V Space* Action, `HF_TOKEN` secret).
    Only the text prompt leaves the device. BYO-key providers (Runway/Luma/Veo/Kling/
    Pika/Sora) remain for longer, higher-quality clips.)** On-device T2V (e.g. the
    iOS-only [On-device-Sora](https://github.com/eai-lab/On-device-Sora), CoreML) is a
    *watch* item — no Android-friendly LiteRT/ONNX T2V is fast enough yet; revisit when
    one lands.
19. Semantic footage search ("find all clips with a dog/sunset/red car") via the
    existing on-device image embeddings. **(shipped — `search_clips` matches on-device
    image labels across each clip's sampled frames)**
20. Audio-event & highlight detection (YAMNet) → auto-trailer / best-moments reel. **(shipped —
    `find_highlights`)**

**Workflow & product**
21. Script-to-video and storyboard-to-video: prompt → generated clips + TTS + music
    + captions → a rough cut the user refines.
22. Auto-chaptering / scene detection → timeline markers + YouTube chapters. **(shipped —
    `detect_scenes` splits a clip into shots on-device)**
23. Platform export presets (TikTok/Reels/Shorts) with safe-zones + direct share. **(shipped —
    `set_export_preset` sets the aspect; the preview shows platform safe-zone guides in Crop mode for
    vertical/square projects; the export dialog has a Share button.)**
24. Kinetic-caption & meme templates, emoji reactions timed to speech. **(kinetic captions shipped —
    `animated_transcribe_clip`, per-syllable scale-keyframed captions; meme templates and emoji
    reactions remain future.)**
25. Teachable-tool marketplace — share user-defined AI editing tools.

---

## Desktop parity

The desktop app (Compose Desktop) shares `:shared` with Android but implements the MCP tool surface
separately (`DesktopMcpTools`), so it trailed Android's 66 tools. The editor core (timeline, keyframes,
transitions, color, text, FFmpeg export, cloud assistant) is at parity; the remaining gap is the
AI-tool surface.

All **66** tools are now *defined* on desktop (the schema is discoverable via `tools/list`), and all
but **five** are functional. Every tool with a real on-device desktop-JVM path is wired; the last five
are honest stubs (a clear "needs an on-device model" error) because they require a runtime that has no
desktop-JVM form, and we do **not** cloud-fake them — keeping the on-device/private invariant is the
point. They are enumerated at the end of this section.

**Functional on desktop:** the timeline/edit/user-tool tools; cloud generation (`generate_image` /
`generate_video` / `generate_music`); the beat suite (`get_beat_map`, `cut_to_beats`, `apply_on_beat`,
`align_clips_to_beats`, `assemble_music_video`, on shared pure-JVM DSP); FFmpeg/DSP (`normalize_levels`,
`normalize_loudness`, `detect_scenes`, `auto_duck`, `apply_ffmpeg_filter` via in-process JavaCV); the
color/LUT render (`apply_lut` / `clear_lut` sampling a `.cube` through the shared `CubeLut` into the
`BufferedImage` pipeline, `auto_color` / `match_color` frame-tone histograms, `list_shader_params` via
shared `GlslShader`); on-device speech captions (`transcribe_clip` / `animated_transcribe_clip` via
Vosk-JVM), multicam `sync_by_audio`, and on-device source separation (`remove_vocals`, `separate_stems`
via Spleeter on ONNX Runtime-for-JVM).

**ONNX-for-JVM foundation (shipped):** `DesktopOnnx` wraps ONNX Runtime; models are pointed at from
Settings exactly like Android (a model *path*, not a bundled binary), so desktop keeps the
on-device/private promise — only the model download touches the network, inference is local. Stem
separation and image labeling already ride this path.

**Wired this pass (each: an ONNX model path in Settings + an inference helper):**
- **Vision / labeling** — `search_clips` and `describe_current_frame` are wired to an ONNX ImageNet
  classifier (`DesktopImageLabeler`), `find_highlights` to an ONNX YAMNet audio-event model
  (`DesktopYamnet`), and the learned-concept pair `add_reference` / `analyze_clip_with_concept` to an
  ONNX image embedder (`DesktopImageEmbedder`, frame-level cosine matching → real cuts). `analyze_clip`
  (prompt-driven cut analysis) and `caption_frame` are wired label-based (the labeler + the clip prompt;
  desktop has no on-device VLM). All vision/labeling tools are now on-device.
- **Face / segmentation** — `auto_reframe` and `blur_faces` are wired to an ONNX UltraFace-style face
  detector (`DesktopFaceDetector`). `auto_reframe` follows the main face with OFFSET_X keyframes;
  `blur_faces` tracks the face and drops a pre-blurred patch on a track above it, keyframed to follow
  (`DesktopFaceBlur` + `EditorViewModel.addFaceBlurOverlay`) — so it renders in preview + export via
  the keyframe system, no per-frame render pass. `replace_background` is wired to an ONNX subject
  segmenter (`DesktopSegmenter`): the export render mattes the subject over a colour/image dropped on a
  track behind (preview shows the un-matted clip — an export-only parity gap for now).
- **Speech models** — `transcribe_precise` and `remove_fillers` are wired via the on-device Vosk
  transcriber (word timings → real cuts for filler removal). `add_voiceover` (neural TTS) and
  `diarize_clip` (speaker diarization) still need sherpa-onnx models with no clean desktop-JVM artifact.
- **Image models / inpaint** — `apply_bokeh` is wired as a portrait blur (the `DesktopSegmenter`
  keeps the subject sharp and blurs the background, on export — desktop has no depth model, so it's
  segmentation-based not true depth-of-field). `apply_image_effect` (generic TFLite superres/style/
  lowlight) and `remove_object_generative` (object mask + cloud inpaint) remain.
- **`apply_transition`** — wired as a cross-dissolve by overlapping the two clips on one track, which
  the renderer's built-in crossfade blends (preview + export). Per-style xfade wipes (slide/circle/…)
  would still need a real two-input filtergraph.

The learned-concept *store* (`shared/model/LearnedConcept.kt`) is already shared, so only the
embedders/detectors are platform-bound.

**Resolved — `apply_shader` now renders on desktop.** GLSL/ISF shaders are translated to Skia's SkSL
(`shared/media/GlslToSksl.kt`) and executed through Skia's **CPU raster runtime effect**
(`desktop/media/DesktopShaderPass.kt`) — no GL context, window, or display needed, so it works in
headless export as well as the live preview, applied last exactly as on Android. The translation is a
best-effort transform of the single-image filter subset the parser accepts; a shader that can't be
compiled to SkSL leaves the frame untouched (the tool reports `shaderRendered:false` for it), so the
worst case is a no-op, never a corrupted picture.

**Resolved — the last four now run on-device via ONNX Runtime** (no sherpa/TFLite binding, no cloud —
each hand-rolled over the ONNX/JavaCV infra desktop already ships, gated on an installed `.azp` model):
- **`add_voiceover`** — a VITS/Piper `.onnx` voice run directly through ONNX Runtime
  (`desktop/media/DesktopTts.kt`): text → token ids (lexicon or character-level; no espeak needed) →
  audio → WAV → audio clip. A phoneme-only voice with no lexicon errors clearly rather than emitting
  garbage. Slot `ttsModelPath`.
- **`diarize_clip`** — energy VAD + an ONNX speaker-embedding model + agglomerative clustering
  (`desktop/media/DesktopDiarizer.kt`); the embedding input auto-adapts between raw waveform and log-mel
  fbank. Slot `diarizeEmbedModelPath`.
- **`apply_image_effect`** — a generic NCHW image `.onnx` (super-res / style / depth / lowlight) over the
  playhead frame (`desktop/media/DesktopImageEffect.kt`), output range auto-detected. Slots `effect_*`.
- **`remove_object_generative`** — segmentation mask of the salient subject + a LaMa-style inpaint
  `.onnx` (`desktop/media/DesktopInpaint.kt`), fully on-device (desktop does not use Android's cloud
  Leonardo path, so it removes the segmented subject rather than an arbitrary named object). Slots
  `segModelPath` + `inpaintModelPath`.

These are genuine on-device pipelines; their quality tracks the installed model, and each fails loudly
(never fakes) when its `.azp` isn't installed or a model's I/O contract doesn't match.

**Net:** desktop is at **66/66** functional tools — full tool-surface parity with Android — with the
on-device invariant intact (only the generation tools touch the network, and only for the generated
media, exactly as on Android).
