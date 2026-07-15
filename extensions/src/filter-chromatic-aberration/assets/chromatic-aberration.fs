/*{
  "DESCRIPTION": "Lens-style RGB channel split that grows toward the edges.",
  "CREDIT": "HereLiesAz",
  "ISFVSN": "2",
  "LICENSE": "MIT",
  "CATEGORIES": ["Stylize", "Lens"],
  "INPUTS": [
    { "NAME": "amount", "TYPE": "float", "DEFAULT": 0.005, "MIN": 0.0, "MAX": 0.05, "LABEL": "Amount" }
  ]
}*/

void main() {
  vec2 uv = isf_FragNormCoord;
  // offset grows with distance from centre, so the split is strongest at the edges
  vec2 off = (uv - vec2(0.5)) * amount;

  float r = IMG_NORM_PIXEL(inputImage, uv + off).r;
  float g = IMG_NORM_PIXEL(inputImage, uv).g;
  float b = IMG_NORM_PIXEL(inputImage, uv - off).b;
  float a = IMG_THIS_PIXEL(inputImage).a;

  gl_FragColor = vec4(r, g, b, a);
}
