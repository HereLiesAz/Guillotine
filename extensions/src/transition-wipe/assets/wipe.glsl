// Directional Wipe — the incoming clip is revealed along `direction` behind a soft edge.
// gl-transition format. Uniforms: progress (0→1), direction (default right), smoothness (edge width).
// SPDX-License-Identifier: MIT
// Author: HereLiesAz

uniform vec2 direction; // default (1.0, 0.0) — left → right
uniform float smoothness; // default 0.05

vec4 transition(vec2 uv) {
  vec2 dir = length(direction) > 0.0 ? normalize(direction) : vec2(1.0, 0.0);
  float d = dot(uv, dir);          // 0..1 along the wipe axis
  float s = max(1e-4, smoothness);
  // m = 1 where the outgoing clip still shows, 0 where the incoming clip has arrived.
  float m = smoothstep(progress - s, progress + s, d);
  return mix(getToColor(uv), getFromColor(uv), m);
}
