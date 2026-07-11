# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

## Export parity follow-ups (confirmed by audit)
Surfaced by a codebase audit comparing the preview and export pipelines in detail. All filters,
all 12 keyframe properties, background removal, audio effects, multi-track compositing, and
crossfade are at full parity. The following gaps remain:
- **Caption background box missing from export:** preview renders a semi-transparent black pill
  behind text captions (`Modifier.background(…)` in `PreviewPlayer.kt`); export renders bare white
  text only (`CaptionOverlay.kt` uses `ForegroundColorSpan` + `AbsoluteSizeSpan` with no background
  shape). Exported captions may be unreadable over light/busy backgrounds.
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
- **Auto-update framework** — currently users download new releases from GitHub Releases by hand.
- **On-device ML on desktop** — the ONNX-Runtime-for-JVM foundation has landed: stem separation
  (Spleeter), speech captions (Vosk), audio sync, and the color/LUT render all run on-device on
  desktop. The remaining on-device gap is the **vision / face / speech-model tools** (image
  labeling, face detect/segment, Whisper ASR, TTS, diarization, VLM captioning). Each needs a
  desktop ONNX model wired the same way as stems: a model path in Settings + an inference helper.
  `search_clips` is the first wired (ONNX ImageNet labeler); the rest return an honest "needs a
  model" stub until their model is bundled/pointed at. Cloud BYO still works for all.
