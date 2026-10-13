package com.veycad.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MultiSourceRequestTest {
    @Test fun galleryValidatesEverySelectedFileAndKeepsEqualSelectionsLegal() {
        val directory = Files.createTempDirectory("gallery-request").toFile()
        try {
            val video = File(directory, "video.mp4").apply { writeText("video") }
            val music = File(directory, "neon_drift.wav").apply { writeText("music") }
            val sources = MediaSourceSet(gallerySources(20).items.map { it.copy(file = video) })
            VeycadAutomaticEditor.Request.validateSourceFiles(sources, music, MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
            val missing = MediaSourceSet(sources.items.mapIndexed { index, source ->
                if (index == 19) source.copy(file = File(directory, "missing.mp4")) else source })
            assertThrows(IllegalArgumentException::class.java) {
                VeycadAutomaticEditor.Request.validateSourceFiles(missing, music, MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VeycadAutomaticEditor.Request.validateSourceFiles(sources, video, MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VeycadAutomaticEditor.Request.validateSourceFiles(MediaSourceSet(sources.items.take(2)), music,
                    MontageStyleCatalog.Recipe.DUALITY_LOOP)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun rendererPreservesTwentyOrderedSelectionsIncludingEqualFiles() {
        val files = (19 downTo 0).map { File("source-$it.mp4") }.toMutableList()
        files[1] = files[0]
        val request = request(files)
        files.clear()
        assertEquals(20, request.sourceFiles.size)
        assertEquals(File("source-19.mp4"), request.sourceFiles[0])
        assertEquals(File("source-19.mp4"), request.sourceFiles[1])
        assertEquals(File("source-0.mp4"), request.sourceFiles[19])
        assertThrows(UnsupportedOperationException::class.java) {
            (request.sourceFiles as MutableList<File>).clear()
        }
    }

    @Test fun rendererRejectsEmptyOversizedAndInvalidGraphIndices() {
        assertThrows(IllegalArgumentException::class.java) { request(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { request(List(21) { File("$it.mp4") }) }
        assertThrows(IllegalArgumentException::class.java) { request(listOf(File("a.mp4")), 1) }
        assertThrows(IllegalArgumentException::class.java) { request(listOf(File("a.mp4")), -1) }
        assertEquals(19, request(List(20) { File("$it.mp4") }, 19).graph.clips.single().sourceIndex)
    }

    @Test fun legacyRendererWrappersKeepOneAndTwoSourceOrderAndOptions() {
        val a = File("a.mp4")
        val b = File("b.mp4")
        val one = MediaCodecSpeedRampRenderer.Request(masterFile = a, graph = graph(0),
            outputFile = File("out.mp4"), width = 720, height = 1280, bitrate = 5_000_000)
        val two = request(listOf(a, b), 1)
        val legacy = MediaCodecSpeedRampRenderer.Request(masterFile = a, secondaryFile = b,
            graph = graph(1), outputFile = File("out.mp4"), width = 720, height = 1280,
            bitrate = 5_000_000, fps = 60, debugFaceRegionProbe = true)
        assertEquals(listOf(a), one.sourceFiles)
        assertEquals(two.sourceFiles, legacy.sourceFiles)
        assertEquals(60, legacy.fps)
        assertTrue(legacy.debugFaceRegionProbe)
    }

    @Test fun automaticRequestKeepsLegacyRecipeCardinality() {
        MontageStyleCatalog.Recipe.entries.filter { it != MontageStyleCatalog.Recipe.GALLERY_MONTAGE }.forEach { recipe ->
            val expected = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) 2 else 1
            VeycadAutomaticEditor.Request.validateRecipeSources(sourceSet(expected), recipe)
            listOf(0, 3, 20, if (expected == 1) 2 else 1).forEach { count ->
                assertThrows(IllegalArgumentException::class.java) {
                    VeycadAutomaticEditor.Request.validateRecipeSources(sourceSet(count), recipe)
                }
            }
        }
    }

    @Test fun galleryAllowsOneToTwentySelectionsIncludingEqualFiles() {
        for (count in listOf(1, 2, 10, 20)) {
            VeycadAutomaticEditor.Request.validateRecipeSources(sourceSet(count), MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
        }
        for (count in listOf(0, 21)) assertThrows(IllegalArgumentException::class.java) {
            VeycadAutomaticEditor.Request.validateRecipeSources(sourceSet(count), MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
        }
    }

    @Test fun samplerResolvesFaceEvidenceByOrderedSourceIndex() {
        val frame = HighQualityFramePlan.build(graph(19)).frames.first()
        val absent = VisualEventMap(1_000_000, emptyList(), listOf(observation(null)))
        val present = VisualEventMap(1_000_000, emptyList(), listOf(observation(VisualEventMap.Face(.92f, 0f))))
        val maps = MutableList<VisualEventMap?>(20) { absent }.apply { this[19] = present }
        assertEquals(.92f, RenderedVisualSampler.expectedFaceConfidence(frame, maps), 0f)
        assertEquals(0f, RenderedVisualSampler.expectedFaceConfidence(frame.copy(sourceIndex = 7), maps), 0f)
        assertEquals(0f, RenderedVisualSampler.expectedFaceConfidence(frame, emptyList()), 0f)
    }

    @Test fun inspectorRecordsActualSourceIndicesForBothDecoderRoles() {
        val graph = graph(19)
        val collector = VeykadRenderInspector.Collector(graph, 1)
        collector.record(HighQualityFramePlan.build(graph).frames.first(), null, true,
            secondarySourceTimeUs = 800_000, decodedSourceTimeUs = 33_333,
            decodedSecondarySourceTimeUs = 833_333, secondarySourceIndex = 7)
        val evidence = collector.evidence().single()
        assertEquals(19, evidence.sourceIndex)
        assertEquals(7, evidence.secondarySourceIndex)
        assertEquals(33_333L, evidence.decodedSourceTimeUs)
        assertEquals(833_333L, evidence.decodedSecondarySourceTimeUs)
    }

    @Test fun heldBoundaryKeepsActualSourceTimeClipTransformAndFaceMap() {
        val graph = boundaryGraph()
        val plan = HighQualityFramePlan.build(graph).frames
        val maps = List<VisualEventMap?>(20) { index -> VisualEventMap(2_000_000, emptyList(),
            listOf(VisualEventMap.Observation(333_333, face = if (index == 19) VisualEventMap.Face(.92f, 0f) else null))) }
        for ((boundaryUs, sourceIndex, clipIndex) in listOf(Triple(400_000L, 19, 0), Triple(800_000L, 7, 1))) {
            val scheduled = plan.single { it.outputTimeUs == boundaryUs }
            val held = plan.last { it.clipIndex == clipIndex }.copy(outputTimeUs = boundaryUs,
                transitionIn = MontageGraph.Transition.HARD_CUT, transitionProgress = -1f)
            val collector = VeykadRenderInspector.Collector(graph, 1)
            collector.record(held, null, false, decodedSourceTimeUs = 333_333)
            val witness = collector.evidence().single()
            val provenance = VeykadRenderInspector.DecodedFrameProvenance(witness.sourceIndex,
                witness.clipIndex, witness.sourceTimeUs, witness.decodedSourceTimeUs!!, witness.transition)
            val actual = RenderedVisualSampler.withDecodedFrameProvenance(scheduled, plan,
                graph.frameAttachments, 0, provenance)
            assertEquals(sourceIndex, actual.sourceIndex)
            assertEquals(clipIndex, actual.clipIndex)
            assertEquals(333_333L, actual.sourceTimeUs)
            assertEquals(boundaryUs, actual.outputTimeUs)
            assertEquals(held.transform, actual.transform)
            assertEquals(held.effects, actual.effects)
            assertEquals(MontageGraph.Transition.HARD_CUT, actual.transitionIn)
            assertEquals(-1f, actual.transitionProgress!!, 0f)
            assertEquals(if (sourceIndex == 19) .92f else 0f,
                RenderedVisualSampler.expectedFaceConfidence(actual, maps), 0f)
        }
    }

    @Test fun missingProvenanceKeepsLegacyDecodedClockAndPlannedFrameIdentity() {
        val graph = boundaryGraph()
        val plan = HighQualityFramePlan.build(graph).frames
        val scheduled = plan.single { it.outputTimeUs == 400_000L }
        assertSame(scheduled, RenderedVisualSampler.withDecodedFrameProvenance(scheduled, plan,
            graph.frameAttachments, null, null))
        val resolved = RenderedVisualSampler.withDecodedFrameProvenance(scheduled, plan,
            graph.frameAttachments, 100_000, null)
        assertEquals(7, resolved.sourceIndex)
        assertEquals(1, resolved.clipIndex)
        assertEquals(100_000L, resolved.sourceTimeUs)
        assertEquals(scheduled.transform, resolved.transform)
        assertEquals(scheduled.transitionIn, resolved.transitionIn)
    }

    @Test fun sameSourceHeldBetweenDifferentClipsStillUsesPreviousFrameTransform() {
        val initial = boundaryGraph()
        val graph = initial.copy(clips = initial.clips.map { it.copy(sourceIndex = 19) })
        val plan = HighQualityFramePlan.build(graph).frames
        val incoming = plan.single { it.outputTimeUs == 400_000L }
        val outgoing = plan.last { it.clipIndex == 0 }
        val provenance = VeykadRenderInspector.DecodedFrameProvenance(19, 0,
            outgoing.sourceTimeUs, 333_333, MontageGraph.Transition.HARD_CUT)
        val resolved = RenderedVisualSampler.withDecodedFrameProvenance(incoming, plan,
            graph.frameAttachments, null, provenance)
        assertEquals(0, resolved.clipIndex)
        assertEquals(outgoing.transform, resolved.transform)
        assertNotEquals(incoming.transform, resolved.transform)
    }

    @Test fun regularProvenanceKeepsScheduledStateAndInconsistentEvidenceFailsClosed() {
        val graph = boundaryGraph()
        val plan = HighQualityFramePlan.build(graph).frames
        val incoming = plan.first { it.outputTimeUs > 400_000L }
        val provenance = VeykadRenderInspector.DecodedFrameProvenance(7, 1,
            incoming.sourceTimeUs, 33_333, incoming.transitionIn)
        val actual = RenderedVisualSampler.withDecodedFrameProvenance(incoming, plan,
            graph.frameAttachments, 0, provenance)
        assertEquals(incoming.copy(sourceTimeUs = 33_333), actual)
        assertThrows(IllegalArgumentException::class.java) {
            RenderedVisualSampler.withDecodedFrameProvenance(incoming, plan,
                graph.frameAttachments, null, provenance.copy(sourceIndex = 19))
        }
    }

    // Dropping sources in the combined helper would refresh the legacy matte instead of
    // the held source's decoded witness, despite preserving its decoder provenance.
    @Test fun heldSourceProvenanceRefreshesItsOwnDecodedAttachmentWithManualRefreshEnabled() {
        val graph = boundaryGraph()
        val stale = attachmentWitness(100_000, .1f)
        val heldWitness = attachmentWitness(250_000, .7f)
        val incomingWitness = attachmentWitness(250_000, .9f)
        val legacyWitness = attachmentWitness(250_000, .2f)
        val plan = HighQualityFramePlan.build(graph).frames.map { frame ->
            if (frame.clipIndex == 1) frame.copy(attachments = stale,
                layer = LayerCompositorModel.Sample(kind = MontageGraph.OverlayKind.SUBJECT_STAGE, opacity = 1f))
            else frame
        }
        val incoming = plan.single { it.outputTimeUs == 800_000L }
        val outgoing = plan.last { it.clipIndex == 1 }
        val provenance = VeykadRenderInspector.DecodedFrameProvenance(7, 1,
            outgoing.sourceTimeUs, 250_000, MontageGraph.Transition.HARD_CUT)
        // Order differs from source IDs; choosing the incoming or first timeline is wrong.
        val sources = listOf(SourceAttachments(0, FrameAttachmentTimeline(listOf(incomingWitness))),
            SourceAttachments(7, FrameAttachmentTimeline(listOf(heldWitness))))
        val actual = RenderedVisualSampler.withDecodedFrameProvenance(incoming, plan,
            FrameAttachmentTimeline(listOf(legacyWitness)), 125_000, provenance,
            sources, refreshAllTransitions = true)
        assertEquals(7, actual.sourceIndex)
        assertEquals(1, actual.clipIndex)
        assertEquals(250_000L, actual.sourceTimeUs)
        assertEquals(800_000L, actual.outputTimeUs)
        assertEquals(outgoing.globalOutputTimeUs, actual.globalOutputTimeUs)
        assertEquals(outgoing.transform, actual.transform)
        assertNotEquals(incoming.transform, actual.transform)
        assertEquals(outgoing.effects, actual.effects)
        assertEquals(outgoing.layer, actual.layer)
        assertEquals(MontageGraph.Transition.HARD_CUT, actual.transitionIn)
        assertEquals(-1f, actual.transitionProgress!!, 0f)
        assertSame(heldWitness, actual.attachments)
        assertEquals(250_000L, actual.attachments!!.sourceTimeUs)
        assertEquals(.7f, actual.attachments.mask!!.confidence, 0f)
    }

    // The absent-provenance branch must forward sources too; it cannot silently fall
    // back to the primary timeline when clip/source identity remains the planned one.
    @Test fun missingProvenanceRefreshesPlannedSourceAttachmentAtTheActualDecodedTime() {
        val graph = boundaryGraph()
        val plan = HighQualityFramePlan.build(graph).frames
        val stale = attachmentWitness(0, .1f)
        val decodedWitness = attachmentWitness(125_000, .8f)
        val otherSourceWitness = attachmentWitness(125_000, .3f)
        val scheduled = plan.single { it.outputTimeUs == 400_000L }.copy(
            transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY, attachments = stale)
        val sources = listOf(SourceAttachments(19, FrameAttachmentTimeline(listOf(otherSourceWitness))),
            SourceAttachments(7, FrameAttachmentTimeline(listOf(decodedWitness))))
        val actual = RenderedVisualSampler.withDecodedFrameProvenance(scheduled, plan,
            FrameAttachmentTimeline(listOf(otherSourceWitness)), 125_000, null,
            sources, refreshAllTransitions = true)
        assertEquals(7, actual.sourceIndex)
        assertEquals(1, actual.clipIndex)
        assertEquals(125_000L, actual.sourceTimeUs)
        assertEquals(scheduled.outputTimeUs, actual.outputTimeUs)
        assertEquals(scheduled.globalOutputTimeUs, actual.globalOutputTimeUs)
        assertEquals(scheduled.transform, actual.transform)
        assertEquals(scheduled.effects, actual.effects)
        assertEquals(scheduled.layer, actual.layer)
        assertEquals(scheduled.transitionIn, actual.transitionIn)
        assertEquals(scheduled.transitionProgress, actual.transitionProgress)
        assertSame(decodedWitness, actual.attachments)
        assertEquals(125_000L, actual.attachments!!.sourceTimeUs)
        assertEquals(.8f, actual.attachments.mask!!.confidence, 0f)
    }

    // Nonempty sources themselves enable foreground refresh. An empty-source legacy
    // case is necessary to catch loss of refreshAllTransitions independently of sources.
    @Test fun manualRefreshUpdatesLaterClipWithAndWithoutProvenanceWhenSourceTimelinesAreAbsent() {
        val graph = boundaryGraph()
        val plan = HighQualityFramePlan.build(graph).frames
        val stale = attachmentWitness(0, .1f)
        val decodedWitness = attachmentWitness(125_000, .6f)
        val scheduled = plan.single { it.outputTimeUs == 400_000L }.copy(
            transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY, attachments = stale)
        val timeline = FrameAttachmentTimeline(listOf(decodedWitness))
        val provenance = VeykadRenderInspector.DecodedFrameProvenance(7, 1,
            scheduled.sourceTimeUs, 125_000, scheduled.transitionIn)
        val legacy = RenderedVisualSampler.withDecodedFrameProvenance(scheduled, plan,
            timeline, 125_000, null)
        for (witness in listOf(null, provenance)) {
            val manual = RenderedVisualSampler.withDecodedFrameProvenance(scheduled, plan,
                timeline, 125_000, witness, emptyList(), refreshAllTransitions = true)
            assertEquals(7, manual.sourceIndex)
            assertEquals(1, manual.clipIndex)
            assertEquals(125_000L, manual.sourceTimeUs)
            assertEquals(scheduled.outputTimeUs, manual.outputTimeUs)
            assertEquals(scheduled.globalOutputTimeUs, manual.globalOutputTimeUs)
            assertEquals(scheduled.transform, manual.transform)
            assertEquals(scheduled.transitionIn, manual.transitionIn)
            assertSame(decodedWitness, manual.attachments)
            assertEquals(125_000L, manual.attachments!!.sourceTimeUs)
            assertEquals(.6f, manual.attachments.mask!!.confidence, 0f)
            // Default compatibility must remain unchanged for a later legacy foreground clip.
            assertSame(stale, legacy.attachments)
            assertEquals(manual.copy(attachments = stale), legacy)
        }
    }

    private fun attachmentWitness(timeUs: Long, confidence: Float) = FrameAttachments(timeUs,
        mask = FrameAttachments.Plane(1, 1, listOf(1f), confidence))

    private fun boundaryGraph() = MontageGraph(2000, 1200, clips = listOf(19, 7, 0).mapIndexed { index, source ->
        MontageGraph.Clip("boundary-$index", 0, 400, 400, MontageGraph.ShotRole.ACTION,
            if (index == 0) MontageGraph.Transition.HARD_CUT else MontageGraph.Transition.WHIP,
            MontageGraph.Motion.PUSH_IN, 1f, index * 400L, sourceIndex = source,
            transform = MontageGraph.ClipTransform(listOf(
                MontageGraph.ClipTransform.Keyframe(0f, 1f + index * .1f),
                MontageGraph.ClipTransform.Keyframe(1f, 1.2f + index * .1f))),
            transitionDurationMs = if (index == 0) null else 100)
    })

    private fun observation(face: VisualEventMap.Face?) = VisualEventMap.Observation(
        sourceTimeUs = 0, face = face, composition = VisualEventMap.Composition(.5f, .5f, .5f, .5f, .5f))

    private fun request(files: List<File>, index: Int = 0) = MediaCodecSpeedRampRenderer.Request(
        sourceFiles = files, graph = graph(index), outputFile = File("out.mp4"),
        width = 720, height = 1280, bitrate = 5_000_000)

    private fun graph(index: Int) = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip(
        id = "clip", sourceStartMs = 0, sourceEndMs = 1000, outputDurationMs = 1000,
        role = MontageGraph.ShotRole.OPENING, transitionIn = MontageGraph.Transition.HARD_CUT,
        motion = MontageGraph.Motion.HOLD, confidence = 1f, beatAnchorMs = 0,
        sourceIndex = index)))
}
