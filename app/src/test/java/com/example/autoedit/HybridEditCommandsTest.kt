package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal fun editProject(fps: Int = 30, length: Int = 30): HybridProject {
    fun clip(id: String, start: Int): HybridClip {
        val original = MontageGraph.Clip(id, 500, 1500, 1000, MontageGraph.ShotRole.ACTION,
            MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.PUSH_IN, 1f, 0,
            transform = MontageGraph.ClipTransform(listOf(
                MontageGraph.ClipTransform.Keyframe(0f, 1f),
                MontageGraph.ClipTransform.Keyframe(1f, 2f))))
        return HybridClip(id, "video", FrameSpan(start, start + length), SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(length, 1_500_000))), original)
    }
    val clips = listOf(clip("left", 0), clip("right", length), clip("last", length * 2))
    val revision = HybridRevision(0, null, MontageGraph(3000, 3000, clips = clips.map { it.original }),
        clips, ProjectMusic("music", 0, 1f, 0, 0, false), emptyList(),
        ProjectStyle("style", 1, ProjectStyle.Mode.AUTHORED, true), emptySet())
    return HybridProject("project", 1, fps, 1, listOf(
        ProjectAsset("video", "video.mp4", ProjectAsset.Kind.VIDEO, 3_000_000, "video-hash"),
        ProjectAsset("music", "music.wav", ProjectAsset.Kind.AUDIO, 3_000_000, "music-hash")),
        revision, revision, emptyList(), emptyList(), emptyList())
}

internal fun HybridProject.withInitialClips(clips: List<HybridClip>): HybridProject {
    val revision = current.copy(clips = clips)
    return copy(original = revision, current = revision)
}

class HybridEditCommandsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun title(text: String = "Title") = TextItem("title", text, FrameSpan(0, 20),
        .5f, .5f, .8f, .1f, -1, TextItem.Appearance.PLAIN, 2)

    @Test fun rollingCutMovesTwoFramesWithoutChangingDuration() {
        val base = editProject()
        val edited = HybridEditCommands.apply(base, ProjectCommand.MoveCut("right", 32))
        assertEquals(listOf(FrameSpan(0, 32), FrameSpan(32, 60), FrameSpan(60, 90)), edited.current.clips.map { it.span })
        assertEquals(90, edited.current.clips.last().span.endExclusive)
        assertEquals(setOf("right"), edited.current.lockedCutIds)
        assertSame(base.current.clips.last(), edited.current.clips.last())
        assertSame(base.original, edited.original)
        assertEquals(1L, edited.current.id)
        assertEquals(0L, edited.current.parentId)
        assertEquals(2L, edited.nextRevisionId)
        assertEquals(listOf(base.current), edited.undo)
    }

    @Test fun speedRampRetainedFramesKeepTheirPts() {
        val base = editProject(fps = 60, length = 60)
        val sparse = SourceTimeMap(listOf(SourceTimeMap.Point(0, 0), SourceTimeMap.Point(3, 2),
            SourceTimeMap.Point(20, 180_001), SourceTimeMap.Point(60, 1_200_007)))
        val project = base.withInitialClips(base.current.clips.map { it.copy(sourceMap = sparse) })
        val edited = HybridEditCommands.moveCut(project, "right", 61)
        assertEquals(1L, edited.current.clips[1].sourceMap.sample(1)) // Original frame 2, not rounded a second time.
        for (frame in 0 until 60) assertEquals("left frame $frame", sparse.sample(frame), edited.current.clips[0].sourceMap.sample(frame))
        for (frame in 1 until 60) assertEquals("right frame $frame", sparse.sample(frame), edited.current.clips[1].sourceMap.sample(frame - 1))
        val extended = HybridEditCommands.moveCut(editProject(60, 60), "right", 58)
        val original = editProject(60, 60).current.clips[1].sourceMap
        for (frame in 0 until 60) assertEquals(original.sample(frame), extended.current.clips[1].sourceMap.sample(frame + 2))
    }

    @Test fun slipPreservesOutputFrames() {
        val base = editProject()
        val edited = HybridEditCommands.apply(base, ProjectCommand.SlipClip("right", 123_456))
        assertEquals(base.current.clips.map { it.span }, edited.current.clips.map { it.span })
        for (frame in 0..30) assertEquals(base.current.clips[1].sourceMap.sample(frame) + 123_456,
            edited.current.clips[1].sourceMap.sample(frame))
        assertEquals(setOf("right"), edited.current.lockedCutIds)
        assertSame(base.current.clips[0], edited.current.clips[0])
        assertSame(base.current.clips[1].original, edited.current.clips[1].original)
    }

    private fun nonlinearProject(): HybridProject {
        val base = editProject()
        val map = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(10, 600_000),
            SourceTimeMap.Point(20, 1_200_000), SourceTimeMap.Point(30, 1_500_000)))
        return base.withInitialClips(base.current.clips.map { it.copy(sourceMap = map) })
    }

    @Test fun trimThenRestoreRecoversEveryNonlinearSampleAtBothEnds() {
        val original = nonlinearProject()
        for (boundary in listOf(15, 42)) {
            val trimmed = HybridEditCommands.moveCut(original, "right", boundary)
            val restored = HybridEditCommands.moveCut(trimmed, "right", 30)
            for (clip in 0..1) for (frame in 0..30) assertEquals("boundary $boundary clip $clip frame $frame",
                original.current.clips[clip].sourceMap.sample(frame), restored.current.clips[clip].sourceMap.sample(frame))
            assertEquals(0, restored.current.clips[1].originalFrameOffset)
        }
    }

    @Test fun nonlinearRestorationPreservesSlipBeforeAndAfterTrimAcrossReopen() {
        val original = nonlinearProject()
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        for ((before, after) in listOf(123_456L to 0L, 0L to 123_456L, 100_000L to 23_456L)) {
            var edited = HybridEditCommands.slipClip(original, "right", before)
            edited = HybridEditCommands.moveCut(edited, "right", 42)
            edited = HybridEditCommands.slipClip(edited, "right", after)
            edited = codec.decode(codec.encode(edited))
            val restored = HybridEditCommands.moveCut(edited, "right", 30)
            for (frame in 0..30) assertEquals(original.current.clips[1].sourceMap.sample(frame) + 123_456,
                restored.current.clips[1].sourceMap.sample(frame))
            assertEquals(0, restored.current.clips[1].originalFrameOffset)
        }
    }

    @Test fun sameIdExternalCurveSurvivesTrimSlipAndCodecOrStoreReopen() {
        for (useStore in listOf(false, true)) for (slip in listOf(0L, 123_456L)) {
            val base = nonlinearProject()
            val store = HybridProjectStore(temporary.newFolder())
            val sources = File(store.directory(base.id), "sources").apply { mkdirs() }
            base.assets.forEach { File(sources, it.fileName).writeText("fixture source") }
            store.create(base)
            val right = base.current.clips[1]
            val selectedMap = SourceTimeMap(right.sourceMap.points.mapIndexed { index, point ->
                if (index == 0) point.copy(sourceTimeUs = 300_000) else point
            })
            val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = listOf(
                base.current.clips[0], right.copy(sourceMap = selectedMap), base.current.clips[2])))
            store.save(selected, base.current.id)
            val trimmed = HybridEditCommands.moveCut(selected, "right", 42)
            store.save(trimmed, selected.current.id)
            val slipped = HybridEditCommands.slipClip(trimmed, "right", slip)
            if (slipped !== trimmed) store.save(slipped, trimmed.current.id)
            val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
            val reopened = if (useStore) store.load(base.id) else codec.decode(codec.encode(slipped))
            val restored = HybridEditCommands.moveCut(reopened, "right", 30)
            for (frame in 0..30) assertEquals("store=$useStore slip=$slip frame=$frame",
                selectedMap.sample(frame) + slip, restored.current.clips[1].sourceMap.sample(frame))
            store.save(restored, reopened.current.id)
            assertEquals(restored, store.load(base.id))
        }
    }

    @Test fun provenanceComparisonBudgetIsSharedAcrossBothClipsAndEntireHistory() {
        val length = 10_000
        var project = editProject(length = length)
        repeat(50) { index ->
            val knot = 100 + index
            val map = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500_000),
                SourceTimeMap.Point(knot, 500_000L + knot * 100L), SourceTimeMap.Point(length, 1_500_000)))
            project = HybridEditCommands.commitRevision(project, project.current.copy(clips = project.current.clips.map {
                if (it.id == "left" || it.id == "right") it.copy(sourceMap = map) else it
            }))
        }
        assertEquals(50, project.undo.size)
        rejected { HybridCutConstraints.range(project, "right") }
    }

    @Test fun provenanceComparisonBudgetAlsoIncludesTheFinalExtension() {
        val length = 7_000
        var project = editProject(length = length)
        repeat(50) { index ->
            val knot = 7 * (100 + index)
            val map = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500_000),
                SourceTimeMap.Point(knot, 500_000L + knot * 1_000L / 7), SourceTimeMap.Point(length, 1_500_000)))
            project = HybridEditCommands.commitRevision(project, project.current.copy(clips = project.current.clips.map {
                if (it.id == "left" || it.id == "right") it.copy(sourceMap = map) else it
            }))
        }
        assertTrue(length + 1 in HybridCutConstraints.range(project, "right"))
        val error = assertThrows(HybridEditRejected::class.java) {
            HybridEditCommands.moveCut(project, "right", length + 1)
        }
        assertTrue(error.reason.contains("сравнений"))
    }

    @Test(timeout = 5_000) fun hugeSparseEquivalentMapRejectsBeforeEnumeratingItsFrameExtent() {
        val length = 715_827_882
        val base = editProject(length = length)
        val right = base.current.clips[1].copy(sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(length / 2, 1_000_000),
            SourceTimeMap.Point(length, 1_500_000))))
        val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = listOf(
            base.current.clips[0], right, base.current.clips[2])))
        val error = assertThrows(HybridEditRejected::class.java) { HybridCutConstraints.range(selected, "right") }
        assertTrue(error.reason.contains("сравнений"))
    }

    @Test fun sameIdHiddenCurveCannotCrossPrunedAncestryOrBorrowRedo() {
        val base = nonlinearProject()
        val right = base.current.clips[1]
        val selectedMap = SourceTimeMap(right.sourceMap.points.mapIndexed { index, point ->
            if (index == 0) point.copy(sourceTimeUs = 300_000) else point
        })
        val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = listOf(
            base.current.clips[0], right.copy(sourceMap = selectedMap), base.current.clips[2])))
        val trimmed = HybridEditCommands.moveCut(selected, "right", 42)
        val branch = trimmed.copy(undo = listOf(base.original),
            redo = listOf(selected.current.copy(id = 3, parentId = 2)), nextRevisionId = 4)
        rejected { HybridEditCommands.moveCut(branch, "right", 30) }
        var pruned = trimmed
        repeat(51) { pruned = HybridEditCommands.putText(pruned, title("$it")) }
        rejected { HybridEditCommands.moveCut(pruned, "right", 30) }
    }

    @Test fun savedHeldSamplesCanBeRestoredWithoutInventingNewFrozenFrames() {
        val base = editProject()
        val right = base.current.clips[1].copy(sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(5, 500_000), SourceTimeMap.Point(30, 1_500_000))))
        val original = base.withInitialClips(listOf(base.current.clips[0], right, base.current.clips[2]))
        val trimmed = HybridEditCommands.moveCut(original, "right", 32)
        assertTrue(30 in HybridCutConstraints.range(trimmed, "right"))
        val restored = HybridEditCommands.moveCut(trimmed, "right", 30)
        for (frame in 0..30) assertEquals(right.sourceMap.sample(frame), restored.current.clips[1].sourceMap.sample(frame))
    }

    @Test fun externalClipRestoresFromUndoButRejectsWhenItsBackingWasPruned() {
        val base = nonlinearProject()
        val external = base.current.clips[1].copy(id = "external", original = base.current.clips[1].original.copy(id = "external"))
        val inserted = HybridEditCommands.commitRevision(base, base.current.copy(clips = listOf(
            base.current.clips[0], external, base.current.clips[2])))
        val trimmed = HybridEditCommands.moveCut(inserted, "external", 42)
        val restored = HybridEditCommands.moveCut(trimmed, "external", 30)
        for (frame in 0..30) assertEquals(external.sourceMap.sample(frame), restored.current.clips[1].sourceMap.sample(frame))
        var pruned = trimmed
        repeat(51) { pruned = HybridEditCommands.putText(pruned, title("$it")) }
        rejected { HybridEditCommands.moveCut(pruned, "external", 30) }
    }

    @Test fun redoCurveCannotSupplyBackingForANewEditorialBranch() {
        val base = nonlinearProject()
        val external = base.current.clips[1].copy(id = "external", original = base.current.clips[1].original.copy(id = "external"))
        val inserted = HybridEditCommands.commitRevision(base, base.current.copy(clips = listOf(
            base.current.clips[0], external, base.current.clips[2])))
        val trimmed = HybridEditCommands.moveCut(inserted, "external", 42)
        val future = inserted.current.copy(id = 3, parentId = 2)
        val branched = trimmed.copy(undo = listOf(base.original), redo = listOf(future), nextRevisionId = 4)
        rejected { HybridEditCommands.moveCut(branched, "external", 30) }
        assertEquals(listOf(future), branched.redo)
    }

    @Test fun oneVisibleFrameDoesNotEstablishHiddenCurveProvenance() {
        val trimmed = HybridEditCommands.moveCut(nonlinearProject(), "right", 59)
        assertEquals(1, trimmed.current.clips[1].span.length)
        rejected { HybridEditCommands.moveCut(trimmed, "right", 30) }
    }

    @Test fun longIntegralSparseMapsRemainPersistableAfterTrimming() {
        val project = editProject(length = 20_000)
        val edited = HybridEditCommands.moveCut(project, "right", 20_002)
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val reopened = codec.decode(codec.encode(edited))
        for (frame in 2 until 20_000) assertEquals(500_000L + frame * 50L,
            reopened.current.clips[1].sourceMap.sample(frame - 2))
        assertEquals(60_000, reopened.current.clips.last().span.endExclusive)
    }

    @Test fun longFractionalSlicePersistsExactSamplesBeyondGenericCollectionBudget() {
        val base = editProject(fps = 60, length = 120_000)
        val source = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(120_000, 1_500_007)))
        val project = base.withInitialClips(base.current.clips.map { it.copy(sourceMap = source) })
        val edited = HybridEditCommands.moveCut(project, "right", 120_001)
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val reopened = codec.decode(codec.encode(edited))
        for (frame in 1 until 120_000) assertEquals("frame $frame", source.sample(frame),
            reopened.current.clips[1].sourceMap.sample(frame - 1))
    }

    @Test fun enormousFractionalSliceRejectsBeforeUnboundedExpansion() {
        val base = editProject(length = Int.MAX_VALUE / 3)
        rejected { HybridEditCommands.moveCut(base, "right", Int.MAX_VALUE / 3 + 1) }
        assertSame(base.original, base.current)
        assertTrue(base.undo.isEmpty())
    }

    @Test fun slicingBothFractionalEndsPreservesEveryRetainedSample() {
        val base = editProject(60, 60)
        val sparse = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(7, 500_004),
            SourceTimeMap.Point(25, 500_017), SourceTimeMap.Point(60, 500_033)))
        val project = base.withInitialClips(base.current.clips.map { it.copy(sourceMap = sparse) })
        val startTrimmed = HybridEditCommands.moveCut(project, "right", 61)
        val bothTrimmed = HybridEditCommands.moveCut(startTrimmed, "last", 118)
        for (frame in 1 until 58) assertEquals("frame $frame", sparse.sample(frame),
            bothTrimmed.current.clips[1].sourceMap.sample(frame - 1))
        assertEquals(1, bothTrimmed.current.clips[1].originalFrameOffset)
    }

    @Test fun retainedCameraPhaseSurvivesCutAndSlip() {
        val base = editProject()
        val held = base.withInitialClips(base.current.clips.map { if (it.id == "right") it.copy(sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(30, 500_000)))) else it })
        val trimmed = HybridEditCommands.moveCut(held, "right", 32)
        assertEquals(2, trimmed.current.clips[1].originalFrameOffset)
        assertEquals(0, trimmed.current.clips[0].originalFrameOffset)
        for (frame in 0 until 28) {
            val clip = trimmed.current.clips[1]
            assertEquals(500_000L, clip.sourceMap.sample(frame))
            assertEquals(held.current.clips[1].original.transform.sample((frame + 2) / 30f),
                clip.original.transform.sample((frame + clip.originalFrameOffset) / 30f))
        }
        val slipped = HybridEditCommands.slipClip(trimmed, "right", 10)
        assertEquals(2, slipped.current.clips[1].originalFrameOffset)
        val extended = HybridEditCommands.moveCut(base, "right", 28)
        assertEquals(-2, extended.current.clips[1].originalFrameOffset)
    }

    @Test fun slipRejectsBothSourceEndsOverflowAndUnknownClip() {
        val base = editProject()
        for (offset in listOf(-500_001L, 1_500_001L, Long.MAX_VALUE, Long.MIN_VALUE))
            rejected { HybridEditCommands.slipClip(base, "right", offset) }
        rejected { HybridEditCommands.slipClip(base, "unknown", 1) }
        assertEquals(0L, base.current.id)
        assertTrue(base.undo.isEmpty())
        assertEquals(0L, HybridEditCommands.slipClip(base, "right", -500_000).current.clips[1].sourceMap.sample(0))
        assertEquals(3_000_000L, HybridEditCommands.slipClip(base, "right", 1_500_000).current.clips[1].sourceMap.sample(30))
    }

    @Test fun musicImportIsAtomicAndDoesNotRebindPublishedAssets() {
        val base = editProject()
        val asset = ProjectAsset("new", "new.wav", ProjectAsset.Kind.AUDIO, 1_000_000, "new-hash")
        val music = ProjectMusic("new", 123, .7f, 100, 200, true)
        val edited = HybridEditCommands.apply(base, ProjectCommand.ReplaceMusic(music, asset))
        assertEquals(music, edited.current.music)
        assertEquals(base.assets + asset, edited.assets)
        assertEquals(base.current.clips, edited.current.clips)
        assertEquals(base.assets + asset, HybridEditCommands.undo(edited).assets)
        rejected { HybridEditCommands.replaceMusic(base, music) }
        rejected { HybridEditCommands.replaceMusic(base, music, asset.copy(kind = ProjectAsset.Kind.VIDEO)) }
        rejected { HybridEditCommands.replaceMusic(base, base.current.music, base.assets.last().copy(contentHash = "rebound")) }
        rejected { HybridEditCommands.replaceMusic(base, music.copy(startUs = 1_000_000), asset) }
        rejected { HybridEditCommands.replaceMusic(base, music, asset.copy(id = "other")) }
        assertEquals(2, base.assets.size)
    }

    @Test fun undoAfterReopenRestoresMusicTextAndCut() {
        val base = editProject()
        val cut = HybridEditCommands.moveCut(base, "right", 32)
        val music = HybridEditCommands.replaceMusic(cut, cut.current.music.copy(gain = .4f, startUs = 123))
        val text = HybridEditCommands.putText(music, title())
        val hidden = HybridEditCommands.setAuthoredText(text, false)
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        var reopened = codec.decode(codec.encode(hidden))
        for (expected in listOf(text.current, music.current, cut.current, base.current)) {
            reopened = HybridEditCommands.apply(reopened, ProjectCommand.Undo)
            assertEquals(expected, reopened.current)
        }
        for (expected in listOf(cut.current, music.current, text.current, hidden.current)) {
            reopened = HybridEditCommands.apply(reopened, ProjectCommand.Redo)
            assertEquals(expected, reopened.current)
        }
        assertEquals(5L, reopened.nextRevisionId)
        assertSame(reopened, HybridEditCommands.redo(reopened))
    }

    @Test fun textUpsertRemovalAndAuthoredVisibilityAreReversible() {
        val base = editProject()
        val added = HybridEditCommands.apply(base, ProjectCommand.PutText(title()))
        val replaced = HybridEditCommands.putText(added, title("Changed"))
        assertEquals(listOf(title("Changed")), replaced.current.texts)
        val removed = HybridEditCommands.apply(replaced, ProjectCommand.RemoveText("title"))
        assertTrue(removed.current.texts.isEmpty())
        assertEquals(replaced.current, HybridEditCommands.undo(removed).current)
        val hidden = HybridEditCommands.apply(replaced, ProjectCommand.SetAuthoredText(false))
        assertFalse(hidden.current.style.showAuthoredText)
        assertEquals(replaced.current.texts, hidden.current.texts)
        rejected { HybridEditCommands.putText(base, title().copy(span = FrameSpan(0, 91))) }
        rejected { HybridEditCommands.removeText(base, "missing") }
    }

    @Test fun oneGestureIsOneCommandAndHistoryCapsAt50() {
        val base = editProject()
        var edited = HybridEditCommands.moveCut(base, "right", 40)
        assertEquals(1, edited.undo.size)
        for (i in 1..55) edited = HybridEditCommands.putText(edited, title("$i"))
        assertEquals(50, edited.undo.size)
        assertEquals(56L, edited.current.id)
        repeat(50) { edited = HybridEditCommands.undo(edited) }
        assertEquals(6L, edited.current.id)
        assertEquals(50, edited.redo.size)
        assertSame(edited, HybridEditCommands.undo(edited))
        edited = HybridEditCommands.putText(edited, title("New branch"))
        assertEquals(57L, edited.current.id)
        assertEquals(6L, edited.current.parentId)
        assertEquals(58L, edited.nextRevisionId)
        assertTrue(edited.redo.isEmpty())
    }

    @Test fun restoreAutomaticIsUndoableAndPreservesSourcesAndExports() {
        val base = editProject()
        val edited = HybridEditCommands.putText(base, title()).let { it.copy(exports = listOf(
            ProjectExportRef(it.current.id, "export.mp4", ProjectExportSettings(1080, 1920, 30, 1, 1)))) }
        val restored = HybridEditCommands.apply(edited, ProjectCommand.RestoreAutomatic)
        assertEquals(base.original.copy(id = 2, parentId = 1), restored.current)
        assertEquals(edited.exports, restored.exports)
        assertEquals(edited.assets, restored.assets)
        assertSame(base.original, restored.original)
        assertEquals(edited.current, HybridEditCommands.undo(restored).current)
    }

    @Test fun externalCommitUsesSharedCounterHistoryAndRejectsStaleOrInvalidPayload() {
        val base = editProject()
        val edited = HybridEditCommands.putText(base, title())
        val undone = HybridEditCommands.undo(edited)
        val candidate = undone.current.copy(texts = listOf(title("External")))
        val committed = HybridEditCommands.apply(undone, ProjectCommand.CommitRevision(candidate))
        assertEquals(2L, committed.current.id)
        assertEquals(0L, committed.current.parentId)
        assertEquals(3L, committed.nextRevisionId)
        assertTrue(committed.redo.isEmpty())
        assertEquals(listOf(undone.current), committed.undo)
        rejected { HybridEditCommands.commitRevision(committed, candidate) }
        rejected { HybridEditCommands.commitRevision(base, base.current.copy(music = base.current.music.copy(assetId = "absent"))) }
        rejected { HybridEditCommands.commitRevision(base, base.current.copy(clips = base.current.clips.map {
            it.copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 3_000_000), SourceTimeMap.Point(30, 3_000_000)))) })) }
        rejected { HybridEditCommands.commitRevision(base.copy(nextRevisionId = Long.MAX_VALUE), candidate) }
    }

    @Test fun noOpCommandsDoNotAllocateHistoryOrInvalidateRedo() {
        val base = HybridEditCommands.undo(HybridEditCommands.putText(editProject(), title()))
        assertSame(base, HybridEditCommands.moveCut(base, "right", 30))
        assertSame(base, HybridEditCommands.slipClip(base, "left", 0))
        assertSame(base, HybridEditCommands.replaceMusic(base, base.current.music))
        assertSame(base, HybridEditCommands.setAuthoredText(base, true))
        assertSame(base, HybridEditCommands.restoreAutomatic(base))
        assertSame(base, HybridEditCommands.commitRevision(base, base.current))
    }

    private fun rejected(action: () -> Unit) {
        val error = assertThrows(HybridEditRejected::class.java, action)
        assertTrue(error.reason.isNotBlank())
    }
}
