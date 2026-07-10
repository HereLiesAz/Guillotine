# Frequently asked questions

## Does my video get uploaded anywhere?

No. **Your video never leaves the device.** All frame and audio analysis runs on-device. Cloud AIs (Gemini / OpenAI / Anthropic) are *controllers only* — they drive the editor as text through the in-app MCP server and never receive your clips or frames.

## Is my footage private? How is this different from a cloud editor?

Yes — that's the whole idea. Guillotine is **on-device first**: all frame and audio analysis (vision, transcription, beat detection, denoise, matting) runs locally, there's **no account and nothing to sign up for**, and nothing is uploaded. If you choose to use a cloud AI, it only ever exchanges **text** — your prompt and the on-device analysis results — never your frames. Your footage and all rights to it stay entirely yours. A typical cloud editor uploads your media to be processed and stored on its servers; Guillotine doesn't. See [PROVIDERS.md](PROVIDERS.md) for exactly what each AI role can and can't see, and the [manual](MANUAL.md) for the editor itself.

## Do I need an API key or an account?

No. There's a free, no-key, on-device path: ML Kit + MediaPipe vision for keep/remove analysis, an optional on-device LLM brain to drive the editor, a Local silence detector for audio, and free Pollinations.ai image generation. The app is fully usable with zero configuration. Cloud providers are bring-your-own-key and stored encrypted on-device if you choose to use them.

## Can I generate images or video for free?

Yes — with no key and no account. Images use **Pollinations** (keyless); video uses **Guillotine (free)**, a text-to-video model running on Guillotine's own free Hugging Face Space. Both are cloud services, so **only your text prompt is sent** — never your footage — and they're tuned for short, quick results. Add your own key for a paid provider when you want longer or higher-quality output. See [PROVIDERS.md](PROVIDERS.md) for the full provider list.

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

## Is there a desktop version?

Yes — native installers for **macOS (`.dmg`)**, **Windows (`.msi`)** and **Linux (`.deb`)** are attached to every GitHub Release. The desktop app shares the editor core with Android (multi-track timeline, keyframes, AI-driven cuts, mp4 export). The media engine is JavaCV/FFmpeg on desktop where Android uses Media3. Installers are currently unsigned — macOS: right-click → Open on first launch; Windows: SmartScreen → More info → Run anyway; Linux: `sudo apt install ./guillotine_*.deb`.

## How do I report a bug?

The Export dialog has a **Report** button that opens a pre-filled GitHub issue with your device details and the diagnostic stack trace already in the body — just hit Submit. For non-export bugs, use the [issue tracker](https://github.com/HereLiesAz/Guillotine/issues) directly.

## What are animated / kinetic captions?

Regular **Transcribe** creates timed text clips that appear and disappear with the spoken words. **Animated transcribe** goes further: it splits each word into syllables on separate tracks with scale keyframes so each syllable grows from small to full size as it's spoken — a kinetic typography effect. Ask the AI for "animated captions," "kinetic text," or "per-syllable animation."

## What are user-defined editing tools?

You can teach the AI a named editing method — say "create a tool called comedy zoom that does X, Y, Z" — and later invoke it on any clip with "do comedy zoom on this clip." The AI saves the step-by-step instructions and replays them using the editor's built-in tools. Use **list tools** to see your saved methods and **delete tool** to remove one.

## Can I record my own edits as a reusable tool?

Yes. Tell the AI "record what I do" (or "watch me edit this"), then edit the clip by hand — split, trim, delete, add keyframes, change filters, whatever you like. Every action is captured. When you're done, say "save that as X" to turn the recorded steps into a user-defined tool. You can add written caveats (e.g. "adapt timings to clip length") so the AI generalizes the method for other clips.

## Can I back up my AI settings?

Yes. Open **Settings → Advanced** and tap **Export settings** to save your AI configuration (provider, API keys, models, speech/agent model paths, cache size) as a JSON file. **Import settings** restores them — useful for migrating to a new device or sharing a setup.

## What keyframe properties can I animate?

Twelve: **opacity, scale, rotation, offset X, offset Y, brightness, contrast, saturation, hue, sepia, volume, and pan.** Each keyframe supports per-point cubic-bezier easing with draggable handles.

## Is Guillotine free and open source? What license is it under?

Yes — Guillotine is free and open source under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. You're free to use, study, modify, and share it. Because the AGPL is a strong copyleft license, anyone who distributes Guillotine — **including running a modified version as a network service** — must make their complete source available under the same license. If you fork it you must also **preserve attribution** (keep the `NOTICE` file and show "Based on Guillotine (c) HereLiesAz" in your About / credits screen), **mark your version as changed**, and **rename it** — the "Guillotine" name and logo are reserved and can't be reused by forks (you may still state, factually, that your work is "based on Guillotine"). See [`LICENSE`](../LICENSE) and [`NOTICE`](../NOTICE) for the full terms.

## How do I see what a button does?

Press the **?** (help) button in the toolbar or top-right corner and open the **icon key** — it lists every icon button and what it does. The **Tutorial** and **FAQ** are in the menu (the app icon, top-left). For the complete reference — every screen, control, and option — see the [user manual](MANUAL.md).
