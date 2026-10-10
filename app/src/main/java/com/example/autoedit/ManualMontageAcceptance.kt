package com.veycad.app

import kotlin.math.abs

/** Technical evidence for the saved revision; selection of this policy belongs to the caller. */
object ManualMontageAcceptance {
    data class Report(val passed: Boolean, val issues: List<String>)

    fun evaluate(graph: MontageGraph, container: RenderedMp4Acceptance.ContainerSample,
        samples: List<RenderedMp4Acceptance.VisualSample>, decodedAudio: DecodedAudioQuality.Report,
        fps: Int): Report {
        val clock = ProjectClock(fps)
        val plan = HighQualityFramePlan.build(graph, fps)
        val frameIndex = RenderQaFrameIndex(plan.frames)
        val toleranceUs = clock.timeUs(1) + 1L
        val transitionWindows = RenderQaTransitionWindow.inPlan(plan.frames)
        val activeTransitions = transitionWindows.map { plan.frames[it.first].clipIndex }.toSet()
        val windowByFrame = IntArray(plan.frames.size) { -1 }
        transitionWindows.forEachIndexed { windowId, window ->
            for (index in window.first..window.last) windowByFrame[index] = windowId
        }
        val observedPeaks = FloatArray(transitionWindows.size)
        val transitionWitnesses = BooleanArray(transitionWindows.size)
        val effective = graph.copy(clips = graph.clips.mapIndexed { index, clip -> clip.copy(
            transitionIn = if (index in activeTransitions) graph.renderTransition(clip) else MontageGraph.Transition.HARD_CUT) },
            effectGraph = GpuEffectGraph(graph.renderNodes()), overlays = graph.renderOverlays())
        val measured = RenderedMp4Acceptance.evaluate(effective,
            AudioBeatMap(1, 0, null, emptyList(), emptyList()), container, samples,
            reference = RenderedMp4Acceptance.ReferenceMontageCard(maximumAvDriftUs = toleranceUs,
                maximumDurationErrorUs = toleranceUs), decodedAudio = decodedAudio,
            requireDecodedAudioEvidence = true, policy = RenderedMp4Acceptance.EvaluationPolicy.MANUAL_TECHNICAL,
            expectedDurationUs = plan.durationUs)
        val issues = buildList {
            addAll(measured.issues)
            if (samples.isEmpty()) add("video-decode-evidence-missing")
            if (abs(decodedAudio.durationUs - plan.durationUs) > toleranceUs) add("audio-decoded-duration-mismatch")
            val coveredClips = mutableSetOf<Int>()
            samples.forEach { sample ->
                val scheduledIndex = frameIndex.nearest(sample.outputTimeUs)
                val frame = plan.frames[scheduledIndex]
                if (sample.outputTimeUs >= plan.durationUs || abs(frame.outputTimeUs - sample.outputTimeUs) > toleranceUs)
                    add("video-sample-outside-revision")
                if (sample.decodedSourceIndex == null || sample.decodedClipIndex == null) add("source-evidence-missing")
                else if (sample.decodedSourceIndex != frame.sourceIndex || sample.decodedClipIndex != frame.clipIndex)
                    add("source-revision-mismatch")
                else {
                    coveredClips += frame.clipIndex
                    val windowId = windowByFrame[scheduledIndex]
                    if (windowId >= 0) {
                        val window = transitionWindows[windowId]
                        val startUs = plan.frames[window.first].outputTimeUs
                        val endUs = plan.frames.getOrNull(window.last + 1)?.outputTimeUs ?: plan.durationUs
                        if (sample.outputTimeUs >= startUs - 1 && sample.outputTimeUs <= endUs) {
                            transitionWitnesses[windowId] = true
                            observedPeaks[windowId] = maxOf(observedPeaks[windowId], sample.transitionStrength)
                        }
                    }
                }
            }
            if (graph.clips.indices.any { it !in coveredClips }) add("clip-decode-evidence-missing")
            transitionWindows.forEachIndexed { windowId, window ->
                val frame = plan.frames[window.first]
                val clip = graph.clips[frame.clipIndex]
                val phaseDurationUs = graph.editableTiming?.clips?.get(frame.clipIndex)?.transitionPhaseDurationUs
                    ?: ((clip.transitionDurationMs ?: TransitionTimeline.durationMs(frame.transitionIn)) * 1_000L)
                val endUs = plan.frames.getOrNull(window.last + 1)?.outputTimeUs ?: plan.durationUs
                val coverage = ((endUs - frame.outputTimeUs).toDouble() / phaseDurationUs).coerceIn(0.0, 1.0)
                val envelope = (window.first..window.last).maxOf { index ->
                    val phase = plan.frames[index].transitionProgress!!
                    (1f - abs(2f * phase - 1f)).coerceIn(0f, 1f)
                }
                // Empirical technical floor, scaled to the retained authored phase. A single
                // middle frame needs evidence, but never the peak of a removed full transition.
                val requiredPeak = measured.reference.minimumTransitionPeak * coverage * envelope
                if (requiredPeak > 0.0) {
                    if (!transitionWitnesses[windowId]) add("transition-evidence-missing:${clip.id}")
                    else if (observedPeaks[windowId] < requiredPeak) add("weak-rendered-transition:${clip.id}")
                }
            }
            // Only an enabled requested effect needs evidence; deleted recipe accents cannot fail.
            if (plan.frames.any { it.effects.glitch >= .04f }) {
                val glitch = samples.filter { it.expectedEffect == RenderedMp4Acceptance.DecodedEffect.GLITCH }
                if (glitch.isEmpty()) add("decoded-glitch-evidence-missing")
                else if (glitch.maxOf { it.effectSignatureStrength } < measured.reference.minimumDecodedGlitchPeak)
                    add("decoded-glitch-missing")
            }
        }.distinct()
        return Report(issues.isEmpty(), issues)
    }
}
