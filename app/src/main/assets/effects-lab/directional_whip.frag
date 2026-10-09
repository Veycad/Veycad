#version 300 es
precision highp float;

// Directional Whip — a physical side-to-side cut transition.
// uProgress: 0.0 = settled image, 1.0 = maximum transition energy.
// uDirection: normalized screen-space direction, alternating for each cut.
// uStrength: recommended 0.035–0.12 in UV space.

uniform sampler2D uTexture;
uniform vec2 uDirection;
uniform float uProgress;
uniform float uStrength;
in vec2 vTexCoord;
out vec4 outColor;

vec4 sampleSafe(vec2 uv) {
  return texture(uTexture, clamp(uv, vec2(0.001), vec2(0.999)));
}

void main() {
  float energy = pow(clamp(uProgress, 0.0, 1.0), 1.7);
  vec2 travel = normalize(uDirection + vec2(0.00001)) * uStrength * energy;

  // Nine-tap directional integration: the frame smears only along the whip direction.
  vec4 color = vec4(0.0);
  color += sampleSafe(vTexCoord - 1.00 * travel) * 0.03;
  color += sampleSafe(vTexCoord - 0.75 * travel) * 0.05;
  color += sampleSafe(vTexCoord - 0.50 * travel) * 0.09;
  color += sampleSafe(vTexCoord - 0.25 * travel) * 0.13;
  color += sampleSafe(vTexCoord)                  * 0.40;
  color += sampleSafe(vTexCoord + 0.25 * travel) * 0.13;
  color += sampleSafe(vTexCoord + 0.50 * travel) * 0.09;
  color += sampleSafe(vTexCoord + 0.75 * travel) * 0.05;
  color += sampleSafe(vTexCoord + 1.00 * travel) * 0.03;

  // A restrained RGB split gives the cut a sense of velocity without a colour wash.
  float split = 0.008 * energy;
  color.r = sampleSafe(vTexCoord + travel * split * 16.0).r;
  color.b = sampleSafe(vTexCoord - travel * split * 16.0).b;
  outColor = color;
}
