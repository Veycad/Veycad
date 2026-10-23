package com.veycad.app

/** Framing wraps source reads, so authored effects and both temporal roles retain their own sampler. */
internal object GlesFramingShaders {
    fun vertex(original: String): String = original
        .replace("void main()", "uniform highp vec2 uFitEnabled; uniform highp vec4 uIncomingFit; void main()")
        .replace("vScreenTexCoord=incoming; vSemanticTexCoord=vec2(incoming.x,1.0-incoming.y);",
            "vec2 semantic=incoming;if(uFitEnabled.x>.5)semantic=(aTexCoord.xy-uIncomingFit.xy)/(uIncomingFit.zw-uIncomingFit.xy);vScreenTexCoord=semantic;vSemanticTexCoord=vec2(semantic.x,1.-semantic.y);")

    fun fragment(original: String): String {
        var value = original
        for ((sampler, function) in listOf("uIncoming" to "framedIncoming", "uOutgoing" to "framedOutgoing",
            "uMask" to "framedMask", "uDepth" to "framedDepth", "uFlow" to "framedFlow")) {
            value = value.replace(Regex("texture2D\\($sampler\\s*,"), "$function(")
        }
        val at = value.indexOf("vec3 grade(")
        require(at >= 0) { "Framing source wrapper must precede the artistic functions" }
        return value.substring(0, at) + FUNCTIONS + value.substring(at)
    }

    private const val FUNCTIONS = """
    uniform highp vec2 uFitEnabled;
    uniform highp vec4 uIncomingFit,uOutgoingFit;
    uniform highp mat4 uIncomingTextureInverse,uOutgoingTextureInverse;
    uniform sampler2D uFramingBackground;
    uniform vec2 uFramingAtlasTexel;
    vec2 fitBackgroundUv(vec2 p,float page){
        vec2 lower=vec2(page*.5,0.)+.5*uFramingAtlasTexel;
        vec2 upper=vec2((page+1.)*.5,1.)-.5*uFramingAtlasTexel;
        return mix(lower,upper,clamp(p,0.,1.));
    }
    bool fitOutside(vec2 p,vec4 bounds){return any(lessThan(p,bounds.xy))||any(greaterThanEqual(p,bounds.zw));}
    vec4 framedIncoming(vec2 uv){
        if(uFitEnabled.x<.5)return texture2D(uIncoming,uv);
        vec2 p=(uIncomingTextureInverse*vec4(uv,0.,1.)).xy;
        if(fitOutside(p,uIncomingFit))return texture2D(uFramingBackground,fitBackgroundUv(p,0.));
        vec2 source=(p-uIncomingFit.xy)/(uIncomingFit.zw-uIncomingFit.xy);
        return texture2D(uIncoming,(uIncomingTexMatrix*vec4(source,0.,1.)).xy);
    }
    vec4 framedOutgoing(vec2 uv){
        if(uFitEnabled.y<.5)return texture2D(uOutgoing,uv);
        vec2 p=(uOutgoingTextureInverse*vec4(uv,0.,1.)).xy;
        if(fitOutside(p,uOutgoingFit))return texture2D(uFramingBackground,fitBackgroundUv(p,1.));
        vec2 source=(p-uOutgoingFit.xy)/(uOutgoingFit.zw-uOutgoingFit.xy);
        return texture2D(uOutgoing,(uOutgoingTexMatrix*vec4(source,0.,1.)).xy);
    }
    bool outsideSemantic(vec2 p){return uFitEnabled.x>.5&&(any(lessThan(p,vec2(0.)))||any(greaterThanEqual(p,vec2(1.))));}
    vec4 framedMask(vec2 uv){return outsideSemantic(uv)?vec4(0.):texture2D(uMask,uv);}
    vec4 framedDepth(vec2 uv){return outsideSemantic(uv)?vec4(0.):texture2D(uDepth,uv);}
    vec4 framedFlow(vec2 uv){return outsideSemantic(uv)?vec4(0.):texture2D(uFlow,uv);}
"""
}
