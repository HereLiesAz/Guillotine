# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

## Export fidelity — remaining gaps (need on-device verification)
These were deliberately left after the effects pass because they depend on Media3's
`presentationTimeUs` semantics for sequenced + clipped items, which can't be verified without a
device — implementing them blind risks regressing currently-working behavior.
- **Keyframed (time-varying) opacity/scale/volume** are not baked into the export — only each
  clip's static transform/volume is. Needs time-varying `RgbMatrix`/`MatrixTransformation` (and a
  time-varying audio gain) with the keyframe→output-time mapping verified on device.
- **Caption/matte overlays** are attached to the first base item and timed linearly, so they can
  drift / disappear once AI 'remove' ranges are physically cut. Needs a cut-aware output-time→
  timeline-time map and per-item overlay attachment (`Exporter.buildComposition`,
  `CaptionOverlay`, `MatteOverlay`).
- **Preview parity for audio**: pan and peak-normalize are applied on export only. The preview
  uses a single ExoPlayer volume float, so it doesn't render pan/normalize (would need a custom
  `AudioProcessor` pipeline on the players).
- **Perf**: matte segmentation runs synchronously inside the Media3 frame callback
  (`MatteOverlay`); precompute mattes off-thread. Waveform/codec decode loops have no cooperative
  cancellation (`MediaPreview`).
- **TimelineMath test coverage**: `topActiveClip`, `activeClips`, multi-keyframe `valueAt`, and
  overlapping-remove `keptRanges` are exercised in production but not unit-tested.

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
