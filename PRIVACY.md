# Privacy Policy — Guillotine

**Effective date:** 2026-06-17

Guillotine is an on-device, non-linear video editor for Android, tablets, and Chromebooks.
This policy explains what the app does and does not do with your data. In short: **Guillotine
has no servers of its own, no account system, and no first‑party analytics.** Your media and
projects stay on your device unless *you* choose to use a third‑party AI service. The app does
show ads via **Google AdMob** (see “Advertising” below), and it uses your device’s advertising
identifier for that purpose.

## The short version

- We (the developer) do **not** collect, store, or receive your data on any server we control.
- We **do** show ads through **Google AdMob**, which collects your device’s advertising ID and
  related data to serve and measure ads — this is the one third‑party SDK in the app.
- Editing, playback, thumbnails, waveforms, on‑device vision (ML Kit), and on‑device speech
  recognition (Vosk) all run **locally on your device**.
- The app only sends data over the network when **you** trigger an action that uses a
  third‑party service you configured (an AI provider, image generation, or crash reporting).
- API keys you enter are **encrypted on your device** and are sent only to the matching
  provider, only in requests you initiate.

## Information stored on your device

The app stores the following **locally** (in app‑private storage), not on any server we run:

- **Projects** — your timeline, edits, keyframes, text, and settings, auto‑saved so work is
  not lost and restored on next launch.
- **Media references** — links (URIs) to the video/audio/image files you import. The app keeps
  read access to those files; it does not copy or upload them.
- **Settings** — including AI provider choice, selected models, prompt history, and a crash‑
  relay URL (if you set one).
- **API keys** — encrypted at rest using Android’s Jetpack Security
  (`EncryptedSharedPreferences`, backed by the Android Keystore).

Uninstalling the app removes this local data.

## Third‑party AI services (only when you use them)

Guillotine lets you use external AI services by bringing your own API key. **When, and only
when, you run one of these actions, the relevant content is sent directly from your device to
the provider you selected** — it does not pass through any server we operate:

- **Analysis / editing suggestions** — your selected clip’s media (or sampled frames) and your
  prompt are sent to the provider you chose: Google Gemini, OpenAI, Anthropic, OpenRouter,
  Groq, xAI, or Mistral.
- **Image generation** — your text prompt is sent to **Leonardo.ai** (with your key) or, for
  the free no‑key option, to **Pollinations.ai**.
- **Transcription / captions** — handled **on‑device** with Vosk if you configure a local
  speech model; otherwise audio is sent to OpenAI Whisper using your key.

Your use of these services is governed by **their** privacy policies and terms. Free,
no‑key on‑device options (ML Kit vision, Vosk speech, the local heuristic analyzer) never
transmit your media off the device. Representative provider policies:
Google ([Gemini](https://ai.google.dev/gemini-api/terms)),
[OpenAI](https://openai.com/policies/privacy-policy),
[Anthropic](https://www.anthropic.com/legal/privacy),
[OpenRouter](https://openrouter.ai/privacy),
[Groq](https://groq.com/privacy-policy/),
[xAI](https://x.ai/legal/privacy-policy),
[Mistral](https://mistral.ai/terms/),
[Leonardo.ai](https://leonardo.ai/privacy-policy/),
[Pollinations.ai](https://pollinations.ai/).

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

## Advertising (Google AdMob)

Guillotine displays ads served by **Google AdMob** (currently an “app‑open” ad shown when you
bring the app to the foreground). To serve and measure these ads, the Google Mobile Ads SDK
collects your device’s **advertising identifier (AD_ID)** and related technical/usage data, as
described in Google’s policies. This data is processed by Google, not by us, and is governed by:

- [How Google uses information from sites or apps that use our services](https://policies.google.com/technologies/partner-sites)
- [Google AdMob & AdSense privacy](https://support.google.com/admob/answer/6128543)

Where required by law (e.g., the EEA/UK), a consent prompt governs whether personalized ads are
shown. You can also reset or limit ad personalization in your device’s Google settings
(*Settings → Google → Ads*).

## Permissions

- **Internet** — to call the AI providers, the ad service, and the crash relay you configure.
- **Advertising ID (`com.google.android.gms.permission.AD_ID`)** — used by Google AdMob to serve ads.
- **Storage access (Storage Access Framework)** — only the specific media files you pick are
  accessible to the app; it does not scan your library.
- **Media output (MediaStore)** — to save exported videos to your Movies folder.

The app does not request location, contacts, the microphone, or the camera; it only works with
media you explicitly import.

## Analytics and tracking

Guillotine contains **no first‑party analytics** and no trackers we operate, and we do not build
a profile of you. The only third‑party SDK is **Google AdMob** (see “Advertising” above); any
profiling for ads is performed by Google under its policies, not by us.

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
