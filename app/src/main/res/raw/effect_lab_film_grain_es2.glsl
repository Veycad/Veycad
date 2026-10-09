precision highp float;

uniform sampler2D uTexSampler;
uniform float uAmount;
varying vec2 vTexCoord;

float noise(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
  vec4 base = texture2D(uTexSampler, vTexCoord);
  float grain = (noise(floor(vTexCoord * 900.0)) - 0.5) * clamp(uAmount, 0.0, 0.08);
  base.rgb = clamp(base.rgb + vec3(grain), 0.0, 1.0);
  gl_FragColor = base;
}
