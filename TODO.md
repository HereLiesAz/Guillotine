# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

## Frame-analysis cache for reference-mode analyze
The standard `analyze` path now caches per-frame ObjectVision labels and ImageLabeler results in
`FrameAnalysisCache`, so rescanning the same clip with a different prompt reuses last time's work.
`analyzeWithReference` (used by `analyze_clip_with_reference`) still recomputes per frame because
its match compares each candidate detection's embedding against a runtime reference — the result
isn't a function of the frame alone. Cache the detection LIST (Detections including boxes/scores)
per (uri, atMs) so at least the ObjectVision.detect call is skipped on repeat scans; the embedding
step stays uncached.

## Multi-track audio in the exporter
Preview now plays parallel audio tracks (music + voiceover + effects mix through Android's audio
layer, one ExoPlayer per audio track). The exporter still builds a **single** `audioSeq` and sorts
audio clips by start time, so two overlapping audio clips on different tracks will clash / one will
win instead of mixing. Fix: build one `EditedMediaItemSequence` per audio track and pass all of
them to the Transformer (`Composition.Builder(sequences)` mixes them). Match the per-track wiring
in `AudioTrackLayer` (volume × keyframed volume × normalize × pan) so export matches preview.

## Audit follow-ups (deferred — need a device or a design call)
Surfaced by a codebase audit; the clear, safe bugs were fixed. These were left because they need
on-device verification, are perf-only churn on the render thread, or are design decisions:
- **Export: background-removed video clips contribute no audio** (`Exporter.kt` — foreground/bg-removed
  clips are added only as the matte overlay, never as sequence items, and aren't in `audioClips`).
  Confirm whether a bg-removed clip's own audio should still export, then include it if so.
- **Keyframed opacity / export crossfade via `RgbMatrix[15]`** (`VideoEffects.kt` `FadeInAlpha`/
  `KeyframeAlpha`): verify on-device that writing only the alpha term actually changes output alpha —
  if not, opacity keyframes and dissolves are silent no-ops.
- **Render-thread allocation churn**: `KeyframeAlpha` re-filters keyframes every frame (siblings
  pre-sort); `CaptionOverlay.getOverlaySettings` rebuilds `StaticOverlaySettings` per frame
  (`MatteOverlay` caches); `MatteOverlay` blank 1×1 bitmap never recycled. Pre-compute like the
  sibling classes once the compositor is being tuned on-device.
- **`remove_object_generative` reloads the EfficientDet model per segment** (`McpTools.kt`): hoist the
  `ObjectVision` instance out of the per-segment loop.
- **Minor robustness**: `McpDispatcher` catches `Exception` not `Throwable` (native `Error`s escape the
  "never throws" contract); `McpRelayClient.stop()` shuts down the shared OkHttp executor (safe only if
  the client is always recreated); `OperationController.start()` busy-check is not atomic;
  `Transcription.whisper` leaks the `HttpURLConnection` on the null-input path; `SubjectSegmenter` doesn't
  recycle the source frame when sizes match; `KeyframeVolumeProcessor` doesn't override `onFlush` (seek
  desync); `PreviewAudio` pans >2-channel audio as if stereo; `MediaImport` imports an unreadable file as
  a 0-duration VIDEO; waveform cache key omits `buckets`.

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

## Windows & Linux desktop builds (Compose Multiplatform)
Ship native desktop apps reusing the existing Kotlin/Compose code.

- Restructure to KMP/CMP: `commonMain` (UI + domain), `androidMain`, `desktopMain`.
- Reusable as-is: Compose UI, `EditorViewModel`/StateFlow, `Document` model + serialization,
  autosave, prompt history, timeline math/snapping, and all cloud AI (Leonardo/OpenAI/etc.
  + `ModelCatalog` — they use `java.net`, which is pure JVM).
- Needs desktop `expect`/`actual` implementations (Android keeps its current code):
  - Preview playback: ExoPlayer → VLCJ or JavaCV/FFmpeg
  - Export/encode: Media3 Transformer → FFmpeg (JavaCV/bytedeco bundles native libs)
  - Thumbnails / waveforms / metadata: MediaMetadataRetriever/MediaExtractor → FFmpeg/JavaCV
  - On-device vision + background removal: ML Kit → ONNX Runtime, or omit on desktop (cloud BYO still works)
  - Speech-to-text: Vosk (already has desktop JVM builds)
  - File pick/save: SAF + MediaStore → java file dialogs + `java.io.File`
  - Secret storage: EncryptedSharedPreferences → java prefs / OS keystore
- Packaging: Compose Desktop `nativeDistributions { targetFormats(Msi, Exe, Deb, AppImage) }`;
  build on `windows-latest` / `ubuntu-latest` in CI next to the APK.
- Caveats: the media engine is ~80% of the effort; Expressive components (material3 1.5.0-alpha)
  may lag in Compose Multiplatform → desktop may need a non-Expressive fallback theme.
