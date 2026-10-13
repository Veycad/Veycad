package com.veycad.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridPreviewPreparationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun duplicateAndUnusedSelectionsKeepTheirSlotsAndCanonicalFrames() {
        for (fps in listOf(30, 60)) {
            val fixture = fixture(fps)
            val prepared = HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store)
            assertEquals(listOf("first", "repeat", "unused"), prepared.sources.map { it.selection.id.value })
            assertEquals(listOf(0, 1, 2), prepared.sources.map { it.sourceIndex })
            assertEquals(prepared.sources[0].file, prepared.sources[1].file)
            assertNotEquals(prepared.sources[0].file, prepared.sources[2].file)
            assertEquals(SourceGeometry(1920, 1080, 90, 1f), prepared.sources[0].metadata.geometry)
            assertEquals(fixture.project.current.id, prepared.revisionId)
            assertEquals(fixture.export, prepared.exportSettings)
            assertEquals(HighQualityFramePlan.build(EditableMontageCompiler.compile(
                EditableMontageProject(fixture.project, fixture.export)), fps), prepared.framePlan)
            assertEquals(4_000_000L, prepared.framePlan.frames.first { it.sourceIndex == 1 }.sourceTimeUs)
            assertEquals(.1f, prepared.framePlan.frames.first { it.sourceIndex == 1 }.redBias, 0f)
            rejectedMutation { (prepared.sources as MutableList<PreparedVideoSource>).clear() }
            rejectedMutation { (prepared.framePlan.frames as MutableList<HighQualityFramePlan.Frame>).clear() }
        }
    }

    @Test fun emptyAnalysisAndTrackInputsAreDetachedIncludingManualPayload() {
        val frames = arrayListOf<FrameAttachments>()
        val refinements = arrayListOf<FrameAttachments>()
        val values = arrayListOf(1.25f)
        val keys = arrayListOf(ParameterTrack.Keyframe(0, values, ParameterTrack.Interpolation.HOLD))
        val tracks = arrayListOf(ParameterTrack("A1-scale", ParameterTargets.clip("A1", "transform.scale"),
            ParameterTrack.ValueType.SCALAR, keys))
        val fixture = fixture(graphChange = { graph -> graph.copy(
            frameAttachments = FrameAttachmentTimeline(frames, refinements),
            sourceAttachments = arrayListOf(SourceAttachments(0, FrameAttachmentTimeline(frames, refinements))),
            parameterTracks = tracks,
            manualMontageState = graph.manualMontageState!!.let { state -> state.copy(clips = state.clips.map {
                if (it.clipId == "A1") it.copy(localTracks = tracks) else it
            }) }) })
        val prepared = HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store)
        val before = prepared.framePlan
        values[0] = 2f
        keys.clear(); tracks.clear()
        frames.add(attachment()); refinements.add(attachment())
        assertTrue(prepared.graph.frameAttachments.frames.isEmpty())
        assertTrue(prepared.graph.sourceAttachments.single().timeline.maskRefinements.isEmpty())
        assertEquals(1.25f, prepared.graph.parameterTracks.single().keyframes.single().value.single(), 0f)
        assertEquals(1.25f, prepared.graph.manualMontageState!!.clips.first().localTracks.single().keyframes.single().value.single(), 0f)
        assertEquals(before, HighQualityFramePlan.build(prepared.graph, prepared.exportSettings.fps))
        rejectedMutation { (prepared.graph.sourceAttachments.single().timeline.frames as MutableList<FrameAttachments>).add(attachment()) }
    }

    @Test fun installedManualReorderAndTrimKeepSelectionAndAuthoredSourceClock() {
        for (fps in listOf(30, 60)) {
            val fixture = fixture(fps)
            val baseline = fixture.project.current
            var cursor = 0
            val clips = listOf(1, 0, 2).map { index ->
                val clip = baseline.clips[index]
                val trim = if (index == 0) 1 else 0
                val length = clip.span.length - trim
                clip.copy(span = FrameSpan(cursor, cursor + length).also { cursor = it.endExclusive },
                    originalFrameOffset = trim,
                    sourceMap = SourceTimeMap(clip.sourceMap.points.drop(trim).mapIndexed { frame, point ->
                        SourceTimeMap.Point(frame, point.sourceTimeUs)
                    }))
            }
            val revision = baseline.copy(id = 2, parentId = baseline.id, clips = clips)
            val project = fixture.project.copy(current = revision, nextRevisionId = 3)
            val prepared = HybridPreviewPreparation.prepare(project, fixture.export, fixture.store)
            assertEquals(2L, prepared.revisionId)
            assertEquals(listOf(1, 0, 0), prepared.graph.clips.map { it.sourceIndex })
            assertEquals(listOf("first", "repeat", "unused"), prepared.sources.map { it.selection.id.value })
            assertEquals(4_000_000L, prepared.framePlan.frames.first().sourceTimeUs)
            assertEquals(1_000_000L + kotlin.math.round(1_000_000.0 / fps).toLong(),
                prepared.framePlan.frames.first { it.clipIndex == 1 }.sourceTimeUs)
            assertEquals(HighQualityFramePlan.build(EditableMontageCompiler.compile(
                EditableMontageProject(project, fixture.export)), fps), prepared.framePlan)
            assertSame(baseline, project.original)
            assertTrue(project.undo.isEmpty() && project.redo.isEmpty())
        }
    }

    @Test fun analysisFramesAndRefinementsAreRejectedWithoutDroppingThem() {
        for (source in listOf(false, true)) for (refined in listOf(false, true)) {
            val timeline = if (refined) FrameAttachmentTimeline(maskRefinements = listOf(attachment()))
                else FrameAttachmentTimeline(listOf(attachment()))
            val fixture = fixture(graphChange = { if (source) it.copy(sourceAttachments = listOf(SourceAttachments(1, timeline)))
                else it.copy(frameAttachments = timeline) })
            rejected("analysis-free") { HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store) }
        }
    }

    // Catches shallow attachment copies, scalar/PTS loss and planning from producer payloads.
    @Test fun analyzedGlobalAndSourceTimelinesRemainFrozenOnBothCanonicalGrids() {
        for (fps in listOf(30, 60)) {
            val pixels = floatArrayOf(.2f, .8f)
            val mask = FrameAttachments.Plane(2, 1, pixels, .9f)
            val target = FrameAttachments.Plane(2, 1, floatArrayOf(.6f, .4f), .7f)
            val depth = FrameAttachments.Plane(2, 1, floatArrayOf(2f, 3f), .8f)
            val vectors = arrayListOf(.1f, .2f, .3f, .4f)
            val flow = FrameAttachments.FlowPlane(2, 1, vectors, .6f)
            val face = FrameAttachments.FaceRegion(.4f, .3f, .2f, .1f, .8f)
            val first = FrameAttachments(1_000_000, mask, depth, flow, .2f, .1f, .4f, face,
                target, .25f, true)
            val last = first.copy(sourceTimeUs = 1_200_000, mask = target,
                subjectQuality = .8f, subjectOcclusion = .3f, maskTemporalIou = .6f)
            val frames = arrayListOf(first, last)
            val refinements = arrayListOf(first.copy(maskBlendTarget = null, maskBlendProgress = 0f), last)
            val secondFrames = arrayListOf(first.copy(sourceTimeUs = 4_000_000),
                last.copy(sourceTimeUs = 4_200_000))
            val sourceTimelines = arrayListOf(SourceAttachments(0, FrameAttachmentTimeline(frames, refinements)),
                SourceAttachments(1, FrameAttachmentTimeline(secondFrames)))
            val fixture = fixture(fps) { it.copy(frameAttachments = FrameAttachmentTimeline(frames, refinements),
                sourceAttachments = sourceTimelines) }
            val budget = PreviewMemoryBudget()
            val prepared = HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget)
            val plan = prepared.framePlan
            val frozen = prepared.graph.frameAttachments.frames.first()
            assertEquals(first, frozen)
            assertSame(frozen.mask, prepared.graph.sourceAttachments.first().timeline.frames.first().mask)
            assertSame(frozen.mask, plan.frames.first().attachments!!.mask)
            pixels.fill(0f); depth.values.fill(0f); target.values.fill(0f); vectors.clear()
            frames.clear(); refinements.clear(); secondFrames.clear(); sourceTimelines.clear()
            assertEquals(listOf(1_000_000L, 1_200_000L), prepared.graph.frameAttachments.frames.map { it.sourceTimeUs })
            assertEquals(2, prepared.graph.frameAttachments.maskRefinements.size)
            assertEquals(.2f, frozen.mask!![0], 0f)
            assertEquals(2f, frozen.depth!![0], 0f)
            assertEquals(listOf(.1f, .2f, .3f, .4f), frozen.flow!!.vectors)
            assertEquals(face, frozen.faceRegion)
            assertEquals(.25f, frozen.maskBlendProgress, 0f)
            assertTrue(frozen.maskIsOpacity)
            val interpolated = prepared.graph.sourceAttachments.first().timeline.interpolated(1_100_000)!!
            assertEquals(.5f, interpolated.subjectQuality, .00001f)
            assertEquals(.2f, interpolated.subjectOcclusion, .00001f)
            assertEquals(.5f, interpolated.maskTemporalIou, .00001f)
            assertEquals(.5f, interpolated.maskBlendProgress, 0f)
            assertEquals(.6f, interpolated.maskBlendTarget!![0], 0f)
            assertSame(frozen.mask, interpolated.mask)
            assertEquals(1_100_000L, plan.frames.first { it.sourceTimeUs == 1_100_000L }.attachments!!.sourceTimeUs)
            val second = plan.frames.first { it.sourceIndex == 1 }
            assertEquals(4_000_000L, second.sourceTimeUs)
            assertSame(frozen.mask, second.attachments!!.mask)
            frozen.mask.values[0] = 1f // Compatibility getter is a detached, caller-owned copy.
            rejectedMutation { (frozen.flow.vectors as MutableList<Float>)[0] = 1f }
            rejectedMutation { (prepared.graph.frameAttachments.frames as MutableList<FrameAttachments>).clear() }
            assertEquals(.2f, frozen.mask[0], 0f)
            assertEquals(plan, HighQualityFramePlan.build(prepared.graph, fps))
            assertEquals(listOf("first", "repeat", "unused"), prepared.sources.map { it.selection.id.value })
            prepared.close()
            assertEquals(0L, budget.usedBytes)
        }
    }

    // Catches duplicate array charges even when different Plane wrappers use the same input array.
    @Test fun sharedArraysAreCopiedOnceAndActiveSnapshotsCannotBeEvicted() {
        fun prepared(budget: PreviewMemoryBudget, shared: Boolean): PreparedHybridPreview {
            val values = FloatArray(4096) { .5f }
            val a = FrameAttachments.Plane(64, 64, values, .8f)
            val b = FrameAttachments.Plane(64, 64, if (shared) values else values.copyOf(), .7f)
            val fixture = fixture { it.copy(frameAttachments = FrameAttachmentTimeline(listOf(
                FrameAttachments(1_000_000, mask = a, depth = b)))) }
            return HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget)
        }
        val sharedBudget = PreviewMemoryBudget()
        val uniqueBudget = PreviewMemoryBudget()
        val shared = prepared(sharedBudget, true)
        val unique = prepared(uniqueBudget, false)
        assertEquals(4096L * 4 + 32, uniqueBudget.usedBytes - sharedBudget.usedBytes)
        assertFalse(sharedBudget.reserve("competing-preview", 32L * 1024 * 1024) {})
        assertEquals(.5f, shared.framePlan.frames.first().attachments!!.mask!![0], 0f)
        shared.close(); unique.close()
        assertEquals(0L, sharedBudget.usedBytes)
        assertEquals(0L, uniqueBudget.usedBytes)
    }

    // Catches value-equal compiler no-op graphs whose original payload identity count is larger.
    @Test fun valueEqualOriginalAndCurrentSharingTopologiesCannotUnderchargeTheFrozenGraph() {
        val sharedMask = FrameAttachments.Plane(270, 480, FloatArray(270 * 480) { .5f }, .8f)
        val sharedFlow = FrameAttachments.FlowPlane(1, 1, arrayListOf(.1f, .2f), .7f)
        val currentFrames = List(8) { FrameAttachments(1_000_000L + it * 1000,
            mask = sharedMask, flow = sharedFlow) }
        val fixture = fixture { it.copy(frameAttachments = FrameAttachmentTimeline(currentFrames)) }
        val originalFrames = currentFrames.map { it.copy(
            mask = FrameAttachments.Plane(270, 480, sharedMask.values.copyOf(), .8f),
            flow = FrameAttachments.FlowPlane(1, 1, arrayListOf(.1f, .2f), .7f)) }
        val original = fixture.project.original.copy(graph = fixture.project.original.graph.copy(
            frameAttachments = FrameAttachmentTimeline(originalFrames)))
        val project = fixture.project.copy(original = original)
        assertEquals(project.original.graph, project.current.graph) // Values match; identities do not.

        val measurement = PreviewMemoryBudget()
        val currentOnly = HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, measurement)
        val affordableCurrentBytes = measurement.usedBytes
        currentOnly.close()
        val limited = PreviewMemoryBudget(affordableCurrentBytes + 1024)
        var foreignDisposed = false
        assertTrue(limited.reserve("active-texture", 1024) { foreignDisposed = true })
        limited.pin("active-texture")
        rejected("budget") { HybridPreviewPreparation.prepare(project, fixture.export, fixture.store, limited) }
        assertEquals(1024L, limited.usedBytes)
        assertFalse(foreignDisposed)
        assertSame(originalFrames[0].mask, project.original.graph.frameAttachments.frames[0].mask)
        assertSame(sharedMask, project.current.graph.frameAttachments.frames[0].mask)
        assertEquals(.5f, originalFrames[0].mask!![0], 0f)
        limited.release("active-texture")

        val generous = PreviewMemoryBudget(16L * 1024 * 1024)
        val prepared = HybridPreviewPreparation.prepare(project, fixture.export, fixture.store, generous)
        val distinctFlowCharge = generous.usedBytes
        assertTrue(distinctFlowCharge >= 8L * (270 * 480 * 4 + 32))
        val frozen = prepared.graph.frameAttachments.frames
        assertNotSame(frozen[0].mask, frozen[1].mask)
        assertNotSame(frozen[0].flow!!.vectors, frozen[1].flow!!.vectors)
        assertEquals(1_007_000L, frozen.last().sourceTimeUs)
        assertEquals(.7f, frozen.last().flow!!.confidence, 0f)
        prepared.close()
        assertEquals(0L, generous.usedBytes)

        // Holding every plane identity/metadata fixed isolates union vector-container accounting.
        val sharedVectorsOriginal = original.copy(graph = original.graph.copy(frameAttachments =
            FrameAttachmentTimeline(originalFrames.map { it.copy(flow = sharedFlow) })))
        val vectorShared = HybridPreviewPreparation.prepare(project.copy(original = sharedVectorsOriginal),
            fixture.export, fixture.store, generous)
        assertEquals(8L * (96 + 2 * 24), distinctFlowCharge - generous.usedBytes)
        vectorShared.close()
        assertEquals(0L, generous.usedBytes)
    }

    // Catches uncharging live borrowed planes, failed-prepare leaks and loss of thread ownership.
    @Test fun sharedBudgetFailureAndCloseReleaseStorageAtomically() {
        val fixture = fixture { it.copy(frameAttachments = FrameAttachmentTimeline(listOf(attachment()))) }
        val tiny = PreviewMemoryBudget(1)
        rejected("budget") { HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, tiny) }
        assertEquals(0L, tiny.usedBytes)
        val budget = PreviewMemoryBudget()
        var foreignDisposed = false
        assertTrue(budget.reserve("existing-texture", 512) { foreignDisposed = true })
        budget.pin("existing-texture")
        val unused = fixture.store.resolve(fixture.project.assets.first())
        val original = unused.readBytes()
        unused.writeBytes(original.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
        rejected("SHA-256") { HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget) }
        assertEquals(512L, budget.usedBytes)
        assertFalse(foreignDisposed)
        unused.writeBytes(original)
        val prepared = HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget)
        val graph = prepared.graph
        val frames = prepared.framePlan.frames
        val borrowed = graph.frameAttachments.frames.single().mask!!
        var foreignFailure: Throwable? = null
        Thread { try { prepared.close() } catch (error: Throwable) { foreignFailure = error } }.apply { start(); join() }
        assertTrue(foreignFailure is IllegalStateException)
        assertEquals(.5f, borrowed[0], 0f)
        budget.clear() // Shared-owner disposal must also invalidate borrowed data.
        assertEquals(0L, budget.usedBytes)
        closed { borrowed[0] }; closed { borrowed.values }; closed { graph.frameAttachments.frames.size }
        closed { frames.size }; closed { prepared.graph }; closed { prepared.framePlan }
        prepared.close(); prepared.close()
        assertEquals(0L, budget.usedBytes)
        assertArrayEquals(original, unused.readBytes())
        assertEquals(1, fixture.project.current.graph.frameAttachments.frames.size)
    }

    // Catches leaks when validation fails after a payload copy, rather than at file validation.
    @Test fun failedFrozenPlaneValidationReleasesOnlyItsOwnedReservation() {
        val values = floatArrayOf(.5f)
        val fixture = fixture { it.copy(frameAttachments = FrameAttachmentTimeline(listOf(
            FrameAttachments(0, mask = FrameAttachments.Plane(1, 1, values, 1f))))) }
        values[0] = Float.NaN // Producer mutation after construction must not install invalid frozen data.
        val budget = PreviewMemoryBudget()
        var disposed = false
        assertTrue(budget.reserve("foreign-owner", 1024) { disposed = true })
        budget.pin("foreign-owner")
        try {
            HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget)
            fail("Expected invalid plane rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1024L, budget.usedBytes)
        assertFalse(disposed)
        assertTrue(fixture.project.current.graph.frameAttachments.frames.single().mask!![0].isNaN())
        values[0] = .5f
        val prepared = HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget)
        val mask = prepared.graph.frameAttachments.frames.single().mask!!
        val plan = prepared.framePlan.frames
        prepared.close(); prepared.close()
        closed { mask.size }; closed { plan.size }; closed { prepared.sources }
        assertEquals(1024L, budget.usedBytes)
        assertFalse(disposed)
        budget.release("foreign-owner")
        assertTrue(disposed)
        assertEquals(0L, budget.usedBytes)
    }

    // Catches planning from the unfrozen global fallback and retaining mutable initially-empty lists.
    @Test fun budgetedGlobalFallbackSnapshotsEmptySourceAndRefinementContainers() {
        val values = floatArrayOf(.3f)
        val emptyFrames = arrayListOf<FrameAttachments>()
        val emptyRefinements = arrayListOf<FrameAttachments>()
        val fixture = fixture { it.copy(frameAttachments = FrameAttachmentTimeline(listOf(
            FrameAttachments(1_000_000, mask = FrameAttachments.Plane(1, 1, values, .8f))), emptyRefinements)) }
        val budget = PreviewMemoryBudget()
        val prepared = HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget)
        values[0] = .9f
        emptyRefinements.add(attachment())
        assertEquals(.3f, prepared.framePlan.frames.first().attachments!!.mask!![0], 0f)
        assertTrue(prepared.graph.frameAttachments.maskRefinements.isEmpty())
        prepared.close()
        val withEmpty = fixture.project.current.graph.copy(sourceAttachments = listOf(SourceAttachments(0,
            FrameAttachmentTimeline(emptyFrames))))
        val revised = fixture.project.current.copy(graph = withEmpty)
        val project = fixture.project.copy(original = revised, current = revised)
        val second = HybridPreviewPreparation.prepare(project, fixture.export, fixture.store, budget)
        emptyFrames.add(attachment())
        assertTrue(second.graph.sourceAttachments.single().timeline.frames.isEmpty())
        second.close()
        assertEquals(0L, budget.usedBytes)
    }

    // Catches unbounded quadratic GL-worker preflight despite a tiny shared payload.
    @Test fun oversizedAttachmentHistoryIsRejectedBeforeTouchingTheSharedBudget() {
        val plane = FrameAttachments.Plane(1, 1, floatArrayOf(.5f), .8f)
        val frames = List(1025) { FrameAttachments(it.toLong(), mask = plane) }
        val fixture = fixture { it.copy(frameAttachments = FrameAttachmentTimeline(frames)) }
        val budget = PreviewMemoryBudget()
        var foreignDisposed = false
        assertTrue(budget.reserve("active-preview", 1024) { foreignDisposed = true })
        budget.pin("active-preview")
        rejected("too large") { HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store, budget) }
        assertEquals(1024L, budget.usedBytes)
        assertFalse(foreignDisposed)
        assertEquals(1025, fixture.project.current.graph.frameAttachments.frames.size)
        assertEquals(.5f, plane[0], 0f)
        val smallCurrent = fixture.project.current.copy(id = fixture.project.nextRevisionId,
            parentId = fixture.project.original.id, graph = fixture.project.current.graph.copy(
                frameAttachments = FrameAttachmentTimeline(listOf(frames.first()))))
        val originalOnlyOversized = fixture.project.copy(current = smallCurrent, nextRevisionId = smallCurrent.id + 1)
        rejected("too large") { HybridPreviewPreparation.prepare(originalOnlyOversized,
            fixture.export, fixture.store, budget) }
        assertEquals(1024L, budget.usedBytes)
        assertFalse(foreignDisposed)
        budget.release("active-preview")
    }

    private fun closed(action: () -> Any?) {
        try { action(); fail("Expected closed prepared lifetime") } catch (_: IllegalStateException) { }
    }

    @Test fun sameSizeCorruptionIsRejectedEvenForAnUnusedSelection() {
        val fixture = fixture()
        val unused = fixture.project.assets.single { it.id == fixture.project.selectedVideos!!.last().assetId }
        val file = fixture.store.resolve(unused)
        val bytes = file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        rejected("SHA-256") { HybridPreviewPreparation.prepare(fixture.project, fixture.export, fixture.store) }
    }

    @Test fun legacyUnknownOrderAndGeometryAreRejected() {
        val legacy = ManualMontageFixtures.linearProject()
        // Legacy constructors allow uninspected assets; preparation must not guess their geometry/order.
        rejected("selection") { HybridPreviewPreparation.prepare(legacy.shared, legacy.export,
            ProjectAssetStore(temporary.newFolder())) }
        val fixture = fixture()
        val missing = fixture.project.assets.map { if (it.kind == ProjectAsset.Kind.VIDEO) it.copy(videoMetadata = null) else it }
        rejected("metadata") { HybridProject("unknown-metadata", 1, fixture.project.fps, 2, missing,
            fixture.project.original, fixture.project.current, emptyList(), emptyList(), emptyList(), fixture.project.selectedVideos) }
    }

    private data class Fixture(val project: HybridProject, val export: ProjectExportSettings, val store: ProjectAssetStore)

    private fun fixture(fps: Int = 30, graphChange: (MontageGraph) -> MontageGraph = { it }): Fixture {
        val base = ManualMontageFixtures.linearProject(fps)
        val store = ProjectAssetStore(temporary.newFolder())
        fun imported(name: String, contents: String): ProjectAsset {
            val file = temporary.newFile(name).apply { writeText(contents) }
            return store.import(file, ProjectAsset.Kind.VIDEO, 10_000_000).copy(videoMetadata = VideoSourceMetadata(
                SourceGeometry(1920, 1080, 90, 1f), file.length(), "video/avc", null, false, 0, 10_000_000))
        }
        val video = imported("source-${fps}-${temporary.root.list()!!.size}.bin", "owned video fixture")
        val unused = imported("unused-${fps}-${temporary.root.list()!!.size}.bin", "other owned fixture")
        val selections = listOf(SelectedVideo(SourceId("first"), video.id, SourceOwnership.IMPORTED, displayName = "first"),
            SelectedVideo(SourceId("repeat"), video.id, SourceOwnership.IMPORTED, displayName = "repeat"),
            SelectedVideo(SourceId("unused"), unused.id, SourceOwnership.IMPORTED, displayName = "unused"))
        val revision = base.shared.current.copy(graph = graphChange(base.shared.current.graph),
            clips = base.shared.current.clips.map { it.copy(assetId = video.id) })
        return Fixture(base.shared.copy(assets = listOf(video, unused, base.music), original = revision,
            current = revision, selectedVideos = selections), base.export, store)
    }

    private fun attachment() = FrameAttachments(0, mask = FrameAttachments.Plane(1, 1, floatArrayOf(.5f), 1f))
    private fun rejected(message: String, action: () -> Unit) {
        try { action(); fail("Expected rejection: $message") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message)) }
    }
    private fun rejectedMutation(action: () -> Unit) {
        try { action(); fail("Expected owned unmodifiable list") } catch (_: UnsupportedOperationException) { }
    }
}
