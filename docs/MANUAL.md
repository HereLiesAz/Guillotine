# Guillotine user manual

The complete guide to editing in Guillotine — every screen, control, gesture, and
option, from importing your first clip to rendering the finished video.

Guillotine is an **on-device-first AI video editor**. The invariant that shapes the whole
app holds everywhere in this manual: **your video and audio never leave the device.** The
free path needs no key and no account; cloud AI is always bring-your-own-key and acts only
as a *text* controller — it never receives your footage.

New here? Start with the [Tutorial](TUTORIAL.md) for a guided first edit, then come back to
this manual for the full reference. Quick questions are answered in the [FAQ](FAQ.md).

---

## Contents

1. [Getting started](#1-getting-started)
2. [The editor at a glance](#2-the-editor-at-a-glance)
3. [Playback and the preview](#3-playback-and-the-preview)
4. [The timeline](#4-the-timeline)
5. [Clip tools and effects](#5-clip-tools-and-effects)
6. [Advanced looks](#6-advanced-looks)
7. [AI features](#7-ai-features)
8. [Exporting](#8-exporting)
9. [Settings](#9-settings)
10. [Platforms](#10-platforms)
11. [Keyboard shortcuts](#11-keyboard-shortcuts)
12. [Where to go next](#12-where-to-go-next)

---

## 1. Getting started

### Importing media

Open the menu (the app icon, top-left) and choose **Import**, or tap the **+** button in the
tool strip. Guillotine accepts **video, audio, and images** in one picker — pick several at
once. Each import lands on the timeline as a clip:

- A **video with sound** arrives as a video clip plus a linked audio "shadow" clip on an
  audio track. The two are grouped, so trimming or moving one moves the other.
- **Audio-only** files land on an audio track.
- **Images** land as clips with a default on-timeline duration of **5 seconds** (change the
  length by trimming the clip edges).

You can also aim an import at a specific track from that track's header popup (**Import
clip…**), or create a new empty text clip on a video track (**Add text clip** / **Create
clip…**). See [The timeline](#4-the-timeline) for track headers.

No footage yet? Use **Generate** to make an image with AI — see
[Generating media](#generating-media-image-video-music).

### The project model

Guillotine keeps **one active project** and saves it for you continuously. There is no "save"
step you can forget: every change is auto-saved to internal storage (debounced, and flushed
immediately when the app is backgrounded), and the project is restored automatically the next
time you open the app.

The menu gives you explicit project actions on top of the autosave:

| Menu item | What it does |
|-----------|--------------|
| **New** | Replaces the current project with an empty one. This overwrites the autosave, so you're asked to confirm — export anything you want to keep first. |
| **Open** | Loads a `.gilt` project file from disk into the editor. |
| **Save** | Exports the current project as a `.gilt` file to a location you choose. |
| **Rename** | Sets the project's display name (shown in the top bar). The project keeps auto-saving regardless — this only names it. |

A **`.gilt`** file is the Guillotine project format: a JSON description of your timeline
(clips, tracks, timing, filters, keyframes, and project settings). It references your source
media by location — it is a project, not a bundle of your video. Use **Save** / **Open** to
move a project between devices or keep versioned copies.

To back up your **AI configuration** (providers, keys, model paths) rather than a project,
use Settings → Advanced → Export/Import settings — see [Settings](#9-settings).

---

## 2. The editor at a glance

The editor is one adaptive screen. On phones (portrait) the panels stack; on tablets,
Chromebooks, and desktop (wide layouts) the preview and timeline sit side by side. From top
to bottom you have:

- **Top bar** — the menu trigger (app icon), the project name, and a row of quick actions:
  **Fit all**, **Zoom out**, **Zoom in**, **Undo**, **Redo**, and **Help**.
- **Preview** — the video canvas, showing the composited frame at the playhead. Doubles as
  the direct-manipulation surface for the Crop / transform tool.
- **Transport controls** — the playback bar (time readout, preview quality, transport
  buttons, loop, and speed).
- **Tool strip** — a horizontally scrolling row of editing tools and per-clip tools, with the
  **AI prompt bar** beneath it.
- **Timeline** — the multi-track lanes with the ruler and playhead.
- **Activity log** — a pull-up sheet at the bottom that streams AI output, running-process
  progress, and errors.

The **menu** (app icon) holds everything that isn't a moment-to-moment editing control: New,
Open, Save, Rename, Import, Generate, Render, Project settings, Settings, Compare AI,
Tutorial, FAQ, Icon Key, and Ad-Free. A footer adds About, Feedback, and the author link.

**Help / Icon Key.** Forgot what a button does? Tap **Help** (the **?**) in the top bar or the
tool strip to open the icon key, which names every icon button and its action.

---

## 3. Playback and the preview

### Transport controls

The transport bar sits directly under the preview:

| Control | Behavior |
|---------|----------|
| Time readout | `current / total` in seconds, e.g. `3.20s / 41.75s`. |
| **Preview quality** | Tap to cycle the preview resolution: **240p → 480p → 720p → 1080p → Full**. Lower is smoother but less sharp; it affects the preview only, never the export. Default is 720p. |
| **Start** | Jump the playhead to the beginning. |
| **Back 1 frame** | Step back one frame (frame size follows the project fps). |
| **Play / Pause** | Toggle playback. (Keyboard: **Space**.) |
| **Forward 1 frame** | Step forward one frame. |
| **End** | Jump the playhead to the end. |
| **Loop** | When on, playback restarts at the start of the loop region (or the timeline) instead of stopping at the end. |
| **Speed** | Tap to cycle playback rate: **0.5× → 1× → 1.5× → 2×**. |

During playback the timeline auto-scrolls to keep the playhead centered, and any AI "remove"
ranges are skipped so you preview the cut result.

### Crop / transform on the preview

Select the **Crop / transform** tool in the tool strip, then manipulate the selected clip
directly on the preview:

- **Pinch** to scale.
- **Drag** to reposition.
- **Twist** (two-finger rotate) to rotate.

While the Crop tool is active, **safe-zone guides** appear for the vertical (9:16) and square
(1:1) aspect ratios so you can keep important action inside frame. The crop tool is also how
you **size and place text/caption clips**.

---

## 4. The timeline

The timeline is multi-track: video tracks stack into compositing layers
(higher track = on top), audio tracks sit below. Text and image clips live on video tracks as
overlays. Clips render **on-device previews** — thumbnails for video/image, stereo waveforms
for audio.

### Track headers

Each track has a header on the left. Tap it to open the track popup:

| Control | Applies to | Notes |
|---------|-----------|-------|
| **Mute** | Audio, Video | Silences the track. |
| **Hide / Disable track** | Video/Text (Hide), Audio (Disable) | Removes the track from the composite/mix. |
| **Volume** | Audio, Video | Track gain, **0–2×**. |
| **Opacity** | Video, Text | Track opacity, **0–1**. |
| **Import clip…** | All | Import media straight onto this track. |
| **Create clip… / Add text clip** | All | Add a clip (a text overlay on video tracks). |
| **+ New track** | All | Add another track of this kind. |

A muted or hidden track shows a small red icon in its header.

### Gestures

| Gesture | Result |
|---------|--------|
| Tap empty timeline / ruler | Move the playhead there and clear the selection. |
| Tap a clip | Select it and move the playhead to the tapped point. |
| Double-tap a clip | Set the **playback / loop region** to that clip's span. |
| Drag along the ruler | Define the **playback / loop region** (drag; a plain tap still seeks). |
| Drag a clip | Move it: horizontally in time, vertically across same-type tracks. |
| Hold a clip past the top/bottom track edge (~1s) | Create a new track there and drop the clip onto it. |
| **Long-press a clip edge, then drag** | **Edge-trim** that in/out point (see below). |
| Long-press a clip's middle | Range-select from the current selection to this clip (across tracks). |
| Grab the red playhead line and drag | Scrub the playhead from anywhere along its height. |
| Pinch horizontally | Zoom **time** (pixels per second). |
| Pinch vertically | Zoom **track height**. |
| **Ctrl + scroll** (mouse/trackpad) | Zoom the timeline in/out. |

Zoom (from pinch, Ctrl+scroll, or the top-bar buttons) always pivots around the playhead,
keeping it centered. **Fit all** frames the whole project in the viewport.

### Snapping

While you drag a clip or group, any moving edge snaps to magnets: the timeline start, the
playhead, and any other clip's start or end on any track (strong snap), or the timeline grid
(weaker snap). Snapping is soft — keep dragging past a magnet to **overlap two clips into a
crossfade**. Grouped clips move together and snap as a unit.

### Split, trim, and edge-trim

- **Split at playhead** (scissors, or **S**): cut at the playhead — the selected clip/group,
  or every clip on every track when nothing is selected.
- **Edge-trim**: long-press within about 24dp of a clip's left or right edge, then drag to
  pull that cut point in or out. A clip you previously split or trimmed **re-extends** back
  into its source media the same way; linked audio trims with the picture. A subtle red handle
  marks each edge of a selected clip.

### Select, group, delete, ripple

- **Select** (arrow): the default tap-to-select mode.
- **Select range** (dashed box / marquee): drag a rectangle over the timeline to grab every
  clip whose time span it touches, across the tracks it covers.
- **Group / Ungroup**: with more than one clip selected, link them so they move together (or
  free them). Grouping appears in the tool strip only when a multi-clip selection is active.
- **Delete** (or **Del**): remove the selected clips (and their linked audio / group).
  Backspace deliberately does *not* delete, so it's safe while editing text.
- **Ripple (close gaps)**: pull clips left to close empty space — among the selected clips, or
  among all clips if nothing is selected — keeping every track in sync.

### Copy and paste

Copy/paste is available via keyboard:

- **Ctrl+C / Ctrl+V** — copy and paste whole **clips**.
- **Ctrl+Alt+C / Ctrl+Alt+V** — copy and paste the **effects** on a clip (filters, LUT,
  shader, and adjustments), so a look you dialed in transfers to another clip in one move.

### Undo / redo

Every edit is undoable. Use the top-bar **Undo** / **Redo** buttons, or **Ctrl+Z** (undo) and
**Ctrl+Shift+Z** (redo). History is deep, so you can step back through a whole session.

---

## 5. Clip tools and effects

Select exactly one clip (or a single linked group, such as a video+audio pair) and the tool
strip grows a set of **per-clip tool buttons**. Each opens a small popup. Which buttons appear
depends on the clip type.

### Filters

The **Filters** popup (tune icon) holds the per-clip color and image adjustments. Each is a
slider; the ones marked keyframeable carry a red **diamond** that records the current value as
a keyframe at the playhead.

| Filter | Range | Default | Keyframeable |
|--------|-------|---------|:---:|
| Brightness | 0 – 2 | 1 | ● |
| Contrast | 0 – 2 | 1 | ● |
| Saturation | 0 – 2 | 1 | ● |
| Sepia | 0 – 100 % | 0 | ● |
| Hue | 0 – 360 ° | 0 | ● |
| Invert | 0 – 100 % | 0 | — |
| Grayscale | 0 – 100 % | 0 | — |
| Blur | 0 – 20 px | 0 | — |

The Filters popup also hosts **LUT**, **Shader**, and **Presets** rows — see
[Advanced looks](#6-advanced-looks). Built-in presets are **Vintage**, **Noir**, and
**Reset** (which clears all filters back to defaults).

### Audio

The **Audio** popup (speaker icon) edits an audio or video clip's sound:

| Control | Range | Notes |
|---------|-------|-------|
| Volume | 0 – 2× | Clip gain. Keyframeable. |
| Pan | −1 (L) – +1 (R) | Stereo pan. Keyframeable. |
| Normalize audio | on/off | Evens out the clip's loudness. |

For per-track volume/mute, use the track header instead.

### Background and privacy

The **Background** popup (layers icon) runs two on-device effects:

- **Remove background (subject only)** — an on-device cutout keeps the foreground subject and
  drops the background so a lower track shows through. Put another clip on a lower track to
  composite behind it. A live cutout preview is shown.
- **Blur faces (anonymize)** — on-device face detection blurs every detected face, in both
  preview and export.

Both run entirely on the device.

### Text and captions

For a **text** clip, the **Text & font** popup (T icon) edits the caption text and picks a
font — **Sans, Serif, Mono, or Cursive**. Size and placement are set with the **Crop /
transform** tool on the preview. To turn spoken audio into caption clips automatically, use
**Auto-captions** — see [Captions and transcription](#captions-and-transcription).

### Keyframes and the curve editor

Keyframes animate a clip property over time. There are three ways to set them:

- The **Keyframes** popup (timeline icon): pick a property chip, tap **Add keyframe**, and each
  keyframe gets a value slider, a time label, a remove (×) button, and an **easing curve
  editor**.
- The red **diamond** next to a keyframeable filter/audio slider: records that property's
  current value at the playhead.
- The **Keyframe crop/placement at playhead** button (diamond in the tool strip): records the
  clip's crop/placement (and opacity) at the playhead.

Move the playhead, change the value, keyframe again — Guillotine animates between the points.
On the clip itself, keyframes plot as a small envelope; tap a keyframe diamond to select it,
then drag its **cubic-bezier ease handles** (in the popup's curve editor or directly on the
clip) to shape the acceleration. The **Auto-ease keyframes** toggle in the tool strip applies
smooth easing to new keyframes automatically.

Guillotine documents **twelve animatable properties**, each with its own range:

| Property | Range |
|----------|-------|
| Opacity | 0 – 1 |
| Scale | 0 – 6 |
| Rotation | −180 – 180 ° |
| Offset X | −1.5 – 1.5 |
| Offset Y | −1.5 – 1.5 |
| Brightness | 0 – 2 |
| Contrast | 0 – 2 |
| Saturation | 0 – 2 |
| Hue | 0 – 360 ° |
| Sepia | 0 – 100 |
| Volume | 0 – 2 |
| Pan | −1 – 1 |

> The Keyframes popup builds one chip per animatable property, so a **Speed** chip
> (time-remapping, 0.1–10×) can also appear alongside the twelve above.

### Split into pieces

After an AI keep/remove analysis marks ranges on a clip, a **Split into N clips** button
appears — it splits the clip at every keep/remove boundary, keeping all the pieces so you can
rearrange them by hand.

---

## 6. Advanced looks

Guillotine takes **standard, already-existing formats** for grades, effects, and filters —
not a bespoke format. A LUT you already made for a colour-grading tool, or an ISF shader from
your VJ kit, drops straight in. All of these run **on-device** in both preview and export. This section
summarizes them; the [Ecosystem guide](ECOSYSTEM.md) is the deep dive.

| Look | How to apply | Notes |
|------|--------------|-------|
| **LUT** (`.cube` 3D grade) | Filters popup → **LUT → Pick .cube** | Any standard 3D `.cube` (grading-tool exports and free packs). WYSIWYG in preview and export; 1D LUTs are rejected. |
| **GLSL / ISF shader** | Filters popup → **Shader → Pick .isf/.fs** | Single-input ISF filters or a raw `.fs`/`.glsl` fragment, run on every frame. A shader's FLOAT inputs become **adjustable sliders** right in the popup. |
| **FFmpeg / Frei0r filters** | Via the AI assistant (`apply_ffmpeg_filter`) | Bakes a standard `-vf` filtergraph (e.g. `hue=s=0, gblur=sigma=2`, or `frei0r=cartoon`) onto the clip. Advanced: requires you to supply an `ffmpeg` binary; bakes to a new clip. |
| **Clip-to-clip transitions** | Overlap two clips, or the AI assistant (`apply_transition`) | Overlapping clips crossfade; the assistant can bake ~50 `xfade`-style transitions (fade, wipes, slides, circle open/close, dissolve, pixelize, radial, and more). |

See [ECOSYSTEM.md](ECOSYSTEM.md) for supported subsets, how each is implemented, and how to
author your own.

---

## 7. AI features

AI in Guillotine is optional, and the free path is fully on-device. Whatever engine you
choose, the **on-device invariant holds: your clips and frames never leave the device.** Cloud
providers are text-only controllers that drive the editor through its tools; they never receive
your media.

### The AI prompt bar

A single prompt field sits beneath the tool strip and does **two** jobs depending on what's
selected:

- **A clip is selected** → the text is a **per-clip analysis prompt**. Type what to keep or
  cut — e.g. *"keep shots with a face"* or *"cut every frame with my phone"* — and the Send
  button runs the on-device analyzer, which finds the matching frames and splits/deletes them.
  This path is free and needs no LLM.
- **Nothing is selected** → the text is a **general instruction** for the AI assistant. An
  agent drives the whole editor for you through the app's tools — e.g. *"cut the silences in
  clip 1"* or *"add kinetic captions."*

The small **provider label** under the Send button shows which engine the AI button will use;
tap it to change it in Settings. The field remembers recent prompts — focus the empty field to
pick one from the history dropdown.

### AI keep / remove (on-device vision)

The on-device analyzer (ML Kit object/face/scene detection plus MediaPipe) samples frames at
about 3 fps, extends each match a few frames either side, and then splits the clip at the
matched boundaries and deletes the matched pieces. Progress and per-region findings stream into
the activity log.

### Teach the AI a concept

To target *your* specific object rather than any object of that kind, scrub to a frame that
shows it and say *"this is my phone"*. Guillotine takes a reference from that frame and matches
that instance by image similarity — so *"cut every frame with my phone"* tracks that phone, not
every phone.

### Cut vs. erase

- **Cutting** (*"cut/remove the frames with X"*) shortens the clip: it splits and deletes the
  matched pieces.
- **Erasing** (*"remove X but keep the length"*) keeps the clip the same length and repaints
  the object out with generative inpainting (Leonardo.ai, bring-your-own-key), producing
  grouped replacement segments.

### Captions and transcription

**Auto-captions** (the subtitles icon in the clip tool strip) turns a clip's speech into timed
caption clips that appear and disappear with the words. Tap it and pick a style — both run
**on-device** (your audio never leaves the device) when a speech model is configured, otherwise
via **cloud Whisper** (bring-your-own OpenAI key on Android). Captions burn into the export.

- **Captions** — clean subtitle clips, timed to the speech.
- **Animated** — word-pop / karaoke style: each word is split into syllables on separate tracks
  with scale keyframes that grow each syllable as it's spoken. (Needs per-word timing; falls back
  to plain captions when the model doesn't provide it.)

You can also drive either style from the assistant ("add captions," "add kinetic captions").

### Background removal and face blur

Both are on-device per-clip effects in the **Background** popup — see
[Background and privacy](#background-and-privacy).

### Generating media (image, video, music)

- **Image** — the menu's **Generate** opens a dialog: describe the image, then generate with
  free **Pollinations.ai** (no key) or **Leonardo.ai** (bring-your-own-key, with a model
  picker). The result drops onto the timeline as a 5-second image clip.
- **Video and music** — ask the AI assistant (e.g. *"generate a video of…"*, *"add background
  music that's…"*). These use cloud generation providers with your own key and add the result
  to the timeline. (The in-app **Generate** dialog itself covers images.)

### Voice commands

When an offline speech model is configured, a **microphone** appears next to the prompt field
(while nothing is selected). Tap to record, tap to stop, and your words are transcribed
**on-device** into the prompt for you to review and send.

### The activity log

The pull-up sheet at the bottom of the editor is the AI's output channel: the assistant's
running status, progress, per-region findings, and errors all stream here. When the agent needs
a clarification, you can reply to it directly from the sheet.

### Choosing a provider, and the MCP surface

- **Compare AI** (menu) shows a capability table — what the on-device options (Local silence
  detection, ML Kit vision) can and can't do versus cloud providers (Gemini, OpenAI,
  Anthropic, and others), and why you might bring a key.
- While the app is open it also runs a small **token-gated MCP server** (and an optional
  end-to-end-encrypted Cloudflare relay), so external AI tools can read the timeline and apply
  edits over the same text-only tool surface.

For the full AI reference, see the tool schema in [TOOLS.md](TOOLS.md), the provider matrix in
[PROVIDERS.md](PROVIDERS.md), and model choices in [MODELS.md](MODELS.md). The MCP protocol and
user-defined tool packs are documented in [PLUGINS.md](PLUGINS.md).

---

## 8. Exporting

### Project settings — aspect ratio and quality

Before you render, set the output shape and resolution from the menu's **Project** settings:

| Aspect ratio | | Quality | |
|---|---|---|---|
| **16:9** | Widescreen | **Original** | Keep the source resolution |
| **9:16** | Vertical | **4K** | 3840-wide |
| **1:1** | Square | **1080p** | Full HD |
| **Original** | Match the source | **720p** | HD |

The safe-zone guides in the Crop tool follow the 9:16 and 1:1 choices.

### Rendering

Choose **Render** from the menu to open the Export dialog. Name the file and tap **Start
render**. Guillotine renders a **real mp4** — Media3 on Android, FFmpeg on desktop — and
**bakes in everything**:

- your cuts and every composited video track,
- clip positions, per-clip filters, transforms, LUTs, and shaders,
- the project crop / aspect ratio,
- background mattes (cutouts) and face blur,
- caption and text overlays,
- and the full audio mix (volume / pan / normalize / mute / track opacity).

The render runs in the **background** via a foreground service with a **progress
notification** you can cancel, so you can leave the app while it works. The dialog narrates each
phase (analyzing audio, precomputing mattes, encoding, saving).

### Destination

- **Android:** `Movies/Guillotine` in your gallery. When it finishes you get a **Share** button.
- **Desktop:** `~/Videos/Guillotine`.

### If a render fails

The dialog shows the failing phase and a headline error; tap **Show details** for the full
cause chain. The **Report** button files a pre-filled GitHub issue with your device details and
the diagnostic already in the body — one tap, no copy-pasting. (If it can't reach the reporting
relay it falls back to opening the pre-filled issue in your browser, or copying the diagnostic
to your clipboard.)

---

## 9. Settings

Settings (menu → **Settings**) is where you choose your AI provider and analyzer, enter and
manage bring-your-own-key credentials (stored encrypted on-device), set on-device model paths
(LLM brain, speech/ASR, vision), configure the frame-analysis cache and the MCP server token
and relay, and export or import your whole configuration as a JSON backup. Because it's a large
surface with its own reference, it lives in a dedicated document — see **[SETTINGS.md](SETTINGS.md)**.

Prefer no ads? Menu → **Ad-Free** offers a one-time purchase that removes ads permanently.

---

## 10. Platforms

Guillotine is one editor across form factors, sharing a common editor core (multi-track
timeline, keyframes, AI-driven cuts, mp4 export).

- **Android phone** — the stacked portrait layout, touch gestures throughout.
- **Tablet / Chromebook** — a first-class large-screen layout (not a phone port), with the
  preview and timeline side by side, keyboard shortcuts, and mouse + **Ctrl-scroll** zoom.
  Installs without a touchscreen.
- **Desktop** — native installers for **macOS (`.dmg`)**, **Windows (`.msi`)**, and
  **Linux (`.deb`)**, attached to every GitHub Release. Installers are currently unsigned:
  macOS → right-click → Open on first launch; Windows → SmartScreen → More info → Run anyway;
  Linux → `sudo apt install ./guillotine_*.deb`.

Two platform differences worth knowing:

- **Media engine.** Android uses **Media3** (ExoPlayer preview, Transformer export); desktop
  uses **JavaCV / FFmpeg**. Both produce a real mp4 with the same edit applied.
- **On-device AI is Android-first.** Some on-device AI capabilities (the MediaPipe/ML Kit
  vision analyzer, on-device LLM brain, and offline speech) are Android-first; on desktop,
  lean on the cloud (bring-your-own-key) providers for the equivalent AI features.

See [BUILDING.md](BUILDING.md) to build from source, and the [FAQ](FAQ.md) for more
platform notes.

---

## 11. Keyboard shortcuts

On a hardware keyboard (Chromebook, tablet, or desktop):

| Action | Shortcut |
|--------|----------|
| Play / pause | `Space` |
| Split at playhead | `S` |
| Delete selection | `Del` |
| Zoom timeline | `Ctrl` + `scroll` |
| Undo | `Ctrl` + `Z` |
| Redo | `Ctrl` + `Shift` + `Z` |
| Copy / paste clip | `Ctrl` + `C` / `Ctrl` + `V` |
| Copy / paste effects | `Ctrl` + `Alt` + `C` / `Ctrl` + `Alt` + `V` |

On macOS the `Cmd` key works in place of `Ctrl`; `Ctrl` + `Y` also redoes. `Backspace`
intentionally does **not** delete a clip, so it's safe to use while editing text.

---

## 12. Where to go next

- **[TUTORIAL.md](TUTORIAL.md)** — a guided first edit, start to finish.
- **[FAQ.md](FAQ.md)** — quick answers on privacy, platforms, captions, and more.
- **[SETTINGS.md](SETTINGS.md)** — every setting and how to configure AI.
- **[TOOLS.md](TOOLS.md)** · **[PROVIDERS.md](PROVIDERS.md)** · **[MODELS.md](MODELS.md)** —
  the AI tool schema, provider matrix, and model reference.
- **[ECOSYSTEM.md](ECOSYSTEM.md)** — LUTs, shaders, FFmpeg/Frei0r filters, and transitions.
- **[PLUGINS.md](PLUGINS.md)** — the MCP plugin protocol and user-defined tools.

Explore the **Icon Key** (the **?** button) any time you forget what a control does.
