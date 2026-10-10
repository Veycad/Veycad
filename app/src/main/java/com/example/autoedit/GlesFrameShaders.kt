package com.veycad.app

/** Single owner of the authored shader source; relocated without algorithm changes. */
internal object GlesFrameShaders {
internal const val VERTEX_SHADER = "attribute vec4 aPosition; attribute vec4 aTexCoord; uniform mat4 uTransform,uIncomingTexMatrix,uOutgoingTexMatrix; uniform vec2 uIncomingCrop,uOutgoingCrop; varying vec2 vIncomingTexCoord,vOutgoingTexCoord,vScreenTexCoord,vSemanticTexCoord,vOutputTexCoord; void main() { gl_Position = uTransform * aPosition; vec2 incoming=vec2(.5)+(aTexCoord.xy-vec2(.5))*uIncomingCrop; vec2 outgoing=vec2(.5)+(aTexCoord.xy-vec2(.5))*uOutgoingCrop; vIncomingTexCoord = (uIncomingTexMatrix * vec4(incoming,0.,1.)).xy; vOutgoingTexCoord = (uOutgoingTexMatrix * vec4(outgoing,0.,1.)).xy; vScreenTexCoord=incoming; vSemanticTexCoord=vec2(incoming.x,1.0-incoming.y); vOutputTexCoord=aTexCoord.xy; }"

internal const val POST_VERTEX_SHADER = "attribute vec4 aPosition; attribute vec4 aTexCoord; varying vec2 vTexCoord; void main(){gl_Position=aPosition;vTexCoord=aTexCoord.xy;}"
internal val POST_FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D uInput,uAuxiliary,uFlow,uDepth;
    uniform float uMode,uAmount,uFlowConfidence,uDepthConfidence;
    uniform vec2 uDirection,uTexel;
    vec3 screenBlend(vec3 base,vec3 glow){return 1.0-(1.0-base)*(1.0-glow);}
    void main(){
        vec4 source=texture2D(uInput,vTexCoord);
        if(uMode<.5){
            gl_FragColor=source;
        }else if(uMode<1.5){
            vec2 measured=texture2D(uFlow,vTexCoord).ra*2.0-1.0;
            vec2 direction=normalize(mix(uDirection,measured,clamp(uFlowConfidence,0.,.72))+vec2(.0001,0.));
            vec2 stepUv=direction*uAmount*.24;
            vec4 blurred=texture2D(uInput,vTexCoord-stepUv*3.0)*.07+
                texture2D(uInput,vTexCoord-stepUv*2.0)*.11+
                texture2D(uInput,vTexCoord-stepUv)*.20+
                source*.24+
                texture2D(uInput,vTexCoord+stepUv)*.20+
                texture2D(uInput,vTexCoord+stepUv*2.0)*.11+
                texture2D(uInput,vTexCoord+stepUv*3.0)*.07;
            gl_FragColor=blurred;
        }else if(uMode<2.5){
            float highlight=max(max(source.r,source.g),source.b);
            float gate=smoothstep(.54,.91,highlight);
            gl_FragColor=vec4(source.rgb*gate,1.0);
        }else if(uMode<3.5){
            vec2 stepUv=uDirection*uTexel;
            vec4 blurred=texture2D(uInput,vTexCoord-stepUv*4.0)*.05+
                texture2D(uInput,vTexCoord-stepUv*2.0)*.12+
                texture2D(uInput,vTexCoord-stepUv)*.20+
                source*.26+
                texture2D(uInput,vTexCoord+stepUv)*.20+
                texture2D(uInput,vTexCoord+stepUv*2.0)*.12+
                texture2D(uInput,vTexCoord+stepUv*4.0)*.05;
            gl_FragColor=blurred;
        }else if(uMode<4.5){
            vec3 glow=texture2D(uAuxiliary,vTexCoord).rgb*clamp(uAmount*2.2,0.,.72);
            gl_FragColor=vec4(screenBlend(source.rgb,glow),source.a);
        }else{
            float depth=texture2D(uDepth,vTexCoord).r;
            vec2 direction=normalize(uDirection+vec2(.0001,0.));
            float separation=(.58-depth)*uAmount*uDepthConfidence;
            vec2 shifted=clamp(vTexCoord+direction*separation*.045,vec2(.002),vec2(.998));
            vec4 parallax=texture2D(uInput,shifted);
            float subject=smoothstep(.68,.22,depth)*clamp(uDepthConfidence,0.,1.);
            gl_FragColor=mix(parallax,source,subject);
        }
    }
""".trimIndent()
/** Both samplers are external OES textures backed directly by separate MediaCodec decoders.
 * Five shifted samples give the whip its directional motion blur. */
internal val TRANSITION_FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vIncomingTexCoord,vOutgoingTexCoord,vScreenTexCoord,vSemanticTexCoord,vOutputTexCoord;
    uniform highp vec2 uIncomingCrop;
    uniform samplerExternalOES uIncoming;
    uniform samplerExternalOES uOutgoing;
    uniform sampler2D uMask,uDepth,uFlow,uOpeningTitleTexture;
    uniform highp mat4 uIncomingTexMatrix;
    uniform highp mat4 uOutgoingTexMatrix;
    uniform float uUseTransition,uIncomingAlpha,uOutgoingAlpha,uBlur,uBlackout,uOcclusion,uForegroundMode,uForegroundReentry,uOriginalBackgroundReveal,uOutlineStrength,uOpeningAccentPulse,uIncomingExposure,uOutgoingExposure;
    uniform float uLayerOpacity,uLayerMode,uLayerKind,uLayerProgress;
    uniform float uHeartbeatEcho;
    uniform float uHeartbeatProfile;
    uniform float uFearProfile;
    uniform float uDualityProfile;
    uniform float uSigmaProfile,uFinalFade,uEntranceTravel;
    uniform float uOutputTime;
    uniform float uTextureProbe;
    uniform float uFaceRegionProbe;
    uniform float uHeartbeatImagePivot;
    uniform float uOpeningTitle;
    uniform float uTitleOpacity,uTitleAtlasRows,uTitleBandCenter,uTitleBandHeight,uTitleBaseOpacity;
    uniform vec2 uIncomingOffset,uOutgoingOffset;
    uniform vec2 uMaskTexel;
    uniform float uMaskIsOpacity;
    uniform vec4 uFaceRegion;
    uniform float uFaceRegionConfidence;
    uniform vec3 uIncomingColourBias,uOutgoingColourBias,uLayerColour;
    uniform vec3 uAttachmentConfidence;
    uniform vec4 uPostEffects;
    uniform vec2 uDefocusRadius;
    vec3 grade(vec3 c,float exposure,vec3 bias){
        if(uDualityProfile>.5){
            // Soft colour-domain protection works for either decoder, without borrowing
            // the primary source's face matte for a frame from the second source.
            float warm=smoothstep(.015,.10,c.r-c.b)*(1.-smoothstep(.16,.34,c.r-c.g));
            float skin=warm*smoothstep(.10,.28,c.r)*(1.-smoothstep(.82,1.,c.r));
            vec3 exposed=clamp(c*(1.+exposure*.55),0.,1.);
            vec3 toned=pow(exposed,vec3(1.40));
            toned=clamp((toned-.42)*1.12+.42,0.,1.);
            toned=toned/(vec3(1.)+toned*.16);
            vec3 protectedSkin=mix(exposed,toned,.48);
            toned=mix(toned,protectedSkin,skin);
            float luma=dot(toned,vec3(.299,.587,.114));
            toned=mix(vec3(luma),toned,mix(.78,.98,skin));
            float shadow=(1.-smoothstep(.08,.48,luma))*(1.-skin*.90);
            toned+=vec3(-.006,.009,.017)*shadow;
            return clamp(toned,0.,1.);
        }
        if(uHeartbeatProfile>.5&&exposure>1.){
            // Heartbeat's two measured light plates approach white while retaining the hero's
            // eyes, hair and outline. Linear gain clipped the bright selfie source into a
            // featureless white patch. A white-point lift preserves relative contrast.
            c=clamp(c*(1.+bias),0.,1.);
            c=mix(c,vec3(1.),1.-exp(-exposure*.55));
        }else{
            c=clamp(c*(1.0+exposure+bias),0.,1.);
        }
        // Across non-pulse frames the current Heartbeat candidate decoded at .455 mean luma
        // versus .419 in the author reference. A restrained style-local toe restores that
        // darker tonal bed without touching the two authored white plates (exposure > 1),
        // measured flash overlays, or Sigma's separate rendering profile.
        if(uHeartbeatProfile>.5&&exposure<=1.)c=pow(c,vec3(1.10));
        if(uFearProfile>.5){
            // The dark, contrasty reference should not crush footage that is already
            // dim. The director applies negative exposure only to bright source moments.
            float brightSource=clamp(-exposure/.24,0.,1.);
            c=pow(c,vec3(mix(1.04,1.24,brightSource)));
            c=clamp((c-.5)*mix(1.08,1.18,brightSource)+.5,0.,1.);
            float cascade=step(5.1,uOutputTime)*(1.-step(16.8333,uOutputTime));
            float opener=1.-step(3.6,uOutputTime);
            c=clamp(c+vec3(.18)*brightSource*cascade*
                smoothstep(vec3(.30),vec3(.72),c),0.,1.);
            // A bright source graded down to FEAR's dark bed must retain texture in
            // hair and clothing; the earlier contrast toe made both flat black.
            c=clamp(c+vec3(.055*cascade+.09*opener)*brightSource*
                (vec3(1.)-smoothstep(vec3(.02),vec3(.18),c)),0.,1.);
        }
        if(uSigmaProfile>.5){
            c=pow(c,vec3(1.38))*.90;
            float sigmaLuma=dot(c,vec3(.299,.587,.114));
            c=mix(vec3(sigmaLuma),c,.92);
        }
        float luma=dot(c,vec3(.299,.587,.114));
        c=mix(vec3(luma),c,1.055);
        return clamp((c-.5)*1.035+.5,0.,1.);
    }
    vec3 screenBlend(vec3 base,vec3 layer){return 1.0-(1.0-base)*(1.0-layer);}
    vec3 overlayBlend(vec3 base,vec3 layer){return mix(2.0*base*layer,1.0-2.0*(1.0-base)*(1.0-layer),step(.5,base));}
    vec3 softLightBlend(vec3 base,vec3 layer){return (1.0-2.0*layer)*base*base+2.0*layer*base;}
    float faceProtection(){
        float facePadding=mix(.72,.52,step(.5,uSigmaProfile));
        vec2 halfSize=max(uFaceRegion.zw*facePadding,vec2(.025));
        vec2 distanceToFace=abs(vSemanticTexCoord-uFaceRegion.xy)/halfSize;
        return (1.0-smoothstep(.78,1.08,max(distanceToFace.x,distanceToFace.y)))*uFaceRegionConfidence;
    }
    // One dominant live pose, with small subordinate silhouette repeats. No unrelated
    // face or background enters the composition; offsets are in output-frame units.
    vec3 sigmaContourEcho(vec3 base,float personMask,float spread){
        float pulse=sin(clamp(uLayerProgress,0.,1.)*3.14159265);
        float reliable=smoothstep(.65,.90,uAttachmentConfidence.x);
        float core=smoothstep(.30,.75,personMask);
        vec3 result=base;
        for(int side=0;side<2;side++){
            float direction=side==0?-1.:1.;
            vec2 raw=vScreenTexCoord+vec2(direction*spread*pulse,.004*pulse)*uIncomingCrop;
            float valid=step(0.,raw.x)*step(raw.x,1.)*step(0.,raw.y)*step(raw.y,1.);
            vec2 maskUv=vec2(raw.x,1.-raw.y);
            float shifted=smoothstep(.30,.75,texture2D(uMask,clamp(maskUv,vec2(.002),vec2(.998))).r);
            float edge=max(shifted-core,0.)*valid*reliable;
            vec2 uv=(uIncomingTexMatrix*vec4(raw,0.,1.)).xy;
            vec3 echo=grade(texture2D(uIncoming,clamp(uv,vec2(.002),vec2(.998))).rgb,
                uIncomingExposure,uIncomingColourBias);
            float amount=uLayerOpacity*pulse*(1.-faceProtection()*.95);
            result=mix(result,echo,edge*amount*.28);
            result+=vec3(.20,.18,.15)*edge*amount;
        }
        return clamp(result,0.,1.);
    }
    vec3 applyLayer(vec3 base,float personMask){
        if(uLayerOpacity<=.001)return base;
        if(uLayerKind>4.5&&uLayerKind<5.5)return mix(base,vec3(0.0),clamp(uLayerOpacity,0.,1.));
        if(uLayerKind>5.5&&uLayerKind<6.5){
            if(uSigmaProfile>.5){
                // Preserve the real scene and the available body; never shrink a cropped
                // torso onto black. A narrow light edge separates the live subject.
                float expanded=max(max(texture2D(uMask,vSemanticTexCoord+vec2(uMaskTexel.x,0.)).r,
                    texture2D(uMask,vSemanticTexCoord-vec2(uMaskTexel.x,0.)).r),
                    max(texture2D(uMask,vSemanticTexCoord+vec2(0.,uMaskTexel.y)).r,
                    texture2D(uMask,vSemanticTexCoord-vec2(0.,uMaskTexel.y)).r));
                float rim=max(0.,expanded-personMask)*smoothstep(.65,.90,uAttachmentConfidence.x);
                return clamp(base+vec3(.18,.16,.13)*rim*uLayerOpacity,0.,1.);
            }
            if(uMaskIsOpacity>.5){
                float panel=smoothstep(.18,.28,vScreenTexCoord.x)*(1.0-smoothstep(.70,.82,vScreenTexCoord.x));
                vec3 stage=vec3(.004,.003,.009)+uLayerColour*panel*.72;
                return mix(base,mix(stage,base,clamp(personMask,0.,1.)),clamp(uLayerOpacity,0.,1.));
            }
            // The raw selfie matte contains bright background spill around hair and shoulders.
            // A one-texel cross erosion keeps those source pixels out of the retained subject;
            // the discarded fringe is replaced by a restrained coloured rim, not a white halo.
            vec2 matteStep=uMaskTexel*.65;
            float stageEroded=min(personMask,min(min(
                texture2D(uMask,vSemanticTexCoord+vec2(matteStep.x,0.)).r,
                texture2D(uMask,vSemanticTexCoord-vec2(matteStep.x,0.)).r),min(
                texture2D(uMask,vSemanticTexCoord+vec2(0.,matteStep.y)).r,
                texture2D(uMask,vSemanticTexCoord-vec2(0.,matteStep.y)).r)));
            float stageFaceProtection=faceProtection();
            float stageBlend=smoothstep(.20,1.0,uAttachmentConfidence.x);
            float subject=mix(smoothstep(.42,.76,stageEroded),smoothstep(.36,.68,stageEroded),stageBlend*0.28)*uAttachmentConfidence.x;
            // The live finale must not punch a transient hole through the face when the
            // temporally changing matte softens. Keep this local to the measured face region;
            // the rest of the silhouette still follows the semantic mask frame by frame.
            subject=max(subject,stageFaceProtection*.97*uAttachmentConfidence.x);
            float stageExpanded=smoothstep(.22,.56,personMask)*uAttachmentConfidence.x;
            float rim=max(0.,stageExpanded-subject)*.11;
            float panel=smoothstep(.18,.28,vScreenTexCoord.x)*(1.0-smoothstep(.70,.82,vScreenTexCoord.x));
            vec3 stage=vec3(.004,.003,.009)+uLayerColour*panel*.72;
            float stageInterior=smoothstep(.50,.82,stageEroded);
            vec3 stageSubject=mix(base*.64,base,stageInterior);
            float stageLuma=dot(stageSubject,vec3(.299,.587,.114));
            float stageChroma=max(max(stageSubject.r,stageSubject.g),stageSubject.b)-
                min(min(stageSubject.r,stageSubject.g),stageSubject.b);
            float stageSpill=smoothstep(.70,.96,stageLuma)*(1.0-smoothstep(.06,.20,stageChroma))*
                smoothstep(.02,.28,max(0.,personMask-stageEroded));
            stageSubject=mix(stageSubject,mix(stageSubject*.34,vec3(.18,.045,.24),.32),stageSpill*.88);
            // Restore face readability after spill suppression; doing this before the spill
            // pass left the final live frames too dark for both the viewer and face QA.
            stageSubject=mix(stageSubject,base,stageFaceProtection*.82);
            vec3 isolated=mix(stage,stageSubject,clamp(subject,0.,1.));
            vec3 edgeColour=mix(base*.48,vec3(.20,.045,.28),.42);
            isolated=mix(isolated,edgeColour,rim);
            return mix(base,isolated,clamp(uLayerOpacity,0.,1.));
        }
        if(uLayerKind>2.5&&uLayerKind<3.5){
            if(uHeartbeatEcho>.5){
                // At 14.2 s the reference has several silhouettes separated by roughly a
                // shoulder width; they converge through 15.2 s. The previous .14 step remained
                // a soft halo on static footage. Keep the radial grammar, but give its first
                // frame enough separation to read before the model envelope resolves it.
                float echoStrength=clamp(uLayerOpacity/.62,0.,1.);
                float zoomStep=.05+.17*echoStrength;
                // Face landmarks use top-down semantic UV; SurfaceTexture owns rotation
                // and its Y flip. Scale around the face without suppressing the whole trail.
                vec2 faceRawUv=vec2(uFaceRegion.x,1.-uFaceRegion.y);
                vec2 faceTextureUv=(uOutgoingTexMatrix*vec4(faceRawUv,0.,1.)).xy;
                vec2 pivot=mix(vec2(.5),faceTextureUv,smoothstep(.4,.8,uFaceRegionConfidence));
                vec2 rawPivot=mix(vec2(.5),faceRawUv,smoothstep(.4,.8,uFaceRegionConfidence));
                if(uHeartbeatImagePivot>.5){pivot=vec2(.5);rawPivot=vec2(.5);}
                vec2 centred=vOutgoingTexCoord-pivot;
                // The reference's copies do not form concentric rings: through 14.2–14.9 s
                // their heads and shoulders trail diagonally before resolving. Add a bounded
                // directional component to the same three samples; it vanishes with the shot.
                vec2 trailDrift=vec2(.038,-.014)*echoStrength;
                vec2 trailUv1=clamp(pivot+centred/(1.+zoomStep)+trailDrift,vec2(.002),vec2(.998));
                vec2 trailUv2=clamp(pivot+centred/(1.+zoomStep*2.)+trailDrift*2.,vec2(.002),vec2(.998));
                vec2 trailUv3=clamp(pivot+centred/(1.+zoomStep*3.)+trailDrift*3.,vec2(.002),vec2(.998));
                vec3 trailing=texture2D(uOutgoing,trailUv1).rgb*.55+
                    texture2D(uOutgoing,trailUv2).rgb*.30+
                    texture2D(uOutgoing,trailUv3).rgb*.15;
                trailing=grade(trailing,uOutgoingExposure,uOutgoingColourBias);
                // The reference displaces the moving figure much more strongly than the
                // surrounding plate. Reuse the already PTS-bound matte at the same three
                // inverse transforms: keep a restrained full-frame trail, then restore the
                // authored strength where a transformed subject copy actually exists.
                vec2 rawCentred=vScreenTexCoord-rawPivot;
                vec2 rawTrailDrift=vec2(.038,.014)*echoStrength;
                vec2 rawTrail1=clamp(rawPivot+rawCentred/(1.+zoomStep)+rawTrailDrift,
                    vec2(.002),vec2(.998));
                vec2 rawTrail2=clamp(rawPivot+rawCentred/(1.+zoomStep*2.)+rawTrailDrift*2.,
                    vec2(.002),vec2(.998));
                vec2 rawTrail3=clamp(rawPivot+rawCentred/(1.+zoomStep*3.)+rawTrailDrift*3.,
                    vec2(.002),vec2(.998));
                float trailMask=texture2D(uMask,vec2(rawTrail1.x,1.-rawTrail1.y)).r*.55+
                    texture2D(uMask,vec2(rawTrail2.x,1.-rawTrail2.y)).r*.30+
                    texture2D(uMask,vec2(rawTrail3.x,1.-rawTrail3.y)).r*.15;
                float maskReliability=smoothstep(.72,.92,uAttachmentConfidence.x);
                float silhouetteGate=mix(1.,.18+.82*smoothstep(.24,.66,trailMask),maskReliability);
                return mix(base,trailing,clamp(uLayerOpacity,0.,.62)*silhouetteGate);
            }
            if(uSigmaProfile>.5)return sigmaContourEcho(base,personMask,.022);
            float echoPulse=sin(uLayerProgress*3.14159265);
            vec2 drift=vec2(mix(-.105,.078,uLayerProgress),mix(.014,-.010,uLayerProgress));
            vec2 echoUv=clamp(vOutgoingTexCoord+drift*echoPulse,vec2(.002),vec2(.998));
            vec2 counterUv=clamp(vOutgoingTexCoord-drift*1.30*echoPulse,vec2(.002),vec2(.998));
            vec3 echo=texture2D(uOutgoing,echoUv).rgb;
            vec3 counterEcho=texture2D(uOutgoing,counterUv).rgb;
            echo=grade(echo,uOutgoingExposure,uOutgoingColourBias);
            counterEcho=grade(counterEcho,uOutgoingExposure,uOutgoingColourBias);
            vec3 threeLayer=mix(mix(base,echo,.52),counterEcho,.30);
            float echoOpacity=uLayerOpacity*1.45;
            float readableOpacity=clamp(echoOpacity,0.,.82)*
                (1.0-faceProtection()*.68);
            return mix(base,threeLayer,readableOpacity);
        }
        if(uLayerKind>3.5){
            if(uSigmaProfile>.5)return sigmaContourEcho(base,personMask,.040);
            // One soft vertical temporal reflection replaces the former six horizontal bands.
            // The seam drifts across the frame and is feathered, so there are no stacked bars.
            float mirrorPulse=sin(uLayerProgress*3.14159265);
            float verticalSeam=mix(.62,.38,uLayerProgress);
            float reflectedSide=smoothstep(verticalSeam-.055,verticalSeam+.055,vScreenTexCoord.x);
            vec2 mirrorUv=vec2(1.0-vOutgoingTexCoord.x,vOutgoingTexCoord.y);
            mirrorUv.x=clamp(mirrorUv.x+(uLayerProgress-.5)*.025,.002,.998);
            vec3 mirrored=texture2D(uOutgoing,mirrorUv).rgb;
            mirrored=grade(mirrored,uOutgoingExposure,uOutgoingColourBias);
            float readableSplit=clamp(uLayerOpacity*reflectedSide*mirrorPulse*.84,0.,.58)*
                (1.0-faceProtection()*.72);
            return mix(base,mirrored,readableSplit);
        }
        vec3 blended=screenBlend(base,uLayerColour);
        if(uLayerMode>.5&&uLayerMode<1.5)blended=base*uLayerColour;
        else if(uLayerMode>=1.5&&uLayerMode<2.5)blended=overlayBlend(base,uLayerColour);
        else if(uLayerMode>=2.5)blended=softLightBlend(base,uLayerColour);
        vec2 centred=vIncomingTexCoord-vec2(.5);
        float radial=clamp(1.0-length(centred)*1.55,0.0,1.0);
        float shape=1.0;
        if(uLayerKind>.5&&uLayerKind<1.5)shape=radial;
        else if(uLayerKind>=1.5)shape=1.0-radial;
        return mix(base,blended,clamp(uLayerOpacity*shape,0.0,1.0));
    }
    vec3 applyPost(vec3 base){
        vec2 centred=vIncomingTexCoord-vec2(.5);
        vec2 radial=centred*dot(centred,centred)*uPostEffects.z*.045;
        vec3 lens=vec3(
            texture2D(uIncoming,vIncomingTexCoord-radial).r,
            texture2D(uIncoming,vIncomingTexCoord).g,
            texture2D(uIncoming,vIncomingTexCoord+radial).b
        );
        base=mix(base,lens,clamp(uPostEffects.z*2.2,0.,.42));
        vec2 glitchOffset=vec2(uPostEffects.y*uPostEffects.w*.018,0.);
        vec3 split=vec3(
            texture2D(uIncoming,vIncomingTexCoord+glitchOffset).r,
            base.g,
            texture2D(uIncoming,vIncomingTexCoord-glitchOffset).b
        );
        base=mix(base,split,clamp(uPostEffects.y*2.4,0.,.55));
        if(uSigmaProfile>.5&&uPostEffects.y>.001){
            float band=floor(vScreenTexCoord.y*7.);
            float stepPhase=floor(uOutputTime*24.);
            float offset=(mod(band+stepPhase,3.)-1.)*uPostEffects.y*.28;
            vec2 bandUv=clamp((uIncomingTexMatrix*
                vec4(vScreenTexCoord+vec2(offset,0.),0.,1.)).xy,vec2(.002),vec2(.998));
            vec3 bandColour=grade(texture2D(uIncoming,bandUv).rgb,
                uIncomingExposure,uIncomingColourBias);
            bandColour*=1.-step(1.,mod(band+stepPhase,3.))*min(.30,uPostEffects.y*.75);
            base=mix(base,bandColour,clamp(uPostEffects.y*2.,0.,.9));
        }
        float highlight=max(max(base.r,base.g),base.b);
        vec3 glowColour=screenBlend(base,base*min(1.0,highlight*1.35));
        return mix(base,glowColour,clamp(uPostEffects.x,0.,.45));
    }
    vec3 applyFearTexture(vec3 base){
        if(uFearProfile<.5)return base;
        float scan=sin(vScreenTexCoord.y*1280.*3.14159265)*.006;
        float seed=dot(floor(vScreenTexCoord*vec2(720.,1280.)),vec2(12.9898,78.233))+floor(uOutputTime*30.)*.071;
        float grain=(fract(sin(seed)*43758.5453)-.5)*.022;
        float vignette=smoothstep(.82,.20,length(vScreenTexCoord-vec2(.5)));
        base=clamp(base+scan+grain-(1.-vignette)*.045,0.,1.);
        float opener=1.-step(3.6,uOutputTime);
        if(opener>.5){
            // The reference opening has a cool, dense film surface even before the title.
            vec3 toned=pow(base,vec3(1.30));
            toned=clamp((toned-.43)*1.14+.43,0.,1.);
            float luma=dot(toned,vec3(.299,.587,.114));
            toned=mix(toned,vec3(luma*.90,luma*.98,luma*1.08),.28);
            float fine=(fract(sin(seed*1.57)*28657.312)-.5)*.065;
            base=mix(base,toned,mix(.90,.65,faceProtection()))+fine;
            vec2 edgeOffset=vec2(.0032,0.);
            float colourEdge=texture2D(uIncoming,clamp(vIncomingTexCoord+edgeOffset,
                vec2(.002),vec2(.998))).r-
                texture2D(uIncoming,clamp(vIncomingTexCoord-edgeOffset,
                vec2(.002),vec2(.998))).r;
            base+=vec3(.30,0.,-.30)*colourEdge;
        }
        float titleTexture=smoothstep(3.6,4.5,uOutputTime)*(1.-step(5.1,uOutputTime));
        if(titleTexture>.001){
            // The title in the author edit gains a dense, cool film/CRT surface while
            // the FEAR lettering remains clean because it is composited afterward.
            vec3 toned=pow(base,vec3(1.10));
            toned=clamp((toned-.46)*1.10+.46,0.,1.);
            float luma=dot(toned,vec3(.299,.587,.114));
            toned=mix(toned,vec3(luma*.96,luma,luma*1.03),.18);
            base=mix(base,toned,titleTexture*.65);
            float column=mod(floor(gl_FragCoord.x),3.);
            vec3 rgbStripe=vec3(column<1.?1.08:.94,
                column>=1.&&column<2.?1.06:.95,column>=2.?1.08:.94);
            base*=mix(vec3(1.),rgbStripe,titleTexture*.52);
            float fine=(fract(sin(seed*1.93)*22578.1453)-.5)*.052;
            base+=fine*titleTexture;
        }
        return clamp(base,0.,1.);
    }
    void main(){
        if(uFaceRegionProbe>.5){
            vec3 diagnostic=texture2D(uIncoming,vIncomingTexCoord).rgb;
            vec2 faceDelta=(vSemanticTexCoord-uFaceRegion.xy)/max(uFaceRegion.zw*.5,vec2(.025));
            float core=(1.-smoothstep(.45,1.15,length(faceDelta)))*uFaceRegionConfidence;
            gl_FragColor=vec4(mix(diagnostic,vec3(0.,1.,0.),core*.8),1.);
            return;
        }
        if(uTextureProbe>.5){
            gl_FragColor=vScreenTexCoord.x<.5?texture2D(uIncoming,vIncomingTexCoord):texture2D(uOutgoing,vOutgoingTexCoord);
            return;
        }
        vec4 result;
        float rawPersonMask=texture2D(uMask,vSemanticTexCoord).r;
        float personMask=(rawPersonMask*4.0+
            texture2D(uMask,vSemanticTexCoord+vec2(uMaskTexel.x,0.)).r+
            texture2D(uMask,vSemanticTexCoord-vec2(uMaskTexel.x,0.)).r+
            texture2D(uMask,vSemanticTexCoord+vec2(0.,uMaskTexel.y)).r+
            texture2D(uMask,vSemanticTexCoord-vec2(0.,uMaskTexel.y)).r+
            texture2D(uMask,vSemanticTexCoord+uMaskTexel).r*.5+
            texture2D(uMask,vSemanticTexCoord-uMaskTexel).r*.5+
            texture2D(uMask,vSemanticTexCoord+vec2(uMaskTexel.x,-uMaskTexel.y)).r*.5+
            texture2D(uMask,vSemanticTexCoord+vec2(-uMaskTexel.x,uMaskTexel.y)).r*.5)/10.0;
        vec4 incomingBase=texture2D(uIncoming,vIncomingTexCoord);
        incomingBase.rgb=grade(incomingBase.rgb,uIncomingExposure,uIncomingColourBias);
        if(uSigmaProfile>.5&&uForegroundMode<.5){
            float subjectLight=smoothstep(.20,.72,personMask);
            float reliableSeparation=smoothstep(.65,.90,uAttachmentConfidence.x);
            float retainedLight=max(mix(.55,.92,subjectLight),faceProtection());
            incomingBase.rgb*=mix(1.,retainedLight,reliableSeparation);
        }
        if(uForegroundMode>.5&&uAttachmentConfidence.x>=.8){
            // The reference entrance is a moving live cutout, not a horizontal wipe over
            // a stationary subject. Translate each advancing texture and its PTS-aligned matte
            // from below the frame, then expose the untouched plate only after arrival.
            float entranceTravel=uEntranceTravel;
            // Author motion once in raw screen UV. The decoder image then goes through the
            // exact SurfaceTexture matrix, while the CPU matte uses its bitmap-space Y flip.
            vec2 subjectRawUv=vScreenTexCoord+vec2(0.,entranceTravel*uIncomingCrop.y);
            vec2 subjectUv=vec2(subjectRawUv.x,1.0-subjectRawUv.y);
            vec2 subjectTextureUv=(uIncomingTexMatrix*vec4(subjectRawUv,0.,1.)).xy;
            float validSubject=step(0.,subjectRawUv.y)*step(subjectRawUv.y,1.0);
            vec2 safeSubjectUv=clamp(subjectUv,vec2(.002),vec2(.998));
            vec2 safeTextureUv=clamp(subjectTextureUv,vec2(.002),vec2(.998));
            float shiftedRaw=texture2D(uMask,safeSubjectUv).r;
            float shiftedRight=texture2D(uMask,safeSubjectUv+vec2(uMaskTexel.x,0.)).r;
            float shiftedLeft=texture2D(uMask,safeSubjectUv-vec2(uMaskTexel.x,0.)).r;
            float shiftedDown=texture2D(uMask,safeSubjectUv+vec2(0.,uMaskTexel.y)).r;
            float shiftedUp=texture2D(uMask,safeSubjectUv-vec2(0.,uMaskTexel.y)).r;
            float shiftedMask=(shiftedRaw*4.0+
                shiftedRight+shiftedLeft+shiftedDown+shiftedUp+
                texture2D(uMask,safeSubjectUv+uMaskTexel).r*.5+
                texture2D(uMask,safeSubjectUv-uMaskTexel).r*.5+
                texture2D(uMask,safeSubjectUv+vec2(uMaskTexel.x,-uMaskTexel.y)).r*.5+
                texture2D(uMask,safeSubjectUv+vec2(-uMaskTexel.x,uMaskTexel.y)).r*.5)/10.0;
            float stageErodedAverage=(shiftedRaw*4.0+
                shiftedRight+shiftedLeft+shiftedDown+shiftedUp+
                texture2D(uMask,safeSubjectUv+uMaskTexel).r*.35+
                texture2D(uMask,safeSubjectUv-uMaskTexel).r*.35+
                texture2D(uMask,safeSubjectUv+vec2(uMaskTexel.x,-uMaskTexel.y)).r*.35+
                texture2D(uMask,safeSubjectUv+vec2(-uMaskTexel.x,uMaskTexel.y)).r*.35)/9.4;
            float edgeTightening=smoothstep(.62,.78,uForegroundReentry);
            vec2 coreStep=uMaskTexel*mix(.65,2.88,edgeTightening);
            float shiftedCore=min(shiftedRaw,min(min(
                texture2D(uMask,safeSubjectUv+vec2(coreStep.x,0.)).r,
                texture2D(uMask,safeSubjectUv-vec2(coreStep.x,0.)).r),min(
                texture2D(uMask,safeSubjectUv+vec2(0.,coreStep.y)).r,
                texture2D(uMask,safeSubjectUv-vec2(0.,coreStep.y)).r)));
            float refinedMask=mix(shiftedMask,shiftedCore,mix(.38,.97,edgeTightening));
            float stableMask=smoothstep(
                mix(.42,.64,edgeTightening),
                mix(.72,.89,edgeTightening),
                mix(stageErodedAverage,refinedMask,.45))*validSubject;
            if(uSigmaProfile>.5){
                // Increasing multi-texel erosion ate the neck and collar late in the
                // entrance. Exact masks use a fixed edge treatment through the take.
                stableMask=smoothstep(.48,.80,mix(shiftedMask,stageErodedAverage,.25))*validSubject;
            }
            // Physical opacity already includes partial hair coverage. Class-confidence
            // thresholds/erosion must not turn a half-transparent strand into a hole.
            if(uMaskIsOpacity>.5)stableMask=clamp(shiftedRaw,0.,1.)*validSubject;
            vec4 subjectBase=texture2D(uIncoming,safeTextureUv);
            subjectBase.rgb=grade(subjectBase.rgb,uIncomingExposure,uIncomingColourBias);
            // Background colour is baked into semi-transparent boundary pixels. Walk along
            // the alpha gradient toward the subject interior and borrow only its colour,
            // leaving the original soft alpha and fine hair geometry intact.
            vec2 maskGradient=vec2(shiftedRight-shiftedLeft,shiftedDown-shiftedUp);
            vec2 interiorDirection=normalize(maskGradient+vec2(.00001));
            // Gradient offsets live in bitmap/mask coordinates, not decoder UV.
            // Map the neighbour through the same Y flip and SurfaceTexture transform
            // as the subject; otherwise colour can be borrowed from the background.
            vec2 interiorMaskUv=clamp(safeSubjectUv+interiorDirection*uMaskTexel*2.2,
                vec2(.002),vec2(.998));
            vec2 interiorRawUv=vec2(interiorMaskUv.x,1.-interiorMaskUv.y);
            vec2 interiorTextureUv=clamp((uIncomingTexMatrix*vec4(interiorRawUv,0.,1.)).xy,
                vec2(.002),vec2(.998));
            vec3 interiorColour=grade(texture2D(uIncoming,interiorTextureUv).rgb,
                uIncomingExposure,uIncomingColourBias);
            float fringe=smoothstep(.015,.22,max(0.,shiftedMask-shiftedCore))*
                smoothstep(.05,.48,shiftedMask)*validSubject;
            float colourMatteWeight=0.;
            if(uSigmaProfile>.5&&uMaskIsOpacity<.5){
                vec2 outerMaskUv=clamp(safeSubjectUv-interiorDirection*uMaskTexel*3.5,
                    vec2(.002),vec2(.998));
                float outerMask=texture2D(uMask,outerMaskUv).r;
                float innerMask=texture2D(uMask,interiorMaskUv).r;
                vec2 outerRawUv=vec2(outerMaskUv.x,1.-outerMaskUv.y);
                vec2 outerTextureUv=(uIncomingTexMatrix*vec4(outerRawUv,0.,1.)).xy;
                vec3 foregroundRgb=texture2D(uIncoming,interiorTextureUv).rgb;
                vec3 backgroundRgb=texture2D(uIncoming,outerTextureUv).rgb;
                vec3 observedRgb=texture2D(uIncoming,safeTextureUv).rgb;
                vec3 separation=foregroundRgb-backgroundRgb;
                float contrast=dot(separation,separation);
                colourMatteWeight=smoothstep(.80,.94,innerMask)*
                    (1.-smoothstep(.12,.24,outerMask))*smoothstep(.006,.025,contrast)*
                    smoothstep(.10,.38,shiftedRaw)*(1.-smoothstep(.90,.98,shiftedRaw));
                float colourAlpha=clamp(dot(observedRgb-backgroundRgb,separation)/
                    max(contrast,.000001),0.,1.);
                // Recover foreground colour before grading. The alpha follows actual RGB
                // coverage; ambiguous low-contrast boundaries retain the semantic matte.
                vec3 recoveredRgb=clamp((observedRgb-backgroundRgb*(1.-colourAlpha))/
                    max(colourAlpha,.08),0.,1.);
                stableMask=mix(stableMask,min(stableMask,colourAlpha*validSubject),colourMatteWeight);
                subjectBase.rgb=mix(subjectBase.rgb,
                    grade(recoveredRgb,uIncomingExposure,uIncomingColourBias),colourMatteWeight);
            }
            if(uMaskIsOpacity<.5)subjectBase.rgb=mix(subjectBase.rgb,interiorColour,
                fringe*.88*(1.-colourMatteWeight));
            subjectBase.rgb=mix(subjectBase.rgb,screenBlend(subjectBase.rgb,vec3(.34,.08,.48)),
                uOpeningAccentPulse*.22);
            float solidInterior=smoothstep(.46,.80,shiftedRaw);
            if(uMaskIsOpacity<.5)subjectBase.rgb=mix(subjectBase.rgb*.64,subjectBase.rgb,solidInterior);
            float subjectLuma=dot(subjectBase.rgb,vec3(.299,.587,.114));
            float subjectChroma=max(max(subjectBase.r,subjectBase.g),subjectBase.b)-
                min(min(subjectBase.r,subjectBase.g),subjectBase.b);
            float edgeSpill=smoothstep(.70,.96,subjectLuma)*(1.0-smoothstep(.06,.20,subjectChroma))*
                smoothstep(.02,.28,max(0.,shiftedMask-shiftedCore));
            if(uMaskIsOpacity<.5)subjectBase.rgb=mix(subjectBase.rgb,mix(subjectBase.rgb*.34,vec3(.18,.045,.24),.32),edgeSpill*.88);
            // Erosion and colour borrowing are edge operations. They must not turn an
            // eyebrow into a hole or smear its texture inside a confidently detected face.
            // Use the translated mask coordinates so this protection travels with the head.
            vec2 faceDistance=(safeSubjectUv-uFaceRegion.xy)/max(uFaceRegion.zw*.42,vec2(.001));
            float innerFace=(1.-smoothstep(.70,1.,length(faceDistance)))*
                step(.65,uFaceRegionConfidence)*validSubject;
            if(uMaskIsOpacity<.5){
                stableMask=max(stableMask,innerFace*smoothstep(.25,.60,shiftedMask));
                subjectBase.rgb=mix(subjectBase.rgb,
                    grade(texture2D(uIncoming,safeTextureUv).rgb,uIncomingExposure,uIncomingColourBias),innerFace);
            }
            vec4 darkStage=vec4(vec3(.004),1.);
            vec4 background=mix(darkStage,incomingBase,clamp(uOriginalBackgroundReveal,0.,1.));
            // The 0.551 s author accent must remain visible even when the subject occupies
            // only a small part of a static source. Pulse the dark stage itself; this is
            // musical punctuation, not a substitute crossfade or a white flash.
            background.rgb=screenBlend(background.rgb,
                vec3(.055,.012,.085)*uOpeningAccentPulse);
            // The authored entrance has a readable, enlarged echo behind the live subject,
            // not a generic one-pixel halo. Build it from the same advancing decoder frame
            // and physical matte so hair/body motion stays live and PTS-aligned.
            vec2 ghostAnchor=vec2(.50,.48);
            float ghostScale=mix(1.18,${SigmaComposition.OPENING_GHOST_SCALE},step(.5,uSigmaProfile));
            vec2 ghostOffset=mix(vec2(.12,-.035),vec2(${SigmaComposition.OPENING_GHOST_X},${SigmaComposition.OPENING_GHOST_Y}),step(.5,uSigmaProfile));
            vec2 ghostRawUv=ghostAnchor+
                (subjectRawUv-ghostOffset-ghostAnchor)/ghostScale;
            vec2 ghostMaskUv=clamp(vec2(ghostRawUv.x,1.-ghostRawUv.y),vec2(.002),vec2(.998));
            float validGhost=step(0.,ghostRawUv.x)*step(ghostRawUv.x,1.)*
                step(0.,ghostRawUv.y)*step(ghostRawUv.y,1.);
            float ghostRawMask=texture2D(uMask,ghostMaskUv).r;
            float ghostMask=mix(smoothstep(.48,.76,ghostRawMask),ghostRawMask,
                step(.5,uMaskIsOpacity))*validGhost;
            vec2 ghostTextureUv=clamp((uIncomingTexMatrix*vec4(ghostRawUv,0.,1.)).xy,
                vec2(.002),vec2(.998));
            vec3 ghostColour=grade(texture2D(uIncoming,ghostTextureUv).rgb,
                uIncomingExposure,uIncomingColourBias);
            ghostColour=screenBlend(ghostColour*.66,vec3(.22,.24,.30));
            background.rgb=mix(background.rgb,ghostColour,
                ghostMask*uOutlineStrength*mix(.56,.04,step(.5,uSigmaProfile)));
            result=mix(background,subjectBase,stableMask*smoothstep(0.,.16,uForegroundReentry));
        }else if(uUseTransition<.5){
            result=incomingBase;
        }else{
            vec2 inUv=vIncomingTexCoord+uIncomingOffset;
            vec2 outUv=vOutgoingTexCoord+uOutgoingOffset;
            vec2 encodedFlow=texture2D(uFlow,vSemanticTexCoord).ra;
            vec2 measuredFlow=encodedFlow*2.0-1.0;
            vec2 blurDirection=normalize(mix(uIncomingOffset,measuredFlow,uAttachmentConfidence.z)+vec2(.0001,0.));
            vec2 inBlur=blurDirection*uBlur;
            vec2 outBlur=blurDirection*uBlur*.65;
            vec4 incoming=(texture2D(uIncoming,inUv-inBlur*2.0)+texture2D(uIncoming,inUv-inBlur)+
                texture2D(uIncoming,inUv)+texture2D(uIncoming,inUv+inBlur)+texture2D(uIncoming,inUv+inBlur*2.0))/5.0;
            vec4 outgoing=(texture2D(uOutgoing,outUv-outBlur*2.0)+texture2D(uOutgoing,outUv-outBlur)+
                texture2D(uOutgoing,outUv)+texture2D(uOutgoing,outUv+outBlur)+texture2D(uOutgoing,outUv+outBlur*2.0))/5.0;
            incoming.rgb=grade(incoming.rgb,uIncomingExposure,uIncomingColourBias);
            outgoing.rgb=grade(outgoing.rgb,uOutgoingExposure,uOutgoingColourBias);
            float directionalMask=smoothstep(.12,.88,vIncomingTexCoord.x);
            float depth=texture2D(uDepth,vSemanticTexCoord).r;
            float semanticMask=mix(directionalMask,personMask,uAttachmentConfidence.x);
            semanticMask=clamp(semanticMask+(depth-.5)*uAttachmentConfidence.y*.35,0.,1.);
            vec4 temporalBlend=outgoing*uOutgoingAlpha+incoming*uIncomingAlpha;
            vec4 semanticBlend=mix(outgoing,incoming,clamp(uIncomingAlpha+uOcclusion*(semanticMask-.5),0.,1.));
            result=mix(temporalBlend,semanticBlend,clamp(uOcclusion,0.,1.));
            result=mix(result,vec4(0.,0.,0.,1.),uBlackout);
        }
        // Defocus is an independent source operation before authored flashes/echo.
        // Zero radius leaves the legacy/Sigma pipeline byte-for-byte on its old branch.
        if(uDefocusRadius.x>.00001&&uForegroundMode<.5&&uUseTransition<.5){
            vec3 soft=vec3(0.);float weight=0.;
            // A sparse regular grid creates repeated collar/hair contours. Distribute
            // taps over a fixed disk instead; no frame-random noise or moving grain.
            for(int i=0;i<64;i++){
                float radius=sqrt((float(i)+.5)/64.);
                float angle=float(i)*2.39996323;
                float w=exp(-2.0*radius*radius);
                vec2 offset=vec2(cos(angle),sin(angle))*radius*uDefocusRadius;
                soft+=texture2D(uIncoming,clamp(vIncomingTexCoord+offset,vec2(.002),vec2(.998))).rgb*w;
                weight+=w;
            }
            result.rgb=grade(soft/weight,uIncomingExposure,uIncomingColourBias);
            if(uFearProfile>.5){
                // The first two frames of each authored phrase entry pull the soft image
                // toward the centre, giving the blur the reference's brief zoom direction.
                float smear=clamp((uDefocusRadius.x/.045-.25)/.47,0.,1.);
                if(smear>.001){
                    vec2 ray=vIncomingTexCoord-vec2(.5);
                    vec3 radial=vec3(0.);
                    for(int tap=0;tap<8;tap++){
                        float distance=float(tap)/7.;
                        vec2 uv=clamp(vIncomingTexCoord-ray*distance*.075*smear,
                            vec2(.002),vec2(.998));
                        radial+=texture2D(uIncoming,uv).rgb;
                    }
                    vec3 directional=grade(radial/8.,uIncomingExposure,uIncomingColourBias);
                    result.rgb=mix(result.rgb,directional,smear*.34);
                }
            }
        }
        vec3 beforeLayer=result.rgb;
        result.rgb=applyLayer(result.rgb,personMask);
        if(uHeartbeatEcho>.5&&uLayerOpacity>.001){
            vec2 faceDelta=(vSemanticTexCoord-uFaceRegion.xy)/max(uFaceRegion.zw*.5,vec2(.025));
            float readableFace=(1.-smoothstep(.45,1.15,length(faceDelta)))*uFaceRegionConfidence;
            // The author reference begins strong echo shots with multiple readable heads and
            // resolves to one face. A constant .70 restore pinned one sharp face over the
            // strongest trail and made it look like a translucent haze. Preserve readability
            // as the measured layer resolves, but let peak echo displace the face itself.
            float faceRestore=mix(.70,.30,clamp(uLayerOpacity/.62,0.,1.));
            result.rgb=mix(result.rgb,beforeLayer,readableFace*faceRestore);
        }
        if(uForegroundMode<.5&&uLayerOpacity<.999)result.rgb=applyPost(result.rgb);
        if(uLayerOpacity<.999)result.rgb=applyFearTexture(result.rgb);
        if(uOpeningTitle>.5){
            float row=floor(uOpeningTitle-.5);
            float bandStart=uTitleBandCenter-uTitleBandHeight*.5;
            float bandEnd=uTitleBandCenter+uTitleBandHeight*.5;
            float localY=clamp((vOutputTexCoord.y-bandStart)/uTitleBandHeight,0.,1.);
            float inBand=step(bandStart,vOutputTexCoord.y)*(1.-step(bandEnd,vOutputTexCoord.y));
            float titleAlpha=texture2D(uOpeningTitleTexture,
                vec2(vOutputTexCoord.x,(row+1.-localY)/uTitleAtlasRows)).a*inBand;
            result.rgb=mix(result.rgb,vec3(.94),titleAlpha*uTitleBaseOpacity*uTitleOpacity);
        }
        result.rgb*=1.-uFinalFade;
        gl_FragColor=result;
    }
""".trimIndent()
    const val OUTPUT_FRAGMENT_SHADER = "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uInput; void main(){gl_FragColor=texture2D(uInput,vTexCoord);}"
}
