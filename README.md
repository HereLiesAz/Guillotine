# Guillotine

An AI-powered, on-device **non-linear video editor for Android** (also a first-class
large-screen / Chromebook app).

Built with **Kotlin + Jetpack Compose** (Material 3 Expressive) and **Jetpack Media3**
(`ExoPlayer` for playback, `Transformer` for real on-device editing and mp4 export).

**Your video never leaves the device.** All frame/audio analysis runs **on-device**. Cloud AIs
(Gemini/OpenAI/Anthropic/…) are *controllers only*: they drive the editor as text over the
in-app MCP server (read the timeline, set prompts, run the on-device analysis, apply edits) and
never receive your clips or frames. Cloud keys are bring-your-own, stored encrypted on-device,
and there's a free, no-key on-device path (vision + an optional on-device LLM brain) so the app
is fully usable with zero configuration.

> Guillotine began as a web prototype (Vite + React + Express). That code has been removed; the
> product is the native Android app under `app/`. The brand assets remain in `assets/`.

## Features

- **Multi-track timeline (Vegas-style layers):** import video/audio/images (SAF); split, drag
  across tracks; group/ungroup (grouped clips drag together). **Long-press a clip edge, then drag,
  to trim its in/out point** — a split clip re-extends back into its source the same way; its linked
  audio trims with it. Edge + grid snapping when placing clips, overlapping into a crossfade. Pinch
  to zoom width *and* track height, scroll through tracks, tap anywhere to seek. Clips show on-device
  thumbnails (video/image) and waveforms (audio).
- **Multi-track compositor:** the preview renders **one layer per video track**, stacked bottom-to-top,
  and **crossfades** a track's overlapping clips. A background-removed clip on an upper track shows the
  lower tracks through its matte. The exporter mirrors this (per-track sequences + crossfade + matte).
- **Keyframes:** animate **12 properties** — opacity, scale, rotation, offset X/Y, brightness,
  contrast, saturation, hue, sepia, volume, and pan — with per-keyframe cubic-bezier easing. The
  envelope is drawn on the clip (height = value). A keyframe tool drops keyframes; tap a keyframe to
  select + toggle ease; drag its bezier handles to shape easing; auto-ease on by default; full
  cubic-bezier curve editor in the inspector.
- **Crop / transform tool:** pinch to scale, drag to place, twist to rotate the selected clip
  directly on the preview (video, image, or text).
- **Text / captions:** text clips are transparent overlays on video tracks — edit content + font,
  size/place them with the crop tool.
- **Transcription → captions:** generate timed, grouped caption clips from speech — **on-device**
  (Vosk, BYO model) or cloud (OpenAI Whisper). Captions burn into the export.
- **Animated per-syllable captions (kinetic typography):** each word is split into syllables on
  separate tracks with scale keyframes that grow each syllable as it's spoken — a "grow as said"
  effect. Ask the AI for "animated captions" or "kinetic text."
- **In-app AI assistant:** a minimal command bar where you type what you want and an agent drives the
  editor through the MCP tools. Pick any capable provider (Anthropic / OpenAI-compatible / Gemini) or
  the **on-device LLM brain** (MediaPipe LLM Inference, BYO model) — all of them are *controllers*
  that only exchange text; the actual analysis runs on-device.
- **On-device vision (no video upload):** free **ML Kit + MediaPipe** — **EfficientDet-Lite2** object
  detection, face detection, and scene classification (~1000 ImageNet categories) — turns a prompt
  like "cut every frame with my phone" into split/deleted clips. Frames are **sampled at 3 fps** and
  a match extends ±5 frames, so scans are cheap. Point it at a scrubbed frame ("this is *my* phone")
  to track that specific instance via image-embedding similarity. **Describe current frame** gives the
  AI a vision readout of whatever is on screen. A free **Local** silence detector handles audio.
- **User-defined editing tools:** teach the AI named editing methods — "save this as X" or "create a
  tool called X that does Y" — then invoke them on any clip with "do X on this clip." The AI follows
  the saved step-by-step instructions using the editor's built-in tools.
- **Action recorder:** tell the AI "record what I do," then edit a clip by hand (split, trim, delete,
  keyframe, filter changes…); every action is captured. Say "save that as X" to turn the recorded
  steps into a reusable user-defined tool, with optional written caveats for generalization (e.g.
  "adapt timings to clip length").
- **Generative object removal:** detect + mask the object on-device, then repaint the masked frames
  via **Leonardo.ai** inpainting (BYO key); the result is a run of grouped split clips (some with
  generated frames) the same total length as the original.
- **Background operations:** analysis, generative removal, and export run in a **foreground service**
  with an **ongoing progress notification** — keep working while the app is backgrounded.
  **Pause/Resume** (analysis + generative) and **Cancel** from the notification; export is cancel-only.
- **Background removal (on-device, ML Kit):** segment a clip's subject and composite it over the
  layer below — in the live preview and baked into the export.
- **Image generation:** free **Pollinations.ai** (no key) or **Leonardo.ai** (BYO key, model
  selectable).
- **Real mp4 export** (Media3 Transformer): cuts removed ranges, composites every video track,
  positions clips on the timeline, applies per-clip filters (brightness/contrast/saturation/hue/
  sepia/blur/grayscale/invert) and the crop-tool transform (scale/rotate/offset), project crop/aspect,
  the segmentation matte and caption overlays, bakes per-clip + track volume / pan / peak-normalize /
  mute / opacity, and saves to the gallery.
- **Settings backup & restore:** export all AI settings (provider, keys, models, speech/agent model
  paths, cache size) to a JSON file and import them back — handy for migrating to a new device or
  sharing a configuration.
- **Automation (MCP):** while open, the app runs a small **token-gated MCP server** so external AI
  tools (or the in-app assistant) can drive the editor. An optional **end-to-end-encrypted Cloudflare
  relay** (see [`tools/mcp-relay`](tools/mcp-relay)) makes it reachable from anywhere without
  port-forwarding.
- **Whole-track controls** from each track header: mute, disable/hide, volume, opacity, add clip.
- **Adaptive UI:** phone / tablet / Chromebook layouts, keyboard shortcuts, mouse + Ctrl-scroll
  zoom; Material 3 Expressive, dark with a red accent. A dropdown menu with an in-app **About** reader
  (the AzNavRail footer) surfaces this README and the privacy policy. Built-in **tutorial**, **FAQ**,
  and **icon key** (the **?** button) for self-contained help.


## License

See [LICENSE](LICENSE).
