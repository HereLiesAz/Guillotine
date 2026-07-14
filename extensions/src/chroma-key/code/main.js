// Chroma Key — make pixels near the key colour transparent, with a soft edge. Distances are measured
// in RGB space and normalised against the maximum possible distance (sqrt(3)*255).
import { defineFilter } from "@azphalt/azdk";

const MAX_DIST = Math.sqrt(3) * 255;

export const chromaKey = defineFilter((ctx) => {
  const key = ctx.params.color("keyColor") || { r: 0, g: 255, b: 0, a: 255 };
  const threshold = (ctx.params.number("threshold") ?? 0.4) * MAX_DIST; // fully keyed within this distance
  const softness = Math.max(1e-4, ctx.params.number("softness") ?? 0.1) * MAX_DIST; // ramp width

  const bmp = ctx.bitmap.read(ctx.target);
  const d = bmp.data;
  for (let i = 0; i < d.length; i += 4) {
    const dr = d[i] - key.r;
    const dg = d[i + 1] - key.g;
    const db = d[i + 2] - key.b;
    const dist = Math.sqrt(dr * dr + dg * dg + db * db);
    // alpha ramps 0 (inside threshold) → 1 (threshold + softness and beyond)
    let a = (dist - threshold) / softness;
    a = a < 0 ? 0 : a > 1 ? 1 : a;
    d[i + 3] = Math.round(d[i + 3] * a);
  }
  ctx.bitmap.write(ctx.target, bmp);
  ctx.canvas.requestRedraw();
});
