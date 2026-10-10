package com.veycad.app

import java.util.Collections

data class ClipLayout(val clipId: String, val startFrame: Long, val endFrameExclusive: Long)

/** Prepares a graph for the common compiler. It never saves, allocates IDs, or runs a director. */
internal object EditableMontageCompiler {
    fun layout(revision: MontageRevision, fps: Int): List<ClipLayout> {
        ProjectClock(fps)
        var cursor = 0L
        return montageSnapshot(revision.clips.map { clip ->
            require(clip.timeMap.fps == fps)
            ClipLayout(clip.id, cursor, Math.addExact(cursor, clip.visible.count)).also { cursor = it.endFrameExclusive }
        })
    }

    fun compile(project: EditableMontageProject, revision: HybridRevision = project.shared.current): MontageGraph {
        val view = project.revisionView(revision)
        // No-op preserves the winner's millisecond schedule, absent v1 tracks and authored data.
        if (view.clips == project.baseline.clips && view.effects == project.baseline.effects &&
            revision.graph == project.shared.original.graph) return snapshotMontageGraph(project.originalGraph)
        val fps = project.export.fps
        val layout = layout(view, fps)
        val baselineById = project.shared.original.clips.associateBy { it.id }
        val byId = view.clips.associateBy { it.id }
        val disabledTransitions = view.clips.filterIndexed { index, clip ->
            clip.origin.transitionIn == MontageGraph.Transition.HARD_CUT ||
                (index == 0 && clip.id != project.baseline.clips.first().id &&
                    clip.origin.transitionIn != MontageGraph.Transition.OPEN)
        }.map { it.id }.toSet()
        val disabledEffects = view.effects.filter { effect ->
            val owner = (effect.anchor as? EffectAnchor.Clip)?.clipId
            val requiredTransition = when {
                effect.originId.startsWith("whip-glow-") || effect.originId.startsWith("whip-glitch-") -> MontageGraph.Transition.WHIP
                effect.originId.startsWith("impact-glow-") -> MontageGraph.Transition.BLACKOUT
                else -> null
            }
            !effect.enabled || (owner != null && requiredTransition != null &&
                (owner in disabledTransitions || byId.getValue(owner).origin.transitionIn != requiredTransition))
        }.map { it.id }.toSet()
        val overrides = ManualRenderOverrides(disabledTransitions, disabledEffects)
        val timing = EditableFrameTiming(fps, view.clips.zip(layout).map { (clip, position) ->
            val baseline = baselineById.getValue(clip.id).original
            val phaseDuration = if (baseline.transitionIn == clip.origin.transitionIn)
                baseline.transitionDurationMs ?: TransitionTimeline.durationMs(baseline.transitionIn)
                else TransitionTimeline.durationMs(clip.origin.transitionIn)
            EditableClipTiming(clip.id, clip.timeMap, clip.visible, position.startFrame, position.endFrameExclusive,
                clip.originFrameCount, clip.phase, clip.localTracks, phaseDuration * 1_000L)
        })
        val clips = view.clips.zip(layout).map { (clip, position) ->
            val durationMs = frameTimeUs(position.endFrameExclusive, fps) / 1000 - frameTimeUs(position.startFrame, fps) / 1000
            val transitionDuration = if (baselineById.getValue(clip.id).original.transitionIn == clip.origin.transitionIn)
                clip.origin.transitionDurationMs else TransitionTimeline.durationMs(clip.origin.transitionIn).takeIf { it > 0 }
            clip.origin.copy(outputDurationMs = durationMs,
                transitionDurationMs = transitionDuration?.coerceAtMost(durationMs))
        }
        val overlays = ArrayList<MontageGraph.Overlay>(); val nodes = ArrayList<GpuEffectGraph.Node>()
        val positions = layout.associateBy { it.clipId }
        for (effect in view.effects.filter { it.id !in disabledEffects }) {
            val overlay = effect.originalOverlay; val node = effect.originalNode
            val authoredStart = overlay?.let { it.startMs * 1000 } ?: node!!.startUs
            val authoredEnd = overlay?.let { it.endMs * 1000 } ?: node!!.endUs
            var start: Long; var end: Long; var offset: Long
            var frameShift: Long? = null
            when (val anchor = effect.anchor) {
                is EffectAnchor.Music -> { start = anchor.startUs; end = anchor.endUs; offset = authoredStart - start }
                is EffectAnchor.Boundary -> {
                    val position = positions.getValue(anchor.incomingClipId)
                    if (position.startFrame == 0L) continue // Dormant, retained in the revision payload.
                    start = frameTimeUs(position.startFrame, fps) + anchor.offsetUs
                    end = start + anchor.durationUs; offset = authoredStart - start
                }
                is EffectAnchor.Clip -> {
                    val clip = byId.getValue(anchor.clipId); val position = positions.getValue(anchor.clipId)
                    val phase = clip.phase
                    val originalStart = phase?.startUs ?: 0L
                    fun originalTime(frame: Long) = phase?.timeUs(frame, fps) ?: frameTimeUs(frame, fps)
                    val first = originalTime(clip.visible.start)
                    val limit = originalTime(clip.visible.endExclusive)
                    val left = maxOf(originalStart + anchor.localStartUs, first)
                    val right = minOf(originalStart + anchor.localEndUs, limit)
                    if (right <= left) continue
                    offset = first - frameTimeUs(position.startFrame, fps)
                    frameShift = (phase?.firstFrame ?: 0L) + clip.visible.start - position.startFrame
                    start = left - offset; end = right - offset
                }
            }
            start = start.coerceAtLeast(0L)
            end = minOf(end, frameTimeUs(timing.frameCount, fps))
            if (end <= start) continue
            val window = EffectSampleWindow(start, end, offset, authoredStart, authoredEnd, frameShift, fps.takeIf { frameShift != null })
            val phaseStart = ((start + offset - authoredStart).toFloat() / (authoredEnd - authoredStart)).coerceIn(0f, 1f)
            val phaseEnd = ((end + offset - authoredStart).toFloat() / (authoredEnd - authoredStart)).coerceIn(0f, 1f)
            if (phaseEnd <= phaseStart) continue
            if (overlay != null) overlays += overlay.copy(id = effect.id, originId = effect.originId,
                startMs = start / 1000, endMs = maxOf(start / 1000 + 1, (end + 999) / 1000),
                phaseStart = phaseStart, phaseEnd = phaseEnd, sampleWindow = window)
            if (node != null) nodes += node.copy(id = effect.id, originId = effect.originId,
                startUs = start, endUs = end, phaseStart = phaseStart, phaseEnd = phaseEnd, sampleWindow = window)
        }
        val payload = ManualMontageState(montageSnapshot(view.clips.map {
            ManualClipState(it.id, it.visible, it.originFrameCount, snapshotTracks(it.localTracks), it.phase)
        }), montageSnapshot(view.effects))
        return snapshotMontageGraph(revision.graph.copy(version = 3,
            metadata = revision.graph.metadata.copy(schemaVersion = 3),
            outputDurationMs = clips.sumOf { it.outputDurationMs }, clips = clips,
            overlays = overlays, effectGraph = GpuEffectGraph(nodes), editableTiming = timing,
            manualMontageState = payload, manualOverrides = overrides))
    }
}

internal fun <T> montageSnapshot(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

internal fun snapshotTracks(tracks: List<ParameterTrack>): List<ParameterTrack> = montageSnapshot(tracks.map { track ->
    track.copy(keyframes = montageSnapshot(track.keyframes.map { it.copy(value = montageSnapshot(it.value)) }))
})

/** Own every new metadata/timing/local-track list at the preview/export handoff boundary. */
internal fun snapshotMontageGraph(graph: MontageGraph): MontageGraph = graph.copy(
    manualOverrides = graph.manualOverrides?.let { ManualRenderOverrides(
        Collections.unmodifiableSet(LinkedHashSet(it.disabledTransitionClipIds)),
        Collections.unmodifiableSet(LinkedHashSet(it.disabledEffectIds))) },
    clips = montageSnapshot(graph.clips.map { clip -> clip.copy(
        transform = clip.transform.copy(keyframes = montageSnapshot(clip.transform.keyframes)),
        speedRamp = clip.speedRamp.copy(keyframes = montageSnapshot(clip.speedRamp.keyframes))) }),
    overlays = montageSnapshot(graph.overlays), parameterTracks = snapshotTracks(graph.parameterTracks),
    effectGraph = GpuEffectGraph(montageSnapshot(graph.effectGraph.nodes)),
    sourceAttachments = montageSnapshot(graph.sourceAttachments),
    manualMontageState = graph.manualMontageState?.let { state -> ManualMontageState(
        montageSnapshot(state.clips.map { it.copy(localTracks = snapshotTracks(it.localTracks)) }), montageSnapshot(state.effects)) })
