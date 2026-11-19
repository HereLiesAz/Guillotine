# Privacy Policy — Guillotine

**Effective date:** 2026-11-19

Guillotine is an on-device, non-linear video editor for Android, tablets, Chromebooks, and native
desktop apps for macOS, Windows, and Linux. This policy explains what the app does and does not
do with your data. In short: **no account system, no first‑party analytics, and no ads, ad SDK,
or advertising ID on any platform.** Your media and projects stay on your device unless *you*
use a feature that sends something off it (listed below). The one service the developer runs is
the free video‑generation Space, which receives only your text prompt.

If you tap the **Report** button in the Export dialog after a render failure, the app opens your
default browser at `github.com/HereLiesAz/Guillotine/issues/new` with a pre-filled title and body
(device model, app version, diagnostic stack trace). No data leaves the device until you submit
the issue on GitHub — the app itself does not upload anything.

## The short version

- The developer does **not** collect or store your media, projects, or keys. The only
  developer‑run endpoint is the free video‑generation Space (see below), which receives your
  text prompt only.
- **No ads, no ad SDK, no advertising ID**, on any platform.
- Editing, playback, thumbnails, waveforms, frame/audio analysis (ML Kit + MediaPipe vision and
  the local silence heuristic), an optional on‑device LLM, and on‑device speech recognition all
  run **locally on your device** by default.
- Data leaves the device only when **you** use a feature that needs a network service: a cloud AI
  controller (text; plus the current frame if you turn on **Let cloud AI see frames**), generative
  object removal (masked frames), cloud transcription/voice (audio), video/music generation
  (text prompt), or crash reporting you set up.
- API keys you enter are **stored encrypted on your device** and are sent only to the matching
  provider, only in requests you initiate.

## Information stored on your device

The app stores the following **locally** (in app‑private storage), not on any server we run:

- **Projects** — your timeline, edits, keyframes, text, and settings, auto‑saved so work is
  not lost and restored on next launch.
- **Media references** — links (URIs) to the video/audio/image files you import. The app keeps
  read access to those files; it does not copy or upload them.
- **Settings** — including AI provider choice, selected models, prompt history, and a crash‑
  relay URL (if you set one).
- **API keys** — on Android, encrypted at rest with Jetpack Security
  (`EncryptedSharedPreferences`, backed by the Android Keystore). On desktop, encrypted in a local
  keystore file whose random password is stored **beside it** in the app’s data folder with
  owner‑only file permissions — this protects against other users on the machine, not against
  software running as you. It is not an OS keychain.
- **User‑defined editing tools** — named step‑by‑step editing methods you create or record,
  stored as plain JSON in app‑private storage. These contain only the tool names and parameters
  (e.g. "split at 3000 ms") — no media data.
- **Settings backup files** — when you export your AI configuration, the app writes a JSON file
  to a location you choose via the system file picker. API keys are **left out** of that file
  unless you explicitly opt in to including them when exporting; if you do, treat the file like a
  password export.

Uninstalling the app removes all app‑private local data (projects, settings, keys, and
user‑defined tools). Backup files you saved outside the app remain where you put them.

**Android backup.** If Google backup is on for your device, Android may back up the app’s data to
your Google account, including your project file. The app excludes downloaded models, extensions,
crash files, face‑recognition concepts (face embeddings), and the encrypted settings (API keys,
MCP token, relay key) from backup and device transfer.

## Third‑party AI services (only when you use them)

Guillotine lets you use external AI services, mostly by bringing your own API key. **When, and
only when, you run one of these actions, the relevant content is sent directly from your device
to the service named** — it does not pass through any other server:

- **AI assistant / editing control** — when you use a **cloud** AI (Google Gemini, OpenAI,
  Anthropic, OpenRouter, Groq, xAI, or Mistral) as the assistant, your instructions and the
  project/timeline state go to the provider, and it replies with tool calls that drive the
  editor. By default it receives **text only**; frame analysis and background removal run on
  your device. The on‑device LLM keeps even that text local.
- **Let cloud AI see frames (opt‑in, off by default)** — when on, the assistant can send the
  **current frame at the playhead, as a JPEG image**, to the cloud AI provider you chose. Turn it
  off in Settings to keep the assistant text‑only.
- **Generative object removal** — the object is detected and masked **on‑device**; the masked
  frame(s) + mask are sent to **Leonardo.ai** (with your key) to be repainted.
- **Video generation (free, default)** — your **text prompt** (plus a fixed safety negative
  prompt, the clip length, and a seed) is sent to the developer’s Hugging Face Space
  (`hereliesaz-guillotine-t2v.hf.space`). The Space code does not log or store prompts; the
  generated clip is written to temporary storage and deleted after about 15 minutes. Hugging Face
  hosts the Space and its own [privacy policy](https://huggingface.co/privacy) applies to the
  request.
- **Video / music generation (BYO key)** — only your text prompt is sent to the provider you
  chose. Guillotine does not generate images.
- **Transcription / captions** — handled **on‑device** if you install a local speech model;
  otherwise audio is sent to OpenAI Whisper using your key.

Your use of these services is governed by **their** privacy policies and terms. The
on‑device options (ML Kit vision, local speech models, the local heuristic analyzer) never
transmit your media off the device. Representative provider policies:
Google ([Gemini](https://ai.google.dev/gemini-api/terms)),
[OpenAI](https://openai.com/policies/privacy-policy),
[Anthropic](https://www.anthropic.com/legal/privacy),
[OpenRouter](https://openrouter.ai/privacy),
[Groq](https://groq.com/privacy-policy/),
[xAI](https://x.ai/legal/privacy-policy),
[Mistral](https://mistral.ai/terms/),
[Leonardo.ai](https://leonardo.ai/privacy-policy/),
[Hugging Face](https://huggingface.co/privacy).

## Crash reporting (optional, off by default)

Crash reporting is **disabled unless you enter a crash‑relay URL** in Settings. If you enable
it, then after a crash the app captures a report and sends it — on the next launch — to **the
relay endpoint you configured** (which you host), which files it as an issue in your project’s
issue tracker. A report contains:

- the exception type and stack trace,
- device model, Android version, and app version,
- a short slice of the app’s own recent log output (logcat).

These technical logs could incidentally include text relevant to the crash. Reports go only to
the endpoint **you** set up; if you never set a relay URL, nothing is sent. The credential used
to file issues lives in your relay’s server‑side secret and is **never** included in the app.
No relay is configured by default — the app ships without one. If you use the reference relay
(`tools/crash-relay`), it files reports to a **public** issue tracker and applies basic redaction
first (email addresses, common token formats, and `content://` / file paths), but that redaction is
best-effort.

## Automation interface (MCP) and optional cloud relay

For power users and AI tooling, Guillotine runs a small **local automation server** (the Model
Context Protocol, MCP) while the editor is open. It lets an external AI tool read and edit the
**current project** — list clips, set prompts, run analysis, apply edits. It carries your
project/editing data, **not** your media files, and:

- access requires a **bearer token** shown in Settings — a request without it is rejected;
- it does not send anything on its own — it only responds to a tool **you** connect.

Optionally, you can enable an **encrypted cloud relay** so a tool can reach the editor without
being on the same network. When enabled, the app opens an outbound, encrypted connection to a
**Cloudflare Worker that you deploy and configure**. Messages are **end-to-end encrypted** with a
key derived from your MCP token, so the relay only ever passes **ciphertext** between your tool
and the app and cannot read your editing traffic. This relay is **off by default**; if you never
enable it, the app makes no such connection.

## Permissions

- **Internet** — to call the AI and generation services above, the crash relay you configure,
  and (if you enable it) the MCP cloud relay you configure.
- **Storage access (Storage Access Framework)** — only the specific media files you pick are
  accessible to the app; it does not scan your library.
- **Media output (MediaStore)** — to save exported videos to your Movies folder.
- **Notifications + foreground service (`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `WAKE_LOCK`)** — to keep a long operation (analysis,
  generative removal, or export) running with a progress notification while the app is in the
  background. These are purely local; nothing is transmitted.

- **Microphone (`RECORD_AUDIO`)** — used only for voice-command dictation. Guillotine first tries
  the configured offline speech model. If that engine cannot run, the app may use Android's installed
  **system speech-recognition service**; whether that service processes speech locally or over the
  network depends on the device and recognition provider. If system recognition also fails and you
  have configured an **OpenAI API key**, Guillotine may upload that command recording to OpenAI
  Whisper as the final speech-to-text fallback. The UI announces each fallback before it is used.
  Guillotine does not keep the recording after transcription; only the resulting text is placed in
  the prompt/project data. Without a configured cloud key, Guillotine itself does not upload the
  command recording.

The app does not request location, contacts, or the camera; it only works with media you
explicitly import.

## Analytics and tracking

Guillotine contains **no analytics, no ad SDK, no advertising ID, and no trackers**, and the
developer does not build a profile of you.

## Children’s privacy

Guillotine is a general‑purpose creative tool and is not directed at children under 13. It does
not knowingly collect personal information from children.

## Data retention and deletion

- Local data persists until you delete a project, clear the app’s storage, or uninstall.
- To stop using a third‑party provider, remove its API key in Settings.
- To stop crash reporting, clear the crash‑relay URL in Settings.
- Content already sent to a third‑party provider is subject to that provider’s retention policy.

## Changes to this policy

If this policy changes, the “Effective date” above will be updated and the revised version will
be published in the app’s repository.

## Contact

Questions about this policy: open an issue at
<https://github.com/HereLiesAz/Guillotine/issues> or email **hereliesaz@gmail.com**.
