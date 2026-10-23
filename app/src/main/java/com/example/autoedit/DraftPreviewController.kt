package com.veycad.app

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prepared project direct display. Public commands and state callbacks run on main;
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
        val error: String? = null,
        val secondarySourceIndex: Int? = null,
        val decodedSecondarySourceTimeUs: Long? = null,
        /** Conservative app-owned prepared/GL reservations, sampled only on their owner. */
        val appOwnedBytes: Long = 0
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
    @Volatile private var closeRequested = false
    @Volatile private var exportCancelled = false
    private var exporting = false
    @Volatile private var preparationEpoch = 0L
    private var closed = false
    private val stoppedCallbacks = mutableListOf<() -> Unit>()
    private var stopGeneration: PreviewGeneration? = null

    // GL owner fields; the budget is initialized once, on this worker, and never cleared by peers.
    private var budget: PreviewMemoryBudget? = null
    private var target: RenderTarget? = null
    private var targetReservationKey: String? = null
    private var compositor: GlesFrameCompositor? = null
    private var allocationFailure: PreviewAllocationFailure? = null
    private var slots: SourceDecoderSlots? = null
    private var boundInput: Input? = null
    private var boundBinding: PreparedHybridExportBinding? = null
    private var admittedRequest: MediaCodecSpeedRampRenderer.Request? = null
    // Only this GL worker constructs/closes budgeted prepared values; UI references are borrowed.
    private var ownedPrepared: PreparedHybridPreview? = null
    private var ownedRequest: MediaCodecSpeedRampRenderer.Request? = null
    private data class PreparationSource(val projectId: String, val revisionId: Long,
        val settings: ProjectExportSettings, val assets: ProjectAssetStore,
        val output: File, val context: Context)
    private var preparationSource: PreparationSource? = null
    private var operation: PreviewGeneration? = null
    private val decoderRanges = mutableMapOf<DecoderRole, Pair<VideoDecoderCursor, Long>>()
    private var directDraws: List<LiveDecoderTransitionPlan.DirectDraw> = emptyList()
    private var decodedPtsUs: Long? = null
    private var decodedSecondaryPtsUs: Long? = null
    private var shownSecondarySource: Int? = null
    private var shownFrame: HighQualityFramePlan.Frame? = null
    private var started: PreviewGeneration? = null
    private var starting: PreviewGeneration? = null
    private val audio = PreviewAudioClock(policy.seeks::isCurrent) { generation, error ->
        worker.execute { fail(generation, error) }
    }

    /** The existing preparation now shares this compositor's one GL-thread memory budget. */
    fun prepareAndOpen(project: HybridProject, settings: ProjectExportSettings, assets: ProjectAssetStore,
        outputFile: File, context: Context, smartTracks: Map<SourceId, SmartFramingTrack> = emptyMap(),
        onPrepared: (PreparedHybridPreview, MediaCodecSpeedRampRenderer.Request) -> Unit) {
        requireMainOpen()
        val epoch = ++preparationEpoch
        exportCancelled = false
        policy.reset(); policy.seeks.invalidateProject(); audio.pause()
        input = input.copy(request = null)
        val token = policy.seek(rawTimeUs)
        state = State(generation = token, requestedOutputTimeUs = rawTimeUs, buffering = true)
        onState(state)
        worker.execute {
            operation = token
            var candidate: PreparedHybridPreview? = null
            try {
                releaseGl(); closeOwnedPrepared()
                if (preparationEpoch != epoch) return@execute
                if (budget == null) budget = PreviewMemoryBudget(32L * 1024 * 1024)
                val prepared = HybridPreviewPreparation.prepare(project, settings, assets, requireNotNull(budget), context.cacheDir) {
                    if (preparationEpoch != epoch) throw CancellationException("Obsolete project preparation")
                }
                candidate = prepared
                if (preparationEpoch != epoch) throw CancellationException("Obsolete project preparation")
                val request = PreparedHybridExportAdapter.request(prepared, project, outputFile,
                    context = context.applicationContext, smartTracks = smartTracks, checkCancelled = {
                        if (Thread.currentThread().isInterrupted || exportCancelled) throw CancellationException("Export cancelled")
                    })
                if (preparationEpoch != epoch) throw CancellationException("Obsolete project preparation")
                ownedPrepared = prepared; ownedRequest = request; candidate = null
                preparationSource = PreparationSource(project.id, project.current.id, settings, assets,
                    outputFile, context.applicationContext)
                main.post {
                    if (closed || closeRequested || stopping || preparationEpoch != epoch) return@post
                    onPrepared(prepared, request)
                    open(request)
                }
            } catch (cancelled: CancellationException) {
                candidate?.let { disposeCandidate(it, cancelled) }
            } catch (error: Throwable) {
                if (error is PreviewAllocationFailure) allocationFailure = error
                candidate?.let { disposeCandidate(it, error) }
                main.post {
                    if (preparationEpoch == epoch && !closed && !stopping) {
                        state = state.copy(playing = false, exactFrameReady = false, buffering = false,
                            error = error.message ?: error.javaClass.simpleName)
                        onState(state)
                    }
                }
            }
        }
    }

    private fun disposeCandidate(candidate: PreparedHybridPreview, operation: Throwable) {
        try { candidate.close() } catch (cleanup: Throwable) {
            operation.addSuppressed(cleanup)
            val previous = allocationFailure
            ownedPrepared = candidate
            allocationFailure = PreviewAllocationFailure(operation, cleanup, AutoCloseable {
                previous?.retryClose()
                candidate.close()
            })
            reportCleanupFailure(cleanup)
        }
    }

    /** Stops display/audio first; the exact prepared plan is exported and disposed on its owner. */
    fun renderPrepared(onDone: (MediaCodecSpeedRampRenderer.Outcome) -> Unit) {
        requireMainOpen()
        check(!exporting) { "Prepared export is already running" }
        exportCancelled = false
        stopAndRelease(keepPrepared = true) {
            if (closed || closeRequested) {
                onDone(MediaCodecSpeedRampRenderer.Outcome.Failed(CancellationException("Export cancelled")))
                return@stopAndRelease
            }
            exporting = true
            worker.execute {
                val outcome = try {
                    check(!exportCancelled) { "Export cancelled" }
                    val old = requireNotNull(ownedPrepared) { "Prepared project is not available" }
                    val source = requireNotNull(preparationSource)
                    val expected = timelineIdentity(old.framePlan)
                    val expectedText = old.renderText?.let { it.copy(layers = montageSnapshot(it.layers),
                        captions = montageSnapshot(it.captions), legacyItems = montageSnapshot(it.legacyItems)) }
                    val expectedMusic = old.audio.music
                    val expectedMix = old.audio.settings
                    closeOwnedPrepared()
                    check(budget?.usedBytes == 0L) { "Preview cleanup has not acknowledged every reservation" }
                    // Full-resolution output has its own bounded phase, after all display/audio
                    // consumers stop. Reload the same canonical revision instead of retaining the
                    // producer's large analysis graph or transferring live preview allocations.
                    budget = PreviewMemoryBudget(128L * 1024 * 1024)
                    val loaded = HybridProjectStore(source.context.filesDir).loadWithAnalysisStatus(source.projectId)
                    require(!loaded.analysisRegenerationRequired) {
                        "Анализ проекта отсутствует; восстановите резервную копию перед экспортом"
                    }
                    val installed = loaded.project
                    require(installed.current.id == source.revisionId) { "Project changed before export" }
                    fun cancelled() {
                        if (exportCancelled || Thread.currentThread().isInterrupted) throw CancellationException("Export cancelled")
                    }
                    val prepared = HybridPreviewPreparation.prepare(installed, source.settings, source.assets,
                        requireNotNull(budget), source.context.cacheDir, ::cancelled)
                    ownedPrepared = prepared
                    require(timelineIdentity(prepared.framePlan) == expected && prepared.renderText == expectedText &&
                        prepared.audio.music == expectedMusic && prepared.audio.settings == expectedMix) {
                        "Export projection differs from the displayed saved revision"
                    }
                    val tracks = SmartFramingPreparation.prepare(source.context, installed, source.assets, ::cancelled)
                    val value = PreparedHybridExportAdapter.request(prepared, installed, source.output,
                        context = source.context, smartTracks = tracks, checkCancelled = ::cancelled)
                    ownedRequest = value
                    MediaCodecSpeedRampRenderer.Outcome.Saved(value.outputFile, MediaCodecSpeedRampRenderer.render(value))
                } catch (error: Throwable) {
                    if (error is PreviewAllocationFailure) allocationFailure = error
                    MediaCodecSpeedRampRenderer.Outcome.Failed(error)
                }
                var finalOutcome: MediaCodecSpeedRampRenderer.Outcome = outcome
                try { if (allocationFailure == null) closeOwnedPrepared() } catch (error: Throwable) {
                    if (outcome is MediaCodecSpeedRampRenderer.Outcome.Failed) outcome.cause.addSuppressed(error)
                    else finalOutcome = MediaCodecSpeedRampRenderer.Outcome.Failed(error)
                }
                if (allocationFailure == null && budget?.usedBytes == 0L) budget = null
                main.post { exporting = false; onDone(finalOutcome) }
            }
        }
    }

    private fun closeOwnedPrepared() {
        ownedPrepared?.close()
        ownedPrepared = null; ownedRequest = null; preparationSource = null
    }

    private fun timelineIdentity(plan: HighQualityFramePlan.Plan): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = java.nio.ByteBuffer.allocate(64)
        for (frame in plan.frames) {
            bytes.clear()
            bytes.putLong(frame.outputTimeUs).putLong(frame.globalOutputTimeUs).putLong(frame.sourceTimeUs)
                .putInt(frame.clipIndex).putInt(frame.sourceIndex).putLong(frame.secondarySourceTimeUs ?: -1)
                .putInt(frame.secondarySourceIndex ?: -1).putInt(if (frame.transitionProgress == null) 0 else 1)
                .putFloat(frame.transitionProgress ?: 0f).putInt(frame.transitionIn.ordinal)
            digest.update(bytes.array(), 0, bytes.position())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun open(request: MediaCodecSpeedRampRenderer.Request) {
        requireMainOpen()
        preparationEpoch++
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
    fun stopAndRelease(keepPrepared: Boolean = false, onStopped: () -> Unit) {
        requireMain()
        preparationEpoch++
        if (exporting) exportCancelled = true
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
                    else worker.execute {
                        try { if (!keepPrepared || closeRequested) closeOwnedPrepared() }
                        catch (error: Throwable) { fail(generation, error); return@execute }
                        main.post {
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
            warning = if (input.request?.audioFile == null && input.request?.preparedExport == null)
                "Silent preview: monotonic clock" else null, error = null)
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
                audio.prepare(request.audioFile, generation, frame.outputTimeUs, binding.prepared.framePlan.durationUs,
                    snapshot = binding.prepared.audio, directory = File(request.outputFile.parentFile, "preview-audio")) {
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
            previous.request?.textProject == snapshot.request?.textProject &&
            previous.request?.showAuthoredText == snapshot.request?.showAuthoredText &&
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
        val displaySize = binding.visualSettings.aspect.size(360)
        val sharedBudget = requireNotNull(budget)
        val targetKey = "preview-display-${java.util.UUID.randomUUID()}"
        var displayOwner: RenderTarget? = null
        var partialTarget: PreviewAllocationFailure? = null
        // The app's composition texture/FBO and copy program are distinct from platform buffers.
        // Admission precedes native allocation; failed disposal retains this same charged key.
        check(sharedBudget.reserve(targetKey,
            PreviewAllocationOwner.imageBytes(displaySize.width, displaySize.height) + 131_072L) {
            partialTarget?.retryClose(); partialTarget = null
            displayOwner?.close(); displayOwner = null
        }) { "Preview display backing exceeds the shared memory budget" }
        targetReservationKey = targetKey
        sharedBudget.pin(targetKey)
        val display = try {
            EglRenderTarget.forDisplay(snapshot.surface, displaySize).also { displayOwner = it }
        } catch (error: PreviewAllocationFailure) { partialTarget = error; throw error }
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
                if (request.showAuthoredText) AuthoredTitleProfile.forGraph(request.graph) else null, HeartbeatMontageProfile.appliesTo(request.graph),
                FearStrobeProfile.appliesTo(request.graph), request.graph.metadata.generator == DualityLoopProfile.ID,
                ReferenceMontageProfile.appliesTo(request.graph), request.sourceFiles.indices.map(binding.framing::crop),
                sourceAttachments = request.graph.sourceAttachments, manualFraming = binding.framing,
                previewMemoryBudget = budget, textProject = request.textProject,
                finishingText = request.finishingText, onDraw = { evidence ->
                    checkCurrent()
                    check(evidence.decodedSourceTimeUs == decodedPtsUs) {
                        "SurfaceTexture PTS differs from the accepted source decoder PTS"
                    }
                    val expectedSecondary = if (request.debugTextureProbe && request.debugProbeIncomingOnBothUnits)
                        decodedPtsUs else decodedSecondaryPtsUs
                    if (evidence.dualDecoder) check(evidence.decodedSecondarySourceTimeUs == expectedSecondary) {
                        "Secondary SurfaceTexture PTS differs from its role-owned decoder"
                    }
                })
        } catch (error: PreviewAllocationFailure) {
            allocationFailure = error
            throw error
        }
        slots = SourceDecoderSlots { index, role ->
            // This closure reads the worker's CURRENT operation; it never captures an old seek.
            val gl = requireNotNull(compositor)
            VideoDecoderCursor(request.sourceFiles[index],
                if (role == DecoderRole.INCOMING) gl.incomingDecodeSurface else gl.outgoingDecodeSurface, ::checkCurrent)
        }
        val durations = binding.prepared.sources.map { it.durationUs }
        directDraws = LiveDecoderTransitionPlan.directDraws(binding.prepared.framePlan.frames, durations,
            ReferenceMontageProfile.appliesTo(request.graph))
        boundInput = snapshot; boundBinding = binding
    }

    private fun renderFrame(frame: HighQualityFramePlan.Frame) {
        checkCurrent()
        val gl = requireNotNull(compositor)
        val prepared = requireNotNull(boundBinding).prepared
        val index = prepared.framePlan.frames.binarySearchBy(frame.outputTimeUs) { it.outputTimeUs }
        check(index >= 0) { "Preview frame is absent from the canonical schedule" }
        val draw = directDraws[index]
        fun decode(value: HighQualityFramePlan.Frame, role: DecoderRole): VideoDecoderCursor {
            val selected = requireNotNull(slots).acquire(value.sourceIndex, role).decoder as VideoDecoderCursor
            val previous = decoderRanges[role]
            if (previous == null || previous.first !== selected || value.sourceTimeUs < previous.second) selected.beginRange(value.sourceTimeUs)
            check(selected.advanceTo(value.sourceTimeUs) {
                if (role == DecoderRole.INCOMING) { gl.awaitIncomingDecoderFrame(::checkCurrent); gl.updateIncomingTexture() }
                else { gl.awaitOutgoingDecoderFrame(::checkCurrent); gl.updateOutgoingTexture() }
                checkCurrent()
            }) { "Source has no decodable frame at this output time" }
            decoderRanges[role] = selected to value.sourceTimeUs
            return selected
        }
        // Export advances outgoing before incoming during overlaps; retain that ordering.
        val outgoing = draw.outgoing?.let { decode(it, DecoderRole.OUTGOING) }
        val incoming = decode(draw.incoming, DecoderRole.INCOMING)
        decodedPtsUs = requireNotNull(incoming.currentTexturePtsUs)
        decodedSecondaryPtsUs = outgoing?.currentTexturePtsUs
        shownSecondarySource = draw.outgoing?.sourceIndex
        checkCurrent()
        // Each role uses its own inspected rotation and raw PTS, including same-source echoes.
        val rotation = prepared.sources[draw.incoming.sourceIndex].metadata.geometry.rotation
        val secondaryRotation = draw.outgoing?.let { prepared.sources[it.sourceIndex].metadata.geometry.rotation }
        if (draw.outgoing == null) gl.drawScheduled(draw.incoming, rotation)
        else if (draw.temporal) gl.drawTemporalLayer(draw.incoming, draw.outgoing, rotation, requireNotNull(secondaryRotation))
        else gl.drawOverlap(draw.incoming, draw.outgoing, rotation, requireNotNull(secondaryRotation))
        checkCurrent()
        shownFrame = draw.incoming
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
                            if (shownFrame?.outputTimeUs != frame.outputTimeUs) renderFrame(frame)
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
        val generation = requireNotNull(operation)
        policy.checkCurrentOperation(generation, input.request?.checkCancelled ?: {})
    }

    private fun releaseGl() {
        slots?.releaseAll(); slots = null
        decoderRanges.clear(); directDraws = emptyList()
        decodedPtsUs = null; decodedSecondaryPtsUs = null; shownSecondarySource = null; shownFrame = null
        allocationFailure?.retryClose(); allocationFailure = null
        compositor?.close(); compositor = null
        val targetKey = targetReservationKey
        if (targetKey != null) {
            requireNotNull(budget).release(targetKey)
            targetReservationKey = null
        } else target?.close()
        target = null
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
            playing, exact, false, if (input.request?.audioFile == null && input.request?.preparedExport?.prepared?.audio == null)
                "Silent preview: monotonic clock" else null, secondarySourceIndex = shownSecondarySource,
            decodedSecondarySourceTimeUs = decodedSecondaryPtsUs, appOwnedBytes = budget?.usedBytes ?: 0)
        main.post {
            if (policy.seeks.isCurrent(generation)) { state = next; onState(next) }
        }
    }

    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Preview commands require main" } }
    private fun requireMainOpen() { requireMain(); check(!closed && !closeRequested && !stopping && !exporting) { "Preview is stopping/closed/exporting" } }

    private fun requireSupported(request: MediaCodecSpeedRampRenderer.Request, binding: PreparedHybridExportBinding) {
        require(request.audioResourceId == 0 && request.outputWindow == null) { "Preview requires original local music and the complete prepared plan" }
        require(request.textProject == null || request.textBehavior == MediaCodecSpeedRampRenderer.TextBehavior.MONTAGE_OVERLAY) {
            "Saved project text must use the montage output-coordinate overlay"
        }
        binding.validate(request)
    }
}
