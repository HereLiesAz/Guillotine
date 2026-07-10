"""
Guillotine — Free Text-to-Video Space.

Runs an open text-to-video model (LTX-Video) on Hugging Face's free shared GPU (ZeroGPU) and exposes a
`generate` API endpoint the Guillotine editor calls as its keyless "Guillotine (free)" video provider.
Only the user's text prompt is sent here — never their media. Short, low-resolution clips by design so
the free GPU allocation is enough.
"""

import os
import random
import tempfile

import gradio as gr
import spaces
import torch
from diffusers import LTXPipeline
from diffusers.utils import export_to_video

MODEL_ID = "Lightricks/LTX-Video"
MAX_SECONDS = 6
FPS = 24

# Load once at import. On ZeroGPU the GPU is materialized inside the @spaces.GPU call, not here.
pipe = LTXPipeline.from_pretrained(MODEL_ID, torch_dtype=torch.bfloat16)
pipe.to("cuda")


@spaces.GPU(duration=120)
def generate(prompt: str, negative_prompt: str = "", seconds: int = 3, seed: int = 0):
    """Generate a short clip. Signature order matches the app's API call: (prompt, negative, seconds, seed)."""
    if not prompt or not prompt.strip():
        raise gr.Error("Enter a prompt.")
    seconds = int(max(1, min(MAX_SECONDS, int(seconds or 3))))
    seed = int(seed or 0) or random.randint(0, 2**31 - 1)
    generator = torch.Generator(device="cuda").manual_seed(seed)

    # LTX-Video wants frame counts of the form 8*k + 1 and dimensions divisible by 32.
    num_frames = min(seconds * FPS, 120)
    num_frames = (num_frames // 8) * 8 + 1
    width, height = 704, 448

    frames = pipe(
        prompt=prompt.strip(),
        negative_prompt=(negative_prompt or "worst quality, blurry, jittery, distorted"),
        width=width,
        height=height,
        num_frames=num_frames,
        num_inference_steps=30,
        generator=generator,
    ).frames[0]

    out = os.path.join(tempfile.mkdtemp(), "guillotine.mp4")
    export_to_video(frames, out, fps=FPS)
    return out


with gr.Blocks(title="Guillotine — Free Text-to-Video") as demo:
    gr.Markdown(
        "# Guillotine — Free Text-to-Video\n"
        "Open text-to-video (LTX-Video) on Hugging Face's free shared GPU. Short, low-resolution clips; "
        "may queue when busy. This Space backs the **Guillotine** editor's keyless free video generator."
    )
    prompt = gr.Textbox(label="Prompt", lines=2, placeholder="a golden retriever running on a beach at sunset")
    with gr.Row():
        negative = gr.Textbox(label="Negative prompt", value="")
        seconds = gr.Slider(1, MAX_SECONDS, value=3, step=1, label="Seconds")
        seed = gr.Number(value=0, label="Seed (0 = random)")
    btn = gr.Button("Generate", variant="primary")
    video = gr.Video(label="Result")
    btn.click(generate, [prompt, negative, seconds, seed], video, api_name="generate")

demo.queue(max_size=20).launch()
