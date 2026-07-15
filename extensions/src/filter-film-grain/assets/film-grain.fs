/*{
  "DESCRIPTION": "Animated film grain with a subtle vignette.",
  "CREDIT": "HereLiesAz",
  "ISFVSN": "2",
  "LICENSE": "MIT",
  "CATEGORIES": ["Stylize", "Film"],
  "INPUTS": [
    { "NAME": "amount", "TYPE": "float", "DEFAULT": 0.08, "MIN": 0.0, "MAX": 0.5, "LABEL": "Grain" },
    { "NAME": "vignette", "TYPE": "float", "DEFAULT": 0.3, "MIN": 0.0, "MAX": 1.0, "LABEL": "Vignette" }
  ]
}*/

// Cheap hash noise, re-seeded each frame by TIME so the grain moves.
float rand(vec2 co) {
  return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
  vec4 c = IMG_THIS_PIXEL(inputImage);
  vec2 uv = isf_FragNormCoord;

  float g = (rand(uv * (mod(TIME, 100.0) + 1.0)) - 0.5) * amount;
  c.rgb += g;

  // 1.4142 normalises the centre-to-corner distance to ~1.
  float v = 1.0 - vignette * distance(uv, vec2(0.5)) * 1.4142;
  c.rgb *= clamp(v, 0.0, 1.0);

  gl_FragColor = c;
}
