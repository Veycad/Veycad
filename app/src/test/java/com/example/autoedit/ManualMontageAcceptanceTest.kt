package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualMontageAcceptanceTest {
    @Test fun retainedWhipAndBlackoutNeedEvidenceInsideTheirOwnWindow() {
        for (fps in listOf(30, 60)) for (type in listOf(MontageGraph.Transition.WHIP, MontageGraph.Transition.BLACKOUT)) {
            val graph = transitionProject(fps, type).originalGraph.copy(manualOverrides = ManualRenderOverrides())
            assertFalse("$type $fps absent", transitionReport(graph, fps) { 0f }.passed)
            assertFalse("$type $fps other clip", transitionReport(graph, fps) { if (it.clipIndex == 1) 0f else .8f }.passed)
            assertFalse("$type $fps outside retained window", transitionReport(graph, fps) {
                if (it.clipIndex == 1 && TransitionTimeline.blendFor(it) == null) .8f else 0f
            }.passed)
            assertTrue("$type $fps present", transitionReport(graph, fps) { .2f }.passed)
        }
    }

    @Test fun middleOneFrameFragmentUsesRetainedPhaseThresholdAtBothFpsAndFractionalBoundary() {
        for (fps in listOf(30, 60)) for (fractional in listOf(false, true)) {
            val project = transitionProject(fps, MontageGraph.Transition.WHIP, fractional)
            val start = if (fps == 30) { if (fractional) 4L else 5L } else 9L
            val graph = trimTransition(project, start, start + 1)
            assertFalse(transitionReport(graph, fps) { 0f }.passed)
            val report = transitionReport(graph, fps) { .02f }
            assertTrue("fps=$fps fractional=$fractional ${report.issues}", report.passed)
        }
    }

    @Test fun endpointDisabledAndFullyTrimmedTransitionsDoNotRequirePeak() {
        for (fps in listOf(30, 60)) {
            val project = transitionProject(fps, MontageGraph.Transition.WHIP)
            val endpoint = trimTransition(project, 0, 1)
            assertTrue(transitionReport(endpoint, fps) { 0f }.passed)
            val disabled = project.originalGraph.copy(manualOverrides = ManualRenderOverrides(setOf("B")))
            assertTrue(transitionReport(disabled, fps) { 0f }.passed)
            val removed = trimTransition(project, fps.toLong(), fps.toLong() + 1)
            assertTrue(transitionReport(removed, fps) { 0f }.passed)
            val moved = (MontageTimelineEditor.prepare(project, TimelineCommand.Move("B", 0))
                as TimelinePreparation.Prepared).candidate.graph
            assertTrue(transitionReport(moved, fps) { 0f }.passed)
        }
    }

    @Test fun boundaryHoldHasStrictSourceAndBoundaryIdentityAtBothFps() {
        for (fps in listOf(30, 60)) {
            val graph = transitionProject(fps, MontageGraph.Transition.WHIP).originalGraph
                .copy(manualOverrides = ManualRenderOverrides())
            val plan = HighQualityFramePlan.build(graph, fps)
            val samples = plan.frames.map { frame ->
                val boundary = frame.outputTimeUs == 2_000_000L
                RenderedMp4Acceptance.VisualSample(frame.outputTimeUs, .2f, .5f, .5f, .5f, 0f, false, 0f,
                    decodedSourceIndex = if (boundary) 0 else frame.sourceIndex,
                    decodedClipIndex = if (boundary) 0 else frame.clipIndex)
            }
            fun evaluate(evidence: List<RenderedMp4Acceptance.VisualSample>) = ManualMontageAcceptance.evaluate(
                graph, container(), evidence,
                DecodedAudioQuality.evaluate(FloatArray(plan.frames.size) { .1f }, fps, 1, plan.durationUs, 33_334, true), fps)
            assertTrue(evaluate(samples).issues.toString(), evaluate(samples).passed)
            assertTrue(evaluate(samples.map { if (it.outputTimeUs == 2_000_000L) it.copy(decodedSourceIndex = 1,
                decodedClipIndex = 1) else it }).issues.contains("source-revision-mismatch"))
            val next = plan.frames.first { it.outputTimeUs > 2_000_000L }.outputTimeUs
            assertTrue(evaluate(samples.map { if (it.outputTimeUs == next) it.copy(decodedSourceIndex = 0,
                decodedClipIndex = 0) else it }).issues.contains("source-revision-mismatch"))
        }
    }

    @Test fun singleRetainedBlackoutFrameNeedsItsIncomingSourceAndCentralPhaseAtBothFps() {
        for (fps in listOf(30, 60)) {
            val start = if (fps == 30) 1L else 3L
            val graph = trimTransition(transitionProject(fps, MontageGraph.Transition.BLACKOUT), start, start + 1)
            val plan = HighQualityFramePlan.build(graph, fps)
            val only = plan.frames.single { it.clipIndex == 1 }
            assertTrue(only.transitionProgress!! > .25f && only.transitionProgress!! < .75f)
            assertEquals(only, LiveDecoderTransitionPlan.drawFrames(plan.frames).single { it.outputTimeUs == only.outputTimeUs })
            assertTrue(transitionReport(graph, fps) { .2f }.passed)
            assertFalse(transitionReport(graph, fps) { 0f }.passed)
        }
    }

    @Test fun inspectorUsesRetainedWindowsAndStillRejectsMissingRetainedEffects() {
        for (fps in listOf(30, 60)) for (type in listOf(MontageGraph.Transition.WHIP,
            MontageGraph.Transition.BLACKOUT, MontageGraph.Transition.FOREGROUND_REENTRY)) {
            val project = transitionProject(fps, type, durationMs = if (type == MontageGraph.Transition.FOREGROUND_REENTRY) 100 else null)
            val removed = trimTransition(project, fps.toLong(), fps.toLong() + 1)
            val removedPlan = HighQualityFramePlan.build(removed, fps)
            val removedCollector = VeykadRenderInspector.Collector(removed, removedPlan.frames.size, removedPlan.frames)
            removedPlan.frames.forEach { removedCollector.record(it, TransitionTimeline.blendFor(it), true) }
            assertTrue("removed $type $fps ${removedCollector.summary().issues}", removedCollector.summary().accepted)
            val retained = trimTransition(project, 1, 2)
            val plan = HighQualityFramePlan.build(retained, fps)
            val correct = VeykadRenderInspector.Collector(retained, plan.frames.size, plan.frames)
            val missing = VeykadRenderInspector.Collector(retained, plan.frames.size, plan.frames)
            plan.frames.forEach { correct.record(it, TransitionTimeline.blendFor(it), true)
                missing.record(it, null, false) }
            assertTrue("retained $type $fps ${correct.summary().issues}", correct.summary().accepted)
            assertFalse("missing $type $fps", missing.summary().accepted)
        }
    }

    @Test fun inspectorCameraIntentUsesSuppressedWhipButKeepsIndependentScaleAndOtherMotion() {
        val base = transitionProject(30, MontageGraph.Transition.WHIP).originalGraph
        fun graph(motion: MontageGraph.Motion, scale: Float) = base.copy(clips = base.clips.map { it.copy(
            transitionIn = MontageGraph.Transition.HARD_CUT, motion = motion,
            transform = MontageGraph.ClipTransform(listOf(0f, 1f).map { at -> MontageGraph.ClipTransform.Keyframe(at, scale,
                translateX = .1f, rotationDegrees = 5f) })) }, manualOverrides = ManualRenderOverrides())
        fun summary(graph: MontageGraph): VeykadRenderInspector.Summary {
            val plan = HighQualityFramePlan.build(graph, 30)
            val collector = VeykadRenderInspector.Collector(graph, plan.frames.size, plan.frames)
            plan.frames.forEach { collector.record(it.copy(transform = MontageGraph.ClipTransform.Keyframe(0f, 1f)), null, false) }
            return collector.summary()
        }
        assertFalse(summary(graph(MontageGraph.Motion.WHIP_LEFT, 1f)).issues.contains("virtual_camera_not_visible"))
        assertTrue(summary(graph(MontageGraph.Motion.WHIP_LEFT, 1.2f)).issues.contains("virtual_camera_not_visible"))
        assertTrue(summary(graph(MontageGraph.Motion.HOLD, 1f)).issues.contains("virtual_camera_not_visible"))
    }

    private fun transitionReport(graph: MontageGraph, fps: Int, strength: (HighQualityFramePlan.Frame) -> Float): ManualMontageAcceptance.Report {
        val plan = HighQualityFramePlan.build(graph, fps)
        val samples = LiveDecoderTransitionPlan.drawFrames(plan.frames).map { frame -> RenderedMp4Acceptance.VisualSample(frame.outputTimeUs,
            strength(frame), .5f, .5f, .5f, 0f, false, 0f, decodedSourceIndex = frame.sourceIndex, decodedClipIndex = frame.clipIndex) }
        return ManualMontageAcceptance.evaluate(graph,
            container().copy(videoLastPtsUs = plan.durationUs, audioLastPtsUs = plan.durationUs), samples,
            DecodedAudioQuality.evaluate(FloatArray(plan.frames.size) { .1f }, fps, 1, plan.durationUs, 33_334, true), fps)
    }

    private fun trimTransition(project: EditableMontageProject, start: Long, end: Long): MontageGraph {
        val startResult = MontageTimelineEditor.prepare(project, TimelineCommand.Trim("B", ClipEdge.START, start))
        val adapter = if (startResult is TimelinePreparation.Prepared) project.copy(shared = project.shared.copy(
            current = startResult.candidate.copy(id = 2, parentId = 1), nextRevisionId = 3)) else project
        return (MontageTimelineEditor.prepare(adapter, TimelineCommand.Trim("B", ClipEdge.END, end))
            as TimelinePreparation.Prepared).candidate.graph
    }

    private fun transitionProject(fps: Int, type: MontageGraph.Transition, fractional: Boolean = false, durationMs: Long? = null): EditableMontageProject {
        val base = ManualMontageFixtures.generatedGraph()
        val graph = base.copy(clips = base.clips.mapIndexed { index, clip -> when (index) {
            0 -> clip.copy(outputDurationMs = if (fractional) 1201 else 2000)
            1 -> clip.copy(transitionIn = type, transitionDurationMs = durationMs)
            else -> clip.copy(transitionIn = MontageGraph.Transition.HARD_CUT)
        } }, outputDurationMs = if (fractional) 5201 else 6000, overlays = emptyList(), parameterTracks = emptyList())
        fun asset(id: String, kind: ProjectAsset.Kind) = ProjectAsset(id, "$id.bin", kind, 10_000_000, "0".repeat(64), id)
        return EditableMontageImporter.create("manual-qa", graph, MontageStyleCatalog.Recipe.DUALITY_LOOP,
            ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000),
            listOf(asset("a", ProjectAsset.Kind.VIDEO), asset("b", ProjectAsset.Kind.VIDEO)),
            asset("score", ProjectAsset.Kind.AUDIO), null)
    }

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
        val evidence = LiveDecoderTransitionPlan.drawFrames(plan.frames).map { frame ->
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
