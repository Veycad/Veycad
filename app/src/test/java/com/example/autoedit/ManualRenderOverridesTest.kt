package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualRenderOverridesTest {
    // Catch incoming transition nodes remaining active when their owner becomes first.
    @Test fun firstIncomingBoundaryIsDormantButOpeningSceneSurvives() {
        val project = imported()
        val moved = prepare(project, TimelineCommand.Move("B", 0))
        val frame = HighQualityFramePlan.build(moved.graph).frames[2]
        assertEquals(MontageGraph.Transition.HARD_CUT, frame.transitionIn)
        assertEquals(0f, frame.effects.glow, 0f)
        assertEquals(0f, frame.effects.glitch, 0f)
        assertFalse(frame.transitionEffectsAllowed)
        assertTrue(moved.graph.manualMontageState!!.effects.any { it.originId == "whip-glow-1" && it.enabled })
        val adapter = project.copy(shared = project.shared.copy(current = moved.copy(id = 2, parentId = 1), nextRevisionId = 3))
        val restored = prepare(adapter, TimelineCommand.Move("B", 1)).graph
        assertTrue(HighQualityFramePlan.build(restored).frames[62].effects.glow > 0f)
        assertEquals(MontageGraph.Transition.OPEN, HighQualityFramePlan.build(restored).frames.first().transitionIn)
    }

    // Catch trim renormalizing a retained transition phase into the remaining one-frame window.
    @Test fun oneFrameTransitionDoesNotRestartItsPhase() {
        for (fps in listOf(30, 60)) {
            val project = imported(fps)
            val before = HighQualityFramePlan.build(project.originalGraph, fps).frames.filter { it.clipIndex == 1 }[1]
            val trimmedStart = prepare(project, TimelineCommand.Trim("B", ClipEdge.START, 1))
            val adapter = project.copy(shared = project.shared.copy(current = trimmedStart.copy(id = 2, parentId = 1), nextRevisionId = 3))
            val trimmed = prepare(adapter, TimelineCommand.Trim("B", ClipEdge.END, 2))
            val frame = HighQualityFramePlan.build(trimmed.graph, fps).frames.single { it.clipIndex == 1 }
            assertEquals(before.transitionProgress!!, frame.transitionProgress!!, 0f)
            assertEquals(before.effects, frame.effects)
        }
    }

    // Catch any authored WHIP transform or node reviving after a hard-cut edit.
    @Test fun hardCutDisablesWholeWhipBundle() {
        val project = imported()
        val graph = prepare(project, TimelineCommand.SetTransition("B", MontageGraph.Transition.HARD_CUT)).graph
        val frame = HighQualityFramePlan.build(graph).frames[62]
        assertEquals(MontageGraph.Transition.HARD_CUT, frame.transitionIn)
        assertNull(TransitionTimeline.blendFor(frame))
        assertEquals(0f, frame.effects.glow, 0f)
        assertEquals(0f, frame.effects.glitch, 0f)
        assertEquals(0f, frame.transform.translateX, 0f)
        assertEquals(.1f, frame.redBias, 0f)
        assertTrue(frame.layer.opacity > 0f)
        assertEquals(project.originalGraph.metadata.generator, graph.metadata.generator)
        assertEquals(project.originalGraph.audioTrack, graph.audioTrack)
    }

    // Catch a secondary source's explicit PTS being silently clamped to another source duration.
    @Test fun explicitSecondaryPtsIsNeverSilentlyClamped() {
        val frame = HighQualityFramePlan.build(ManualMontageFixtures.generatedGraph()).frames.first()
            .copy(secondarySourceIndex = 1, secondarySourceTimeUs = 4_000_000)
        assertThrows(IllegalArgumentException::class.java) {
            MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(frame, 2_000_000)
        }
    }

    // Preserve legacy 1201 ms phase coordinates through trim and an output-window rebase.
    @Test fun fractionalTrimAndPreviewKeepOriginalAndGlobalClocks() {
        val project = imported(60, fractional = true)
        val before = HighQualityFramePlan.build(project.originalGraph, 60).frames.filter { it.clipIndex == 1 }[1]
        val trimmed = prepare(project, TimelineCommand.Trim("B", ClipEdge.START, 1)).graph
        val plan = HighQualityFramePlan.build(trimmed, 60)
        val after = plan.frames.first { it.clipIndex == 1 }
        assertEquals(before.transitionProgress!!, after.transitionProgress!!, 0f)
        assertEquals(before.originalOutputTimeUs, after.originalOutputTimeUs)
        val window = MediaCodecSpeedRampRenderer.outputWindow(plan,
            MediaCodecSpeedRampRenderer.OutputWindow(after.outputTimeUs, after.outputTimeUs + 50_000))
        assertEquals(0L, window.frames.first().outputTimeUs)
        assertEquals(after.globalOutputTimeUs, window.frames.first().globalOutputTimeUs)
        assertEquals(after.originalOutputTimeUs, window.frames.first().originalOutputTimeUs)
        assertEquals(window.frames.first().transitionIn, RenderedVisualSampler.transitionAt(window.frames.first()))
        assertTrue(RenderedVisualSampler.samplingTargets(trimmed, 100_000, 60).contains(after.outputTimeUs))
    }

    // Explicit secondary identity must carry its own matte and choose a different crop downstream.
    @Test fun equalPtsSourcesKeepDistinctDecoderFrameAndRefreshedMask() {
        fun timeline(confidence: Float) = FrameAttachmentTimeline(listOf(FrameAttachments(0,
            mask = FrameAttachments.Plane(1, 1, listOf(1f), confidence))))
        val a = timeline(.2f); val b = timeline(.9f)
        val frame = HighQualityFramePlan.build(ManualMontageFixtures.generatedGraph()).frames.first().copy(
            sourceIndex = 0, sourceTimeUs = 0, attachments = a.interpolated(0),
            secondarySourceIndex = 1, secondarySourceTimeUs = 0, secondaryAttachments = b.interpolated(0))
        val secondary = MediaCodecSpeedRampRenderer.temporalLayerFrame(frame, 0)
        assertEquals(1, secondary.sourceIndex)
        assertEquals(.9f, secondary.attachments!!.mask!!.confidence, 0f)
        val sources = listOf(SourceAttachments(0, a), SourceAttachments(1, b))
        assertEquals(.9f, MediaCodecSpeedRampRenderer.decodedAttachments(secondary, 0, a, sources)!!.mask!!.confidence, 0f)
        assertNull(MediaCodecSpeedRampRenderer.decodedAttachments(secondary.copy(sourceIndex = 2), 0, a, sources))
        assertEquals(.9f, RenderedVisualSampler.withDecodedSourceTime(
            secondary.copy(transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY), a, 0, sources).attachments!!.mask!!.confidence, 0f)
    }

    // The canonical descriptor, not stale enabled bits or its previous type's duration, is authoritative.
    @Test fun differentTransitionUsesItsDefaultAndCannotCarryOldWhipNodes() {
        val project = imported(authoredDuration = 80)
        val revision = project.shared.current
        val candidate = revision.copy(clips = revision.clips.map { if (it.id == "B")
            it.copy(original = it.original.copy(transitionIn = MontageGraph.Transition.BLACKOUT)) else it })
        val graph = EditableMontageCompiler.compile(project, candidate)
        val frame = HighQualityFramePlan.build(graph).frames[62]
        assertEquals(0f, frame.effects.glow, 0f)
        assertEquals(0f, frame.effects.glitch, 0f)
        assertEquals(0f, frame.transform.translateX, 0f)
        assertEquals(105L, graph.clips[1].transitionDurationMs)
        assertEquals(.6349238f, frame.transitionProgress!!, .0000001f)
        assertTrue(frame.layer.opacity > 0f)
    }

    private fun prepare(project: EditableMontageProject, command: TimelineCommand): HybridRevision =
        (MontageTimelineEditor.prepare(project, command) as TimelinePreparation.Prepared).candidate

    private fun imported(fps: Int = 30, fractional: Boolean = false, authoredDuration: Long? = null): EditableMontageProject {
        val base = ManualMontageFixtures.generatedGraph()
        val graph = base.copy(clips = base.clips.map { if (it.id == "B") it.copy(motion = MontageGraph.Motion.WHIP_RIGHT,
            transitionDurationMs = authoredDuration,
            transform = MontageGraph.ClipTransform(listOf(MontageGraph.ClipTransform.Keyframe(0f, 1.03f, .2f),
                MontageGraph.ClipTransform.Keyframe(1f, 1.03f, 0f)))) else it },
            effectGraph = GpuEffectGraph(listOf(
                GpuEffectGraph.Node("whip-glow-1", GpuEffectGraph.Kind.GLOW, 2_000_000, 2_240_000, .28f),
                GpuEffectGraph.Node("whip-glitch-1", GpuEffectGraph.Kind.GLITCH, 2_000_000, 2_120_000, .16f))))
        fun asset(id: String, kind: ProjectAsset.Kind) = ProjectAsset(id, "$id.bin", kind, 10_000_000, "0".repeat(64), id)
        val winner = if (fractional) graph.copy(clips = graph.clips.map { if (it.id == "A1") it.copy(outputDurationMs = 1201) else it },
            outputDurationMs = 5201, overlays = emptyList(), effectGraph = GpuEffectGraph()) else graph
        return EditableMontageImporter.create("manual-render", winner, MontageStyleCatalog.Recipe.DUALITY_LOOP,
            ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000),
            listOf(asset("a", ProjectAsset.Kind.VIDEO), asset("b", ProjectAsset.Kind.VIDEO)),
            asset("score", ProjectAsset.Kind.AUDIO), null)
    }
}
