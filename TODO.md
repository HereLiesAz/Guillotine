# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

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
