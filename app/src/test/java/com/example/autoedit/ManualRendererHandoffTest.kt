package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualRendererHandoffTest {
    // A temporal role must sample its own semantics at actual texture PTS, not requested PTS.
    @Test fun temporalRoleRefreshUsesOwnSourceAtActualPts() {
        val a = timeline(.2f)
        val b = timeline(.9f)
        val frame = HighQualityFramePlan.build(ManualMontageFixtures.generatedGraph()).frames.first().copy(
            sourceTimeUs = 0, attachments = a.interpolated(0), secondarySourceIndex = 1,
            secondarySourceTimeUs = 0, secondaryAttachments = b.interpolated(0),
            layer = LayerCompositorModel.Sample(kind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opacity = .6f))
        val role = MediaCodecSpeedRampRenderer.temporalLayerFrame(frame, 0)
        val actual = RenderedVisualSampler.withDecodedSourceTime(role, a, 100_000,
            listOf(SourceAttachments(0, a), SourceAttachments(1, b)))
        assertEquals(1, actual.sourceIndex)
        assertEquals(100_000L, actual.sourceTimeUs)
        assertEquals(100_000L, actual.attachments!!.sourceTimeUs)
        assertEquals(.9f, actual.attachments!!.mask!!.confidence, 0f)
        assertEquals(0L, role.sourceTimeUs)
    }

    // A nonempty table cannot borrow a different file's matte, even for a hard-cut frame.
    @Test fun missingSourceNeverFallsBackToEqualPtsLegacyAttachment() {
        val a = timeline(.2f)
        val frame = HighQualityFramePlan.build(ManualMontageFixtures.generatedGraph()).frames.first().copy(
            sourceIndex = 2, sourceTimeUs = 0, attachments = a.interpolated(0))
        val actual = RenderedVisualSampler.withDecodedSourceTime(frame, a, 100_000,
            listOf(SourceAttachments(0, a)))
        assertNull(actual.attachments)
    }

    // The held image keeps the previous scene clock, but absolute authored cues advance.
    @Test fun heldBoundaryUsesCurrentProjectClockAndPreviousSceneAndSource() {
        val graph = ManualMontageFixtures.generatedGraph()
        val plan = HighQualityFramePlan.build(graph).frames
        val incoming = plan.first { it.clipIndex == 1 }
        val outgoing = plan.last { it.clipIndex == 0 }
        val provenance = VeykadRenderInspector.DecodedFrameProvenance(0, 0,
            outgoing.sourceTimeUs, 1_966_667, MontageGraph.Transition.HARD_CUT)
        val held = RenderedVisualSampler.withDecodedFrameProvenance(incoming, plan,
            FrameAttachmentTimeline(), null, provenance)
        assertEquals(2_000_000L, held.globalOutputTimeUs)
        assertEquals(1_966_667L, held.originalOutputTimeUs)
        assertEquals(2_000_000L, held.outputTimeUs)
        assertEquals(0, held.sourceIndex)
        assertEquals(outgoing.transform, held.transform)
    }

    // Actual held provenance must override a misleading scheduled source and clock entry.
    @Test fun heldProvenanceSelectsOwnSemanticTableBeforeActualPtsRefresh() {
        val graph = ManualMontageFixtures.generatedGraph()
        val plan = HighQualityFramePlan.build(graph).frames
        val incoming = plan.first { it.clipIndex == 1 }
        val outgoing = plan.last { it.clipIndex == 0 }
        val a = timeline(.2f); val b = timeline(.9f)
        val provenance = VeykadRenderInspector.DecodedFrameProvenance(0, 0,
            outgoing.sourceTimeUs, 100_000, MontageGraph.Transition.HARD_CUT)
        val held = RenderedVisualSampler.withDecodedFrameProvenance(incoming, plan, b, 0,
            provenance, listOf(SourceAttachments(0, a), SourceAttachments(1, b)))
        assertEquals(0, held.sourceIndex)
        assertEquals(100_000L, held.sourceTimeUs)
        assertEquals(.2f, held.attachments!!.mask!!.confidence, 0f)
        assertEquals(100_000L, held.attachments!!.sourceTimeUs)
    }

    // Exercise the published importer/commands/compiler through renderer role and window adapters.
    @Test fun reorderedRepeatedSelectionsKeepTemporalRoleAndThreeClocksAt30And60Fps() {
        for (fps in listOf(30, 60)) {
            val base = ManualMontageFixtures.generatedGraph()
            val graph = base.copy(clips = base.clips.map { if (it.id == "A2") it.copy(sourceIndex = 2) else it },
                overlays = listOf(MontageGraph.Overlay("role", 0, 1000, "temporal-role",
                    overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opacity = .8f,
                    secondaryTimelineStartMs = 4000)))
            fun asset(id: String) = ProjectAsset(id, "$id.mp4", ProjectAsset.Kind.VIDEO, 10_000_000,
                "a".repeat(64), videoMetadata = VideoSourceMetadata(SourceGeometry(1920, 1080, 0, 1f),
                    1234, "video/avc", 3, true))
            val a = asset("a")
            val sources = listOf(a, asset("b"), a, asset("unused"))
            val selections = listOf("first", "second", "repeat", "unused").zip(sources).map { (id, source) ->
                SelectedVideo(SourceId(id), source.id, SourceOwnership.IMPORTED, displayName = id)
            }
            val project = EditableMontageImporter.create("renderer-handoff", graph, MontageStyleCatalog.Recipe.DUALITY_LOOP,
                ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000), sources,
                ProjectAsset("score", "score.wav", ProjectAsset.Kind.AUDIO, 10_000_000, "fixture"), null, selections)
            assertEquals(HighQualityFramePlan.build(graph, fps),
                HighQualityFramePlan.build(EditableMontageCompiler.compile(project), fps))
            val prepared = MontageTimelineEditor.prepare(project, TimelineCommand.Move("B", 0)) as TimelinePreparation.Prepared
            val moved = project.copy(shared = HybridEditCommands.apply(project.shared, ProjectCommand.CommitRevision(prepared.candidate)))
            val compiled = EditableMontageCompiler.compile(moved)
            assertEquals(listOf(1, 0, 2), compiled.clips.map { it.sourceIndex })
            assertEquals(selections, moved.shared.selectedVideos)
            val plan = HighQualityFramePlan.build(compiled, fps)
            val frame = plan.frames.single { it.outputTimeUs == 2_500_000L }
            assertEquals(0, frame.sourceIndex)
            assertEquals(2, frame.secondarySourceIndex)
            assertEquals(1_500_000L, frame.sourceTimeUs)
            assertEquals(6_500_000L, frame.secondarySourceTimeUs)
            val files = sources.map { java.io.File(it.fileName) }
            val request = MediaCodecSpeedRampRenderer.Request(sourceFiles = files, graph = compiled,
                outputFile = java.io.File("unused-output.mp4"), width = 720, height = 1280, bitrate = 5_000_000, fps = fps)
            assertEquals(4, request.sourceFiles.size)
            val secondary = MediaCodecSpeedRampRenderer.temporalLayerFrame(frame,
                MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(frame, sources[2].durationUs))
            assertEquals(2, secondary.sourceIndex)
            assertEquals(java.io.File("a.mp4"), request.sourceFiles[secondary.sourceIndex])
            val local = MediaCodecSpeedRampRenderer.outputWindow(plan,
                MediaCodecSpeedRampRenderer.OutputWindow(2_500_000, 2_600_000)).frames.first()
            assertEquals(0L, local.outputTimeUs)
            assertEquals(2_500_000L, local.globalOutputTimeUs)
            assertEquals(500_000L, local.originalOutputTimeUs)
            assertEquals(frame.layer, local.layer)
        }
    }

    private fun timeline(confidence: Float) = FrameAttachmentTimeline(listOf(0L, 100_000L).map { pts ->
        FrameAttachments(pts, mask = FrameAttachments.Plane(1, 1, listOf(1f), confidence))
    })
}
