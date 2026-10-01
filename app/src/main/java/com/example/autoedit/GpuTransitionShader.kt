package com.example.autoedit

/** GLSL contract for the owned two-source compositor; consumed by the GLES export/preview backend. */
object GpuTransitionShader {
    const val fragment = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uOutgoing;
        uniform samplerExternalOES uIncoming;
        uniform float uIncomingAlpha;
        uniform float uOutgoingAlpha;
        uniform vec2 uOutgoingOffset;
        uniform vec2 uIncomingOffset;
        uniform float uDirectionalBlur;
        uniform float uBlackout;
        uniform float uOcclusionMask;
        uniform float uIncomingExposure;
        uniform float uOutgoingExposure;
        vec4 blur(samplerExternalOES tex, vec2 uv, float amount) {
          vec2 d = vec2(amount, 0.0);
          return (texture2D(tex, uv-d) + 2.0*texture2D(tex, uv) + texture2D(tex, uv+d)) * 0.25;
        }
        void main() {
          vec4 outgoing = blur(uOutgoing, vTexCoord + uOutgoingOffset, uDirectionalBlur) * uOutgoingAlpha;
          vec4 incoming = blur(uIncoming, vTexCoord + uIncomingOffset, uDirectionalBlur) * uIncomingAlpha;
          outgoing.rgb = clamp(outgoing.rgb * (1.0 + uOutgoingExposure), 0.0, 1.0);
          incoming.rgb = clamp(incoming.rgb * (1.0 + uIncomingExposure), 0.0, 1.0);
          vec4 composed = outgoing + incoming;
          composed.rgb *= (1.0 - uBlackout);
          composed.rgb = mix(composed.rgb, incoming.rgb, uOcclusionMask * uIncomingAlpha);
          gl_FragColor = vec4(composed.rgb, 1.0);
        }
    """
}
