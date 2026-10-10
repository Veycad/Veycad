package com.veycad.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


/** Shared artistic GLES renderer; the target owns EGL and presentation. Task6b adds project framing. */
internal class GlesFrameCompositor(
    private val target: RenderTarget,
    private val renderPlan: RenderPassPlanner.Plan,
    private val attachmentTimeline: FrameAttachmentTimeline,
    private val debugTextureProbe: Boolean = false,
    private val debugProbeIncomingOnBothUnits: Boolean = false,
    private val debugHeartbeatImagePivot: Boolean = false,
    private val debugFaceRegionProbe: Boolean = false,
    private val authoredTitle: AuthoredTitleProfile.Spec? = null,
    private val heartbeatProfile: Boolean = false,
    private val fearProfile: Boolean = false,
    private val dualityProfile: Boolean = false,
    private val sigmaProfile: Boolean = false,
    private val sourceCrops: List<SourceFraming.Crop> = listOf(SourceFraming.Crop(1f, 1f)),
    private val onDraw: (DrawEvidence) -> Unit = {},
    private val onPassExecuted: (RenderPassPlanner.PassKind) -> Unit = {},
    private val programDriver: GlProgramOwnership.Driver = GlesProgram
) : AutoCloseable {
    private val width = target.size.width
    private val height = target.size.height
    private val worker = Thread.currentThread()
    data class DrawEvidence(
        val frame: HighQualityFramePlan.Frame, val blend: GpuTransitionModel.FrameBlend?,
        val dualDecoder: Boolean, val secondarySourceTimeUs: Long?,
        val decodedSourceTimeUs: Long, val decodedSecondarySourceTimeUs: Long?,
        val secondarySourceIndex: Int? = null
    )
    private lateinit var incomingInput: DecoderInput
    private lateinit var outgoingInput: DecoderInput
    private var program = 0
    private var postProgram = 0
    private var maskTexture = 0
    private var depthTexture = 0
    private var flowTexture = 0
    private var openingTitleTexture = 0
    private val positionBuffer: FloatBuffer
    private val texBuffer: FloatBuffer
    private var sceneTarget: OffscreenTarget? = null
    private var motionTarget: OffscreenTarget? = null
    private var depthTarget: OffscreenTarget? = null
    private var glowTargetA: OffscreenTarget? = null
    private var glowTargetB: OffscreenTarget? = null
    private var released = false
    private val passExecutions = linkedMapOf<RenderPassPlanner.PassKind, Int>()
    private var heartbeatTextureLogged = false
    private val fearPivotByClip = mutableMapOf<Int, Pair<Float, Float>>()

    init {
        try {
            makeCurrent()
            incomingInput = DecoderInput(0)
            outgoingInput = DecoderInput(1)
            program = createProgram(GlesFrameShaders.VERTEX_SHADER, GlesFrameShaders.TRANSITION_FRAGMENT_SHADER)
            postProgram = createProgram(GlesFrameShaders.POST_VERTEX_SHADER, GlesFrameShaders.POST_FRAGMENT_SHADER)
            maskTexture = create2dTexture()
            depthTexture = create2dTexture()
            flowTexture = create2dTexture()
            openingTitleTexture = createOpeningTitleTexture(authoredTitle)
            positionBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            // SurfaceTexture's matrix owns the decoder-to-GL orientation. These coordinates must
            // remain unflipped or Samsung CameraX masters are rendered upside down.
            texBuffer = floatBuffer(VideoDisplayOrientation.externalOesTextureCoordinates())
            val hasMotionPass = renderPlan.passes.any { it.kind == RenderPassPlanner.PassKind.DIRECTIONAL_BLUR }
            val hasDepthPass = renderPlan.passes.any { it.kind == RenderPassPlanner.PassKind.DEPTH_COMPOSITE }
            val hasGlowPass = renderPlan.passes.any { it.kind == RenderPassPlanner.PassKind.GLOW_EXTRACT }
            val requiresOffscreen = hasMotionPass || hasDepthPass || hasGlowPass
            sceneTarget = if (requiresOffscreen) createOffscreenTarget(width, height) else null
            motionTarget = renderPlan.passes.firstOrNull {
                it.kind == RenderPassPlanner.PassKind.DIRECTIONAL_BLUR
            }?.let { createOffscreenTarget(scaled(width, it.resolutionScale), scaled(height, it.resolutionScale)) }
            depthTarget = renderPlan.passes.firstOrNull {
                it.kind == RenderPassPlanner.PassKind.DEPTH_COMPOSITE
            }?.let { createOffscreenTarget(scaled(width, it.resolutionScale), scaled(height, it.resolutionScale)) }
            val glowScale = renderPlan.passes.firstOrNull {
                it.kind == RenderPassPlanner.PassKind.GLOW_EXTRACT
            }?.resolutionScale
            glowTargetA = glowScale?.let { createOffscreenTarget(scaled(width, it), scaled(height, it)) }
            glowTargetB = glowScale?.let { createOffscreenTarget(scaled(width, it), scaled(height, it)) }
        } catch (error: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    val decodeSurface: Surface get() { makeCurrent(); return incomingInput.surface }
    val incomingDecodeSurface: Surface get() { makeCurrent(); return incomingInput.surface }
    val outgoingDecodeSurface: Surface get() { makeCurrent(); return outgoingInput.surface }
    fun awaitDecoderFrame() = awaitIncomingDecoderFrame()
    fun awaitIncomingDecoderFrame() { makeCurrent(); incomingInput.awaitFrame() }
    fun awaitOutgoingDecoderFrame() { makeCurrent(); outgoingInput.awaitFrame() }
    fun updateTexture() = updateIncomingTexture()
    fun updateIncomingTexture() = incomingInput.update()
    fun updateOutgoingTexture() = outgoingInput.update()
    fun executedPasses(): Map<RenderPassPlanner.PassKind, Int> { checkWorker(); return passExecutions.toMap() }

    fun drawScheduled(frame: HighQualityFramePlan.Frame, sourceRotation: Int) {
        drawInternal(frame, null, sourceRotation, sourceRotation)
    }

    fun drawOverlap(
        incomingFrame: HighQualityFramePlan.Frame,
        outgoingFrame: HighQualityFramePlan.Frame,
        incomingRotation: Int,
        outgoingRotation: Int
    ) {
        // The geometry follows the incoming clip's transform. Each decoder keeps its own
        // SurfaceTexture matrix, so sampling never substitutes a historical output frame.
        drawInternal(incomingFrame, outgoingFrame, incomingRotation, outgoingRotation)
    }

    fun drawTemporalLayer(
        incomingFrame: HighQualityFramePlan.Frame,
        temporalFrame: HighQualityFramePlan.Frame,
        incomingRotation: Int,
        temporalRotation: Int
    ) {
        require(MediaCodecSpeedRampRenderer.isTemporalLayer(incomingFrame.layer.kind, sigmaProfile))
        drawInternal(incomingFrame, temporalFrame, incomingRotation, temporalRotation)
    }

    private fun drawInternal(
        incomingFrame: HighQualityFramePlan.Frame,
        outgoingFrame: HighQualityFramePlan.Frame?,
        incomingRotation: Int,
        outgoingRotation: Int
    ) {
        makeCurrent()
        if (sceneTarget == null) bindOutput() else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneTarget!!.framebuffer)
            GLES20.glViewport(0, 0, sceneTarget!!.width, sceneTarget!!.height)
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val transform = FloatArray(16); Matrix.setIdentityM(transform, 0)
        val incomingCrop = sourceCrops[incomingFrame.sourceIndex]
        val fearPivot = if (fearProfile && incomingFrame.transform.scale > 1.0001f) {
            fearPivotByClip.getOrPut(incomingFrame.clipIndex) {
                val face = incomingFrame.attachments?.faceRegion
                if (face != null && face.confidence >= .75f) {
                    Pair(
                        (.5f + (face.centerX - .5f) / incomingCrop.x).coerceIn(.2f, .8f),
                        (.5f + (face.centerY - .5f) / incomingCrop.y).coerceIn(.2f, .8f)
                    )
                } else Pair(.5f, .5f)
            }
        } else Pair(.5f, .5f)
        val pivotDx = (1f - incomingFrame.transform.scale) * (fearPivot.first * 2f - 1f)
        val pivotDy = (1f - incomingFrame.transform.scale) * (1f - fearPivot.second * 2f)
        Matrix.translateM(transform, 0,
            incomingFrame.transform.translateX * 2f + pivotDx,
            incomingFrame.transform.translateY * 2f + pivotDy, 0f)
        val outputAspect = width.toFloat() / height
        // Rotate in pixel space: an NDC rotation stretches circles in a rectangular viewport.
        Matrix.scaleM(transform, 0, 1f / outputAspect, 1f, 1f)
        Matrix.rotateM(
            transform,
            0,
            VideoDisplayOrientation.rendererGeometryRotation(
                incomingFrame.transform.rotationDegrees,
                incomingRotation
            ),
            0f,
            0f,
            1f
        )
        Matrix.scaleM(transform, 0, outputAspect, 1f, 1f)
        Matrix.scaleM(transform, 0, incomingFrame.transform.scale, incomingFrame.transform.scale, 1f)
        val outgoingCrop = sourceCrops[outgoingFrame?.sourceIndex ?: incomingFrame.sourceIndex]
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uIncomingCrop"), incomingCrop.x, incomingCrop.y)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOutgoingCrop"), outgoingCrop.x, outgoingCrop.y)
        val incomingTexMatrix = incomingInput.transformMatrix()
        val outgoingTexMatrix = outgoingInput.transformMatrix()
        if (incomingFrame.layer.heartbeatEcho && !heartbeatTextureLogged) {
            heartbeatTextureLogged = true
            android.util.Log.d("HeartbeatTexture", "incoming=${incomingInput.texture}:${incomingInput.timestampUs()} " +
                "outgoing=${outgoingInput.texture}:${outgoingInput.timestampUs()} " +
                "matrix=${outgoingTexMatrix.joinToString(",")}")
        }
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uIncomingTexMatrix"), 1, false, incomingTexMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uOutgoingTexMatrix"), 1, false, outgoingTexMatrix, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val tex = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(tex); GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        val blend = TransitionTimeline.blendFor(incomingFrame)?.takeIf {
            outgoingFrame != null || incomingFrame.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        }
        // This call is adjacent to glDrawArrays on purpose: the Inspector reports technology
        // that reached the shader, not merely effects present in a MontageGraph.
        val actualPts = incomingInput.timestampUs()
        val secondarySamplerInput = MediaCodecSpeedRampRenderer.boundSecondaryInput(
            incomingInput, outgoingInput, debugTextureProbe, debugProbeIncomingOnBothUnits
        )
        val attachments = if (
            incomingFrame.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY ||
                sigmaProfile
        ) {
            attachmentTimeline.interpolated(actualPts)
        } else incomingFrame.attachments
        onDraw(DrawEvidence(
            incomingFrame.copy(attachments = attachments),
            blend,
            outgoingFrame != null,
            outgoingFrame?.sourceTimeUs,
            incomingInput.timestampUs(),
            outgoingFrame?.let { secondarySamplerInput.timestampUs() },
            outgoingFrame?.let {
                MediaCodecSpeedRampRenderer.boundSecondaryInput(incomingFrame, it,
                    debugTextureProbe, debugProbeIncomingOnBothUnits).sourceIndex
            }
        ))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, incomingInput.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uIncoming"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, secondarySamplerInput.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uOutgoing"), 1)
        bindPlaneTexture(
            2,
            maskTexture,
            attachments?.mask,
            attachments?.maskBlendTarget,
            attachments?.maskBlendProgress ?: 0f
        )
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMask"), 2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMaskIsOpacity"),
            if (attachments?.maskIsOpacity == true) 1f else 0f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uMaskTexel"),
            1f / (attachments?.mask?.width ?: 1).toFloat(),
            1f / (attachments?.mask?.height ?: 1).toFloat()
        )
        bindPlaneTexture(3, depthTexture, attachments?.depth)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDepth"), 3)
        bindFlowTexture(4, flowTexture, attachments?.flow)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFlow"), 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, openingTitleTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uOpeningTitleTexture"), 5)
        val openingTitle = authoredTitle?.sampleAt?.invoke(incomingFrame.outputTimeUs)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uOpeningTitle"),
            openingTitle?.let { it.atlasRow + 1f } ?: 0f
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTitleOpacity"),
            openingTitle?.opacity ?: 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTitleAtlasRows"),
            authoredTitle?.texts?.size?.toFloat() ?: 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTitleBandCenter"),
            authoredTitle?.bandCenter ?: .5f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTitleBandHeight"),
            authoredTitle?.bandHeight ?: .05f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTitleBaseOpacity"),
            authoredTitle?.baseOpacity ?: 0f)
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(program, "uAttachmentConfidence"),
            attachments?.mask?.confidence ?: 0f,
            attachments?.depth?.confidence ?: 0f,
            attachments?.flow?.confidence ?: 0f
        )
        val faceRegion = attachments?.faceRegion
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uFaceRegion"),
            faceRegion?.centerX ?: 0f,
            faceRegion?.centerY ?: 0f,
            faceRegion?.width ?: 0f,
            faceRegion?.height ?: 0f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uFaceRegionConfidence"),
            faceRegion?.confidence ?: 0f
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uUseTransition"), if (blend == null) 0f else 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uIncomingAlpha"), blend?.incomingAlpha ?: 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOutgoingAlpha"), blend?.outgoingAlpha ?: 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uIncomingOffset"), blend?.incomingOffsetX ?: 0f, blend?.incomingOffsetY ?: 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOutgoingOffset"), blend?.outgoingOffsetX ?: 0f, blend?.outgoingOffsetY ?: 0f)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uBlur"),
            if (motionTarget == null) blend?.directionalBlur ?: 0f else 0f
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlackout"), blend?.blackout ?: 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOcclusion"), blend?.occlusionMask ?: 0f)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uForegroundReentry"),
            blend?.foregroundReentry ?: 0f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uForegroundMode"),
            if (blend != null && incomingFrame.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY) 1f else 0f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uOriginalBackgroundReveal"),
            blend?.originalBackgroundReveal ?: 0f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uOutlineStrength"),
            blend?.outlineStrength ?: 0f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uOpeningAccentPulse"),
            blend?.openingAccentPulse ?: 0f
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uIncomingExposure"), incomingFrame.exposureBias)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOutgoingExposure"), outgoingFrame?.exposureBias ?: 0f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uIncomingColourBias"), incomingFrame.redBias, incomingFrame.greenBias, incomingFrame.blueBias)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uOutgoingColourBias"), outgoingFrame?.redBias ?: 0f, outgoingFrame?.greenBias ?: 0f, outgoingFrame?.blueBias ?: 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLayerOpacity"), incomingFrame.layer.opacity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uFinalFade"), incomingFrame.layer.finalFadeOpacity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSigmaProfile"), if (sigmaProfile) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uEntranceTravel"),
            if (sigmaProfile) SigmaComposition.entranceTravel(incomingFrame.outputTimeUs)
            else ForegroundReentryMotion.verticalTravel(blend?.foregroundReentry ?: 0f))
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLayerColour"), incomingFrame.layer.red, incomingFrame.layer.green, incomingFrame.layer.blue)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLayerMode"), incomingFrame.layer.blendMode.ordinal.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLayerKind"), incomingFrame.layer.kind.ordinal.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLayerProgress"), incomingFrame.layer.progress)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uHeartbeatEcho"), if (incomingFrame.layer.heartbeatEcho) 1f else 0f)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uHeartbeatProfile"),
            if (heartbeatProfile) 1f else 0f
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uFearProfile"),
            if (fearProfile) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDualityProfile"),
            if (dualityProfile) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOutputTime"),
            incomingFrame.outputTimeUs / 1_000_000f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTextureProbe"), if (debugTextureProbe) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uFaceRegionProbe"), if (debugFaceRegionProbe) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uHeartbeatImagePivot"), if (debugHeartbeatImagePivot) 1f else 0f)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uPostEffects"),
            if (glowTargetA == null) incomingFrame.effects.glow else 0f,
            incomingFrame.effects.glitch,
            incomingFrame.effects.lensBlur,
            incomingFrame.effects.direction
        )
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uDefocusRadius"),
            incomingFrame.effects.defocus * .045f, incomingFrame.effects.defocus * .045f * width / height)
        if (incomingFrame.layer.heartbeatEcho &&
            (incomingFrame.outputTimeUs == 500_000L || incomingFrame.outputTimeUs == 15_600_000L)) {
            fun uniform(name: String, count: Int): String {
                val location = GLES20.glGetUniformLocation(program,name)
                if (location < 0) return "$name=inactive"
                val values = FloatArray(count)
                GLES20.glGetUniformfv(program,location,values,0)
                return "$name=${values.joinToString(",") }"
            }
            android.util.Log.d("HeartbeatUniform", "pts=${incomingFrame.outputTimeUs} " +
                listOf(uniform("uFaceRegion",4),uniform("uFaceRegionConfidence",1),
                    uniform("uHeartbeatEcho",1),uniform("uLayerOpacity",1),
                    uniform("uLayerKind",1)).joinToString(" ") + " error=${GLES20.glGetError()}")
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        recordPass(RenderPassPlanner.PassKind.SOURCE_AND_CHEAP_EFFECTS)
        if (blend != null) {
            if (outgoingFrame != null) recordPass(RenderPassPlanner.PassKind.TRANSITION_COMPOSITE)
            if (blend.foregroundReentry > .001f && (attachments?.mask?.confidence ?: 0f) >= .8f) {
                recordPass(RenderPassPlanner.PassKind.FOREGROUND_COMPOSITE)
            }
        }
        if (incomingFrame.layer.kind == MontageGraph.OverlayKind.SUBJECT_STAGE &&
            incomingFrame.layer.opacity > .001f && (attachments?.mask?.confidence ?: 0f) >= .8f) {
            recordPass(RenderPassPlanner.PassKind.FOREGROUND_COMPOSITE)
        }
        sceneTarget?.let { renderPostPipeline(it, incomingFrame, blend, attachments) }
            ?: recordPass(RenderPassPlanner.PassKind.ENCODER_SURFACE)
        target.present(incomingFrame.outputTimeUs)
    }

    override fun close() {
        checkWorker()
        if (released) return
        released = true
        val current = runCatching { target.makeCurrent() }.isSuccess
        if (::incomingInput.isInitialized) runCatching { incomingInput.release(current) }
        if (::outgoingInput.isInitialized) runCatching { outgoingInput.release(current) }
        if (current) runCatching {
            GLES20.glUseProgram(0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteTextures(
                4,
                intArrayOf(maskTexture, depthTexture, flowTexture, openingTitleTexture),
                0
            )
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteProgram(postProgram)
            listOfNotNull(sceneTarget, motionTarget, depthTarget, glowTargetA, glowTargetB).forEach(::releaseTarget)
        }
        target.close()
    }

    private fun checkWorker() {
        check(Thread.currentThread() === worker) { "GLES compositor must be used on its owning GL worker" }
    }
    private fun makeCurrent() {
        checkWorker()
        check(!released) { "GLES compositor is closed" }
        target.makeCurrent()
    }
    private fun bindOutput() {
        if (target is ViewportRenderTarget) target.bindOutput() else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
        }
    }
    fun decoderSurface(slot: Int): Surface { makeCurrent(); return input(slot).surface }
    fun awaitTexture(slot: Int, checkCancelled: () -> Unit) {
        makeCurrent()
        input(slot).awaitFrame(checkCancelled)
        input(slot).update()
    }
    fun updateTexture(slot: Int) { makeCurrent(); input(slot).update() }
    private fun input(slot: Int): DecoderInput = when(slot) {
        0 -> incomingInput
        1 -> outgoingInput
        else -> throw IllegalArgumentException("Decoder slot must be 0 or 1")
    }

    private fun recordPass(kind: RenderPassPlanner.PassKind) {
        passExecutions[kind] = (passExecutions[kind] ?: 0) + 1
        onPassExecuted(kind)
    }
    private fun createExternalTexture(): Int = IntArray(1).also {
        GLES20.glGenTextures(1, it, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, it[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }[0]
    private fun create2dTexture(): Int = IntArray(1).also {
        GLES20.glGenTextures(1, it, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }[0]

    private fun createOpeningTitleTexture(spec: AuthoredTitleProfile.Spec?): Int {
        val texture = create2dTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (spec == null) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 1, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1)
            )
            return texture
        }
        val rowHeight = maxOf(64, kotlin.math.ceil(spec.textSizePx * 1.4f).toInt())
        val atlas = Bitmap.createBitmap(
            OPENING_TITLE_ATLAS_WIDTH,
            rowHeight * spec.texts.size,
            Bitmap.Config.ALPHA_8
        )
        val canvas = Canvas(atlas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = spec.textSizePx
            typeface = Typeface.create(
                if (spec.condensedBold) "sans-serif-condensed" else "sans-serif",
                if (spec.condensedBold) Typeface.BOLD else Typeface.NORMAL
            )
            letterSpacing = spec.letterSpacing
        }
        val metrics = paint.fontMetrics
        spec.texts.forEachIndexed { row, text ->
            val rowCenter = (row + .5f) * rowHeight
            val baseline = rowCenter - (metrics.ascent + metrics.descent) * .5f
            canvas.drawText(text, OPENING_TITLE_ATLAS_WIDTH * .5f, baseline, paint)
        }
        val bytes = ByteBuffer.allocateDirect(atlas.byteCount)
        atlas.copyPixelsToBuffer(bytes)
        bytes.position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA,
            atlas.width, atlas.height, 0,
            GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, bytes
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        atlas.recycle()
        return texture
    }

    private fun bindPlaneTexture(
        unit: Int,
        texture: Int,
        plane: FrameAttachments.Plane?,
        blendTarget: FrameAttachments.Plane? = null,
        blendProgress: Float = 0f
    ) {
        val width = plane?.width ?: 1
        val height = plane?.height ?: 1
        val bytes = ByteBuffer.allocateDirect(width * height)
        if (plane == null) {
            bytes.put(0)
        } else if (blendTarget != null && blendTarget.width == width && blendTarget.height == height) {
            plane.values.indices.forEach { index ->
                val value = plane.values[index] +
                    (blendTarget.values[index] - plane.values[index]) * blendProgress
                bytes.put((value.coerceIn(0f, 1f) * 255f).toInt().toByte())
            }
        } else {
            plane.values.forEach { bytes.put((it.coerceIn(0f, 1f) * 255f).toInt().toByte()) }
        }
        bytes.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        // Multiclass mattes preserve the source aspect ratio (for example 270 px wide).
        // GL's default four-byte row alignment otherwise advances an 8-bit mask by two
        // phantom bytes per row and turns the silhouette into diagonal black bands.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bytes)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
    }

    private fun bindFlowTexture(unit: Int, texture: Int, flow: FrameAttachments.FlowPlane?) {
        val width = flow?.width ?: 1
        val height = flow?.height ?: 1
        val bytes = ByteBuffer.allocateDirect(width * height * 2)
        if (flow == null) {
            bytes.put(127.toByte()); bytes.put(127.toByte())
        } else flow.vectors.forEach { component ->
            bytes.put((((component.coerceIn(-1f, 1f) * .5f + .5f) * 255f).toInt()).toByte())
        }
        bytes.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, width, height, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, bytes)
    }

    private data class OffscreenTarget(
        val framebuffer: Int,
        val texture: Int,
        val width: Int,
        val height: Int
    )

    private fun createOffscreenTarget(targetWidth: Int, targetHeight: Int): OffscreenTarget {
        val textures = IntArray(1)
        val framebuffers = IntArray(1)
        try {
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                targetWidth,
                targetHeight,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )
            GLES20.glGenFramebuffers(1, framebuffers, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                textures[0],
                0
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Unable to allocate ${targetWidth}x$targetHeight render pass"
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return OffscreenTarget(framebuffers[0], textures[0], targetWidth, targetHeight)
        } catch(error: Throwable) {
            GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            GLES20.glDeleteTextures(1, textures, 0)
            throw error
        } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
    }

    private fun releaseTarget(target: OffscreenTarget) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0)
    }

    private fun renderPostPipeline(
        scene: OffscreenTarget,
        frame: HighQualityFramePlan.Frame,
        blend: GpuTransitionModel.FrameBlend?,
        attachments: FrameAttachments?
    ) {
        val motionTarget = this.motionTarget
        val depthTarget = this.depthTarget
        val glowTargetA = this.glowTargetA
        val glowTargetB = this.glowTargetB
        var current = scene
        val motionAmount = blend?.directionalBlur ?: 0f
        if (motionTarget != null && motionAmount > .0001f) {
            val authoredX = (blend?.incomingOffsetX ?: 0f) - (blend?.outgoingOffsetX ?: 0f)
            val authoredY = (blend?.incomingOffsetY ?: 0f) - (blend?.outgoingOffsetY ?: 0f)
            drawPost(
                input = current,
                output = motionTarget,
                mode = 1f,
                amount = motionAmount,
                directionX = authoredX,
                directionY = authoredY,
                flowConfidence = attachments?.flow?.confidence ?: 0f
            )
            current = motionTarget
            recordPass(RenderPassPlanner.PassKind.DIRECTIONAL_BLUR)
        }

        val depthConfidence = attachments?.depth?.confidence ?: 0f
        val depthAmount = maxOf(
            blend?.occlusionMask ?: 0f,
            blend?.foregroundReentry ?: 0f
        ) * depthConfidence
        if (depthTarget != null && depthAmount > .001f) {
            val directionX = ((blend?.incomingOffsetX ?: 0f) -
                (blend?.outgoingOffsetX ?: 0f)).takeUnless { kotlin.math.abs(it) < .001f } ?: .35f
            val directionY = ((blend?.incomingOffsetY ?: 0f) -
                (blend?.outgoingOffsetY ?: 0f)).takeUnless { kotlin.math.abs(it) < .001f } ?: -.15f
            drawPost(
                input = current,
                output = depthTarget,
                mode = 5f,
                amount = depthAmount,
                directionX = directionX,
                directionY = directionY,
                depthConfidence = depthConfidence
            )
            current = depthTarget
            recordPass(RenderPassPlanner.PassKind.DEPTH_COMPOSITE)
        }

        val glowAmount = frame.effects.glow
        if (glowTargetA != null && glowTargetB != null && glowAmount > .0001f) {
            drawPost(current, glowTargetA, 2f, glowAmount)
            recordPass(RenderPassPlanner.PassKind.GLOW_EXTRACT)
            drawPost(glowTargetA, glowTargetB, 3f, glowAmount, 1f, 0f)
            drawPost(glowTargetB, glowTargetA, 3f, glowAmount, 0f, 1f)
            recordPass(RenderPassPlanner.PassKind.GLOW_BLUR)
            drawPost(current, null, 4f, glowAmount, auxiliary = glowTargetA)
            recordPass(RenderPassPlanner.PassKind.FINAL_COMPOSITE)
        } else {
            drawPost(current, null, 0f, 0f)
        }
        recordPass(RenderPassPlanner.PassKind.ENCODER_SURFACE)
    }

    private fun drawPost(
        input: OffscreenTarget,
        output: OffscreenTarget?,
        mode: Float,
        amount: Float,
        directionX: Float = 0f,
        directionY: Float = 0f,
        flowConfidence: Float = 0f,
        depthConfidence: Float = 0f,
        auxiliary: OffscreenTarget? = null
    ) {
        if (output == null) bindOutput() else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.framebuffer)
            GLES20.glViewport(0, 0, output.width, output.height)
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(postProgram)
        val position = GLES20.glGetAttribLocation(postProgram, "aPosition")
        val tex = GLES20.glGetAttribLocation(postProgram, "aTexCoord")
        positionBuffer.position(0)
        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uInput"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, auxiliary?.texture ?: 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uAuxiliary"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flowTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uFlow"), 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uDepth"), 3)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uMode"), mode)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uAmount"), amount)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uFlowConfidence"), flowConfidence)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uDepthConfidence"), depthConfidence)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(postProgram, "uDirection"), directionX, directionY)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(postProgram, "uTexel"),
            1f / input.width,
            1f / input.height
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun scaled(value: Int, scale: Float): Int = (value * scale).toInt().coerceAtLeast(1)

    private inner class DecoderInput(private val textureUnit: Int) {
        private val frameLock = Object()
        private var frameAvailable = false
        val texture = run {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + textureUnit)
            createExternalTexture()
        }
        private lateinit var surfaceTexture: SurfaceTexture
        val surface: Surface

        init {
            try {
                surfaceTexture = SurfaceTexture(texture)
                surfaceTexture.setDefaultBufferSize(width, height)
                surfaceTexture.setOnFrameAvailableListener {
                    synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() }
                }
                surface = Surface(surfaceTexture)
            } catch (error: Throwable) {
                if (::surfaceTexture.isInitialized) runCatching { surfaceTexture.release() }
                GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                throw error
            }
        }

        fun awaitFrame(checkCancelled: () -> Unit = {}) = synchronized(frameLock) {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!frameAvailable && System.nanoTime() < deadline) { checkCancelled(); frameLock.wait(20L) }
        checkCancelled()
            check(frameAvailable) { "Timed out waiting for decoded video frame" }
            frameAvailable = false
        }

        fun update() {
            makeCurrent()
            // Keep each EGL image attachment on its owning sampler unit, not whichever
            // semantic-plane upload happened to leave active during the preceding draw.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + textureUnit)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            surfaceTexture.updateTexImage()
        }
        fun timestampUs(): Long = surfaceTexture.timestamp / 1_000L
        fun transformMatrix(): FloatArray = FloatArray(16).also(surfaceTexture::getTransformMatrix)
        fun release(current: Boolean) {
            runCatching { surface.release() }
            runCatching { surfaceTexture.release() }
            if (current) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }
    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private companion object { const val OPENING_TITLE_ATLAS_WIDTH = 720 }
    private fun createProgram(vertex: String, fragment: String): Int = GlProgramOwnership.create(programDriver, vertex, fragment)
}
