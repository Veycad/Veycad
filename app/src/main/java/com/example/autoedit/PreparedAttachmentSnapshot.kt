package com.veycad.app

import java.util.Collections
import java.util.IdentityHashMap

/** Private payloads are dropped on shared-budget disposal, including through retained borrowed views. */
internal class PreparedAttachmentStorage<T : Any>(value: T) {
    private var value: T? = value
    private val identity = Any()
    fun identity(): Any = identity
    fun get(): T = checkNotNull(value) { "Prepared attachment lifetime is closed" }
    fun dispose() { value = null }
}

internal class PreparedAttachmentLifetime {
    private val storage = ArrayList<PreparedAttachmentStorage<*>>()
    var closed = false
        private set

    fun <T : Any> own(value: T): PreparedAttachmentStorage<T> = PreparedAttachmentStorage(value).also {
        check(!closed) { "Prepared attachment lifetime is closed" }
        storage.add(it)
    }

    fun <T> list(values: List<T>): List<T> {
        val contents = own(ArrayList(values))
        return Collections.unmodifiableList(object : AbstractList<T>() {
            override val size: Int get() = contents.get().size
            override fun get(index: Int): T = contents.get()[index]
        })
    }

    fun dispose() {
        if (closed) return
        closed = true
        storage.forEach { it.dispose() }
        storage.clear()
    }
}

/** One bounded handoff, not a new scheduler, backing store or resource manager. */
internal class PreparedAttachmentSnapshot(private val lifetime: PreparedAttachmentLifetime) {
    private val arrays = IdentityHashMap<Any, PreparedAttachmentStorage<FloatArray>>()
    private val planes = IdentityHashMap<FrameAttachments.Plane, FrameAttachments.Plane>()
    private val vectors = IdentityHashMap<List<Float>, List<Float>>()
    private val flows = IdentityHashMap<FrameAttachments.FlowPlane, FrameAttachments.FlowPlane>()
    private val frames = IdentityHashMap<FrameAttachments, FrameAttachments>()

    private fun plane(input: FrameAttachments.Plane?): FrameAttachments.Plane? {
        if (input == null) return null
        return planes.getOrPut(input) {
            val storage = arrays.getOrPut(input.inputIdentity()) { lifetime.own(input.copyInput()) }
            FrameAttachments.Plane.frozen(input.width, input.height, storage, input.confidence)
        }
    }

    private fun frame(input: FrameAttachments): FrameAttachments = frames.getOrPut(input) {
        input.copy(mask = plane(input.mask), depth = plane(input.depth), maskBlendTarget = plane(input.maskBlendTarget),
            flow = input.flow?.let { flow -> flows.getOrPut(flow) {
                flow.copy(vectors = vectors.getOrPut(flow.vectors) { lifetime.list(flow.vectors) })
            } })
    }

    private fun timeline(input: FrameAttachmentTimeline) = FrameAttachmentTimeline(
        lifetime.list(input.frames.map(::frame)), lifetime.list(input.maskRefinements.map(::frame)))

    fun graph(input: MontageGraph): MontageGraph = input.copy(
        frameAttachments = timeline(input.frameAttachments), sourceAttachments = lifetime.list(input.sourceAttachments.map {
            it.copy(timeline = timeline(it.timeline))
        }))

    companion object {
        private const val MAX_SOURCE_TIMELINES = 64
        private const val MAX_ATTACHMENT_REFERENCES = 1024L
        // Value equality does not imply shared arrays: the existing compiler's no-op path can
        // return original.graph. Cover both possible inputs without duplicating its selection logic.
        private fun visitInputGraphs(project: HybridProject, visit: (MontageGraph) -> Unit) {
            visit(project.current.graph)
            if (project.original.graph !== project.current.graph) visit(project.original.graph)
        }

        private fun visitFrames(graph: MontageGraph, visit: (FrameAttachments) -> Unit) {
            fun timeline(value: FrameAttachmentTimeline) {
                value.frames.forEach(visit)
                value.maskRefinements.forEach(visit)
            }
            timeline(graph.frameAttachments)
            graph.sourceAttachments.forEach { timeline(it.timeline) }
        }

        private fun visitInputFrames(project: HybridProject, visit: (FrameAttachments) -> Unit) =
            visitInputGraphs(project) { visitFrames(it, visit) }

        private fun visitInputPlanes(project: HybridProject, visit: (FrameAttachments.Plane) -> Unit) = visitInputFrames(project) {
            it.mask?.let(visit); it.depth?.let(visit); it.maskBlendTarget?.let(visit)
        }

        /**
         * Checked conservative accounting, BEFORE compiler/snapshot/index/plan allocations.
         * The quadratic identity preflight holds no growing index: input is already caller-owned.
         * Counts the UNION of possible original/current graph inputs: array headers/pixels and
         * boxed-vector containers once per identity, never assuming equal values imply sharing;
         * attachment/plane/index/list metadata per occurrence (a safe overestimate when shared).
         * Plan allowance includes primary/role-mapped frames, interpolation/face/scalar samples,
         * intermediate list references and effect/layer sampling. This is not an app heap-peak claim.
         */
        fun reservationBytes(project: HybridProject, fps: Int): Long {
            // Bound GL-worker work BEFORE any per-frame scan or identity comparison.
            // Up to 3 planes/row => at most (3 * 1024)^2 identity comparisons.
            var sourceTimelines = 0L
            visitInputGraphs(project) { sourceTimelines = Math.addExact(sourceTimelines, it.sourceAttachments.size.toLong()) }
            require(sourceTimelines <= MAX_SOURCE_TIMELINES) {
                "Analyzed prepared preview is too large: bounded handoff supports up to 64 source timelines across original/current inputs"
            }
            fun rows(timeline: FrameAttachmentTimeline) = Math.addExact(
                timeline.frames.size.toLong(), timeline.maskRefinements.size.toLong())
            var references = 0L
            visitInputGraphs(project) { graph ->
                references = Math.addExact(references, rows(graph.frameAttachments))
                graph.sourceAttachments.forEach { references = Math.addExact(references, rows(it.timeline)) }
            }
            require(references <= MAX_ATTACHMENT_REFERENCES) {
                "Analyzed prepared preview is too large: bounded handoff supports up to 1024 attachment rows across original/current global/source frames and refinements"
            }
            var bytes = 131_072L // File hash buffer, fixed graph/lifetime/reservation objects and scratch.
            fun charge(count: Long, unit: Long) { bytes = Math.addExact(bytes, Math.multiplyExact(count, unit)) }
            val winnerDurationMs = maxOf(project.current.graph.outputDurationMs, project.original.graph.outputDurationMs)
            val winnerFrames = Math.floorDiv(Math.addExact(Math.multiplyExact(winnerDurationMs, fps.toLong()), 999), 1000)
            val editedFrames = project.current.clips.last().span.endExclusive.toLong()
            var graphItems = 0L
            visitInputGraphs(project) { graph -> graphItems = Math.addExact(graphItems,
                Math.addExact(graph.overlays.size.toLong(), graph.effectGraph.nodes.size.toLong())) }
            charge(maxOf(winnerFrames, editedFrames), Math.addExact(2048L, Math.multiplyExact(graphItems, 128L)))
            charge(project.selectedVideos?.size?.toLong() ?: 0, 512)
            charge(project.assets.size.toLong(), 256) // Compiler and source-verification identity maps.
            charge(project.current.clips.size.toLong(), 1024)
            // Compiler builds baseline/current/candidate dense source maps; include their arrays,
            // temporary LongArray copies and any original timing metadata even after a large trim.
            fun revision(value: HybridRevision) {
                value.clips.forEach { charge(it.sourceMap.points.last().localFrame.toLong() + 1, 64) }
                value.graph.manualMontageState?.clips?.forEach { tracksForAccounting(it.localTracks, ::charge) }
            }
            revision(project.original); revision(project.current)
            charge(graphItems, 512)
            fun tracks(values: List<ParameterTrack>) {
                charge(values.size.toLong(), 256)
                values.forEach { track ->
                    charge(track.keyframes.size.toLong(), 192)
                    track.keyframes.forEach { charge(it.value.size.toLong(), 32) }
                }
            }
            visitInputGraphs(project) { graph ->
                charge(graph.sourceAttachments.size.toLong() + 1, 384) // Timelines and empty list views too.
                charge(graph.clips.size.toLong(), 1024)
                charge(graph.manualMontageState?.effects.orEmpty().size.toLong(), 512)
                tracks(graph.parameterTracks)
                graph.manualMontageState?.clips?.forEach { tracks(it.localTracks) }
                graph.clips.forEach { charge(it.transform.keyframes.size.toLong() + it.speedRamp.keyframes.size, 128) }
            }
            visitInputFrames(project) { charge(1, 512) } // Frames, optional face object, identity index and list references.
            var ordinal = 0L
            visitInputPlanes(project) { plane ->
                charge(1, 256) // Plane wrapper, array/plane identity indexes and lifetime entry.
                val current = ordinal++
                var previous = 0L
                var seen = false
                visitInputPlanes(project) { other ->
                    if (previous++ < current && other.inputIdentity() === plane.inputIdentity()) seen = true
                }
                if (!seen) { charge(1, 32); charge(plane.size.toLong(), 4) }
            }
            ordinal = 0
            visitInputFrames(project) { attachment ->
                val current = ordinal++
                attachment.flow?.let { flow ->
                    charge(1, 256) // Flow wrapper/index/lifetime entry.
                    var previous = 0L
                    var seen = false
                    visitInputFrames(project) { other ->
                        if (previous++ < current && other.flow?.vectors === flow.vectors) seen = true
                    }
                    if (!seen) { charge(1, 96); charge(flow.vectors.size.toLong(), 24) }
                }
            }
            return bytes
        }

        private fun tracksForAccounting(values: List<ParameterTrack>, charge: (Long, Long) -> Unit) {
            charge(values.size.toLong(), 512)
            values.forEach { track ->
                charge(track.keyframes.size.toLong(), 384)
                track.keyframes.forEach { charge(it.value.size.toLong(), 64) }
            }
        }
    }
}
