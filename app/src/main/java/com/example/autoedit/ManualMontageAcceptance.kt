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
        val toleranceUs = clock.timeUs(1) + 1L
        val activeTransitions = plan.frames.filter { TransitionTimeline.blendFor(it) != null }.map { it.clipIndex }.toSet()
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
                val frame = plan.frames.minByOrNull { abs(it.outputTimeUs - sample.outputTimeUs) }!!
                if (sample.outputTimeUs >= plan.durationUs || abs(frame.outputTimeUs - sample.outputTimeUs) > toleranceUs)
                    add("video-sample-outside-revision")
                if (sample.decodedSourceIndex == null || sample.decodedClipIndex == null) add("source-evidence-missing")
                else if (sample.decodedSourceIndex != frame.sourceIndex || sample.decodedClipIndex != frame.clipIndex)
                    add("source-revision-mismatch")
                else coveredClips += frame.clipIndex
            }
            if (graph.clips.indices.any { it !in coveredClips }) add("clip-decode-evidence-missing")
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
