// Duotone — remap luminance to a shadow→highlight colour ramp. Written against @azphalt/azdk; the
// conforming host provides the SDK and injects only the granted capabilities (bitmap, params, canvas).
import { defineFilter } from "@azphalt/azdk";

export const duotone = defineFilter((ctx) => {
  const sh = ctx.params.color("shadow") || { r: 26, g: 16, b: 53, a: 255 };     // {r,g,b,a} in 0..255
  const hi = ctx.params.color("highlight") || { r: 255, g: 211, b: 110, a: 255 };
  const amount = Math.max(0, Math.min(1, ctx.params.number("amount") ?? 1));

  const bmp = ctx.bitmap.read(ctx.target);
  const d = bmp.data;
  for (let i = 0; i < d.length; i += 4) {
    // Rec. 601 luma in 0..1.
    const l = (0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]) / 255;
    d[i] += (sh.r + (hi.r - sh.r) * l - d[i]) * amount;
    d[i + 1] += (sh.g + (hi.g - sh.g) * l - d[i + 1]) * amount;
    d[i + 2] += (sh.b + (hi.b - sh.b) * l - d[i + 2]) * amount;
    // alpha untouched
  }
  ctx.bitmap.write(ctx.target, bmp);
  ctx.canvas.requestRedraw();
});
