# Guillotine

An AI-powered, on-device **non-linear video editor for Android** (also a first-class
large-screen / Chromebook app).

Built with **Kotlin + Jetpack Compose** (Material 3 Expressive) and **Jetpack Media3**
(`ExoPlayer` for playback, `Transformer` for real on-device editing and mp4 export). AI analysis
uses **your own API keys** (bring-your-own; stored encrypted on-device), with a **free, no-key
on-device fallback** so the app is usable with zero configuration.

> Guillotine began as a web prototype (Vite + React + Express). That code has been removed; the
> product is the native Android app under `app/`. The brand assets remain in `assets/`.

## Features

- Multi-track timeline: import video/audio/images (SAF), trim, split, drag (incl. across tracks),
  zoom (pinch + Ctrl-scroll), keyframes with a bezier curve editor.
- Preview via ExoPlayer, slaved to the timeline; applies filters/keyframes and skips AI-removed ranges.
- AI keep/remove analysis: **Gemini** (video-native), **OpenAI** (frame sampling + Whisper),
  **Anthropic** (frame sampling), and a free **Local** silence detector. BYO keys, encrypted on-device.
- Real **mp4 export** via Media3 Transformer (cuts removes, applies effects + crop/aspect, mixes audio),
  saved to the gallery.
- Adaptive layout + keyboard shortcuts + mouse for phone and Chromebook/desktop.

## Building

See [BUILDING.md](BUILDING.md). Short version: open the repo in **Android Studio** (copied out of
any cloud-synced folder), let it sync, and run on a device/emulator (Android 8.0 / API 26+).

Unit tests for the timeline math run without a device: `gradlew test`.

> First-sync note: the AGP / Kotlin / Compose BOM / Gradle versions in
> `gradle/libs.versions.toml` are set for offline authoring. If Android Studio's AGP Upgrade
> Assistant proposes adjusted versions, accept them — that's the source of truth for a green build.

## License

See [LICENSE](LICENSE).
