precision highp float;

uniform sampler2D uTexSampler;
uniform vec2 uVelocity;
uniform float uAmount;
varying vec2 vTexCoord;

vec4 sampleSafe(vec2 uv) {
  return texture2D(uTexSampler, clamp(uv, vec2(0.001), vec2(0.999)));
}

void main() {
  vec2 blur = clamp(uVelocity * uAmount, vec2(-0.14), vec2(0.14));
  vec4 color = vec4(0.0);
  color += sampleSafe(vTexCoord - 1.00 * blur) * 0.02;
  color += sampleSafe(vTexCoord - 0.80 * blur) * 0.04;
  color += sampleSafe(vTexCoord - 0.60 * blur) * 0.07;
  color += sampleSafe(vTexCoord - 0.40 * blur) * 0.10;
  color += sampleSafe(vTexCoord - 0.20 * blur) * 0.14;
  color += sampleSafe(vTexCoord)               * 0.26;
  color += sampleSafe(vTexCoord + 0.20 * blur) * 0.14;
  color += sampleSafe(vTexCoord + 0.40 * blur) * 0.10;
  color += sampleSafe(vTexCoord + 0.60 * blur) * 0.07;
  color += sampleSafe(vTexCoord + 0.80 * blur) * 0.04;
  color += sampleSafe(vTexCoord + 1.00 * blur) * 0.02;
  gl_FragColor = color;
}
