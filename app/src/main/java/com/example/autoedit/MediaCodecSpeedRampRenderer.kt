package com.veycad.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.view.Surface
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * VME's owned speed-ramp pre-renderer. It selects frames with the exact timestamps from
 * [HighQualityFramePlan], composites them in GLES and encodes H.264 directly via MediaCodec.
 * It also owns two-live-decoder transition compositing; audio is encoded and muxed locally after
 * video rendering so its sample clock remains tied to the final output duration.
 */
object MediaCodecSpeedRampRenderer {
    private const val CODEC_STALL_TIMEOUT_NS = 20_000_000_000L

    /** End-exclusive output window used by local visual controls; production exports omit it. */
    data class OutputWindow(val startUs: Long, val endUs: Long) {
        init { require(startUs >= 0L && endUs > startUs) }
        val durationUs: Long get() = endUs - startUs
    }

    data class Request(
        val masterFile: File,
        val secondaryFile: File? = null,
        val graph: MontageGraph,
        val outputFile: File,
        val width: Int,
        val height: Int,
        val fps: Int = HighQualityFramePlan.DEFAULT_FPS,
        val bitrate: Int,
        val audioFile: File? = null,
        val debugTextureProbe: Boolean = false,
        val debugProbeIncomingOnBothUnits: Boolean = false,
        val debugHeartbeatImagePivot: Boolean = false,
        val debugFaceRegionProbe: Boolean = false,
        val outputWindow: OutputWindow? = null,
        val audioResourceId: Int = 0,
        val audioDurationUs: Long = graph.outputDurationMs * 1_000L,
        val context: Context? = null,
        val renderPlan: RenderPassPlanner.Plan = RenderPassPlanner.plan(
            graph,
            RenderPassPlanner.DeviceCapabilities.conservative()
        ),
        val onPassesExecuted: ((Map<RenderPassPlanner.PassKind, Int>) -> Unit)? = null,
        val checkCancelled: () -> Unit = {}
    )

    sealed interface Outcome {
        data class Saved(val file: File, val frameCount: Int) : Outcome
        data class Failed(val cause: Throwable) : Outcome
    }

    fun renderAsync(request: Request, executor: Executor, onDone: (Outcome) -> Unit) {
        executor.execute {
            onDone(runCatching { render(request) }.fold(
                onSuccess = { Outcome.Saved(request.outputFile, it) },
                onFailure = { request.outputFile.delete(); Outcome.Failed(it) }
            ))
        }
    }

    /** Synchronous by design; callers must use [renderAsync] or a background executor. */
    fun render(request: Request): Int {
        require(Build.VERSION.SDK_INT >= 26) { "MediaCodec GPU renderer requires Android 8+" }
        require(request.masterFile.isFile)
        val sourceFiles = listOfNotNull(request.masterFile, request.secondaryFile)
        require(sourceFiles.all(File::isFile))
        require(request.graph.clips.all { it.sourceIndex in sourceFiles.indices }) {
            "Montage references a video source that was not supplied"
        }
        val completePlan = HighQualityFramePlan.build(request.graph, request.fps)
        val plan = request.outputWindow?.let { outputWindow(completePlan, it) } ?: completePlan
        request.outputFile.parentFile?.mkdirs()
        request.outputFile.delete()
        val videoOnlyFile = File(request.outputFile.parentFile, request.outputFile.nameWithoutExtension + ".video.mp4")
        videoOnlyFile.delete()
        val inspector = VeykadRenderInspector.Collector(request.graph, plan.frames.size)
        val encoder = createEncoder(request)
        var inputSurface: Surface? = null
        var glOwner: GlSession? = null
        var muxerOwner: MediaMuxer? = null
        var decoders: DecoderSession? = null
        val frameCount = try {
            val encoderSurface = encoder.createInputSurface().also { inputSurface = it }
            val gl = GlSession(encoderSurface, request.width, request.height, inspector, request.renderPlan,
                request.graph.frameAttachments, request.debugTextureProbe, request.debugProbeIncomingOnBothUnits,
                request.debugHeartbeatImagePivot, request.debugFaceRegionProbe,
                AuthoredTitleProfile.forGraph(request.graph),
                HeartbeatMontageProfile.appliesTo(request.graph),
                FearStrobeProfile.appliesTo(request.graph),
                request.graph.metadata.generator == DualityLoopProfile.ID,
                ReferenceMontageProfile.appliesTo(request.graph),
                sourceFiles.map { VideoDisplayOrientation.cropForFile(it, request.width, request.height) },
                request.graph.sourceAttachments).also { glOwner = it }
            val muxer = MediaMuxer(videoOnlyFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxerOwner = it }
            val muxerState = MuxerState()
            encoder.start()
            decoders = DecoderSession(sourceFiles, gl.incomingDecodeSurface, gl.outgoingDecodeSurface)
            request.context?.let { context ->
                LocalDiagnostics.record(context, "video_decoder_pool", mapOf(
                    "decoder_instances" to "2",
                    "source_files" to sourceFiles.size.toString(),
                    "clip_count" to request.graph.clips.size.toString(),
                    "session_reuse" to (sourceFiles.size == 1).toString()
                ))
            }
            val framesByClip = plan.frames.groupBy { it.clipIndex }
            request.graph.clips.indices.forEach { clipIndex ->
                request.checkCancelled()
                val frames = framesByClip[clipIndex].orEmpty()
                if (frames.isNotEmpty()) {
                    val overlap = if (clipIndex == 0) null else LiveDecoderTransitionPlan.forClip(
                        framesByClip[clipIndex - 1].orEmpty(),
                        frames,
                        requireNotNull(decoders).sourceDurationUs(request.graph.clips[clipIndex - 1].sourceIndex)
                    )
                    if (overlap != null) {
                        // Clip boundaries rarely land exactly on a frame-grid timestamp. The first
                        // scheduled overlap frame is therefore already p>0, although it is also
                        // the first sample produced by two newly attached decoder surfaces. Some
                        // GLES drivers expose that first colour-conversion as black. Legacy
                        // transitions hold a verified texture for one sample. Sigma's authored
                        // whip must start at the beat, including the first scheduled sample.
                        val overlapStart = if (ReferenceMontageProfile.appliesTo(request.graph) &&
                            frames.first().transitionIn == MontageGraph.Transition.WHIP) 0 else 1
                        if (overlapStart > 0) {
                            val held = framesByClip.getValue(clipIndex - 1).last().copy(
                                outputTimeUs = overlap.incoming.first().outputTimeUs,
                                transitionIn = MontageGraph.Transition.HARD_CUT,
                                transitionProgress = -1f
                            )
                            gl.draw(held, requireNotNull(decoders).selectIncoming(held.sourceIndex).rotationDegrees)
                            request.checkCancelled()
                            drainEncoder(encoder, muxer, false, muxerState)
                        }
                        if (overlapStart < overlap.incoming.size) {
                            decodeOverlap(
                                overlap.outgoing.drop(overlapStart),
                                overlap.incoming.drop(overlapStart),
                                gl,
                                requireNotNull(decoders),
                                request.graph.clips[clipIndex - 1].sourceIndex,
                                request.graph.clips[clipIndex].sourceIndex
                            ) {
                                request.checkCancelled()
                                drainEncoder(encoder, muxer, false, muxerState)
                            }
                        }
                    }
                    val remainingFrames = LiveDecoderTransitionPlan.remainingFrames(frames, overlap)
                    if (remainingFrames.isNotEmpty()) {
                        decodeClip(
                            remainingFrames,
                            gl,
                            requireNotNull(decoders),
                            continueIncoming = overlap != null,
                            sourceIndex = request.graph.clips[clipIndex].sourceIndex,
                            sigmaProfile = ReferenceMontageProfile.appliesTo(request.graph)
                        ) {
                            request.checkCancelled()
                            drainEncoder(encoder, muxer, false, muxerState)
                        }
                    }
                }
            }
            drainEncoder(encoder, muxer, true, muxerState)
            request.onPassesExecuted?.invoke(gl.executedPasses())
            plan.frames.size
        } finally {
            runCatching { decoders?.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { glOwner?.release() }
            runCatching { inputSurface?.release() }
            runCatching { muxerOwner?.stop() }
            runCatching { muxerOwner?.release() }
        }
        if (request.audioFile != null) {
            AacEncoderMuxer.muxMusicFile(
                request.audioFile,
                videoOnlyFile,
                request.outputFile,
                request.outputWindow?.durationUs ?: request.audioDurationUs,
                request.checkCancelled
            )
            videoOnlyFile.delete()
        } else if (request.audioResourceId != 0 && request.context != null) {
            AacEncoderMuxer.muxMusic(
                request.context,
                request.audioResourceId,
                videoOnlyFile,
                request.outputFile,
                request.outputWindow?.durationUs ?: request.audioDurationUs
            )
            videoOnlyFile.delete()
        } else check(videoOnlyFile.renameTo(request.outputFile)) { "Unable to move video output" }
        request.checkCancelled()
        request.context?.let { context ->
            VeykadRenderInspector.writeArtifacts(context, request.outputFile, request.graph, inspector)
        }
        return frameCount
    }

    /** Keeps all already-sampled render state but rebases encoder PTS to zero. Starting before the
     * transition boundary deliberately retains previous-clip frames needed by the two decoders. */
    internal fun outputWindow(plan: HighQualityFramePlan.Plan, window: OutputWindow): HighQualityFramePlan.Plan {
        require(window.endUs <= plan.durationUs) { "Output window exceeds montage duration" }
        val frames = plan.frames
            .filter { it.outputTimeUs >= window.startUs && it.outputTimeUs < window.endUs }
            .map { it.copy(outputTimeUs = it.outputTimeUs - window.startUs) }
        require(frames.isNotEmpty()) { "Output window contains no scheduled frames" }
        return HighQualityFramePlan.Plan(frames, window.durationUs, plan.fps)
    }

    private fun createEncoder(request: Request): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, request.width, request.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, request.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return encoder
        } catch (error: Throwable) {
            runCatching { encoder.release() }
            throw error
        }
    }

    private fun decodeClip(
        frames: List<HighQualityFramePlan.Frame>,
        gl: GlSession,
        decoders: DecoderSession,
        continueIncoming: Boolean,
        sourceIndex: Int,
        sigmaProfile: Boolean,
        onPresented: (Long) -> Unit
    ) {
        require(frames.isNotEmpty())
        val incoming = decoders.selectIncoming(sourceIndex)
        if (!continueIncoming) incoming.beginRange(frames.first().sourceTimeUs)
        var temporalSource: Int? = null
        var temporalTimeUs = -1L
        frames.forEach { frame ->
            check(incoming.advanceTo(frame.sourceTimeUs) {
                gl.awaitIncomingDecoderFrame()
                gl.updateIncomingTexture()
            }) { "Incoming decoder did not reach clip frame" }
            val temporal = if (isTemporalLayer(frame.layer.kind, sigmaProfile))
                decoders.selectOutgoing(frame.secondarySourceIndex ?: sourceIndex) else null
            val secondary = temporal?.let { decoder ->
                val secondaryIndex = frame.secondarySourceIndex ?: sourceIndex
                val sourceTimeUs = temporalLayerSourceTimeUs(
                    frame,
                    sourceDurationUs = decoders.sourceDurationUs(secondaryIndex)
                )
                if (temporalSource != secondaryIndex || sourceTimeUs < temporalTimeUs) decoder.beginRange(sourceTimeUs)
                temporalSource = secondaryIndex
                temporalTimeUs = sourceTimeUs
                check(decoder.advanceTo(sourceTimeUs) {
                    gl.awaitOutgoingDecoderFrame()
                    gl.updateOutgoingTexture()
                }) { "Temporal layer decoder did not reach source frame" }
                temporalLayerFrame(frame, sourceTimeUs)
            }
            if (secondary == null) {
                temporalSource = null
                gl.draw(frame, incoming.rotationDegrees)
            } else {
                gl.drawTemporalLayer(
                    frame,
                    secondary,
                    incoming.rotationDegrees,
                    requireNotNull(temporal).rotationDegrees
                )
            }
            onPresented(frame.outputTimeUs)
        }
    }

    internal fun isTemporalLayer(kind: MontageGraph.OverlayKind, sigmaProfile: Boolean = false): Boolean =
        !sigmaProfile && (kind == MontageGraph.OverlayKind.DOUBLE_EXPOSURE ||
            kind == MontageGraph.OverlayKind.MIRROR_SLICE)

    /** The evidence and sampler binding must describe the same decoded texture, including probes. */
    internal fun <T> boundSecondaryInput(
        incoming: T,
        outgoing: T,
        debugTextureProbe: Boolean,
        debugProbeIncomingOnBothUnits: Boolean
    ): T = if (debugTextureProbe && debugProbeIncomingOnBothUnits) incoming else outgoing

    internal fun temporalLayerSourceTimeUs(
        frame: HighQualityFramePlan.Frame,
        sourceDurationUs: Long
    ): Long {
        frame.secondarySourceTimeUs?.let { explicit ->
            require(explicit in 0 until sourceDurationUs) { "Secondary PTS is outside its source" }
            return explicit
        }
        val fallbackOffsetUs = when (frame.layer.kind) {
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE -> -420_000L
            MontageGraph.OverlayKind.MIRROR_SLICE -> 260_000L
            else -> 0L
        }
        val offsetUs = frame.layer.secondarySourceOffsetMs
            // Heartbeat can deliberately use a synchronous spatial echo. Legacy
            // layers retain their historical zero-as-default temporal offset.
            .takeIf { it != 0L || frame.layer.heartbeatEcho }
            ?.times(1_000L)
            ?: fallbackOffsetUs
        return (frame.sourceTimeUs + offsetUs)
            .coerceIn(0L, (sourceDurationUs - 1L).coerceAtLeast(0L))
    }

    internal fun temporalLayerFrame(frame: HighQualityFramePlan.Frame, sourceTimeUs: Long) = frame.copy(
        sourceTimeUs = sourceTimeUs, sourceIndex = frame.secondarySourceIndex ?: frame.sourceIndex,
        attachments = if (frame.secondarySourceIndex != null) frame.secondaryAttachments else frame.attachments)

    internal fun decodedAttachments(frame: HighQualityFramePlan.Frame, actualPts: Long,
        legacy: FrameAttachmentTimeline, sources: List<SourceAttachments>): FrameAttachments? =
        if (sources.isNotEmpty()) sources.firstOrNull { it.sourceIndex == frame.sourceIndex }?.timeline?.interpolated(actualPts)
        else legacy.interpolated(actualPts)

    private fun requestSourceDurationUs(file: File): Long = MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Video duration unavailable")) * 1_000L
        } finally {
            retriever.release()
        }
    }

    /** Two MediaCodec decoders remain active for the whole overlap window. Each advances to its
     * own source timestamp and exposes a distinct OES texture to the GLES compositor. */
    private fun decodeOverlap(
        outgoingFrames: List<HighQualityFramePlan.Frame>,
        incomingFrames: List<HighQualityFramePlan.Frame>,
        gl: GlSession,
        decoders: DecoderSession,
        outgoingSourceIndex: Int,
        incomingSourceIndex: Int,
        onPresented: (Long) -> Unit
    ) {
        require(outgoingFrames.size == incomingFrames.size && incomingFrames.isNotEmpty())
        val outgoing = decoders.selectOutgoing(outgoingSourceIndex)
        val incoming = decoders.selectIncoming(incomingSourceIndex)
        outgoing.beginRange(outgoingFrames.first().sourceTimeUs)
        incoming.beginRange(incomingFrames.first().sourceTimeUs)
        outgoingFrames.zip(incomingFrames).forEach { (outgoingFrame, incomingFrame) ->
            check(outgoing.advanceTo(outgoingFrame.sourceTimeUs) { gl.awaitOutgoingDecoderFrame(); gl.updateOutgoingTexture() }) {
                "Outgoing decoder did not reach overlap frame"
            }
            check(incoming.advanceTo(incomingFrame.sourceTimeUs) { gl.awaitIncomingDecoderFrame(); gl.updateIncomingTexture() }) {
                "Incoming decoder did not reach overlap frame"
            }
            gl.drawOverlap(incomingFrame, outgoingFrame, incoming.rotationDegrees, outgoing.rotationDegrees)
            onPresented(incomingFrame.outputTimeUs)
        }
    }

    /** The renderer owns exactly two decoder/surface bindings for the whole export. Each new clip
     * flushes and seeks an existing codec; transition and temporal paths borrow the second one. */
    private class DecoderSession(
        private val files: List<File>,
        private val incomingSurface: Surface,
        private val outgoingSurface: Surface
    ) {
        private val sourceDurationsUs = files.map(::requestSourceDurationUs)
        private var incomingIndex = 0
        private var outgoingIndex = 0
        var incoming = DecoderCursor(files[0], incomingSurface)
            private set
        var outgoing = try {
            DecoderCursor(files[0], outgoingSurface)
        } catch (error: Throwable) {
            incoming.release()
            throw error
        }
            private set

        fun sourceDurationUs(sourceIndex: Int): Long = sourceDurationsUs[sourceIndex]

        fun selectIncoming(sourceIndex: Int): DecoderCursor {
            if (sourceIndex != incomingIndex) {
                incoming.release()
                incoming = DecoderCursor(files[sourceIndex], incomingSurface)
                incomingIndex = sourceIndex
            }
            return incoming
        }

        fun selectOutgoing(sourceIndex: Int): DecoderCursor {
            if (sourceIndex != outgoingIndex) {
                outgoing.release()
                outgoing = DecoderCursor(files[sourceIndex], outgoingSurface)
                outgoingIndex = sourceIndex
            }
            return outgoing
        }

        fun release() {
            runCatching { outgoing.release() }
            runCatching { incoming.release() }
        }
    }

    private class DecoderCursor(file: File, private val outputSurface: Surface) {
        private val extractor = MediaExtractor()
        private lateinit var decoder: MediaCodec
        private var released = false
        val rotationDegrees: Int
        private var inputEnded = false
        private var outputEnded = false
        private var seeked = false
        private var hasDecodedTexture = false
        private var currentTexturePtsUs = Long.MIN_VALUE

        init {
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: error("No video track")
                rotationDegrees = MediaMetadataRetriever().let { retriever ->
                    try { retriever.setDataSource(file.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
                    finally { retriever.release() }
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: error("No video mime"))
                decoder.configure(format, outputSurface, null, 0)
                decoder.start()
            } catch (error: Throwable) {
                release()
                throw error
            }
        }

        /** Starts one independently directed source range without recreating MediaCodec. */
        fun beginRange(targetUs: Long) {
            // A newly started decoder has nothing to flush. Flushing before its first queued
            // sample stalls some Codec2 implementations when a Surface is rebound to a new file.
            if (seeked) decoder.flush()
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            inputEnded = false
            outputEnded = false
            seeked = true
            hasDecodedTexture = false
            currentTexturePtsUs = Long.MIN_VALUE
        }

        fun advanceTo(targetUs: Long, onTextureFrame: () -> Unit): Boolean {
            // Planned source time can be a few microseconds beyond the final decodable PTS because
            // container duration is not the timestamp of its last frame. Keep the last valid
            // texture at physical EOS rather than failing or shortening the output timeline.
            if (outputEnded) return hasDecodedTexture
            if (hasDecodedTexture && targetUs <= currentTexturePtsUs) return true
            if (!seeked) {
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                seeked = true
            }
            val info = MediaCodec.BufferInfo()
            var lastProgressNs = System.nanoTime()
            while (!outputEnded) {
                check(System.nanoTime() - lastProgressNs < CODEC_STALL_TIMEOUT_NS) { "Transition decoder stalled before reaching overlap frame" }
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: error("Missing decoder input")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        lastProgressNs = System.nanoTime()
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        lastProgressNs = System.nanoTime()
                        val render = info.size > 0
                        decoder.releaseOutputBuffer(outputIndex, render)
                        if (render) {
                            onTextureFrame()
                            hasDecodedTexture = true
                            currentTexturePtsUs = info.presentationTimeUs
                            if (info.presentationTimeUs >= targetUs) return true
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }
            return hasDecodedTexture
        }

        fun release() {
            if (released) return
            released = true
            if (::decoder.isInitialized) {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
            runCatching { extractor.release() }
        }
    }

    private data class MuxerState(var started: Boolean = false, var track: Int = -1)

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        endOfStream: Boolean,
        state: MuxerState
    ) {
        if (endOfStream) encoder.signalEndOfInputStream()
        val info = MediaCodec.BufferInfo()
        var ended = false
        val deadlineNs = System.nanoTime() + CODEC_STALL_TIMEOUT_NS
        while (!ended) when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                if (!endOfStream) return
                check(System.nanoTime() < deadlineNs) { "Video encoder stalled while finalizing output" }
            }
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (!state.started) {
                state.track = muxer.addTrack(encoder.outputFormat); muxer.start(); state.started = true
            }
            else -> if (index >= 0) {
                val buffer = encoder.getOutputBuffer(index) ?: error("Missing encoder output")
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    check(state.started) { "Encoder wrote samples before format" }
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(state.track, buffer, info)
                }
                ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
            }
        }
    }

    private class GlSession(
        encoderSurface: Surface,
        private val width: Int,
        private val height: Int,
        private val inspector: VeykadRenderInspector.Collector,
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
        private val sourceAttachments: List<SourceAttachments> = emptyList()
    ) {
        private var display: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
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
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY)
                val version = IntArray(2); check(EGL14.eglInitialize(display, version, 0, version, 1))
                // EGL_RECORDABLE_ANDROID is mandatory on a number of Qualcomm/Mali devices when the
                // window surface belongs to a MediaCodec encoder rather than a regular View.
                val configAttrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
                val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
                check(context != EGL14.EGL_NO_CONTEXT)
                surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
                check(surface != EGL14.EGL_NO_SURFACE)
                makeCurrent()
                incomingInput = DecoderInput(0)
                outgoingInput = DecoderInput(1)
                program = createProgram(VERTEX_SHADER, TRANSITION_FRAGMENT_SHADER)
                postProgram = createProgram(POST_VERTEX_SHADER, POST_FRAGMENT_SHADER)
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
                release()
                throw error
            }
        }

        val decodeSurface: Surface get() = incomingInput.surface
        val incomingDecodeSurface: Surface get() = incomingInput.surface
        val outgoingDecodeSurface: Surface get() = outgoingInput.surface
        fun awaitDecoderFrame() = awaitIncomingDecoderFrame()
        fun awaitIncomingDecoderFrame() = incomingInput.awaitFrame()
        fun awaitOutgoingDecoderFrame() = outgoingInput.awaitFrame()
        fun updateTexture() = updateIncomingTexture()
        fun updateIncomingTexture() = incomingInput.update()
        fun updateOutgoingTexture() = outgoingInput.update()
        fun executedPasses(): Map<RenderPassPlanner.PassKind, Int> = passExecutions.toMap()

        fun draw(frame: HighQualityFramePlan.Frame, sourceRotation: Int) {
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
            require(isTemporalLayer(incomingFrame.layer.kind, sigmaProfile))
            drawInternal(incomingFrame, temporalFrame, incomingRotation, temporalRotation)
        }

        private fun drawInternal(
            incomingFrame: HighQualityFramePlan.Frame,
            outgoingFrame: HighQualityFramePlan.Frame?,
            incomingRotation: Int,
            outgoingRotation: Int
        ) {
            makeCurrent()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneTarget?.framebuffer ?: 0)
            GLES20.glViewport(0, 0, sceneTarget?.width ?: width, sceneTarget?.height ?: height)
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
            val secondarySamplerInput = boundSecondaryInput(
                incomingInput, outgoingInput, debugTextureProbe, debugProbeIncomingOnBothUnits
            )
            val attachments = if (
                incomingFrame.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY ||
                    sigmaProfile
            ) {
                decodedAttachments(incomingFrame, actualPts, attachmentTimeline, sourceAttachments)
            } else incomingFrame.attachments
            inspector.record(
                incomingFrame.copy(attachments = attachments),
                blend,
                outgoingFrame != null,
                outgoingFrame?.sourceTimeUs,
                incomingInput.timestampUs(),
                outgoingFrame?.let { secondarySamplerInput.timestampUs() }
            )
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
                if (sigmaProfile && incomingFrame.transitionEffectsAllowed) SigmaComposition.entranceTravel(incomingFrame.originalOutputTimeUs)
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
            sceneTarget?.let { renderPostPipeline(it, incomingFrame, blend, attachments) }
                ?: recordPass(RenderPassPlanner.PassKind.ENCODER_SURFACE)
            EGLExt.eglPresentationTimeANDROID(display, surface, incomingFrame.outputTimeUs * 1_000L)
            check(EGL14.eglSwapBuffers(display, surface)) { "Unable to submit GL frame" }
        }

        fun release() {
            if (released) return
            released = true
            val current = context != EGL14.EGL_NO_CONTEXT && runCatching { makeCurrent() }.isSuccess
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
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            EGL14.eglReleaseThread()
        }

        private fun makeCurrent() { check(EGL14.eglMakeCurrent(display, surface, surface, context)) }
        private fun recordPass(kind: RenderPassPlanner.PassKind) {
            passExecutions[kind] = (passExecutions[kind] ?: 0) + 1
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
            val framebuffers = IntArray(1)
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
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output?.framebuffer ?: 0)
            GLES20.glViewport(0, 0, output?.width ?: width, output?.height ?: height)
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

            fun awaitFrame() = synchronized(frameLock) {
                val deadline = System.nanoTime() + 2_000_000_000L
                while (!frameAvailable && System.nanoTime() < deadline) frameLock.wait(20L)
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
        private fun createProgram(vertex: String, fragment: String): Int = GlesProgram.create(vertex, fragment)
    }

    private const val VERTEX_SHADER = "attribute vec4 aPosition; attribute vec4 aTexCoord; uniform mat4 uTransform,uIncomingTexMatrix,uOutgoingTexMatrix; uniform vec2 uIncomingCrop,uOutgoingCrop; varying vec2 vIncomingTexCoord,vOutgoingTexCoord,vScreenTexCoord,vSemanticTexCoord,vOutputTexCoord; void main() { gl_Position = uTransform * aPosition; vec2 incoming=vec2(.5)+(aTexCoord.xy-vec2(.5))*uIncomingCrop; vec2 outgoing=vec2(.5)+(aTexCoord.xy-vec2(.5))*uOutgoingCrop; vIncomingTexCoord = (uIncomingTexMatrix * vec4(incoming,0.,1.)).xy; vOutgoingTexCoord = (uOutgoingTexMatrix * vec4(outgoing,0.,1.)).xy; vScreenTexCoord=incoming; vSemanticTexCoord=vec2(incoming.x,1.0-incoming.y); vOutputTexCoord=aTexCoord.xy; }"

    private const val POST_VERTEX_SHADER = "attribute vec4 aPosition; attribute vec4 aTexCoord; varying vec2 vTexCoord; void main(){gl_Position=aPosition;vTexCoord=aTexCoord.xy;}"
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
        uniform float uOriginalTime;
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
                float cascade=step(5.1,uOriginalTime)*(1.-step(16.8333,uOriginalTime));
                float opener=1.-step(3.6,uOriginalTime);
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
            float opener=1.-step(3.6,uOriginalTime);
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
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val OPENING_TITLE_ATLAS_WIDTH = 720
}
