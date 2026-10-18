package com.veycad.app

import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restricted prepared MANUAL direct display. Public commands and state callbacks run on main;
 * all admission, decode, EGL and owned resource disposal run on one no-Looper GL executor.
 * Surfaces are borrowed: retain them until stopAndRelease's asynchronous success callback.
 */
class DraftPreviewController(private val onState: (State) -> Unit) : AutoCloseable {
    data class State(
        val generation: PreviewGeneration? = null,
        val requestedOutputTimeUs: Long = 0,
        val shownOutputTimeUs: Long? = null,
        val sourceIndex: Int? = null,
        val decodedSourceTimeUs: Long? = null,
        val playing: Boolean = false,
        val exactFrameReady: Boolean = false,
        val buffering: Boolean = false,
        val warning: String? = null,
        val error: String? = null
    )

    private data class Input(val request: MediaCodecSpeedRampRenderer.Request? = null,
        val surface: Surface? = null, val surfaceSize: OutputSize? = null)
    private val main = Handler(Looper.getMainLooper())
    private val policy = PreviewPlaybackPolicy()
    // A plain executor thread deliberately has NO Looper: SurfaceTexture signals can reach main
    // while GL waits. Main commands never join this worker or wait for a frame/stop callback.
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Veycad-preview-GL")
    }
    private val queued = AtomicBoolean(false)
    @Volatile private var input = Input()
    private var state = State()
    private var rawTimeUs = 0L
    private var stopping = false
    private var closeRequested = false
    private var closed = false
    private val stoppedCallbacks = mutableListOf<() -> Unit>()
    private var stopGeneration: PreviewGeneration? = null

    // GL owner fields; the budget is initialized once, on this worker, and never cleared by peers.
    private var budget: PreviewMemoryBudget? = null
    private var target: RenderTarget? = null
    private var compositor: GlesFrameCompositor? = null
    private var allocationFailure: PreviewAllocationFailure? = null
    private var slots: SourceDecoderSlots? = null
    private var boundInput: Input? = null
    private var boundBinding: PreparedHybridExportBinding? = null
    private var admittedRequest: MediaCodecSpeedRampRenderer.Request? = null
    private var operation: PreviewGeneration? = null
    private var cursor: VideoDecoderCursor? = null
    private var lastSourceTargetUs: Long? = null
    private var decodedPtsUs: Long? = null
    private var shownFrame: HighQualityFramePlan.Frame? = null
    private var started: PreviewGeneration? = null
    private var starting: PreviewGeneration? = null
    private val audio = PreviewAudioClock(policy.seeks::isCurrent) { generation, error ->
        worker.execute { fail(generation, error) }
    }

    fun open(request: MediaCodecSpeedRampRenderer.Request) {
        requireMainOpen()
        // No file I/O or full-plan validation on main. Legacy rejection is also reported as state.
        policy.reset(); policy.seeks.invalidateProject(); audio.pause()
        input = input.copy(request = request)
        requestFrame(state.shownOutputTimeUs ?: rawTimeUs)
    }

    fun attach(surface: Surface, size: OutputSize) {
        requireMainOpen()
        policy.reset(); policy.seeks.invalidateSurface(); audio.pause()
        input = input.copy(surface = surface, surfaceSize = size)
        requestFrame(state.shownOutputTimeUs ?: rawTimeUs)
    }

    fun detach() {
        requireMainOpen()
        policy.reset(); policy.seeks.invalidateSurface(); audio.pause()
        input = input.copy(surface = null, surfaceSize = null)
        requestFrame(state.shownOutputTimeUs ?: rawTimeUs)
    }

    fun play() {
        requireMainOpen()
        policy.play()
        requestFrame(state.shownOutputTimeUs ?: rawTimeUs)
    }
    fun pause() {
        requireMainOpen()
        policy.pause()
        requestFrame(state.shownOutputTimeUs ?: rawTimeUs)
    }
    fun beginScrub() {
        requireMainOpen()
        policy.beginScrub()
        requestFrame(state.shownOutputTimeUs ?: rawTimeUs)
    }
    fun seekTo(outputTimeUs: Long) {
        requireMainOpen(); require(outputTimeUs >= 0)
        requestFrame(outputTimeUs)
    }
    fun endScrub() {
        requireMainOpen()
        policy.endScrub()
        requestFrame(rawTimeUs)
    }

    /** Retry this method after a cleanup error. No success callback fires on unconfirmed disposal. */
    fun stopAndRelease(onStopped: () -> Unit) {
        requireMain()
        if (closed) { main.post(onStopped); return }
        stoppedCallbacks += onStopped
        if (!stopping) {
            stopping = true
            policy.reset(); policy.seeks.invalidateProject(); policy.seeks.invalidateSurface()
            input = Input(); audio.pause()
            stopGeneration = policy.seek(rawTimeUs)
            state = state.copy(generation = stopGeneration, playing = false, exactFrameReady = false, buffering = false)
            onState(state)
        }
        val generation = requireNotNull(stopGeneration)
        worker.execute {
            try {
                releaseGl()
                admittedRequest = null
                audio.release { error ->
                    if (error != null) worker.execute { fail(generation, error) }
                    else main.post {
                        if (!stopping || stopGeneration != generation) return@post
                        stopping = false; stopGeneration = null
                        state = State(generation = generation)
                        onState(state)
                        val callbacks = stoppedCallbacks.toList(); stoppedCallbacks.clear()
                        if (closeRequested) {
                            closed = true; audio.close(); worker.shutdown()
                        }
                        callbacks.forEach { it() }
                    }
                }
            } catch (error: Throwable) { fail(generation, error) }
        }
    }

    /** Nonblocking. Use stopAndRelease for the Surface/source release acknowledgement. */
    override fun close() {
        requireMain()
        if (closed) return
        closeRequested = true
        stopAndRelease {}
    }

    private fun requestFrame(timeUs: Long) {
        rawTimeUs = timeUs
        val generation = policy.seek(timeUs)
        audio.pause()
        state = state.copy(generation = generation, requestedOutputTimeUs = timeUs,
            playing = false, exactFrameReady = false, buffering = input.request != null && input.surface != null,
            warning = if (input.request?.audioFile == null) "Silent preview: monotonic clock" else null, error = null)
        onState(state)
        enqueueDrain()
    }

    private fun enqueueDrain() {
        if (!queued.compareAndSet(false, true)) return
        worker.execute {
            queued.set(false)
            val pending = policy.seeks.takeLatest() ?: return@execute
            val (generation, rawUs) = pending
            val snapshot = input
            if (!policy.seeks.isCurrent(generation)) return@execute
            operation = generation; started = null; starting = null
            try {
                val request = snapshot.request
                val surface = snapshot.surface
                if (request == null) {
                    releaseGl()
                    publish(generation, rawUs, false, false)
                    return@execute
                }
                checkCurrent()
                val binding = requireNotNull(request.preparedExport) { "Direct preview requires PreparedHybridExportAdapter.request" }
                if (admittedRequest !== request) {
                    binding.validate(request)
                    requireSupported(request, binding)
                    admittedRequest = request
                }
                if (surface == null) {
                    releaseGl()
                    publish(generation, rawUs, false, false)
                    return@execute
                }
                checkCurrent()
                ensureOutput(snapshot, binding)
                val frame = PreviewFrameSelection.nearest(binding.prepared.framePlan, rawUs)
                audio.prepare(request.audioFile, generation, frame.outputTimeUs, binding.prepared.framePlan.durationUs) {
                    worker.execute {
                        if (policy.audioReady(generation)) tryStart(generation, rawUs)
                    }
                }
                renderFrame(frame)
                if (policy.frameReady(generation)) {
                    publish(generation, rawUs, false, true)
                    tryStart(generation, rawUs)
                }
            } catch (cancelled: CancellationException) {
                // Retire inputs as well as codecs: an already queued SurfaceTexture image cannot
                // become the next generation's accepted decoder callback after flush/reseek.
                try { releaseGl() } catch (error: Throwable) { reportCleanupFailure(error) }
            } catch (error: Throwable) {
                failOperation(generation, error)
            }
        }
    }

    private fun ensureOutput(snapshot: Input, binding: PreparedHybridExportBinding) {
        val previous = boundInput
        val compatible = previous != null && previous.surface === snapshot.surface && previous.surfaceSize == snapshot.surfaceSize &&
            previous.request?.sourceFiles == snapshot.request?.sourceFiles &&
            previous.request?.renderPlan == snapshot.request?.renderPlan &&
            previous.request?.graph?.metadata == snapshot.request?.graph?.metadata &&
            boundBinding?.prepared?.sources == binding.prepared.sources &&
            runCatching { boundBinding!!.framing.requireCompatible(binding.framing) }.isSuccess
        if (compositor != null && compatible) {
            compositor!!.updateManualFraming(binding.framing)
            boundInput = snapshot; boundBinding = binding
            return
        }
        releaseGl()
        checkCurrent()
        val request = requireNotNull(snapshot.request)
        check(requireNotNull(snapshot.surface).isValid) { "Preview Surface is invalid" }
        check(Looper.myLooper() == null) { "GL worker must not own a Looper" }
        if (budget == null) budget = PreviewMemoryBudget(32L * 1024 * 1024)
        val display = EglRenderTarget.forDisplay(snapshot.surface, binding.visualSettings.aspect.size(360))
        // A forwarding gate only; the existing target still owns every EGL operation/backing.
        val guarded = object : RenderTarget by display, ViewportRenderTarget {
            override fun bindOutput() = (display as ViewportRenderTarget).bindOutput()
            override fun present(outputTimeUs: Long) {
                checkCurrent()
                display.present(outputTimeUs)
                checkCurrent()
            }
        }
        target = guarded
        try {
            compositor = GlesFrameCompositor(guarded, request.renderPlan, request.graph.frameAttachments,
                request.debugTextureProbe, request.debugProbeIncomingOnBothUnits,
                request.debugHeartbeatImagePivot, request.debugFaceRegionProbe,
                AuthoredTitleProfile.forGraph(request.graph), HeartbeatMontageProfile.appliesTo(request.graph),
                FearStrobeProfile.appliesTo(request.graph), request.graph.metadata.generator == DualityLoopProfile.ID,
                ReferenceMontageProfile.appliesTo(request.graph), request.sourceFiles.indices.map(binding.framing::crop),
                sourceAttachments = request.graph.sourceAttachments, manualFraming = binding.framing,
                previewMemoryBudget = budget, onDraw = { evidence ->
                    checkCurrent()
                    check(evidence.decodedSourceTimeUs == decodedPtsUs) {
                        "SurfaceTexture PTS differs from the accepted source decoder PTS"
                    }
                })
        } catch (error: PreviewAllocationFailure) {
            allocationFailure = error
            throw error
        }
        slots = SourceDecoderSlots { index: Int ->
            // This closure reads the worker's CURRENT operation; it never captures an old seek.
            VideoDecoderCursor(request.sourceFiles[index], requireNotNull(compositor).incomingDecodeSurface, ::checkCurrent)
        }
        boundInput = snapshot; boundBinding = binding
    }

    private fun renderFrame(frame: HighQualityFramePlan.Frame) {
        checkCurrent()
        val gl = requireNotNull(compositor)
        val selected = requireNotNull(slots).acquire(frame.sourceIndex, DecoderRole.INCOMING).decoder as VideoDecoderCursor
        if (cursor !== selected || lastSourceTargetUs?.let { frame.sourceTimeUs < it } == true) {
            selected.beginRange(frame.sourceTimeUs)
        }
        cursor = selected
        check(selected.advanceTo(frame.sourceTimeUs) {
            gl.awaitIncomingDecoderFrame(::checkCurrent)
            gl.updateIncomingTexture()
            checkCurrent()
        }) { "Source has no decodable frame at this output time" }
        decodedPtsUs = requireNotNull(selected.currentTexturePtsUs)
        checkCurrent()
        // Rotation comes from the SAME inspected geometry as export's MANUAL table.
        val rotation = requireNotNull(boundBinding).prepared.sources[frame.sourceIndex].metadata.geometry.rotation
        gl.drawScheduled(frame, rotation)
        checkCurrent()
        lastSourceTargetUs = frame.sourceTimeUs; shownFrame = frame
    }

    private fun tryStart(generation: PreviewGeneration, rawUs: Long) {
        if (!policy.canStart(generation) || started == generation || starting == generation) return
        starting = generation
        audio.start(generation, { policy.canStart(generation) }, onStarted = {
            worker.execute {
                if (!policy.canStart(generation)) return@execute
                starting = null; started = generation
                publish(generation, rawUs, true, true)
                tick(generation, rawUs)
            }
        }, onEnded = {
            worker.execute {
                if (!policy.pauseIfCurrent(generation)) return@execute
                started = null; starting = null; operation = generation
                try {
                    renderFrame(requireNotNull(boundBinding).prepared.framePlan.frames.last())
                    publish(generation, rawUs, false, true)
                } catch (cancelled: CancellationException) {
                    try { releaseGl() } catch (error: Throwable) { reportCleanupFailure(error) }
                } catch (error: Throwable) { failOperation(generation, error) }
            }
        })
    }

    private fun tick(generation: PreviewGeneration, rawUs: Long) {
        worker.schedule({
            if (!policy.canStart(generation) || started != generation) return@schedule
            audio.position(generation) { timeUs ->
                worker.execute {
                    if (!policy.canStart(generation) || started != generation) return@execute
                    operation = generation
                    try {
                        val plan = requireNotNull(boundBinding).prepared.framePlan
                        if (timeUs >= plan.durationUs) {
                            policy.pauseIfCurrent(generation); audio.pause(); started = null
                            renderFrame(plan.frames.last())
                            publish(generation, rawUs, false, true)
                        } else {
                            val frame = PreviewFrameSelection.nearest(plan, timeUs)
                            if (shownFrame !== frame) renderFrame(frame)
                            publish(generation, rawUs, true, true)
                            tick(generation, rawUs)
                        }
                    } catch (cancelled: CancellationException) {
                        try { releaseGl() } catch (error: Throwable) { reportCleanupFailure(error) }
                    } catch (error: Throwable) { failOperation(generation, error) }
                }
            }
        }, 33, TimeUnit.MILLISECONDS)
    }

    private fun checkCurrent() {
        if (!policy.seeks.isCurrent(requireNotNull(operation))) throw CancellationException("Obsolete preview operation")
        input.request?.checkCancelled?.invoke()
    }

    private fun releaseGl() {
        slots?.releaseAll(); slots = null
        cursor = null; lastSourceTargetUs = null; decodedPtsUs = null; shownFrame = null
        allocationFailure?.retryClose(); allocationFailure = null
        compositor?.close(); compositor = null
        target?.close(); target = null
        boundInput = null; boundBinding = null; started = null; starting = null
    }

    private fun fail(generation: PreviewGeneration, error: Throwable) {
        if (!policy.seeks.isCurrent(generation)) return
        if (!policy.pauseIfCurrent(generation)) return
        audio.pause(); started = null; starting = null
        var detail = error.message ?: error.javaClass.simpleName
        try { releaseGl() } catch (cleanup: Throwable) {
            detail += "; cleanup incomplete, retry stopAndRelease: ${cleanup.message}"
        }
        val message = detail
        main.post {
            if (policy.seeks.isCurrent(generation)) {
                state = state.copy(playing = false, exactFrameReady = false, buffering = false, error = message)
                onState(state)
            }
        }
    }

    /** Only called from the active GL operation, never from an obsolete audio callback. */
    private fun failOperation(generation: PreviewGeneration, error: Throwable) {
        // A platform error may race invalidation; retire that operation's inputs even if its
        // error state is now obsolete. An asynchronous old audio error must not do this.
        try { releaseGl() } catch (cleanup: Throwable) {
            error.addSuppressed(cleanup)
            if (!policy.seeks.isCurrent(generation)) reportCleanupFailure(cleanup)
        }
        fail(generation, error)
    }

    private fun reportCleanupFailure(error: Throwable) {
        // Cleanup belongs to the obsolete operation but blocks future resources too. Report it
        // against the current UI state, without authorizing any image/audio callback.
        main.post {
            state = state.copy(playing = false, exactFrameReady = false, buffering = false,
                error = "Cleanup incomplete; retry stopAndRelease: ${error.message}")
            onState(state)
        }
    }

    private fun publish(generation: PreviewGeneration, rawUs: Long, playing: Boolean, exact: Boolean) {
        val frame = shownFrame
        val next = State(generation, rawUs, frame?.outputTimeUs, frame?.sourceIndex, decodedPtsUs,
            playing, exact, false, if (input.request?.audioFile == null) "Silent preview: monotonic clock" else null)
        main.post {
            if (policy.seeks.isCurrent(generation)) { state = next; onState(next) }
        }
    }

    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Preview commands require main" } }
    private fun requireMainOpen() { requireMain(); check(!closed && !closeRequested && !stopping) { "Preview is stopping/closed" } }

    private fun requireSupported(request: MediaCodecSpeedRampRenderer.Request, binding: PreparedHybridExportBinding) {
        val graph = request.graph
        require(AuthoredTitleProfile.forGraph(graph) == null && !HeartbeatMontageProfile.appliesTo(graph) &&
            !FearStrobeProfile.appliesTo(graph) && graph.metadata.generator != DualityLoopProfile.ID &&
            !ReferenceMontageProfile.appliesTo(graph)) { "Authored automatic profiles are unsupported by direct MANUAL preview" }
        require(graph.renderNodes().isEmpty()) { "Direct preview does not support GpuEffectGraph effects" }
        require(graph.renderOverlays().all { it.overlayKind == MontageGraph.OverlayKind.FLASH &&
            it.secondarySourceOffsetMs == 0L && it.secondaryTimelineStartMs == null }) { "Only non-temporal FLASH overlays are supported" }
        require(request.audioResourceId == 0 && request.outputWindow == null) { "Preview requires original local music and the complete prepared plan" }
        val basic = setOf(MontageGraph.Transition.HARD_CUT, MontageGraph.Transition.OPEN, MontageGraph.Transition.FINAL_HOLD)
        binding.prepared.framePlan.frames.forEach { frame ->
            checkCurrent()
            require(frame.transitionIn in basic && frame.secondarySourceTimeUs == null && frame.secondarySourceIndex == null &&
                frame.attachments == null && frame.secondaryAttachments == null && frame.flowStrength == 0f &&
                frame.effects == GpuEffectGraph.Sample() && frame.layer.kind == MontageGraph.OverlayKind.FLASH &&
                frame.layer.secondarySourceOffsetMs == 0L && frame.layer.secondaryTimelineStartMs == null &&
                !frame.layer.heartbeatEcho && frame.layer.finalFadeOpacity == 0f) {
                "Unsupported mixed/temporal/effect frame at ${frame.outputTimeUs} us"
            }
        }
    }
}
