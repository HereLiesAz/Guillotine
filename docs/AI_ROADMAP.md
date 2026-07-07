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
| **Gemma 3 1B** | 1B | Text LLM | Gemma | Excellent, very light, `.task` available. |
| **Llama 3.2 1B / 3B** | 1B / 3B | Text LLM | Llama 3.2 Community | Good; `.task` (MediaPipe) + ExecuTorch (Meta's official mobile path). |
| **Qwen2.5 0.5–3B / Qwen3 0.6–4B** | 0.5–4B | Text LLM | Apache-2.0 (small); Qwen2.5-3B is Research License | Strong. Qwen2.5-1.5B ships as `.litertlm` in AI Edge Gallery. |
| **SmolLM2 360M / 1.7B** | 0.36 / 1.7B | Text LLM | Apache-2.0 | Very light; 1.7B is a good quality/size sweet spot (135M already bundled). |
| **Phi-3.5-mini / Phi-4-mini** | 3.8B | Reasoning LLM | MIT | Feasible on 8 GB+; Phi-4-mini already supported. |
| **DeepSeek-R1-Distill-Qwen-1.5B** | 1.5B | Reasoning LLM | MIT | Runs via LiteRT-LM; good for chain-of-thought planning. |

**Recommendation:** add **Gemma 3n**, **Qwen2.5-1.5B**, and **SmolLM2-1.7B** to
`shared/.../ai/agent/OnDeviceModels.kt` alongside the current set. Gemma 3n is the
standout because it is natively multimodal.

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
| Auto-reframe | MediaPipe AutoFlip + saliency (BASNet/U²-Net) | Apache/MIT | Smart vertical crop tracking the subject. |

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
**MobileFaceNet-512** (~1–4 MB, MIT community weights) is the safe default; **EdgeFace-XS**
(Idiap research license) is higher accuracy. Guillotine routes person concepts through the
face path when a face model is configured (Settings → face model).

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

### 2.1 Image
Pollinations (free, keyless) · Leonardo · OpenAI `gpt-image-1`/DALL·E 3 · Stability
(Stable Image / SD 3.5) · **Black Forest Labs FLUX** (+ FLUX.1 Kontext editing) ·
Google Imagen (Gemini key) · Ideogram (best text rendering) · Recraft (raster+vector).

### 2.2 Video (async)
Runway (Gen-4) · Luma Dream Machine (Ray2/Ray3) · Google Veo 3.1 (Gemini key, native
audio) · MiniMax/Hailuo · OpenAI Sora · Kling · Pika · Stability image-to-video.

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

Standard pipeline used across beat-sync tools (CapCut/Filmora beat-sync,
BeatSync-Engine, librosa/madmom scripts):

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
1. Beat-synced editing (shipped) + auto music-video assembly from a clip folder.
2. Stem separation (Spleeter/Demucs): "cut to the drums," karaoke, isolate/remove
   vocals, auto-duck music under the vocal stem. **(partial — `remove_vocals` ships a
   dep-free stereo center-channel karaoke/instrumental extractor. True ML multi-stem
   Spleeter is a follow-up: sherpa-onnx v1.13.3 has no Kotlin/JNI source-separation
   binding, so it needs `onnxruntime-android` + an STFT/iSTFT spectrogram pipeline.)**
3. Auto-ducking / sidechain: lower music under speech via VAD + RMS.
4. AI soundtrack: generate a mood- and length-matched score; AI SFX timed to
   actions/transitions (ElevenLabs SFX).
5. Loudness normalization to platform targets (−14 LUFS YouTube) on export.
6. Noise reduction / de-reverb / voice isolation on-device (sherpa-onnx).
7. Multicam sync by audio-waveform correlation; filler-word ("um") removal.

**Speech & text**
8. Better ASR (whisper.cpp/sherpa-onnx) — multilingual, word-level captions. **(shipped —
   `transcribe_precise` via offline Whisper/sherpa-onnx)**
9. Offline TTS voiceover / dubbing (Piper/Kokoro), voice-clone dub (ElevenLabs);
   translate + burn foreign subtitles on-device. **(voiceover shipped — `add_voiceover` via
   offline Piper/sherpa-onnx)**
10. Speaker diarization → auto podcast multicam switching between speakers.
11. Voice-command editing — speak the instruction; ASR feeds the agent.

**Vision & generation**
12. Smart auto-reframe landscape→9:16/1:1 following the subject (AutoFlip).
13. Depth effects: portrait/bokeh blur, 2.5D parallax "3D Ken Burns," fake dolly.
14. Super-resolution upscale of old/low-res footage and stills.
15. Style transfer / AI looks; on-device auto color-correct & shot-match; LUTs.
16. Background replace without green screen (matting) + generated backgrounds.
17. Face tools: auto-blur faces (privacy), face-tracking reframe, "keep only shots
    with person X."
18. Text-to-video B-roll to fill gaps; image-gen titles/thumbnails/lower-thirds;
    generate a thumbnail from the best frame.
19. Semantic footage search ("find all clips with a dog/sunset/red car") via the
    existing on-device image embeddings.
20. Audio-event & highlight detection (YAMNet) → auto-trailer / best-moments reel. **(shipped —
    `find_highlights`)**

**Workflow & product**
21. Script-to-video and storyboard-to-video: prompt → generated clips + TTS + music
    + captions → a rough cut the user refines.
22. Auto-chaptering / scene detection → timeline markers + YouTube chapters. **(shipped —
    `detect_scenes` splits a clip into shots on-device)**
23. Platform export presets (TikTok/Reels/Shorts) with safe-zones + direct share.
24. Kinetic-caption & meme templates, emoji reactions timed to speech.
25. Teachable-tool marketplace — share user-defined AI editing tools.
