// Crossfade — a straight linear dissolve.
// gl-transition format: implement `vec4 transition(vec2 uv)` using the host-provided
// getFromColor(uv) / getToColor(uv) samplers and the `progress` uniform (0 → 1).
// SPDX-License-Identifier: MIT
// Author: HereLiesAz

vec4 transition(vec2 uv) {
  return mix(getFromColor(uv), getToColor(uv), progress);
}
