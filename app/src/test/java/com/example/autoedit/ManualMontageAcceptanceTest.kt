package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualMontageAcceptanceTest {
    // A user-selected clip count must not be tested against the automatic DUALITY grammar.
    @Test fun manualClipCountIsNotAnAutomaticGrammarFailure() {
        val graph = graph()
        assertTrue(ManualMontageAcceptance.evaluate(graph, container(), samples(), audio(), 30).passed)
        val automatic = RenderedMp4Acceptance.evaluate(graph, AudioBeatMap(100, 600, null, emptyList(), emptyList()),
            container(), samples(), decodedAudio = audio())
        assertFalse(automatic.accepted)
        assertTrue(automatic.issues.contains("duality-clip-count"))
    }

    @Test fun badContainerStillFailsManualAcceptance() {
        val report = ManualMontageAcceptance.evaluate(graph(), container().copy(integrityIssues = listOf("undecodable")),
            samples(), audio(), 30)
        assertFalse(report.passed)
        assertTrue(report.issues.contains("container:undecodable"))
        assertFalse(ManualMontageAcceptance.evaluate(graph(), container(), emptyList(), audio(), 30).passed)
    }

    // Null editableTiming gives no permission to guess 30 fps for an actual 60 fps export.
    @Test fun explicitSavedFpsControlsDurationToleranceForNoOpGraph() {
        val late = container().copy(videoLastPtsUs = 6_020_000, audioLastPtsUs = 6_020_000)
        assertTrue(ManualMontageAcceptance.evaluate(graph(), late, samples(), audio(), 30).passed)
        assertFalse(ManualMontageAcceptance.evaluate(graph(), late, samples(), audio(), 60).passed)
    }

    @Test fun wrongSourceAndMissingDecodedAudioEvidenceFail() {
        val wrong = samples().map { it.copy(decodedSourceIndex = 0) }
        assertFalse(ManualMontageAcceptance.evaluate(graph(), container(), wrong, audio(), 30).passed)
        assertFalse(ManualMontageAcceptance.evaluate(graph(), container(), samples(),
            audio().copy(issues = listOf("audio-pcm-empty")), 30).passed)
        assertFalse(ManualMontageAcceptance.evaluate(graph(), container(), samples().dropLast(1), audio(), 30).passed)
    }

    @Test fun manualFearKeepsMeasuredBlackBlockFailures() {
        val graph = graph().copy(metadata = graph().metadata.copy(generator = FearStrobeProfile.ID))
        val report = ManualMontageAcceptance.evaluate(graph, container(),
            samples().map { it.copy(blackBlockScore = .5f) }, audio(), 30)
        assertFalse(report.passed)
        assertTrue(report.issues.contains("black-block-artifact"))
    }

    // A trim past a foreground entrance cannot require pixels from the removed entrance.
    @Test fun trimmedAwayForegroundPhaseDoesNotRequireMaskEvidence() {
        val base = ManualMontageFixtures.linearProject()
        val original = base.shared.original
        val clips = original.clips.mapIndexed { index, clip -> if (index == 0) clip.copy(original =
            clip.original.copy(transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY, transitionDurationMs = 100))
            else clip }
        val revision = original.copy(clips = clips, graph = original.graph.copy(clips = clips.map { it.original }))
        val project = base.copy(shared = base.shared.copy(original = revision, current = revision))
        val candidate = (MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.START, 10))
            as TimelinePreparation.Prepared).candidate
        val plan = HighQualityFramePlan.build(candidate.graph)
        assertTrue(plan.frames.filter { it.clipIndex == 0 }.all { it.transitionProgress == -1f })
        val evidence = plan.frames.distinctBy { it.clipIndex }.map { frame ->
            RenderedMp4Acceptance.VisualSample(frame.outputTimeUs, .2f, .5f, .5f, .5f, 0f, false, 0f,
                decodedSourceIndex = frame.sourceIndex, decodedClipIndex = frame.clipIndex)
        }
        val report = ManualMontageAcceptance.evaluate(candidate.graph,
            container().copy(videoLastPtsUs = 5_666_667, audioLastPtsUs = 5_666_667), evidence,
            DecodedAudioQuality.evaluate(FloatArray(170) { .1f }, 30, 1, 5_666_667, 33_334, true), 30)
        assertTrue(report.issues.toString(), report.passed)
    }

    private fun graph() = ManualMontageFixtures.generatedGraph().let { base -> base.copy(
        clips = base.clips.map { it.copy(transitionIn = MontageGraph.Transition.HARD_CUT) }, overlays = emptyList(),
        metadata = base.metadata.copy(generator = DualityLoopProfile.ID)) }
    private fun container() = RenderedMp4Acceptance.ContainerSample(6_000_000, 6_000_000, "video/avc", "audio/mp4a-latm")
    private fun samples() = listOf(0L, 2_000_000L, 4_000_000L).mapIndexed { i, time ->
        RenderedMp4Acceptance.VisualSample(time, .2f, .5f, .5f, .5f, 0f, false, 0f,
            decodedSourceIndex = if (i == 1) 1 else 0, decodedClipIndex = i)
    }
    private fun audio() = DecodedAudioQuality.evaluate(FloatArray(600) { .1f }, 100, 1, 6_000_000, 33_334, true)
}
