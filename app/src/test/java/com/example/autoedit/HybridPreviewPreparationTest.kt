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
