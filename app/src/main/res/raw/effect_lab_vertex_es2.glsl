attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexCoord;

void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vTexCoord = (uTexTransformationMatrix * vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 1.0)).xy;
}
