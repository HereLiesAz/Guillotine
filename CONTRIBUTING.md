# Contributing to Guillotine

Thanks for wanting to help. Guillotine is a private, on-device-first, open-source video editor, and
it gets better because people like you file bugs, sharpen the docs, and send patches. This guide
covers how to do that smoothly.

Please also read the short [Governance & Charter](GOVERNANCE.md) — it explains the project's values
and how decisions get made — and the [Open-Source Open-Mind covenant](docs/OPEN-SOURCE-OPEN-MIND.md).

## The one legal thing: the CLA

Before your first code contribution can be merged, you agree to the
[Contributor License Agreement](CLA.md). In plain terms: your code ships to everyone under the
AGPL-3.0 like the rest of the project, you keep your copyright, and you also grant the maintainer a
license broad enough to keep the open-core/dual-license path open (why this matters is spelled out in
the CLA and in [`docs/strategy/FINANCIAL.md`](docs/strategy/FINANCIAL.md)).

**How to agree — just sign off your commits:**

```
git commit -s -m "your message"
```

That adds a `Signed-off-by:` line, which certifies the
[Developer Certificate of Origin](https://developercertificate.org/) and, for this project, your
acceptance of the CLA. That's it — no forms.

## Ground rules that make a PR easy to accept

The project has a few hard invariants. A change that breaks one of these will be sent back no matter
how good it otherwise is:

1. **On-device by default.** Frame and audio analysis runs on the device. Cloud AIs are *controllers
   only* — they drive the editor as text and never receive the user's clips or frames. Any new cloud
   path must be **opt-in** and exchange the minimum (text, not media).
2. **No faked capabilities.** If something can't run on-device yet, it's an honest stub that says so —
   never a silent cloud call pretending to be local, never a mocked result. A stub is fine; a lie is
   not.
3. **No dark patterns, no silent data collection.** No telemetry without clear, opt-in consent.
4. **Keep cross-platform parity in mind.** The editor core lives in `:shared` (pure JVM). The Android
   (`:app`) and desktop (`:desktop`) shells each implement the platform seam. If you add a tool or a
   feature to one platform, note in your PR what the other platform's story is (wired, stubbed, or
   follow-up).

## Project layout (Kotlin Multiplatform)

- **`:shared`** — the pure-JVM editor core: timeline model, keyframes, beat/timeline logic, AI
  types, media parsers. Platform-agnostic; this is where shared logic belongs.
- **`:app`** — the Android shell (Jetpack Compose, Media3/ExoPlayer/Transformer, on-device ML via
  ML Kit / TFLite / Vosk). The MCP tool surface lives in `app/.../mcp/McpTools.kt` (66 tools).
- **`:desktop`** — the Compose Desktop shell (JavaCV/FFmpeg, on-device ML via ONNX Runtime for JVM).
  The parallel tool surface is `desktop/.../platform/DesktopMcpTools.kt`.

There is no `expect`/`actual`: the cross-platform seam is an interface implemented twice. When you
touch the tool surface, use the Android impl as the behavior spec for the desktop one and vice versa.

## Building & testing

- **Shared logic (fastest, most reliable check):**
  ```
  ./gradlew :shared:compileKotlin
  ./gradlew :shared:test
  ```
  Use the Gradle **wrapper** (`./gradlew`), not a system Gradle.
- **Android:** requires the Android SDK. `./gradlew :app:assembleDebug`.
- **Desktop:** `./gradlew :desktop:packageDistributionForCurrentOS` (CI packages `.deb`/`.dmg`/`.msi`
  across Ubuntu/macOS/Windows via `.github/workflows/desktop-build.yml` — that workflow is the
  authoritative cross-platform compile check).

If you add or move pure-Kotlin logic into `:shared`, add unit tests for it.

## Sending a change

1. **Open an issue first for anything substantial** so the approach can be discussed before you
   invest time. Small fixes (typos, obvious bugs) can go straight to a PR.
2. **Branch** from the default branch.
3. **Keep commits focused** and messages descriptive; **sign off** (`-s`, see above).
4. **Match the surrounding code** — the existing naming, formatting, and idioms. Don't reformat
   unrelated code in the same PR.
5. **Open a pull request** describing *what* changed and *why*. If it touches the tool surface,
   note the cross-platform status. If it's a bigger direction change, tag it `rfc` and lay out the
   reasoning (see [GOVERNANCE.md](GOVERNANCE.md)).
6. CI must pass. Reviews are done in the open.

## Reporting bugs & requesting features

- **Bugs:** open an issue with steps to reproduce, what you expected, what happened, and your
  platform/version. Logs or a short screen capture help a lot.
- **Features:** open an issue describing the problem you're trying to solve, not just the solution
  you have in mind — it helps find the best fit for the roadmap.

## The name

The code is AGPL and yours to fork. The **name "Guillotine" and the logo are reserved** (see
[`NOTICE`](NOTICE) §7(e) and [GOVERNANCE.md](GOVERNANCE.md)) — a fork may take the code but ships
under its own name. Contributing here doesn't change that; it's what keeps the brand a trustworthy
signal.

Welcome aboard.
