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


/** Shared artistic GLES renderer; the target owns EGL and presentation. */
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
    private val programDriver: GlProgramOwnership.Driver = GlesProgram,
    private val sourceAttachments: List<SourceAttachments> = emptyList(),
    manualFraming: ManualFramingTable? = null,
    private val framebufferAllocator: FramebufferAllocator = GlesFramebufferAllocator,
    previewMemoryBudget: PreviewMemoryBudget? = null,
    private val textProject: TextEditProject? = null,
    private val onFrameProgress: ((Int) -> Unit)? = null,
    private val finishingText: Boolean = false
) : AutoCloseable {
    /** Native allocation boundary; ownership and rollback stay with the compound reservation. */
    internal interface FramebufferAllocator {
        fun generate(names: IntArray)
        fun bind(name: Int)
    }

    internal object GlesFramebufferAllocator : FramebufferAllocator {
        override fun generate(names: IntArray) = GLES20.glGenFramebuffers(1, names, 0)
        override fun bind(name: Int) = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, name)
    }

    private val width = target.size.width
    private val height = target.size.height
    private val worker = Thread.currentThread()
    // Null is the explicit legacy/export path. The common runtime owns any supplied budget.
    private val previewOwner = previewMemoryBudget?.let { PreviewAllocationOwner(it) { target.makeCurrent() } }
    private val previewTextures = mutableMapOf<Int, PreviewTexture>()
    private var uploadStorage: PreviewUploadBuffer? = null
    private var previewPositions: PreviewUploadBuffer? = null
    private var previewCoordinates: PreviewUploadBuffer? = null
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
    private var userTextTexture = 0
    private var userTextBitmap: Bitmap? = null
    private var userTextCanvas: Canvas? = null
    private var userTextUpload: PreviewUploadBuffer? = null
    private var userTextUploaded = false
    private var positionBuffer: FloatBuffer? = null
    private var texBuffer: FloatBuffer? = null
    private var sceneTarget: OffscreenTarget? = null
    private var motionTarget: OffscreenTarget? = null
    private var depthTarget: OffscreenTarget? = null
    private var glowTargetA: OffscreenTarget? = null
    private var glowTargetB: OffscreenTarget? = null
    private var framingBackground: GlesFramingBackground? = null
    private val framingTargets = mutableListOf<OffscreenTarget>()
    private val framingPrograms = mutableListOf<Int>()
    private var released = false
    private var closing = false
    private val passExecutions = linkedMapOf<RenderPassPlanner.PassKind, Int>()
    private var heartbeatTextureLogged = false
    private data class PivotKey(val clipIndex: Int, val sourceIndex: Int)
    private val fearPivotByClip = mutableMapOf<PivotKey, Pair<Float, Float>>()
    private var manualFraming = manualFraming

    init {
        try {
            manualFraming?.requireTarget(target.size)
            if (previewOwner != null) {
                PreviewAllocationOwner.imageBytes(width, height)
                titleDimensions(authoredTitle)
            }
            makeCurrent()
            incomingInput = DecoderInput(0)
            outgoingInput = DecoderInput(1)
            program = createProgram(GlesFramingShaders.vertex(GlesFrameShaders.VERTEX_SHADER),
                GlesFramingShaders.fragment(GlesFrameShaders.TRANSITION_FRAGMENT_SHADER))
            postProgram = createProgram(GlesFrameShaders.POST_VERTEX_SHADER, GlesFrameShaders.POST_FRAGMENT_SHADER)
            maskTexture = create2dTexture()
            depthTexture = create2dTexture()
            flowTexture = create2dTexture()
            openingTitleTexture = createOpeningTitleTexture(authoredTitle)
            if (textProject != null) {
                // The same output-coordinate bitmap/texture is used by direct preview/export.
                previewOwner?.reserve(PreviewAllocationOwner.imageBytes(width, height)) {
                    recycleUserText()
                }
                userTextBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                userTextCanvas = Canvas(checkNotNull(userTextBitmap))
                userTextTexture = create2dTexture(width, height)
                if (previewOwner != null) userTextUpload = PreviewUploadBuffer.create(previewOwner,
                    PreviewUploadBuffer.pixelCapacity(width, height, 4))
            }
            val positions = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            if (previewOwner == null) positionBuffer = floatBuffer(positions)
            else previewPositions = PreviewUploadBuffer.create(previewOwner, 32).also { it.setVertices(positions) }
            // SurfaceTexture's matrix owns the decoder-to-GL orientation. These coordinates must
            // remain unflipped or Samsung CameraX masters are rendered upside down.
            val coordinates = VideoDisplayOrientation.externalOesTextureCoordinates()
            if (previewOwner == null) texBuffer = floatBuffer(coordinates)
            else previewCoordinates = PreviewUploadBuffer.create(previewOwner, 32).also { it.setVertices(coordinates) }
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
            val cleanupFailure = runCatching { close() }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (previewOwner != null) throw PreviewAllocationFailure(error, cleanupFailure, this)
                error.addSuppressed(cleanupFailure)
            }
            throw error
        }
    }

    val decodeSurface: Surface get() { makeCurrent(); return incomingInput.surface }
    val incomingDecodeSurface: Surface get() { makeCurrent(); return incomingInput.surface }
    val outgoingDecodeSurface: Surface get() { makeCurrent(); return outgoingInput.surface }
    fun awaitDecoderFrame() = awaitIncomingDecoderFrame()
    fun awaitIncomingDecoderFrame(checkCancelled: () -> Unit = {}) { makeCurrent(); incomingInput.awaitFrame(checkCancelled) }
    fun awaitOutgoingDecoderFrame(checkCancelled: () -> Unit = {}) { makeCurrent(); outgoingInput.awaitFrame(checkCancelled) }
    fun updateTexture() = updateIncomingTexture()
    fun updateIncomingTexture() = incomingInput.update()
    fun updateOutgoingTexture() = outgoingInput.update()
    fun executedPasses(): Map<RenderPassPlanner.PassKind, Int> { checkWorker(); return passExecutions.toMap() }

    /** Pan/zoom the retained decoded images. No input submission, allocation or revision change. */
    fun updateManualFraming(framing: ManualFramingTable) {
        checkWorker()
        check(!released && !closing) { "GLES compositor is closed" }
        previewOwner?.requireUsable()
        val current = requireNotNull(manualFraming) { "Cached update requires an initial MANUAL selection table" }
        framing.requireTarget(target.size)
        require(target.size == OutputSize(width, height)) { "Composition resize requires the display controller" }
        current.requireCompatible(framing)
        // Validate before replacing anything; both roles observe one immutable table on this worker.
        manualFraming = framing
        fearPivotByClip.clear()
    }

    private fun sourceCrop(sourceIndex: Int, sourceTimeUs: Long): SourceFraming.Crop =
        manualFraming?.crop(sourceIndex, sourceTimeUs) ?: sourceCrops[sourceIndex]

    private fun fitBackground(): GlesFramingBackground = framingBackground ?: GlesFramingBackground(width, height,
        { vertex, fragment -> createProgram(vertex, fragment).also(framingPrograms::add) },
        { w, h -> createOffscreenTarget(w, h).also(framingTargets::add).let {
            GlesFramingBackground.Target(it.framebuffer, it.texture, it.width, it.height)
        } }).also { framingBackground = it }

    private fun bindFraming(incoming: FramingPlan.Sample?, outgoing: FramingPlan.Sample?,
        incomingMatrix: FloatArray, outgoingMatrix: FloatArray) {
        val incomingFit = incoming?.takeIf { it.backgroundSource != null }?.foregroundDestination
        val outgoingFit = outgoing?.takeIf { it.backgroundSource != null }?.foregroundDestination
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFitEnabled"),
            if (incomingFit == null) 0f else 1f, if (outgoingFit == null) 0f else 1f)
        fun role(name: String, rect: NormalizedRect?, matrix: FloatArray) {
            val destination = rect ?: NormalizedRect(0f, 0f, 1f, 1f)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "u${name}Fit"),
                destination.left, destination.top, destination.right, destination.bottom)
            if (rect != null) {
                val inverse = FloatArray(16)
                require(Matrix.invertM(inverse, 0, matrix, 0)) { "Invalid fit source texture transform" }
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u${name}TextureInverse"),
                    1, false, inverse, 0)
            }
        }
        role("Incoming", incomingFit, incomingMatrix); role("Outgoing", outgoingFit, outgoingMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framingBackground?.texture ?: maskTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFramingBackground"), 7)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFramingAtlasTexel"),
            1f / (framingBackground?.atlasWidth ?: 2), 1f / (framingBackground?.atlasHeight ?: 1))
    }

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
        val owner = previewOwner
        if (owner == null) drawOwnedFrame(incomingFrame, outgoingFrame, incomingRotation, outgoingRotation)
        else owner.using { drawOwnedFrame(incomingFrame, outgoingFrame, incomingRotation, outgoingRotation) }
    }

    private fun drawOwnedFrame(
        incomingFrame: HighQualityFramePlan.Frame,
        outgoingFrame: HighQualityFramePlan.Frame?,
        incomingRotation: Int,
        outgoingRotation: Int
    ) {
        makeCurrent()
        manualFraming?.let {
            it.requireTarget(target.size)
            require(target.size == OutputSize(width, height)) { "Composition resize requires the display controller" }
        }
        val incomingCrop = sourceCrop(incomingFrame.sourceIndex, incomingFrame.sourceTimeUs)
        // Validate the requested role even when a diagnostic override binds incoming to both units.
        outgoingFrame?.let { sourceCrop(it.sourceIndex, it.sourceTimeUs) }
        val secondaryFrame = outgoingFrame?.let {
            MediaCodecSpeedRampRenderer.boundSecondaryInput(incomingFrame, it,
                debugTextureProbe, debugProbeIncomingOnBothUnits)
        } ?: incomingFrame
        val outgoingCrop = sourceCrop(secondaryFrame.sourceIndex, secondaryFrame.sourceTimeUs)
        val secondarySamplerInput = MediaCodecSpeedRampRenderer.boundSecondaryInput(
            incomingInput, outgoingInput, debugTextureProbe, debugProbeIncomingOnBothUnits
        )
        val incomingFraming = manualFraming?.sample(incomingFrame.sourceIndex, incomingFrame.sourceTimeUs)
        val outgoingFraming = manualFraming?.sample(secondaryFrame.sourceIndex, secondaryFrame.sourceTimeUs)
        if (incomingFraming?.backgroundSource != null || outgoingFraming?.backgroundSource != null) {
            fitBackground().draw(incomingInput.texture, incomingInput.transformMatrix(), incomingFraming,
                secondarySamplerInput.texture, secondarySamplerInput.transformMatrix(), outgoingFraming)
        }
        val actualPts = incomingInput.timestampUs()
        val attachments = if (sourceAttachments.isNotEmpty() ||
            incomingFrame.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY || sigmaProfile
        ) {
            MediaCodecSpeedRampRenderer.decodedAttachments(incomingFrame, actualPts,
                attachmentTimeline, sourceAttachments)
        } else incomingFrame.attachments
        // Complete admission before changing any currently valid output or semantic binding.
        if (previewOwner != null) preparePreviewSemantics(attachments)
        if (sceneTarget == null) bindOutput() else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneTarget!!.framebuffer)
            GLES20.glViewport(0, 0, sceneTarget!!.width, sceneTarget!!.height)
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val transform = FloatArray(16); Matrix.setIdentityM(transform, 0)
        val semanticCrop = incomingFraming?.takeIf { it.backgroundSource != null }?.foregroundDestination?.let {
            SourceFraming.Crop(1f / (it.right - it.left), 1f / (it.bottom - it.top))
        } ?: incomingCrop
        val fearPivot = if (fearProfile && incomingFrame.transform.scale > 1.0001f) {
            val calculate = {
                val face = attachments?.faceRegion
                if (face != null && face.confidence >= .75f) {
                    Pair(
                        (.5f + (face.centerX - semanticCrop.centerX) / semanticCrop.x).coerceIn(.2f, .8f),
                        (.5f + (face.centerY - semanticCrop.centerY) / semanticCrop.y).coerceIn(.2f, .8f)
                    )
                } else Pair(.5f, .5f)
            }
            // SMART evaluates a moving center. Do not retain a cache entry for every frame/crop.
            if (manualFraming?.status(incomingFrame.sourceIndex, incomingFrame.sourceTimeUs) != null) calculate()
            else fearPivotByClip.getOrPut(PivotKey(incomingFrame.clipIndex, incomingFrame.sourceIndex), calculate)
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
        val inverseSourceTransform = if (manualFraming != null && sceneTarget != null) FloatArray(16).also {
            require(transform.all(Float::isFinite) && Matrix.invertM(it, 0, transform, 0) && it.all(Float::isFinite)) {
                "Incoming source transform must have a finite inverse for semantic sampling"
            }
            // An affine transform reaches its extrema at the viewport corners. Finite matrix
            // coefficients alone do not rule out overflow while evaluating those coordinates.
            for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
                val localX = it[0] * x + it[4] * y + it[12]
                val localY = it[1] * x + it[5] * y + it[13]
                require(localX.isFinite() && localY.isFinite() &&
                    (incomingCrop.centerX + localX * .5f * incomingCrop.x).isFinite() &&
                    (incomingCrop.centerY - localY * .5f * incomingCrop.y).isFinite()) {
                    "Incoming semantic coordinates must be finite"
                }
            }
        } else null
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uIncomingCrop"), incomingCrop.x, incomingCrop.y)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOutgoingCrop"), outgoingCrop.x, outgoingCrop.y)
        // Convert the top-down window origin to the existing bottom-up raw video UV once.
        // Native SurfaceTexture matrices still own decoder orientation, including metadata rotation.
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uIncomingWindowOffset"),
            incomingCrop.centerX - .5f, .5f - incomingCrop.centerY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOutgoingWindowOffset"),
            outgoingCrop.centerX - .5f, .5f - outgoingCrop.centerY)
        val incomingTexMatrix = incomingInput.transformMatrix()
        val outgoingTexMatrix = secondarySamplerInput.transformMatrix()
        bindFraming(incomingFraming, outgoingFraming, incomingTexMatrix, outgoingTexMatrix)
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
        GLES20.glEnableVertexAttribArray(position)
        bindPositionAttribute(position)
        GLES20.glEnableVertexAttribArray(tex)
        bindCoordinateAttribute(tex)
        val blend = TransitionTimeline.blendFor(incomingFrame)?.takeIf {
            outgoingFrame != null || incomingFrame.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        }
        // This call is adjacent to glDrawArrays on purpose: the Inspector reports technology
        // that reached the shader, not merely effects present in a MontageGraph.
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
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        // A valid 2D fallback keeps the sampler complete without allocating an unused bitmap.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (textProject != null) userTextTexture else openingTitleTexture)
        userTextBitmap?.let { bitmap ->
            bitmap.eraseColor(Color.TRANSPARENT)
            TextLayerLayout.draw(checkNotNull(userTextCanvas), requireNotNull(textProject),
                incomingFrame.globalOutputTimeUs, strict = true)
            if (previewOwner == null) android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            else {
                checkNotNull(userTextUpload).uploadRgba(bitmap, !userTextUploaded)
                userTextUploaded = true
            }
            onFrameProgress?.invoke((incomingFrame.outputTimeUs * 100 / textProject.durationUs).toInt())
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uUserText"), 6)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTextOnly"), if (finishingText) 1f else 0f)
        // This quad follows the video's camera transform. Keep the legacy finishing
        // mapping here; montage glyphs use a full-output quad after all video passes.
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCompositeUserText"),
            if (finishingText) 1f else 0f)
        val openingTitle = authoredTitle?.sampleAt?.invoke(incomingFrame.globalOutputTimeUs)
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
            when {
                !incomingFrame.transitionEffectsAllowed -> 0f
                sigmaProfile -> SigmaComposition.entranceTravel(incomingFrame.originalOutputTimeUs)
                else -> ForegroundReentryMotion.verticalTravel(blend?.foregroundReentry ?: 0f)
            })
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
            incomingFrame.globalOutputTimeUs / 1_000_000f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOriginalTime"),
            incomingFrame.originalOutputTimeUs / 1_000_000f)
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
        sceneTarget?.let { renderPostPipeline(it, incomingFrame, blend, attachments, semanticCrop, inverseSourceTransform) }
            ?: run {
                if (textProject != null && !finishingText) drawDirectUserText()
                recordPass(RenderPassPlanner.PassKind.ENCODER_SURFACE)
            }
        target.present(incomingFrame.outputTimeUs)
    }

    override fun close() {
        checkWorker()
        if (released) return
        if (previewOwner != null) {
            closing = true
            // Context failure is retryable and keeps target/session alive and every resource charged.
            target.makeCurrent()
            GLES20.glFinish()
            val attributes = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_ATTRIBS, attributes, 0)
            for (index in 0 until attributes[0]) GLES20.glDisableVertexAttribArray(index)
            GLES20.glUseProgram(0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            previewOwner.close()
            previewTextures.clear()
            uploadStorage = null; previewPositions = null; previewCoordinates = null; userTextUpload = null
            sceneTarget = null; motionTarget = null; depthTarget = null; glowTargetA = null; glowTargetB = null
            framingTargets.clear(); framingPrograms.clear(); framingBackground = null
            // Only completed disposal permits destroying EGL. Never clear the shared budget.
            target.close()
            released = true
            return
        }
        released = true
        val current = runCatching { target.makeCurrent() }.isSuccess
        NativeResources().use { resources ->
            resources.defer(::recycleUserText)
            resources.defer { target.close() }
            if (current) {
                (listOfNotNull(sceneTarget, motionTarget, depthTarget, glowTargetA, glowTargetB) + framingTargets)
                    .forEach { owned -> resources.defer { releaseTarget(owned) } }
                framingPrograms.forEach { owned -> resources.defer { GLES20.glDeleteProgram(owned) } }
                resources.defer { GLES20.glDeleteProgram(postProgram) }
                resources.defer { GLES20.glDeleteProgram(program) }
                resources.defer { GLES20.glDeleteTextures(5,
                    intArrayOf(maskTexture, depthTexture, flowTexture, openingTitleTexture, userTextTexture), 0) }
                resources.defer { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
                resources.defer { GLES20.glUseProgram(0) }
            }
            if (::outgoingInput.isInitialized) resources.defer { outgoingInput.release(current) }
            if (::incomingInput.isInitialized) resources.defer { incomingInput.release(current) }
        }
    }

    private fun recycleUserText() {
        userTextCanvas?.setBitmap(null)
        userTextCanvas = null
        userTextBitmap?.recycle()
        userTextBitmap = null
    }

    private fun checkWorker() {
        check(Thread.currentThread() === worker) { "GLES compositor must be used on its owning GL worker" }
    }
    private fun makeCurrent() {
        checkWorker()
        check(!released && !closing) { "GLES compositor is closed" }
        previewOwner?.requireUsable()
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

    private inner class PreviewTexture(val width: Int, val height: Int) {
        val name = IntArray(1)
        val texture: Int get() = name[0]
        var initialized = false
        lateinit var reservation: PreviewAllocationOwner.Reservation
        fun allocate() {
            target.makeCurrent()
            GLES20.glGenTextures(1, name, 0)
            check(texture != 0) { "Unable to allocate preview texture" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Preview texture setup failed" }
        }
    }

    private fun reservePreviewTexture(width: Int, height: Int): PreviewTexture {
        val bytes = PreviewAllocationOwner.imageBytes(width, height)
        val resource = PreviewTexture(width, height)
        resource.reservation = checkNotNull(previewOwner).reserve(bytes) { deletePreviewTexture(resource.name) }
        return resource
    }

    private fun deletePreviewTexture(names: IntArray) {
        if (names[0] == 0) return
        GLES20.glDeleteTextures(1, names, 0)
        val disposed = !GLES20.glIsTexture(names[0])
        val error = GLES20.glGetError()
        if (disposed) names[0] = 0 // Never retry deletion of a name GL has already made reusable.
        check(disposed && error == GLES20.GL_NO_ERROR) {
            "Preview texture disposal failed"
        }
    }

    private fun bindPreviewTexture(unit: Int, texture: Int) {
        makeCurrent()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    }

    /** Only owned uploads are charged here. Core page handles/leases remain an integration prerequisite. */
    private fun preparePreviewSemantics(attachments: FrameAttachments?) {
        val owner = checkNotNull(previewOwner)
        val old = listOf(maskTexture, depthTexture, flowTexture).map { checkNotNull(previewTextures[it]) }
        val widths = intArrayOf(attachments?.mask?.width ?: 1, attachments?.depth?.width ?: 1, attachments?.flow?.width ?: 1)
        val heights = intArrayOf(attachments?.mask?.height ?: 1, attachments?.depth?.height ?: 1, attachments?.flow?.height ?: 1)
        val capacities = (0..2).map { PreviewUploadBuffer.pixelCapacity(widths[it], heights[it], if (it == 2) 2 else 1) }
        val capacity = capacities.max()
        val pending = mutableListOf<PreviewTexture>()
        var newStorage: PreviewUploadBuffer? = null
        val next = old.toMutableList()
        try {
            // Reserve the whole change before allocating any replacement native texture or changing bindings.
            for (index in 0..2) {
                if (old[index].width != widths[index] || old[index].height != heights[index]) {
                    next[index] = reservePreviewTexture(widths[index], heights[index]).also { pending += it }
                }
            }
            if (capacity > (uploadStorage?.capacity ?: 0)) newStorage = PreviewUploadBuffer.create(owner, capacity)
        } catch (error: Throwable) {
            for (resource in pending) runCatching { resource.reservation.close() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { newStorage?.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        try {
            pending.forEach { it.allocate() }
            val storage = checkNotNull(newStorage ?: uploadStorage)
            for (index in 0..2) {
                val resource = next[index]
                bindPreviewTexture(index + 2, resource.texture)
                when (index) {
                    0 -> storage.uploadPlane(resource.width, resource.height, attachments?.mask,
                        attachments?.maskBlendTarget, attachments?.maskBlendProgress ?: 0f, !resource.initialized)
                    1 -> storage.uploadPlane(resource.width, resource.height, attachments?.depth, null, 0f, !resource.initialized)
                    2 -> storage.uploadFlow(resource.width, resource.height, attachments?.flow, !resource.initialized)
                }
                resource.initialized = true
            }
        } catch (error: Throwable) {
            // A GL failure can leave partially uploaded contents. Protect the session until cleanup.
            closing = true
            for (resource in pending) runCatching { resource.reservation.close() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { newStorage?.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        maskTexture = next[0].texture; depthTexture = next[1].texture; flowTexture = next[2].texture
        pending.forEach { previewTextures[it.texture] = it }
        if (newStorage != null) {
            val previous = uploadStorage
            uploadStorage = newStorage
            previous?.close()
        }
        for (index in 0..2) if (old[index] !== next[index]) {
            val oldName = old[index].texture
            old[index].reservation.close()
            previewTextures.remove(oldName)
        }
        makeCurrent()
    }

    private fun titleDimensions(spec: AuthoredTitleProfile.Spec?): Pair<Int, Int> {
        if (spec == null) return 1 to 1
        require(spec.textSizePx.isFinite() && spec.textSizePx > 0f && spec.texts.isNotEmpty())
        // Preserve the existing float-based title raster dimensions exactly.
        val row = maxOf(64f, kotlin.math.ceil(spec.textSizePx * 1.4f)).toDouble()
        require(row <= Int.MAX_VALUE.toDouble() / spec.texts.size)
        val height = row.toInt() * spec.texts.size
        PreviewUploadBuffer.pixelCapacity(OPENING_TITLE_ATLAS_WIDTH, height, 1)
        return OPENING_TITLE_ATLAS_WIDTH to height
    }

    private fun bindPositionAttribute(location: Int) {
        if (previewOwner != null) checkNotNull(previewPositions).bindAttribute(location)
        else GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, checkNotNull(positionBuffer).apply { position(0) })
    }

    private fun bindCoordinateAttribute(location: Int) {
        if (previewOwner != null) checkNotNull(previewCoordinates).bindAttribute(location)
        else GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, checkNotNull(texBuffer).apply { position(0) })
    }

    private fun createExternalTexture(): Int = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

    private fun createTexture(target: Int): Int = NativeResources().use { resources ->
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        resources.defer { GLES20.glDeleteTextures(1, textures, 0) }
        check(textures[0] != 0) { "Unable to allocate GL texture" }
        GLES20.glBindTexture(target, textures[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        textures[0].also { resources.transfer() }
    }

    private fun create2dTexture(textureWidth: Int = 1, textureHeight: Int = 1): Int {
        if (previewOwner != null) {
            val resource = reservePreviewTexture(textureWidth, textureHeight)
            try {
                resource.allocate()
                previewTextures[resource.texture] = resource
                return resource.texture
            } catch (error: Throwable) {
                runCatching { resource.reservation.close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
        return createTexture(GLES20.GL_TEXTURE_2D)
    }

    private fun createOpeningTitleTexture(spec: AuthoredTitleProfile.Spec?): Int {
        val dimensions = if (previewOwner != null) titleDimensions(spec) else null
        val texture = create2dTexture(dimensions?.first ?: 1, dimensions?.second ?: 1)
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (spec == null) {
                if (previewOwner != null) {
                    uploadStorage = PreviewUploadBuffer.create(previewOwner, 1)
                    uploadStorage!!.uploadTitle(null)
                    return texture
                }
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 1, 1, 0,
                    GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1)
                )
                return texture
            }
            val rowHeight = maxOf(64, kotlin.math.ceil(spec.textSizePx * 1.4f).toInt())
            var atlas: Bitmap? = null
            var titleCanvas: Canvas? = null
            val bitmapReservation = previewOwner?.reserve(PreviewAllocationOwner.imageBytes(
                OPENING_TITLE_ATLAS_WIDTH, checkNotNull(dimensions).second)) {
                titleCanvas?.setBitmap(null)
                titleCanvas = null
                atlas?.recycle()
                atlas = null
            }
            NativeResources().use { bitmaps ->
                if (bitmapReservation != null) bitmaps.defer { bitmapReservation.close() }
                atlas = Bitmap.createBitmap(
                    OPENING_TITLE_ATLAS_WIDTH,
                    rowHeight * spec.texts.size,
                    Bitmap.Config.ALPHA_8
                )
                val bitmap = checkNotNull(atlas)
                if (previewOwner == null) bitmaps.defer { bitmap.recycle() }
                val canvas = Canvas(bitmap).also { titleCanvas = it }
                if (previewOwner == null) bitmaps.defer { canvas.setBitmap(null) }
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
                if (previewOwner != null) {
                    uploadStorage = PreviewUploadBuffer.create(previewOwner, bitmap.byteCount)
                    // Its reservation may have evicted a foreign owner's resource/context.
                    makeCurrent()
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    uploadStorage!!.uploadTitle(bitmap)
                    return texture
                }
                val bytes = ByteBuffer.allocateDirect(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(bytes)
                bytes.position(0)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA,
                    bitmap.width, bitmap.height, 0,
                    GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, bytes
                )
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
                return texture
            }
        } catch (error: Throwable) {
            NativeResources().use { resources ->
                if (previewOwner == null) resources.defer { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
                throw error
            }
        }
    }

    private fun bindPlaneTexture(
        unit: Int,
        texture: Int,
        plane: FrameAttachments.Plane?,
        blendTarget: FrameAttachments.Plane? = null,
        blendProgress: Float = 0f
    ) {
        if (previewOwner != null) {
            bindPreviewTexture(unit, texture)
            return
        }
        val width = plane?.width ?: 1
        val height = plane?.height ?: 1
        val bytes = ByteBuffer.allocateDirect(width * height)
        if (plane == null) {
            bytes.put(0)
        } else if (blendTarget != null && blendTarget.width == width && blendTarget.height == height) {
            (0 until plane.size).forEach { index ->
                val value = plane[index] +
                    (blendTarget[index] - plane[index]) * blendProgress
                bytes.put((value.coerceIn(0f, 1f) * 255f).toInt().toByte())
            }
        } else {
            for (index in 0 until plane.size) bytes.put((plane[index].coerceIn(0f, 1f) * 255f).toInt().toByte())
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
        if (previewOwner != null) {
            bindPreviewTexture(unit, texture)
            return
        }
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
        val reservation = previewOwner?.reserve(Math.addExact(
            PreviewAllocationOwner.imageBytes(targetWidth, targetHeight), 1024L)) {
            if (framebuffers[0] != 0) {
                GLES20.glDeleteFramebuffers(1, framebuffers, 0)
                val disposed = !GLES20.glIsFramebuffer(framebuffers[0])
                val error = GLES20.glGetError()
                if (disposed) framebuffers[0] = 0
                check(disposed && error == GLES20.GL_NO_ERROR)
            }
            deletePreviewTexture(textures)
        }
        try {
            GLES20.glGenTextures(1, textures, 0)
            checkOffscreenAllocation("texture generation", textures[0] != 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            checkOffscreenAllocation("texture binding")
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkOffscreenAllocation("texture parameters")
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
            checkOffscreenAllocation("texture image setup")
            framebufferAllocator.generate(framebuffers)
            checkOffscreenAllocation("framebuffer generation", framebuffers[0] != 0)
            framebufferAllocator.bind(framebuffers[0])
            checkOffscreenAllocation("framebuffer binding")
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                textures[0],
                0
            )
            checkOffscreenAllocation("framebuffer attachment")
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            checkOffscreenAllocation("framebuffer completeness query")
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Unable to allocate ${targetWidth}x$targetHeight render pass" +
                    if (previewOwner != null) ": framebuffer status=0x${status.toString(16)}" else ""
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            checkOffscreenAllocation("framebuffer unbinding")
            return OffscreenTarget(framebuffers[0], textures[0], targetWidth, targetHeight)
        } catch(error: Throwable) {
            if (reservation != null) runCatching { reservation.close() }.exceptionOrNull()?.let(error::addSuppressed)
            else NativeResources().use { resources ->
                resources.defer { GLES20.glDeleteTextures(1, textures, 0) }
                resources.defer { GLES20.glDeleteFramebuffers(1, framebuffers, 0) }
                throw error
            }
            throw error
        } finally {
            // Cleanup may fail to restore this context. Never mutate whichever foreign one is current.
            if (previewOwner != null && runCatching { target.makeCurrent() }.isSuccess) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }
    }

    private fun checkOffscreenAllocation(stage: String, validName: Boolean = true) {
        if (previewOwner == null) {
            check(validName) { "Unable to allocate render pass at $stage" }
            return
        }
        val error = GLES20.glGetError()
        check(validName && error == GLES20.GL_NO_ERROR) {
            "Unable to allocate render pass at $stage: valid name=$validName, GL error=0x${error.toString(16)}"
        }
    }

    private fun releaseTarget(target: OffscreenTarget) = NativeResources().use { resources ->
        resources.defer { GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0) }
        resources.defer { GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0) }
    }

    private fun renderPostPipeline(
        scene: OffscreenTarget,
        frame: HighQualityFramePlan.Frame,
        blend: GpuTransitionModel.FrameBlend?,
        attachments: FrameAttachments?,
        crop: SourceFraming.Crop,
        inverseSourceTransform: FloatArray?
    ) {
        GLES20.glUseProgram(postProgram)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uManualSemanticMapping"),
            if (inverseSourceTransform == null) 0f else 1f)
        if (inverseSourceTransform != null) {
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(postProgram, "uInverseSourceTransform"),
                1, false, inverseSourceTransform, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(postProgram, "uSourceWindow"),
                crop.centerX, crop.centerY, crop.x, crop.y)
        }
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
        GLES20.glEnableVertexAttribArray(position)
        bindPositionAttribute(position)
        GLES20.glEnableVertexAttribArray(tex)
        bindCoordinateAttribute(tex)
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
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, userTextTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uUserText"), 6)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uCompositeUserText"),
            if (textProject != null && !finishingText && output == null) 1f else 0f)
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

    private fun drawDirectUserText() {
        bindOutput()
        // Retain the already rendered video: unlike drawPost, this draw must not clear it.
        GLES20.glUseProgram(postProgram)
        val position = GLES20.glGetAttribLocation(postProgram, "aPosition")
        val tex = GLES20.glGetAttribLocation(postProgram, "aTexCoord")


        GLES20.glEnableVertexAttribArray(position)
        bindPositionAttribute(position)
        GLES20.glEnableVertexAttribArray(tex)
        bindCoordinateAttribute(tex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, userTextTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uUserText"), 6)
        // All samplers reference the complete owned 2D texture in this standalone mode;
        // no source FBO or framebuffer readback is needed to blend over the output.
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uInput"), 6)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uAuxiliary"), 6)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uFlow"), 6)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uDepth"), 6)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uMode"), 6f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uCompositeUserText"), 0f)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_BLEND)
        try {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            recordPass(RenderPassPlanner.PassKind.FINAL_COMPOSITE)
        } finally {
            // The owned video/post pipeline renders with blending disabled.
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO)
        }
    }

    private fun scaled(value: Int, scale: Float): Int = (value * scale).toInt().coerceAtLeast(1)

    private inner class DecoderInput(private val textureUnit: Int) {
        private val frameLock = Object()
        private var frameAvailable = false
        var texture = 0
            private set
        private lateinit var surfaceTexture: SurfaceTexture
        private var ownedSurface: Surface? = null
        val surface: Surface get() = checkNotNull(ownedSurface)
        private var surfaceTextureReleased = false
        private val reservation = previewOwner?.reserve(1024L) {
            ownedSurface?.release()
            ownedSurface = null
            if (::surfaceTexture.isInitialized && !surfaceTextureReleased) {
                surfaceTexture.release()
                surfaceTextureReleased = true
            }
            val names = intArrayOf(texture)
            try { deletePreviewTexture(names) } finally { texture = names[0] }
        }

        init {
            try {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + textureUnit)
                texture = createExternalTexture()
                check(texture != 0) { "Unable to allocate decoder input texture" }
                if (previewOwner != null) check(GLES20.glGetError() == GLES20.GL_NO_ERROR) {
                    "Unable to configure decoder input texture"
                }
                surfaceTexture = SurfaceTexture(texture)
                surfaceTexture.setDefaultBufferSize(width, height)
                surfaceTexture.setOnFrameAvailableListener {
                    synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() }
                }
                ownedSurface = Surface(surfaceTexture)
            } catch (error: Throwable) {
                if (previewOwner != null) runCatching { checkNotNull(reservation).close() }
                    .exceptionOrNull()?.let(error::addSuppressed)
                else NativeResources().use { resources ->
                    resources.defer { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
                    if (::surfaceTexture.isInitialized) resources.defer { surfaceTexture.release() }
                    resources.defer { ownedSurface?.release() }
                    throw error
                }
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
            if (reservation != null) { reservation.close(); return }
            NativeResources().use { resources ->
                if (current) resources.defer { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
                resources.defer { surfaceTexture.release() }
                resources.defer { surface.release() }
            }
        }
    }
    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private companion object { const val OPENING_TITLE_ATLAS_WIDTH = 720 }
    private fun createProgram(vertex: String, fragment: String): Int {
        if (previewOwner == null) return GlProgramOwnership.create(programDriver, vertex, fragment)
        var name = 0
        val shaders = IntArray(2)
        val reservation = previewOwner.reserve((vertex.length.toLong() + fragment.length) * 4 + 65_536L) {
            if (name != 0) {
                GLES20.glUseProgram(0)
                programDriver.deleteProgram(name)
                val disposed = !GLES20.glIsProgram(name)
                val error = GLES20.glGetError()
                if (disposed) name = 0
                check(disposed && error == GLES20.GL_NO_ERROR)
            }
            for (index in shaders.indices) if (shaders[index] != 0) {
                programDriver.deleteShader(shaders[index])
                val disposed = !GLES20.glIsShader(shaders[index])
                val error = GLES20.glGetError()
                if (disposed) shaders[index] = 0
                check(disposed && error == GLES20.GL_NO_ERROR)
            }
        }
        try {
            shaders[0] = programDriver.createShader(GLES20.GL_VERTEX_SHADER)
            check(shaders[0] != 0)
            programDriver.compile(shaders[0], vertex)
            shaders[1] = programDriver.createShader(GLES20.GL_FRAGMENT_SHADER)
            check(shaders[1] != 0)
            programDriver.compile(shaders[1], fragment)
            name = programDriver.createProgram()
            check(name != 0)
            programDriver.attach(name, shaders[0]); programDriver.attach(name, shaders[1])
            programDriver.link(name)
            return name
        } catch (error: Throwable) {
            runCatching { reservation.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
