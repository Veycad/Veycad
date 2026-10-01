#version 300 es
precision highp float;

// Velocity Blur — GPU motion blur driven by the detected face/camera movement.
// uVelocity is the current motion vector in UV coordinates per transition frame.
// uAmount: recommended 0.0–1.0 after local motion analysis.

uniform sampler2D uTexture;
uniform vec2 uVelocity;
uniform float uAmount;
in vec2 vTexCoord;
out vec4 outColor;

vec4 sampleSafe(vec2 uv) {
  return texture(uTexture, clamp(uv, vec2(0.001), vec2(0.999)));
}

void main() {
  vec2 blur = clamp(uVelocity * uAmount, vec2(-0.14), vec2(0.14));
  vec4 color = vec4(0.0);

  // Center-weighted 11-tap blur. Stable frames remain sharp because blur is zero.
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

  outColor = color;
}
