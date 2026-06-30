# Guillotine tutorial

A walkthrough of everything the app does, from getting media in to a finished video. Each step builds on the last.

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

## 7. Animate with keyframes

Drop a **keyframe** at the playhead to record a clip's current look, move the playhead, change it, and keyframe again — the value animates between them. Tap a keyframe to select it and toggle its ease; drag the bezier handles in the inspector to shape the curve. Keyframes work for opacity, scale, rotation, crop/placement, color, volume, and pan.

## 8. Add text and captions

Add a **text** clip — a transparent overlay on a video track — and edit its content and font, then size and place it with the crop tool. Or **transcribe** a clip's speech into timed caption clips, on-device (Vosk) or via cloud Whisper. Captions burn into the export.

## 9. Render your video

When it looks right, open the menu and choose **Export video**. Guillotine renders a real mp4 with Media3: it makes your cuts, composites every track, applies your filters, transforms, background mattes, captions, and audio mix, then saves to your gallery. Long jobs run in the background with a progress notification you can pause, resume, or cancel.

That's the whole loop — import, arrange, cut, enhance, animate, caption, and export. Explore the icon key (the **?** button) any time you forget what a button does.
