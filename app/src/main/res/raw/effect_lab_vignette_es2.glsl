precision highp float;

uniform sampler2D uTexSampler;
uniform float uAmount;
varying vec2 vTexCoord;

void main() {
  vec4 base = texture2D(uTexSampler, vTexCoord);
  vec2 centered = vTexCoord - vec2(0.5);
  float edge = smoothstep(0.22, 0.70, dot(centered, centered) * 2.0);
  base.rgb *= 1.0 - edge * clamp(uAmount, 0.0, 0.42);
  gl_FragColor = base;
}
