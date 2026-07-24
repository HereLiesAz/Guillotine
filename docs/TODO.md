# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

## Full codebase + azphalt audit (2026-07-24)

A repo-wide audit (Guillotine `shared/`, `app/`, `desktop/`, tests/CI, plus the sibling
`HereLiesAz/azphalt` spec/runtime repo). Two stale items below were corrected; everything else in
this section is newly surfaced. Not yet triaged into "confirmed"/"deferred" — read each item on
its own merits.

**Security**
- **Azphalt Store install path skips trust verification for non-model packages.**
  `AzphaltStoreState.install()` (`shared/.../azphalt/AzphaltStoreState.kt:112-136`, used by
  `AzphaltStoreScreen.kt` for shaders/LUTs/UI-schema/`code` packages) only calls `AzpPackage.load`
  (integrity) — it never calls `AzpPackage.verifyTrust` or consults `AzpPublisherPins`, unlike
  `AzpModelInstall.install()` which does full trust-store + trust-on-first-use publisher pinning.
  A compromised registry (or MITM) could push an update signed by a different key than the one the
  user originally trusted, and it installs with only a soft snackbar note — exactly the hijack
  `AzpPublisherPins` exists to stop, but only wired for the model-install path.
- **Desktop self-updater has no integrity check before running the downloaded installer.**
  `UpdateChecker.download()` (`shared/.../update/UpdateChecker.kt:128-154`) and
  `DesktopUpdater.launchInstaller()` (`desktop/.../platform/DesktopUpdater.kt:73-80`) stream the
  GitHub Release asset to disk and hand it straight to `Desktop.open()` — no checksum or signature
  check. Combined with installers shipping unsigned (see Desktop follow-ups below), a compromised
  release asset or CDN edge would run unverified code. At minimum, verify a published SHA-256
  before launch.
- **CI grants broad write perms to no-op workflows.** `.github/workflows/jules-agent.yml` and
  `jules-auto-merge.yml` are literal stubs (`run: echo "Jules invocation placeholder..."`) but
  request `contents: write`, `issues: write`, `pull-requests: write` and trigger on real repo
  events — unused attack surface.

**Bugs**
- ~~**CI's `unit-tests` job was broken on `main`, not just here.**~~ **Fixed in this PR.**
  `merged-build.yml` ran `testGithubDebugUnitTest`, a task name from a product-flavor split
  (`github`/`play`) that was later removed from `app/build.gradle.kts` (only `debug`/`release`
  build types remain) — the task never existed post-removal, so every CI run on `main` since has
  failed red. Corrected to `testDebugUnitTest`.
- **Cancelling a background operation leaves the UI stuck.** `OperationController.kt:119-131`:
  the `CancellationException` catch block is empty and never calls `onComplete()`/`onError()`, but
  every caller only clears its "busy" flag inside those callbacks. Cancelling analysis
  (`NleScreen.kt:351/357-368`) leaves the "Analyzing…" state forever; cancelling export
  (`NleScreen.kt:763/767-798`) leaves `exporting = true` forever, and the export sheet becomes
  **undismissable** since `onDismiss` is gated on `!exporting`.
- **Desktop `DesktopSegmenter` silently no-ops on inference failure**, contradicting the
  "fails loudly, never fakes" invariant: `matte()`/`portraitBlur()`
  (`desktop/.../media/DesktopSegmenter.kt:23-24,29`) wrap inference in
  `runCatching { … }.getOrDefault(img)`, so `replace_background`/`apply_bokeh` silently render the
  un-matted/un-blurred frame with no error if the model file is later missing or corrupt (model
  *path* is validated at tool-call time, but not at every render).
- **`transcribe_precise` has a different contract on desktop than Android for the same tool
  name.** Android returns `{"text": …}` and is read-only; desktop (`DesktopMcpTools.kt:804`)
  silently dispatches to the timeline-mutating `transcribe_clip` path instead and returns
  `{"captions", "clipCount"}` — no `text` field. The tool's own schema description also claims
  desktop returns an error pointing at `transcribe_clip`, which isn't what actually happens.
- **`GlslToSksl.kt` drops alpha on `.rgb`-only shaders (desktop only).** `rewriteMain()`
  (`shared/.../media/GlslToSksl.kt:151-154`) initializes `_fragColor` with alpha `0.0`; a GLSL
  shader that only writes `gl_FragColor.rgb` (a common ISF idiom the surrounding comment says is
  supported) renders/exports fully transparent on desktop. No test fixture exercises an `.rgb`-only
  shader, so `GlslToSkslTest.kt` doesn't catch it.
- **`extensions.yml` has a YAML indentation bug**: `with: node-version: 22`
  (`.github/workflows/extensions.yml:25-30`) is nested under `actions/checkout@v7` instead of the
  `actions/setup-node@v7` step above it — `setup-node` gets no version pin.
- Desktop JavaCV resource leaks: `grabber.start()` / `filter.start()` / `recorder.start()` calls in
  `DesktopExporter.kt` (`exportAudio`, ~:264-268), `DesktopMediaDecoder.kt`, and
  `DesktopFfmpegFilter.apply()` (`:31-56`) sit **before** their `try/finally` release block, so a
  failure on a corrupt/unsupported user-imported file leaks the native grabber/filter/recorder.
  `DesktopFfmpegFilter.apply()` is worst — three `.start()` calls outside the guard.

**Incomplete / mislabeled features**
- **`AI_ROADMAP.md`'s "desktop is at 66/66 functional tools" is inaccurate.**
  `analyze_clip_with_reference` and `denoise_clip` are unconditional stubs
  (`DesktopMcpTools.kt:762-763,791-792` → `visionToolUnavailable()`) regardless of model config.
  `denoise_clip` is the more notable gap: a model slot already resolves
  (`ModelResolver.kt:40` → `denoiseModelPath`/`gtcrn_simple.onnx`) but the handler never calls it —
  wired plumbing, unwired tool.
- **Several `DesktopMcpTools.kt` tool descriptions/comments are stale**, telling an MCP client a
  tool doesn't work when it does: `apply_shader` (`:267-273`, actually renders via
  `DesktopShaderPass`), `remove_fillers` (`:167-171`, actually works via Vosk), and "honest stub"
  comments at `:522, 568-569, 760, 784-785` covering `add_reference`, `blur_faces`,
  `replace_background`, `find_highlights`, `apply_bokeh`, `auto_reframe`, `search_clips`,
  `caption_frame`, `apply_image_effect`, `remove_object_generative` — all of which now have real
  implementations. An agent reading these descriptions may refuse working tools.
- **`TOOLS.md` undercounts the tool surface** — `list_azp_plugins`, `apply_azp_plugin`,
  `clear_azp_plugin` are real dispatchable tools (`McpTools.kt:792,799,810`,
  `DesktopMcpTools.kt:705-729`) missing from the documented list.
- Desktop-only stubs not yet in this doc: prompt-driven clip analysis ("keep shots with a face")
  and Leonardo image generation are both hard "not available on desktop" stubs in `NleScreen.kt`
  (~:269, ~:492-496), with no fallback.
- **Timeline edge-trim confirmed to have no snapping** (`Timeline.kt:843-874`) — the long-press
  trim handler commits raw pixel deltas directly, never calling `snappedDeltaMs`. Confirms the
  follow-up noted below under on-device verification.
- **Azphalt Store UI (in-app) has no in-app Pause/Resume control** — only the system notification's
  action buttons drive `OperationController`; no Compose UI observes its state, so a user who
  pauses from the shade sees no paused indicator on return to the app.
- Dead one-tap model download: the `LOWLIGHT` model URL in `OnDeviceModels.kt:475` (MIRNet via
  TF Hub) 404s — TensorFlow Hub has been effectively decommissioned. `apply_image_effect(lowlight)`
  can't be one-tap installed as documented.

**Corrected stale doc claims**
- ~~Caption background box missing from export~~ — **already fixed.** `CaptionOverlay.kt:41-44`
  sets a `BackgroundColorSpan` scrim matching preview, landed in the same commit that (incorrectly)
  added this backlog item. Struck below.
- ~~Desktop "Auto-update framework… currently users download by hand"~~ — **already shipped.**
  `DesktopUpdater.kt` + `UpdateChecker.kt` check GitHub Releases on launch and offer to
  download+run the installer, matching the README claim. The real remaining gap is the missing
  integrity check called out above under Security, not the absence of an updater. Corrected below.

**Process / test coverage gaps** (see also existing entries)
- `shared/.../mcp/` (dispatcher, protocol, crypto, relay client — 6 files) has zero tests; MCP tool
  dispatch is completely untested.
- `desktop/` has no test directory at all; `app/` has exactly one test file (`ClipPanelContributionsTest.kt`)
  — `Exporter.kt`, billing, and the AI tool layer (33 files) are all untested on Android too.
- No lint/static-analysis step in any CI workflow (no ktlint/detekt, no `./gradlew lint`).
- `material3 = "1.5.0-alpha24"` and `composeUi = "1.12.0-beta02"` are pre-release UI toolkit
  versions pinned in a shipping app.
- No `fastlane/metadata/.../changelogs/` directory and no root `CHANGELOG.md` — Play release notes
  aren't automated per version.

**azphalt (sibling repo, `HereLiesAz/azphalt`) — informational, not actionable here**
- Real, substantive implementation overall (not spec-only): a working QuickJS-in-WASM sandbox with
  capability gating + timeout/memory limits (`packages/runtime-wasm`), a real reference registry
  server + client, 18 working format importers, and a conformance suite that tests capability/
  never-list enforcement, not just `.azp` parsing. All packages are pre-1.0 (0.1.x–0.2.x), which
  the authors flag themselves.
- Weakest link is **registry-side content trust**: the `submissions/` PR-CI only checks package
  *structure*, not the `scanPackage` security sweep that runs at actual registry-publish time;
  payload static analysis (checking a code module's real imports against declared capabilities) is
  explicitly spec'd as "planned… not yet implemented" (`spec/marketplace-integrity.md:61-64`).
  Unsigned packages are accepted.
- Root `SECURITY.md` is an unfilled GitHub template (generic placeholder text, invented version
  numbers) — notable for a trust-and-safety-focused project.
- `packages/registry-store-vercel` is versioned `1.0.0` while every other package sits at
  0.1.x–0.2.x — likely unintentional inconsistency.
- Guillotine's own `AzpCodeRuntime` correctly does *not* fake code execution yet (returns
  `Unavailable`, never a fake `Ok`) — consistent with azphalt's WASM runtime existing but not yet
  being the thing blocking Guillotine; the integration, not the standard, is what's pending.

## Export parity follow-ups (confirmed by audit)
Surfaced by a codebase audit comparing the preview and export pipelines in detail. All filters,
all 12 keyframe properties, background removal, audio effects, multi-track compositing, and
crossfade are at full parity. The following gaps remain:
- **Caption text size differs:** preview uses `14.sp`; export uses `AbsoluteSizeSpan(64)`. The
  relative proportions won't match unless compensated.
- **Quality/FPS settings not wired into export:** `GlobalSettings.quality` and `.fps` exist in the
  model but `Transformer.Builder` never calls `setVideoFrameRate()` or any resolution/bitrate
  configuration — they have no effect on the output.
- **AI edit segments play through in preview:** removed ranges are correctly cut from the export
  (via `TimelineMath.keptRanges`) but play normally in preview (`syncPosition` does a simple linear
  seek). May be deliberate (show full source with proposed cuts highlighted).
- **Project-level crop not shown in preview:** the `Crop` from `GlobalSettings` is applied in export
  (`VideoEffects.geometry()`) but preview only applies aspect ratio. May be deliberate.

## Audit follow-ups (deferred — need a device or a design call)
Surfaced by a codebase audit. Everything that was a clear, safe bug has been fixed. The rest was
left because it needs on-device verification or is a design decision:
- **Export: background-removed video clips contribute no audio** (`Exporter.kt` — foreground/bg-removed
  clips are added only as the matte overlay, never as sequence items, and aren't in `audioClips`).
  Confirm whether a bg-removed clip's own audio should still export, then include it if so.
- **Keyframed opacity / export crossfade via `RgbMatrix[15]`** (`VideoEffects.kt` `FadeInAlpha`/
  `KeyframeAlpha`): verify on-device that writing only the alpha term actually changes output alpha —
  if not, opacity keyframes and dissolves are silent no-ops.

## Needs an on-device verification pass (built; untestable in CI)
Implemented but never run on a device — confirm and tune:
- **Multi-track compositor** (preview `PreviewPlayer` + export `Exporter`): one layer/sequence per
  video track, stacked bottom-to-top; per-track **crossfade** of overlapping clips; a background-
  removed clip on an upper track showing lower tracks through its matte (composition-level overlay).
  Verify leading-gap alignment, N-sequence compositing, and alpha-blend dissolve on Media3 1.10.1.
- **Background operations** (`operation/OperationController` + `OperationService`): foreground-service
  notification, Pause/Resume (analysis + generative), Cancel, and that work survives backgrounding.
- **Long-press edge trim** (`Timeline.kt`): gesture layering vs. move/keyframe handles; re-extend
  bounds; linked-audio sync. **Follow-up:** snap the trimmed edge to playhead/clips/grid.
- **3 fps sampling + ±5-frame extension** (`MlKitProvider.scanVideo`): cut tightness + speed.
- **Export fidelity** (keyframed opacity/scale via `RgbMatrix`/`MatrixTransformation`, keyframed
  volume via `KeyframeVolumeProcessor`, caption/matte overlay timing after cuts, audio gain/pan
  levels): eyeball compositing, centering, and overlay sync.

## Export follow-ups
- **Cross-process resume**: an OS kill currently drops an in-flight operation (by design). Persisting
  a checkpoint to resume analysis/generative after relaunch (and a resumable/segmented export) is open.
- **Pausable export**: Media3's `Transformer` can't pause an encode, so export is cancel-only.

## Desktop follow-ups (v1 ships unsigned, single-arch)

The desktop apps (`.dmg` / `.msi` / `.deb`) ship in every GitHub Release via the CI matrix in
`.github/workflows/release-desktop.yml`. Remaining polish:

- **Signing / notarization** — macOS Developer ID (Apple Developer account required), Windows
  code signing (CA certificate required). Without these, users see a "unknown developer" warning
  on first launch.
- **Universal macOS binary** — `macos-latest` gives us Apple Silicon; Intel Macs need a second
  runner (or `lipo`-ing two builds).
- **AppImage / Flatpak / Snap** — `.deb` covers the mainstream case; broader Linux coverage is
  open.
- ~~Auto-update framework~~ — **already shipped** (`DesktopUpdater.kt` + `UpdateChecker.kt` check
  GitHub Releases on launch and offer to download+run the installer). The real remaining gap: no
  checksum/signature verification of the downloaded installer before launching it — see the
  Security items in the 2026-07-24 audit section above.
- **On-device ML on desktop** — the ONNX-Runtime-for-JVM foundation has landed: stem separation
  (Spleeter), speech captions (Vosk), audio sync, and the color/LUT render all run on-device on
  desktop. The remaining on-device gap is the **vision / face / speech-model tools** (image
  labeling, face detect/segment, Whisper ASR, TTS, diarization, VLM captioning). Each needs a
  desktop ONNX model wired the same way as stems: a model path in Settings + an inference helper.
  `search_clips` is the first wired (ONNX ImageNet labeler); the rest return an honest "needs a
  model" stub until their model is bundled/pointed at. Cloud BYO still works for all.
