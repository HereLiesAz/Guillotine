# Guillotine AI Providers

Guillotine talks to AI in **two completely separate roles**, and it helps to keep them
straight because they behave very differently with respect to your media and your keys.

1. **Controller LLMs — the "brain" that drives the editor.** These are text-only models
   that read your typed request plus the editor's tool list and then *call the editing
   tools* (cut, filter, LUT, transcribe, generate, …). They are how "remove the boring
   parts and add captions" becomes a sequence of real edits. **They never receive your
   video, audio, or raw frames** — only text: your prompt, the tool descriptions, and the
   *text* results of on-device analysis (e.g. the on-device vision's description of "this
   frame"). Analysis itself always runs on-device.

2. **Generation providers — make new media.** These produce brand-new images, video, or
   music/audio from a text prompt (and, for some, a reference image you choose). Only the
   prompt (and any image you explicitly hand it) is sent — never your timeline.

The invariant that governs everything: **your video and audio never leave the device.** A
controller LLM drives the editor by exchanging text only; a generation provider only ever
sees the prompt you give it.

Two things are always free and require no account at all:

- A **fully-local on-device LLM brain** — a downloaded `.task` model that drives the editor
  with no key and no network, pairing with the free on-device analyzers for a completely
  offline assistant. A tiny starter model ships bundled in the app.
- A **keyless free tier** for generation — **Pollinations** for images and
  **Guillotine (free)** for video, neither of which needs a key.

Everything else is **bring-your-own-key (BYO)**: you paste your own provider API key, and it
is **stored encrypted on-device** (see below). No key is ever required to use Guillotine —
the on-device vision analyzer is the default out of the box.

See also: [SETTINGS.md](SETTINGS.md) · [MODELS.md](MODELS.md) · [TOOLS.md](TOOLS.md) ·
[MANUAL.md](MANUAL.md) · [ECOSYSTEM.md](ECOSYSTEM.md).

---

## How keys are stored

Every API key you enter is persisted **encrypted on-device** using Jetpack Security
(`EncryptedSharedPreferences` with a hardware Keystore-backed master key, AES-256). Keys
never leave the device except inside the direct request to the provider *you* initiated. The
whole set of keys and preferences travels only through the local **Backup & Restore** export
(Settings → Advanced), never to Guillotine.

Wherever you paste a key, the app reminds you: *"Stored encrypted on this device."*

---

## Controller LLMs

Select the brain in **Settings → AI Analyzer**. The provider list is a set of radio buttons;
picking a cloud provider reveals its **API key** field, a **Model** dropdown (which can pull
the provider's live model list, or use the default), and a **"Get a … API key ↗"** link.

The default model id shown for each provider is **editable** — leave the Model field on
*Default* to use the built-in one, or pick another from the provider's live list / type any
id the provider accepts.

### The on-device invariant

Regardless of which brain you choose, analysis runs on-device and **only text is sent** to
the brain: your prompt, the tool descriptions it needs to see, and the on-device vision's
*text* results. Your raw frames and audio are never transmitted.

### Summary

| Provider | Enum | Key | Default model (editable) | Base URL |
|---|---|---|---|---|
| On-device vision (default) | `MLKIT` | **None** | — (on-device analyzer) | on device |
| On-device silence cut | `LOCAL` | **None** | — (on-device analyzer) | on device |
| On-device LLM brain | *(`.task` model path)* | **None** | your downloaded `.task` | on device |
| Gemini | `GEMINI` | BYO | `gemini-2.5-flash` | native Google API |
| OpenAI | `OPENAI` | BYO | `gpt-4o` | `api.openai.com/v1/chat/completions` |
| Anthropic | `ANTHROPIC` | BYO | Claude Opus (latest) | native Anthropic API |
| OpenRouter | `OPENROUTER` | BYO | `openai/gpt-4o-mini` | `openrouter.ai/api/v1/chat/completions` |
| Groq | `GROQ` | BYO | `meta-llama/llama-4-scout-17b-16e-instruct` | `api.groq.com/openai/v1/chat/completions` |
| xAI (Grok) | `XAI` | BYO | `grok-2-vision-1212` | `api.x.ai/v1/chat/completions` |
| Mistral | `MISTRAL` | BYO | `pixtral-12b-2409` | `api.mistral.ai/v1/chat/completions` |

Controller base URLs are built in; they are not user-editable. Only the **model id** is
yours to change.

### On-device (free, no key) — the default

Guillotine ships defaulting to the on-device analyzers, so it works with no account:

- **On-device vision** (`MLKIT`) — *"On-device faces & objects, no key — keep/remove by
  what's on screen."* The out-of-the-box default. Keeps or removes footage by what it sees.
- **On-device silence cut** (`LOCAL`) — *"On-device silence cut. No key, works offline."*

These analyzers do the *seeing*, but they don't call tools themselves. To let an AI **drive
the editor** with no key and no network, add an **on-device LLM brain**:

- In **Settings → AI Analyzer → "AI assistant — on-device model (optional)"**, point
  `agentModelPath` at a downloaded LLM in MediaPipe `.task` (or `.litertlm`) format. The
  in-app picker downloads recommended models straight to the device (Wi-Fi recommended),
  or you can paste a path.
- When your selected provider is a keyless on-device analyzer (or a cloud provider whose key
  is blank), this on-device brain runs the tools. **On-device brain + on-device analysis = a
  fully offline assistant**, requiring no key or network.
- A tiny **SmolLM 135M Instruct** model is **bundled** in the app as an always-available
  starter (instant, no download). Heavier, more capable models can be downloaded from the
  picker, e.g. **Qwen2.5 0.5B / 1.5B**, **Gemma 3 1B**, **Phi-4 mini**, **DeepSeek-R1 Distill
  Qwen 1.5B**, and **Qwen3 0.6B**. Bigger models reason better but need more storage/RAM.

### Gemini

*"Google · drives the editor via tools (no video sent)."*

- **Key:** BYO. **Get one:** <https://aistudio.google.com/app/apikey> (Google AI Studio).
- **Default model:** `gemini-2.5-flash` (editable). **API:** native Google Generative
  Language API (not OpenAI-compatible).
- **Where:** Settings → AI Analyzer → select **Gemini** → paste the key.
- The same Gemini key can also drive image (Imagen), video (Veo), and music (Lyria)
  generation — see below.

### OpenAI

*"GPT · drives the editor via tools (no video sent)."*

- **Key:** BYO. **Get one:** <https://platform.openai.com/api-keys>.
- **Default model:** `gpt-4o` (editable). **Base URL:** `https://api.openai.com/v1/chat/completions`.
- **Where:** Settings → AI Analyzer → select **OpenAI** → paste the key.

### Anthropic

*"Claude · drives the editor via tools (no video sent)."*

- **Key:** BYO. **Get one:** <https://console.anthropic.com/settings/keys>.
- **Default model:** the latest Claude Opus (editable). **API:** native Anthropic Messages API
  (not OpenAI-compatible).
- **Where:** Settings → AI Analyzer → select **Anthropic** → paste the key.

### OpenRouter

*"One key, many models · drives the editor via tools."*

- **Key:** BYO. **Get one:** <https://openrouter.ai/keys>.
- **Default model:** `openai/gpt-4o-mini` (editable — any model in OpenRouter's catalog).
- **Base URL (OpenAI-compatible):** `https://openrouter.ai/api/v1/chat/completions`.
- **Where:** Settings → AI Analyzer → select **OpenRouter** → paste the key.

### Groq

*"Fast Llama · drives the editor via tools."*

- **Key:** BYO. **Get one:** <https://console.groq.com/keys>.
- **Default model:** `meta-llama/llama-4-scout-17b-16e-instruct` (editable).
- **Base URL (OpenAI-compatible):** `https://api.groq.com/openai/v1/chat/completions`.
- **Where:** Settings → AI Analyzer → select **Groq** → paste the key.

### xAI (Grok)

*"Grok · drives the editor via tools."*

- **Key:** BYO. **Get one:** <https://console.x.ai>.
- **Default model:** `grok-2-vision-1212` (editable).
- **Base URL (OpenAI-compatible):** `https://api.x.ai/v1/chat/completions`.
- **Where:** Settings → AI Analyzer → select **xAI (Grok)** → paste the key.

### Mistral

*"Mistral · drives the editor via tools."*

- **Key:** BYO. **Get one:** <https://console.mistral.ai/api-keys>.
- **Default model:** `pixtral-12b-2409` (editable).
- **Base URL (OpenAI-compatible):** `https://api.mistral.ai/v1/chat/completions`.
- **Where:** Settings → AI Analyzer → select **Mistral** → paste the key.

---

## Generation providers

Generation is **capability-gated**: a category (Image / Video / Music) and each provider
within it is only offered once it's usable — that means **keyless** providers are always
available, and **BYO** providers appear as soon as you've entered their key. Configure as
many as you like; the app remembers your preferred provider per category across sessions.

Enter generation keys in **Settings → Generation**, grouped into **Image**, **Video**, and
**Music**. Each provider is a collapsible card with a dot that lights up once it's
configured. Keyless providers simply say *"Free — no key needed."* BYO providers show a key
field (*"Stored encrypted on this device."*), an optional **Model** field (with the default
and the list of options), a **"Get a … key ↗"** link, and any provider disclaimer.

The model field takes the **wire model id** the provider expects. Leave it blank to use the
listed default.

### Image

| Provider | Enum | Key | Default model | Notes |
|---|---|---|---|---|
| **Pollinations (free)** | `POLLINATIONS` | **None (keyless)** | `flux` (also `turbo`) | No key, instant. Great for quick placeholder images. Served from `image.pollinations.ai`. |
| Leonardo.ai | `LEONARDO` | BYO | `Leonardo Phoenix 1.0` | High-quality generation + inpainting (used for generative object removal). Also FLUX.1 Dev/Schnell, Kino XL. |
| OpenAI Images | `OPENAI_IMAGE` | BYO | `gpt-image-1` | gpt-image-1 / DALL·E 3 / DALL·E 2. Strong prompt adherence. |
| Stability AI (image) | `STABILITY_IMAGE` | BYO | `sd3.5-large` | Stable Image Ultra/Core and SD 3.5 (Large / Turbo / Medium). |
| Black Forest Labs (FLUX) | `BFL_FLUX` | BYO | `flux-pro-1.1` | FLUX.1/1.1 and FLUX.1 Kontext (prompt-based editing). *Free key, pay-per-image.* |
| Google Imagen | `GEMINI_IMAGEN` | BYO | `imagen-4.0-generate-001` | Imagen 3/4 via a Gemini API key (same key can drive the editor). |
| Ideogram | `IDEOGRAM` | BYO | `V_3` | Best-in-class text rendering in images (3.0 / 2.0 / 2.0 Turbo). |
| Recraft | `RECRAFT` | BYO | `recraftv3` | Raster and vector/SVG output, brand styles. |

> **Keyless free tier — Pollinations.** Always available with no account. Default model
> `flux` (or `turbo`). Ideal for quick placeholders while you iterate.

Get-a-key links: Leonardo `app.leonardo.ai/api-access` · OpenAI `platform.openai.com/api-keys`
· Stability `platform.stability.ai/account/keys` · BFL `docs.bfl.ai` · Imagen
`aistudio.google.com/app/apikey` · Ideogram `developer.ideogram.ai` · Recraft
`recraft.ai/profile/api`.

### Video

| Provider | Enum | Key | Default model | Notes |
|---|---|---|---|---|
| **Guillotine (free)** | `GUILLOTINE_FREE` | **None (keyless)** | `ltx-video` | Runs an open text-to-video model on Guillotine's free Hugging Face Space (ZeroGPU). Short, low-res clips; can queue at busy times. Only your text prompt is sent — never your media. |
| Runway | `RUNWAY` | BYO | `gen4_turbo` | Gen-4 / Gen-4 Turbo text- and image-to-video. |
| Luma Dream Machine | `LUMA` | BYO | `ray-2` | Ray2 / Ray2 Flash / Ray 1.6 text- and image-to-video. |
| Google Veo | `GEMINI_VEO` | BYO | `veo-3.1-generate-preview` | Veo 3.1 (with native audio) via a Gemini API key. |
| MiniMax / Hailuo | `MINIMAX` | BYO | `MiniMax-Hailuo-02` | Hailuo text- and image-to-video. |
| OpenAI Sora | `OPENAI_SORA` | BYO | `sora-2` | Sora 2 (and Sora 2 Pro) video with synced audio. |
| Kling | `KLING` | BYO | `kling-v2` | Kling text- and image-to-video (also reachable via the aggregators). |
| Pika | `PIKA` | BYO | `pika-2.2` | Pika 2.2 text- and image-to-video. |
| Stability AI (video) | `STABILITY_VIDEO` | BYO | `stable-video-diffusion` | Image-to-video (Stable Video Diffusion lineage). |

> **Keyless free tier — Guillotine (free).** No key. Runs LTX-Video on Guillotine's shared
> **Hugging Face ZeroGPU** Space, so expect short, low-res clips and occasional queueing at
> busy times. For longer / higher-quality video, add a key for a paid provider above. As
> always, **only your text prompt is sent — never your media.**

Get-a-key links: Runway `dev.runwayml.com` · Luma `lumalabs.ai/dream-machine/api` · Veo
`aistudio.google.com/app/apikey` · MiniMax `platform.minimax.io` · Sora
`platform.openai.com/api-keys` · Kling `app.klingai.com` · Pika `pika.art` · Stability
`platform.stability.ai/account/keys`.

### Music / audio

| Provider | Enum | Key | Default model | Notes |
|---|---|---|---|---|
| ElevenLabs | `ELEVENLABS` | BYO | `music` | Eleven Music (full songs), Sound Effects, and TTS. The most BYO-friendly audio API. |
| Stability Audio | `STABILITY_AUDIO` | BYO | `stable-audio-2.0` | Stable Audio 2.0 — music and sound effects. |
| Google Lyria | `GEMINI_LYRIA` | BYO | `lyria-002` | Lyria music generation via a Gemini API key. |
| MusicGen (Replicate) | `MUSICGEN_REPLICATE` | BYO | `meta/musicgen` | Meta MusicGen text-to-music through your Replicate key. |
| Mubert | `MUBERT` | BYO | `default` | Royalty-free, mood/genre/duration adaptive music. |
| Beatoven.ai | `BEATOVEN` | BYO | `default` | Mood-based background/bed music. |
| Loudly | `LOUDLY` | BYO | `default` | Genre/mood music generation. |
| Cassette | `CASSETTE` | BYO | `default` | AI music generation API. |
| Suno (via wrapper) | `SUNO_WRAPPER` | BYO **+ wrapper base URL** | `chirp-v4` | Full songs with vocals. **See caveat below.** |
| Udio (via wrapper) | `UDIO_WRAPPER` | BYO **+ wrapper base URL** | `udio-130` | Full songs with vocals. **See caveat below.** |

Get-a-key links: ElevenLabs `elevenlabs.io/app/settings/api-keys` · Stability
`platform.stability.ai/account/keys` · Lyria `aistudio.google.com/app/apikey` · MusicGen
`replicate.com/account/api-tokens` · Mubert `mubert.com/business/api` · Beatoven
`beatoven.ai` · Loudly `loudly.com/developers` · Cassette `cassetteai.com`.

> **Suno / Udio caveat.** Suno and Udio have **no official API**. Guillotine exposes them
> only through a **third-party account-pooling wrapper** that *you* supply — so besides a
> key, these two require a **Wrapper base URL** (`https://…`) you enter in their card.
> Reliability and terms are outside Guillotine's control.

### Aggregators (one key, many models)

Two providers serve **all three** categories through a single key, so they appear in the
Image, Video, *and* Music sections:

| Provider | Enum | Key | Default model | Notes |
|---|---|---|---|---|
| fal.ai | `FAL` | BYO | `fal-ai/flux/dev` | One key → many image/video/music models. Enter the **fal model id** as the model. Get a key: `fal.ai/dashboard/keys`. |
| Replicate | `REPLICATE` | BYO | `black-forest-labs/flux-dev` | One key → any hosted model. Enter the **Replicate model (`owner/name`)** as the model. Get a key: `replicate.com/account/api-tokens`. |

Because they're generic, the "model" field is where you name the exact model you want
(e.g. `fal-ai/kling-video/v2/master/text-to-video`, or `minimax/video-01` on Replicate).

---

## Which should I use?

| If you want… | Use | Key? | Runs |
|---|---|---|---|
| Zero setup, maximum privacy, works offline | On-device analyzers (`MLKIT` default / `LOCAL`) + on-device LLM brain (`.task`) | **No key** | On device |
| A capable cloud brain to drive edits | Gemini · OpenAI · Anthropic | BYO | Cloud (text only) |
| Many brains behind one key | OpenRouter (or Groq for speed) | BYO | Cloud (text only) |
| A free image with no account | **Pollinations** | **No key** | Cloud (prompt only) |
| A free video clip with no account | **Guillotine (free)** | **No key** | Cloud (prompt only) |
| High-quality images | Leonardo · BFL FLUX · Stability · Ideogram · Recraft · OpenAI Images | BYO | Cloud (prompt only) |
| High-quality / longer video | Runway · Luma · Veo · Sora · Kling · Pika · MiniMax | BYO | Cloud (prompt only) |
| Music, songs, or SFX | ElevenLabs · Stability Audio · Lyria · MusicGen · Mubert · Beatoven · Loudly · Cassette | BYO | Cloud (prompt only) |
| Full songs with vocals | Suno / Udio (via wrapper) | BYO + base URL | Cloud (prompt only) |
| One key across image + video + music | fal.ai · Replicate | BYO | Cloud (prompt only) |

**Rules of thumb:**

- **Private and free?** Stay on-device: the default `MLKIT` vision + a downloaded on-device
  `.task` brain never touch the network.
- **Free but cloud?** Pollinations (image) and Guillotine (video) need no key; only your
  prompt is sent.
- **Controller vs. generation are independent.** You can run a fully on-device brain *and*
  still call a BYO image/video/music provider — the brain drives the edit, the generation
  provider makes the new asset.
- Every BYO key is **encrypted on-device** and only ever used in the request you initiate.

---

## See also

- [SETTINGS.md](SETTINGS.md) — every setting, tab by tab.
- [MODELS.md](MODELS.md) — the on-device model catalog (analyzers, brains, ASR/TTS, effects).
- [TOOLS.md](TOOLS.md) — the MCP tools a controller LLM drives the editor with.
- [MANUAL.md](MANUAL.md) — the full user manual.
- [ECOSYSTEM.md](ECOSYSTEM.md) — LUTs, shaders, MCP plugins, and FFmpeg/Frei0r.
