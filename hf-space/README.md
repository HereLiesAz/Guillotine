---
title: Guillotine Free Text To Video
emoji: 🎬
colorFrom: red
colorTo: gray
sdk: gradio
sdk_version: 5.9.1
app_file: app.py
pinned: false
license: apache-2.0
short_description: Free text-to-video for the Guillotine editor (LTX-Video on ZeroGPU)
---

# Guillotine — Free Text-to-Video

Open text-to-video generation ([LTX-Video](https://huggingface.co/Lightricks/LTX-Video)) on Hugging
Face's free shared GPU (**ZeroGPU**). This Space backs the **Guillotine** video editor's keyless
"Guillotine (free)" video provider — the app POSTs a prompt to the `generate` endpoint and downloads
the resulting clip. **Only the text prompt is sent here; never the user's media.**

Short, low-resolution clips by design so the free GPU allocation is enough. Expect occasional queueing
when busy. For longer / higher-quality video, the app also supports bring-your-own-key providers
(Runway, Luma, Veo, Kling, Pika, …).

## API

The app calls the Gradio API endpoint `generate` with `[prompt, negative_prompt, seconds, seed]` and
reads the resulting video file url from the result stream.

## Requires Hugging Face Pro

Hosting a **Gradio** Space (even on `cpu-basic`) and using **ZeroGPU** both require a Hugging Face
**Pro** subscription on the owning account — creating the Space without it returns `402 Payment
Required`. So the free-to-the-*user* generator is funded by the app owner's HF Pro plan (one
subscription serves all app users through this shared Space).

After the Space is created, enable **ZeroGPU** in *Settings → Hardware*. The app then works against the
Space's public URL: `https://hereliesaz-guillotine-t2v.hf.space`.

If you don't want a Pro subscription, the app's free video provider base URL is overridable (the
provider's extra field) — point it at any compatible public Gradio text-to-video Space that exposes a
`generate` endpoint, or just rely on the bring-your-own-key paid providers instead.

## Deploying

Pushed from the [Guillotine repo](https://github.com/HereLiesAz/Guillotine) `hf-space/` folder via the
**Deploy T2V Space** GitHub Action (uses the `HF_TOKEN` repository secret — the token is never printed).

Built with LTX-Video by Lightricks.
