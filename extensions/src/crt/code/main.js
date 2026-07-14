// CRT / Retro — darken every Nth row (scanlines), split R/B horizontally (chromatic aberration), and
// darken toward the corners (vignette). Reads from a snapshot so the aberration samples the original.
import { defineFilter } from "@azphalt/azdk";

export const crt = defineFilter((ctx) => {
  const scan = Math.max(0, Math.min(1, ctx.params.number("scanlines")));
  const gap = Math.max(2, Math.round(ctx.params.number("gap")));
  const shift = Math.max(0, Math.round(ctx.params.number("aberration")));
  const vig = Math.max(0, Math.min(1, ctx.params.number("vignette")));

  const bmp = ctx.bitmap.read(ctx.target);
  const w = bmp.width;
  const h = bmp.height;
  const d = bmp.data;
  const src = d.slice(); // snapshot so channel shifts sample un-mutated pixels

  const cx = (w - 1) / 2;
  const cy = (h - 1) / 2;
  const maxD = Math.sqrt(cx * cx + cy * cy) || 1;

  for (let y = 0; y < h; y++) {
    const line = y % gap === 0 ? 1 - scan : 1; // dark scanline every `gap` rows
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      const xr = x + shift < w ? x + shift : w - 1;
      const xb = x - shift >= 0 ? x - shift : 0;
      const r = src[(y * w + xr) * 4];
      const g = src[i + 1];
      const b = src[(y * w + xb) * 4 + 2];
      const dx = x - cx;
      const dy = y - cy;
      const v = 1 - vig * (Math.sqrt(dx * dx + dy * dy) / maxD);
      const k = line * v;
      d[i] = r * k;
      d[i + 1] = g * k;
      d[i + 2] = b * k;
    }
  }
  ctx.bitmap.write(ctx.target, bmp);
  ctx.canvas.requestRedraw();
});
