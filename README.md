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

- **Multi-track timeline (Vegas-style layers):** import video/audio/images (SAF); trim, split,
  drag across tracks; long-press to range-select; group/ungroup (grouped clips drag together).
  Pinch to zoom width *and* track height, scroll through tracks, tap anywhere to seek. Clips show
  on-device thumbnails (video/image) and waveforms (audio).
- **Keyframes:** animate opacity/scale/volume; the envelope is drawn on the clip (height = value).
  A keyframe tool drops keyframes; tap a keyframe to select + toggle ease; drag its bezier handles
  to shape easing; auto-ease on by default; full cubic-bezier curve editor in the inspector.
- **Crop / transform tool:** pinch to scale, drag to place, twist to rotate the selected clip
  directly on the preview (video, image, or text).
- **Text / captions:** text clips are transparent overlays on video tracks — edit content + font,
  size/place them with the crop tool.
- **AI keep/remove analysis** (BYO key, stored encrypted on-device; per-provider model id editable):
  **Gemini** (video-native), **OpenAI** (frames + Whisper), **Anthropic** (frames),
  **OpenRouter / Groq / xAI / Mistral** (frames, OpenAI-compatible), a free **on-device vision**
  analyzer (ML Kit face/object), and a free **Local** silence detector. Fast stride-and-block scan.
- **Background removal (on-device, ML Kit):** segment a clip's subject and composite it over the
  layer below — in the live preview and baked into the export.
- **Transcription → captions:** generate timed, grouped caption clips from speech — **on-device**
  (Vosk, BYO model) or cloud (OpenAI Whisper). Captions burn into the export.
- **Image generation:** free **Pollinations.ai** (no key) or **Leonardo.ai** (BYO key, model
  selectable).
- **Real mp4 export** (Media3 Transformer): cuts removed ranges, positions clips on the timeline,
  applies per-clip filters (brightness/contrast/saturation/hue/sepia/blur/grayscale/invert) and the
  crop-tool transform (scale/rotate/offset), project crop/aspect, composites the segmentation matte
  and caption overlays, bakes per-clip + track volume / pan / peak-normalize / mute / opacity, and
  saves to the gallery.
- **Automation (MCP):** while open, the app runs a small **token-gated MCP server** so external AI
  tools can drive the editor (read the timeline, set prompts, run analysis, apply edits). An
  optional **end-to-end-encrypted Cloudflare relay** (see [`tools/mcp-relay`](tools/mcp-relay))
  reaches it from anywhere without port-forwarding.
- **Whole-track controls** from each track header: mute, disable/hide, volume, opacity, add clip.
- **Adaptive UI:** phone / tablet / Chromebook layouts, keyboard shortcuts, mouse + Ctrl-scroll
  zoom; Material 3 Expressive, dark with a red accent.


## License

See [LICENSE](LICENSE).
