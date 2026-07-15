// CRT / Retro — darken every Nth row (scanlines), split R/B horizontally (chromatic aberration), and
// darken toward the corners (vignette). Reads from a snapshot so the aberration samples the original.
import { defineFilter } from "@azphalt/azdk";

const num = (ctx, key, dflt) => {
  const v = ctx.params.number(key);
  return Number.isFinite(v) ? v : dflt;
};

export const crt = defineFilter((ctx) => {
  const scan = Math.max(0, Math.min(1, num(ctx, "scanlines", 0.35)));
  const gap = Math.max(2, Math.round(num(ctx, "gap", 3)));
  const shift = Math.max(0, Math.round(num(ctx, "aberration", 2)));
  const vig = Math.max(0, Math.min(1, num(ctx, "vignette", 0.4)));

  const bmp = ctx.bitmap.read(ctx.target);
  const w = bmp.width;
  const h = bmp.height;
  const d = bmp.data;
  const src = d.slice(); // snapshot so channel shifts sample un-mutated pixels

  const cx = (w - 1) / 2;
  const cy = (h - 1) / 2;
  const maxD2 = cx * cx + cy * cy || 1; // squared radius — avoids a per-pixel sqrt (quadratic falloff)

  for (let y = 0; y < h; y++) {
    const line = y % gap === 0 ? 1 - scan : 1; // dark scanline every `gap` rows
    const yOffset = y * w;
    const dy = y - cy;
    const dy2 = dy * dy;
    for (let x = 0; x < w; x++) {
      const i = (yOffset + x) * 4;
      const xr = x + shift < w ? x + shift : w - 1;
      const xb = x - shift >= 0 ? x - shift : 0;
      const r = src[(yOffset + xr) * 4];
      const g = src[i + 1];
      const b = src[(yOffset + xb) * 4 + 2];
      const dx = x - cx;
      const v = Math.max(0, 1 - vig * ((dx * dx + dy2) / maxD2));
      const k = line * v;
      d[i] = r * k;
      d[i + 1] = g * k;
      d[i + 2] = b * k;
    }
  }
  ctx.bitmap.write(ctx.target, bmp);
  ctx.canvas.requestRedraw();
});
