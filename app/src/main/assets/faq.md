# Frequently asked questions

## Does my video get uploaded anywhere?

No. **Your video never leaves the device.** All frame and audio analysis runs on-device. Cloud AIs (Gemini / OpenAI / Anthropic) are *controllers only* — they drive the editor as text through the in-app MCP server and never receive your clips or frames.

## Do I need an API key or an account?

No. There's a free, no-key, on-device path: ML Kit + MediaPipe vision for keep/remove analysis, an optional on-device LLM brain to drive the editor, a Local silence detector for audio, and free Pollinations.ai image generation. The app is fully usable with zero configuration. Cloud providers are bring-your-own-key and stored encrypted on-device if you choose to use them.

## How does "cut every frame with my phone" work?

Select a clip and type the instruction. On-device vision (COCO object detection, face detection, scene labeling) scans the clip — frames are sampled at about 3 fps and a match extends a few frames either side, so it's cheap — then the clip is split at the matched boundaries and the matched pieces are deleted. To target a specific object rather than any phone, scrub to a frame showing it and say "this is *my* phone"; it matches that instance by image similarity.

## What's the difference between cutting an object and erasing it?

**Cutting** ("cut/remove/trim the frames with X") shortens the clip — it splits and deletes the matched pieces. **Erasing** ("remove X but keep it natural / keep the length") keeps the clip the same length and repaints the object out using Leonardo.ai inpainting (bring your own key), producing grouped replacement segments.

## What do the Select range and Ripple buttons do?

**Select range** (dashed box) lets you drag a rectangle over the timeline to select every clip whose time span it touches, across all tracks. **Ripple** closes gaps: it pulls clips left to remove empty space among the selected clips, or among all clips if nothing is selected, keeping every track in sync.

## Can other tools or AIs control the editor?

Yes. While open, the app runs a small token-gated MCP server, so external AI tools (or the in-app assistant) can read the timeline and apply edits. An optional end-to-end-encrypted Cloudflare relay makes it reachable from anywhere without port-forwarding.

## What can I export, and where does it go?

A real mp4, rendered with Media3 Transformer: your cuts, every composited video track, clip positions, per-clip filters and transforms, project crop/aspect, background mattes, caption overlays, and the full audio mix (volume / pan / normalize / mute / opacity). It saves to your gallery. Export runs in the background with a progress notification and can be cancelled.

## Does it work on a tablet or Chromebook?

Yes. Guillotine has phone, tablet, and Chromebook layouts, with keyboard shortcuts and mouse + Ctrl-scroll zoom. It's a first-class large-screen app, not just a phone port.

## How do I see what a button does?

Press the **?** (help) button in the toolbar or top-right corner and open the **icon key** — it lists every icon button and what it does. The **Tutorial** and **FAQ** are in the menu (the app icon, top-left).
