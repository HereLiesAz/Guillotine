"""
Guillotine — Free Text-to-Video Space.

Runs an open text-to-video model (LTX-Video) on Hugging Face's free shared GPU (ZeroGPU) and exposes a
`generate` API endpoint the Guillotine editor calls as its keyless "Guillotine (free)" video provider.
Only the user's text prompt is sent here — never their media. Short, low-resolution clips by design so
the free GPU allocation is enough.
"""

import os
import random
import shutil
import tempfile
import time

import gradio as gr
import spaces
import torch
from diffusers import LTXPipeline
from diffusers.utils import export_to_video

MODEL_ID = "Lightricks/LTX-Video"
MAX_SECONDS = 6
FPS = 24
MAX_PROMPT_CHARS = 500
# Each clip is written to its own subdir here; Gradio copies the result into its own cache, so
# stale subdirs are pruned on every call to keep the Space's disk from filling up.
OUT_ROOT = os.path.join(tempfile.gettempdir(), "guillotine-out")
OUT_TTL_SECONDS = 15 * 60

# Load once at import. On ZeroGPU the GPU is materialized inside the @spaces.GPU call, not here.
pipe = LTXPipeline.from_pretrained(MODEL_ID, torch_dtype=torch.bfloat16)
pipe.to("cuda")


def _parse_seed(seed) -> int:
    """Accept int/float/str/None; anything unparseable or <= 0 means 'random'."""
    try:
        value = int(float(seed))
    except (TypeError, ValueError, OverflowError):
        value = 0
    if value <= 0:
        return random.randint(0, 2**31 - 1)
    return value % (2**31)


def _parse_seconds(seconds) -> int:
    try:
        value = int(float(seconds))
    except (TypeError, ValueError, OverflowError):
        value = 3
    return max(1, min(MAX_SECONDS, value))


def _prune_old_outputs() -> None:
    if not os.path.isdir(OUT_ROOT):
        return
    cutoff = time.time() - OUT_TTL_SECONDS
    for name in os.listdir(OUT_ROOT):
        path = os.path.join(OUT_ROOT, name)
        try:
            if os.path.getmtime(path) < cutoff:
                shutil.rmtree(path, ignore_errors=True)
        except OSError:
            pass


@spaces.GPU(duration=120)
def generate(prompt: str, negative_prompt: str = "", seconds: int = 3, seed: int = 0):
    """Generate a short clip. Signature order matches the app's API call: (prompt, negative, seconds, seed)."""
    prompt = (prompt or "").strip()
    if not prompt:
        raise gr.Error("Enter a prompt.")
    if len(prompt) > MAX_PROMPT_CHARS:
        raise gr.Error(f"Prompt too long (max {MAX_PROMPT_CHARS} characters).")
    negative_prompt = (negative_prompt or "").strip()[:MAX_PROMPT_CHARS]
    seconds = _parse_seconds(seconds)
    seed = _parse_seed(seed)
    generator = torch.Generator(device="cuda").manual_seed(seed)

    # LTX-Video wants frame counts of the form 8*k + 1 and dimensions divisible by 32.
    num_frames = min(seconds * FPS, 120)
    num_frames = (num_frames // 8) * 8 + 1
    width, height = 704, 448

    frames = pipe(
        prompt=prompt,
        negative_prompt=(negative_prompt or "worst quality, blurry, jittery, distorted"),
        width=width,
        height=height,
        num_frames=num_frames,
        num_inference_steps=30,
        generator=generator,
    ).frames[0]

    _prune_old_outputs()
    os.makedirs(OUT_ROOT, exist_ok=True)
    out = os.path.join(tempfile.mkdtemp(dir=OUT_ROOT), "guillotine.mp4")
    export_to_video(frames, out, fps=FPS)
    return out


with gr.Blocks(title="Guillotine — Free Text-to-Video") as demo:
    gr.Markdown(
        "# Guillotine — Free Text-to-Video\n"
        "Open text-to-video (LTX-Video) on Hugging Face's free shared GPU. Short, low-resolution clips; "
        "may queue when busy. This Space backs the **Guillotine** editor's keyless free video generator."
    )
    prompt = gr.Textbox(label="Prompt", lines=2, max_length=MAX_PROMPT_CHARS, placeholder="a golden retriever running on a beach at sunset")
    with gr.Row():
        negative = gr.Textbox(label="Negative prompt", value="", max_length=MAX_PROMPT_CHARS)
        seconds = gr.Slider(1, MAX_SECONDS, value=3, step=1, label="Seconds")
        seed = gr.Number(value=0, label="Seed (0 = random)")
    btn = gr.Button("Generate", variant="primary")
    video = gr.Video(label="Result")
    btn.click(generate, [prompt, negative, seconds, seed], video, api_name="generate")

demo.queue(max_size=20).launch()
