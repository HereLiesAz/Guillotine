# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

## Export fidelity — implemented; verify on device
The previously-deferred export/preview gaps are now implemented (assume `presentationTimeUs` is
item-relative/0-based per Media3 item) and need an on-device pass to confirm:
- **Keyframed opacity/scale** baked into export via time-varying `RgbMatrix`/`MatrixTransformation`,
  **keyframed volume** via `KeyframeVolumeProcessor` (clip-local time = `rangeStart - trimStart + pts`).
- **Caption/matte overlays** attached to every base item with that item's timeline start, so they
  stay in sync across 'remove' cuts.
- **Preview audio parity**: pan + peak-normalize (with boost) via `LiveAudioProcessor` on the
  preview ExoPlayers (mono pan is export-only).
- **Matte precompute**: segmentation runs off-thread up front (`Exporter.precomputeMattes`), not per
  render frame.

Things to eyeball on device: opacity-via-alpha compositing, scale/translate centering, audio
gain/pan levels, and overlay timing after cuts.

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
