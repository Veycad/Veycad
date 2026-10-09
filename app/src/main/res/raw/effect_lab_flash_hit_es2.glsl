precision highp float;

uniform sampler2D uTexSampler;
uniform float uProgress;
varying vec2 vTexCoord;

void main() {
  vec4 base = texture2D(uTexSampler, clamp(vTexCoord, vec2(0.001), vec2(0.999)));
  float hit = pow(clamp(uProgress, 0.0, 1.0), 2.2);
  // Short neutral highlight, with only a restrained cool split in the first frames.
  vec3 flash = mix(base.rgb, vec3(1.0), hit * 0.40);
  flash.r += hit * 0.025;
  flash.b += hit * 0.055;
  gl_FragColor = vec4(clamp(flash, 0.0, 1.0), base.a);
}
