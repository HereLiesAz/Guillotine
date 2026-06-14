# Guillotine

An AI-powered, on-device **non-linear video editor for Android**.

Built with **Kotlin + Jetpack Compose** and **Jetpack Media3** (`ExoPlayer` for playback,
`Transformer` for real on-device editing and mp4 export). AI analysis uses **your own API keys**
(bring-your-own), with **free, no-key fallbacks** so the app is usable with zero configuration.

> **Note:** Guillotine began as a web prototype (Vite + React + Express). Those files
> (`src/`, `server.ts`, `package.json`, …) are now **legacy reference only** and are being
> removed as the native app reaches parity. The product is the Android app under `app/`.

## Status

Active rewrite. See the in-repo task list / `plans/` for progress. Current foundation:
Gradle + Compose + Media3 project that builds to a runnable app shell.

## Building

See [BUILDING.md](BUILDING.md). Short version: open the repo in **Android Studio** (Koala or
newer), let it sync, and run on a device/emulator (Android 8.0 / API 26+).

## License

See [LICENSE](LICENSE).
