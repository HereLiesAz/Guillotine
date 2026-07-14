// Iris Reveal — a circle grows from `center` and reveals the incoming clip inside it.
// gl-transition format. Uniforms: progress (0→1), center (default 0.5,0.5), smoothness (edge width).
// SPDX-License-Identifier: MIT
// Author: HereLiesAz

uniform vec2 center;      // default (0.5, 0.5)
uniform float smoothness; // default 0.1

vec4 transition(vec2 uv) {
  float dist = distance(uv, center);
  // grow past the far corner (0.5*sqrt(2)) so the reveal completes fully
  float radius = progress * (0.7072 + smoothness);
  // m = 1 inside the circle (show incoming), 0 outside (show outgoing)
  float m = smoothstep(radius, radius - smoothness, dist);
  return mix(getFromColor(uv), getToColor(uv), m);
}
