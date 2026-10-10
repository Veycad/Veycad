package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
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

    class Request(
        masterFile: File? = null,
        secondaryFile: File? = null,
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
        val checkCancelled: () -> Unit = {},
        sourceFiles: List<File>? = null
    ) {
        val sourceFiles: List<File> = java.util.Collections.unmodifiableList(ArrayList(
            sourceFiles ?: listOfNotNull(requireNotNull(masterFile), secondaryFile)))
        val masterFile: File get() = this.sourceFiles.first()
        val secondaryFile: File? get() = this.sourceFiles.getOrNull(1)

        init {
            require(sourceFiles == null || (masterFile == null && secondaryFile == null)) {
                "Supply the ordered source list or legacy files, not both"
            }
            require(this.sourceFiles.size in 1..GalleryImportPolicy.MAX_FILES)
            require(graph.clips.all { it.sourceIndex in this.sourceFiles.indices }) {
                "Montage references a video source that was not supplied"
            }
        }
    }

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
        val sourceFiles = request.sourceFiles
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
        var glOwner: GlesFrameCompositor? = null
        var targetOwner: RenderTarget? = null
        var muxerOwner: MediaMuxer? = null
        var decoders: DecoderSession? = null
        val frameCount = try {
            val encoderSurface = encoder.createInputSurface().also { inputSurface = it }
            val gl = GlesFrameCompositor(EglRenderTarget.forEncoder(encoderSurface, OutputSize(request.width, request.height)).also { targetOwner = it }, request.renderPlan,
                request.graph.frameAttachments, request.debugTextureProbe, request.debugProbeIncomingOnBothUnits,
                request.debugHeartbeatImagePivot, request.debugFaceRegionProbe,
                AuthoredTitleProfile.forGraph(request.graph),
                HeartbeatMontageProfile.appliesTo(request.graph),
                FearStrobeProfile.appliesTo(request.graph),
                request.graph.metadata.generator == DualityLoopProfile.ID,
                ReferenceMontageProfile.appliesTo(request.graph),
                VideoDisplayOrientation.cropsForFiles(sourceFiles, request.width, request.height,
                    request.checkCancelled),
                onDraw = inspector::record).also { glOwner = it }
            val muxer = MediaMuxer(videoOnlyFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxerOwner = it }
            val muxerState = MuxerState()
            encoder.start()
            decoders = DecoderSession(sourceFiles, gl.incomingDecodeSurface, gl.outgoingDecodeSurface,
                request.checkCancelled)
            request.context?.let { context ->
                LocalDiagnostics.record(context, "video_decoder_pool", mapOf(
                    "decoder_limit" to "2",
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
                            gl.drawScheduled(held, requireNotNull(decoders).selectIncoming(held.sourceIndex).rotationDegrees)
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
            runCatching { glOwner?.close() }
            runCatching { targetOwner?.close() }
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
        gl: GlesFrameCompositor,
        decoders: DecoderSession,
        continueIncoming: Boolean,
        sourceIndex: Int,
        sigmaProfile: Boolean,
        onPresented: (Long) -> Unit
    ) {
        require(frames.isNotEmpty())
        val incoming = decoders.selectIncoming(sourceIndex)
        val temporal = if (frames.any { isTemporalLayer(it.layer.kind, sigmaProfile) }) {
            decoders.selectOutgoing(sourceIndex)
        } else {
            null
        }
        val sourceDurationUs = if (temporal != null) decoders.sourceDurationUs(sourceIndex) else 0L
        if (!continueIncoming) incoming.beginRange(frames.first().sourceTimeUs)
        var temporalActive = false
        frames.forEach { frame ->
            check(incoming.advanceTo(frame.sourceTimeUs) {
                gl.awaitTexture(0, decoders.checkCancelled)
            }) { "Incoming decoder did not reach clip frame" }
            val secondary = temporal?.takeIf { isTemporalLayer(frame.layer.kind, sigmaProfile) }?.let { decoder ->
                val sourceTimeUs = temporalLayerSourceTimeUs(
                    frame,
                    sourceDurationUs = sourceDurationUs
                )
                if (!temporalActive) decoder.beginRange(sourceTimeUs)
                temporalActive = true
                check(decoder.advanceTo(sourceTimeUs) {
                    gl.awaitTexture(1, decoders.checkCancelled)
                }) { "Temporal layer decoder did not reach source frame" }
                frame.copy(sourceTimeUs = sourceTimeUs)
            }
            if (secondary == null) {
                temporalActive = false
                gl.drawScheduled(frame, incoming.rotationDegrees)
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
        return (frame.secondarySourceTimeUs ?: (frame.sourceTimeUs + offsetUs))
            .coerceIn(0L, (sourceDurationUs - 1L).coerceAtLeast(0L))
    }

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
        gl: GlesFrameCompositor,
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
            check(outgoing.advanceTo(outgoingFrame.sourceTimeUs) { gl.awaitTexture(1, decoders.checkCancelled) }) {
                "Outgoing decoder did not reach overlap frame"
            }
            check(incoming.advanceTo(incomingFrame.sourceTimeUs) { gl.awaitTexture(0, decoders.checkCancelled) }) {
                "Incoming decoder did not reach overlap frame"
            }
            gl.drawOverlap(incomingFrame, outgoingFrame, incoming.rotationDegrees, outgoing.rotationDegrees)
            onPresented(incomingFrame.outputTimeUs)
        }
    }

    /** Two role-owned surface bindings. Sources are opened lazily and evicted before replacement. */
    private class DecoderSession(
        private val files: List<File>,
        private val incomingSurface: Surface,
        private val outgoingSurface: Surface,
        val checkCancelled: () -> Unit
    ) {
        private val sourceDurationsUs = files.map { file -> checkCancelled(); requestSourceDurationUs(file) }
        private val slots = SourceDecoderSlots { index, role ->
            require(index in files.indices)
            VideoDecoderCursor(files[index], if (role == DecoderRole.INCOMING) incomingSurface else outgoingSurface, checkCancelled)
        }

        fun sourceDurationUs(sourceIndex: Int): Long = sourceDurationsUs[sourceIndex]

        fun selectIncoming(sourceIndex: Int): VideoDecoderCursor =
            slots.acquire(sourceIndex, DecoderRole.INCOMING).decoder as VideoDecoderCursor

        fun selectOutgoing(sourceIndex: Int): VideoDecoderCursor =
            slots.acquire(sourceIndex, DecoderRole.OUTGOING).decoder as VideoDecoderCursor

        fun release() {
            slots.releaseAll()
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

    internal val TRANSITION_FRAGMENT_SHADER get() = GlesFrameShaders.TRANSITION_FRAGMENT_SHADER
    internal val POST_FRAGMENT_SHADER get() = GlesFrameShaders.POST_FRAGMENT_SHADER
}
