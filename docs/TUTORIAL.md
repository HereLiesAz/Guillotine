# Guillotine tutorial

A quick, hands-on tour from getting media in to a finished video — each step builds on the last. This is the fast path; for the complete reference (every screen, control, gesture, and option) see the **[full manual](MANUAL.md)**, and the **[FAQ](FAQ.md)** for quick answers.

## 1. Import or create your media

Open the menu (the app icon, top-left) and choose **Import media** to pull in video, audio, or images from your device. Each import lands on the timeline as a clip — a video with sound shows its audio as a linked waveform clip on an audio track.

No footage yet? Choose **Generate image** to make one with AI: free **Pollinations.ai** (no key) or **Leonardo.ai** (bring your own key). You can also drop in still images and give them a duration.

## 2. Learn the timeline

The timeline is multi-track and Vegas-style: video tracks stack into layers, audio sits below. Tap anywhere to move the playhead (the red line). **Pinch** to zoom — horizontally changes width (time), vertically changes track height. Scroll to move through tracks. Clips show on-device thumbnails (video/image) and waveforms (audio).

## 3. Arrange and trim clips

Drag clips to move them, including across tracks. Clips snap to each other, the playhead, and the grid; overlap two clips to make a crossfade. **Long-press a clip edge, then drag** to trim its in/out point — a split clip re-extends back into its source the same way, and its linked audio trims with it.

Use **Select range** (the dashed-box tool) to drag a rectangle over the timeline and grab every clip in that span at once. **Group** multiple clips so they move together; **Ungroup** to free them.

## 4. Cut with the scissors

Position the playhead and press the **scissors** to split — the selected clip/group, or every clip on every track when nothing is selected. Delete what you don't want, then press **Ripple** to pull the remaining clips left and close the gaps.

## 5. Let the AI do the finding

Select a clip, type what you want in the prompt bar — e.g. "cut every frame with my phone" or "keep shots with a face" — and run it. Free **on-device vision** (ML Kit + MediaPipe) finds the matching frames and splits/deletes them for you. Your video never leaves the device. Point it at the current frame ("this is *my* phone") to track that specific object.

Or use the **assistant bar**: type an instruction in plain language and an agent drives the editor for you through the app's tools. Pick a cloud provider (bring your own key) or the on-device brain — either way they only exchange text, never your footage.

## 6. Style each clip

Use the **Crop / transform** tool to pinch-scale, drag-place, and twist-rotate a clip right on the preview. Open a clip's tools to adjust brightness/contrast/saturation/hue/sepia/blur, volume/pan, remove its background (on-device), or repaint an object out generatively (Leonardo, BYO key) while keeping the length.

For deeper looks — **`.cube` LUTs**, adjustable **GLSL/ISF shaders**, and clip-to-clip **transitions** — see the manual's [Advanced looks](MANUAL.md#6-advanced-looks).

## 7. Animate with keyframes

Drop a **keyframe** at the playhead to record a clip's current look, move the playhead, change it, and keyframe again — the value animates between them. Tap a keyframe to select it and toggle its ease; drag the bezier handles in the inspector to shape the curve. Keyframes work for **12 properties**: opacity, scale, rotation, offset X/Y, brightness, contrast, saturation, hue, sepia, volume, and pan.

## 8. Add text and captions

Add a **text** clip — a transparent overlay on a video track — and edit its content and font, then size and place it with the crop tool. Or **transcribe** a clip's speech into timed caption clips, on-device (Vosk) or via cloud Whisper. Captions burn into the export.

For a more dynamic look, ask the AI for **animated captions** (or "kinetic text," "per-syllable animation"). This splits each word into syllables on separate tracks with scale keyframes that grow each syllable as it's spoken — a kinetic typography effect.

## 9. Teach the AI your own editing methods

You can create **user-defined tools** — named editing methods the AI can replay:

- **Write a method:** tell the AI "create a tool called X that does Y, Z" and it saves step-by-step instructions.
- **Record a method:** tell the AI "record what I do," then edit a clip by hand (split, trim, keyframe, filter changes — everything is captured). Say "save that as X" to store the recorded actions as a reusable tool. Add caveats like "adapt timings to clip length" so the steps generalize to other clips.
- **Use a method:** say "do X on this clip" and the AI follows the saved instructions using the editor's built-in tools.

## 10. Back up your settings

Open **Settings → Advanced** and tap **Export settings** to save your AI configuration (provider, keys, models, paths) as a JSON file. **Import settings** restores them on this or another device.

## 11. Render your video

When it looks right, open the menu and choose **Render**. Guillotine renders a real mp4 — Media3 on Android, FFmpeg on desktop — making your cuts, compositing every track, applying filters, transforms, background mattes, captions, and audio mix, then saving to your gallery (Android) or `~/Videos/Guillotine` (desktop). Long jobs run in the background with a progress notification you can cancel.

The Export dialog narrates every phase (analyzing audio, precomputing mattes, encoding, saving) in the activity-log sheet. If the render fails you'll see the failure phase with a stack-frame diagnostic and a **Report** button — one tap opens a pre-filled GitHub issue so the bug lands in the tracker without you copy-pasting anything.

That's the whole loop — import, arrange, cut, enhance, animate, caption, teach, and render. Explore the icon key (the **?** button) any time you forget what a button does, and see the **[full manual](MANUAL.md)** when you want the complete reference.
