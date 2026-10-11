package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridRestoreMontageTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun reset(project: HybridProject) = HybridEditCommands.restoreMontage(project)

    @Test fun resetKeepsCurrentAuthorPayloadWhileRestoringOriginalMontageInOneRevision() {
        val base = project()
        val edited = authored(HybridEditCommands.moveCut(base, "right", 28))
        val restored = HybridEditCommands.apply(edited, ProjectCommand.RestoreMontage)
        assertEquals(edited.current.music, restored.current.music)
        assertEquals(edited.current.texts, restored.current.texts)
        assertEquals(edited.current.style, restored.current.style)
        assertEquals(edited.current.visualSettings, restored.current.visualSettings)
        assertEquals(edited.current.textState, restored.current.textState)
        assertEquals(setOf("right"), restored.current.lockedCutIds)
        assertEquals(base.original.clips, restored.current.clips)
        assertEquals(base.original.graph, restored.current.graph)
        assertEquals(30, restored.current.clips[1].span.start)
        assertEquals(edited.nextRevisionId, restored.current.id)
        assertEquals(edited.current.id, restored.current.parentId)
        assertEquals(edited.nextRevisionId + 1, restored.nextRevisionId)
        assertEquals(edited.undo + edited.current, restored.undo)
        assertTrue(restored.redo.isEmpty())
        assertTrue(restored.current.restoresAutomaticSources)
        assertSame(base.original, restored.original)
        assertEquals(edited.assets, restored.assets)
        assertEquals(edited.selectedVideos, restored.selectedVideos)
        assertEquals(edited.exports, restored.exports)
    }

    @Test fun fullResetStillRestoresEveryOriginalField() {
        val edited = authored(HybridEditCommands.moveCut(project(), "right", 28))
        val restored = HybridEditCommands.apply(edited, ProjectCommand.RestoreAutomatic)
        assertEquals(edited.original.copy(id = edited.nextRevisionId, parentId = edited.current.id,
            restoresAutomaticSources = true), restored.current)
    }

    @Test fun resetEventHistoryAndRepeatKeepExactPayloadAndMonotoneIds() {
        val edited = authored(HybridEditCommands.moveCut(project(), "right", 28))
        val restored = reset(edited)
        val undone = HybridEditCommands.undo(restored)
        assertEquals(edited.current, undone.current)
        assertEquals(restored.nextRevisionId, undone.nextRevisionId)
        val redone = HybridEditCommands.redo(undone)
        assertEquals(restored, redone)
        assertSame(redone, reset(redone))
        assertSame(redone, HybridEditCommands.commitRevision(redone, redone.current))
        val ordinary = HybridEditCommands.commitRevision(redone, redone.current.copy(
            music = redone.current.music.copy(gain = 1.5f), restoresAutomaticSources = true))
        assertFalse(ordinary.current.restoresAutomaticSources)
        assertEquals(redone.current, HybridEditCommands.undo(ordinary).current)
        val branch = reset(HybridEditCommands.undo(edited))
        assertEquals(edited.nextRevisionId, branch.current.id)
        assertTrue(branch.redo.isEmpty())
    }

    @Test fun authorOnlyChangesAfterResetRemainReferentialNoOpWithRedo() {
        val restored = reset(HybridEditCommands.moveCut(project(), "right", 28))
        val edited = authored(restored)
        val extra = HybridEditCommands.setAuthoredText(edited, true)
        val current = HybridEditCommands.undo(extra)
        val originalGraph = HybridEditCommands.commitRevision(current, current.current.copy(graph = current.original.graph))
        val withRedo = HybridEditCommands.undo(HybridEditCommands.setAuthoredText(originalGraph, true))
        assertFalse(withRedo.current.restoresAutomaticSources)
        assertFalse(withRedo.redo.isEmpty())
        assertSame(withRedo, reset(withRedo))
        for (useStore in listOf(false, true)) {
            val reopened = reopen(withRedo, useStore)
            assertSame(reopened, reset(reopened))
        }
        // Original provenance is equally provable before the first explicit reset.
        val base = authored(project())
        val visibleOriginal = HybridEditCommands.commitRevision(base, base.current.copy(graph = base.original.graph))
        assertSame(visibleOriginal, reset(visibleOriginal))
    }

    @Test fun rewrittenNonlinearSourceResetSurvivesCodecStoreTrimExtendAndPhase() {
        for (useStore in listOf(false, true)) {
            val base = project()
            val selected = rewrite(base)
            var reset = authored(reset(selected))
            reset = HybridEditCommands.moveCut(reset, "right", 42)
            val reopened = reopen(reset, useStore)
            val extended = HybridEditCommands.moveCut(reopened, "right", 28)
            val right = extended.current.clips[1]
            assertEquals(-2, right.originalFrameOffset)
            assertEquals(32, right.span.length)
            for (frame in 0..30) assertEquals("store=$useStore frame=$frame",
                base.original.clips[1].sourceMap.sample(frame), right.sourceMap.sample(frame + 2))
            assertEquals(480_000L, right.sourceMap.sample(0))
            assertEquals(490_000L, right.sourceMap.sample(1))
            assertEquals(reopened.current.textState, extended.current.textState)
            assertEquals(extended, reopen(extended, useStore))
            assertEquals(extended.current, HybridEditCommands.redo(HybridEditCommands.undo(extended)).current)
        }
    }

    @Test fun visibleBaselineWithDifferentHiddenCurveResetsOnceAndUndoRecoversSelection() {
        val base = editProject()
        val right = base.current.clips[1].copy(span = FrameSpan(30, 80), sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(30, 1_500_000), SourceTimeMap.Point(50, 2_600_000))))
        val last = base.current.clips[2].copy(span = FrameSpan(80, 90), originalFrameOffset = 20,
            sourceMap = SourceTimeMap((20..30).map { SourceTimeMap.Point(it - 20, base.current.clips[2].sourceMap.sample(it)) }))
        val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = listOf(base.current.clips[0], right, last)))
        val trimmed = HybridEditCommands.moveCut(selected, "last", 60)
        val visible = HybridEditCommands.commitRevision(trimmed, trimmed.current.copy(clips = trimmed.original.clips))
        assertEquals(base.original.clips, visible.current.clips)
        val restored = reset(visible)
        assertEquals(visible.nextRevisionId, restored.current.id)
        assertSame(restored, reset(restored))
        for (useStore in listOf(false, true)) {
            val loaded = reopen(restored, useStore)
            assertSame(loaded, reset(loaded))
            assertEquals(2_166_667L, HybridEditCommands.moveCut(loaded, "last", 80).current.clips[1].sourceMap.sample(50))
            assertEquals(2_600_000L, HybridEditCommands.moveCut(HybridEditCommands.undo(loaded), "last", 80)
                .current.clips[1].sourceMap.sample(50))
        }
    }

    @Test fun missingHistoryProofDoesNotUseRedoOrMatchingOverlap() {
        val restored = reset(HybridEditCommands.moveCut(project(), "right", 28))
        val edited = authored(restored)
        val graphOriginal = HybridEditCommands.commitRevision(edited, edited.current.copy(graph = edited.original.graph))
        val gap = graphOriginal.copy(undo = emptyList())
        val reset = reset(gap)
        assertEquals(gap.nextRevisionId, reset.current.id)
        assertSame(reset, reset(reset))
        val selected = rewrite(project())
        val returned = HybridEditCommands.commitRevision(selected, selected.current.copy(clips = selected.original.clips))
        val newer = reset(returned)
        val undone = HybridEditCommands.undo(newer)
        assertFalse(undone.redo.isEmpty())
        assertEquals(undone.nextRevisionId, reset(undone).current.id)
    }

    @Test fun invalidIncomingLocksRejectWithoutChangingProjectOrStore() {
        val base = project()
        val extra = base.current.clips.last().copy(id = "added", span = FrameSpan(90, 120))
        val added = HybridEditCommands.commitRevision(base, base.current.copy(clips = base.current.clips + extra,
            lockedCutIds = setOf("added")))
        assertRejectedUnchanged(added, "added")
        val reordered = base.current.clips.reversed().mapIndexed { index, clip ->
            clip.copy(span = FrameSpan(index * 30, (index + 1) * 30))
        }
        val firstLocked = HybridEditCommands.commitRevision(base, base.current.copy(clips = reordered, lockedCutIds = setOf("left")))
        assertRejectedUnchanged(firstLocked, "left")
    }

    @Test fun retainedNormalizedLayerOrCueBeyondOriginalDurationRejectAtomically() {
        for (kind in listOf("normalized", "layer", "cue")) for (fps in listOf(30, 60)) {
            val base = editProject(fps)
            val longer = base.current.clips.last().copy(span = FrameSpan(60, 120), sourceMap = SourceTimeMap(listOf(
                SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(60, 1_500_000))))
            val endUs = ProjectClock(fps).timeUs(90) + 1
            val candidate = base.current.copy(clips = base.current.clips.dropLast(1) + longer,
                texts = if (kind == "normalized") listOf(text(91)) else emptyList(),
                textState = HybridTextState(
                    layers = if (kind == "layer") listOf(TextLayer("layer", "Late", 0, endUs)) else emptyList(),
                    captions = if (kind == "cue") listOf(CaptionCue("cue", "Late", 0, endUs)) else emptyList()))
            val edited = HybridEditCommands.commitRevision(base, candidate)
            val withRedo = HybridEditCommands.undo(HybridEditCommands.setAuthoredText(edited, false))
            assertFalse(withRedo.redo.isEmpty())
            assertRejectedUnchanged(withRedo, when (kind) { "normalized" -> "text"; else -> kind })
        }
    }

    @Test fun staleStoreSaveOfPreparedResetFailsWithoutPublishing() {
        val base = project()
        val store = store(base)
        val edited = HybridEditCommands.moveCut(base, "right", 28)
        store.save(edited, 0)
        val reset = reset(edited)
        val competing = HybridEditCommands.setAuthoredText(edited, false)
        store.save(competing, edited.current.id)
        val before = bytes(store)
        assertThrows(IllegalStateException::class.java) { store.save(reset, edited.current.id) }
        assertEquals(before, bytes(store))
        assertEquals(competing, store.load(base.id))
    }

    @Test fun resetUsesOrdinaryFiftyRevisionHistoryRetention() {
        var edited = HybridEditCommands.moveCut(project(), "right", 28)
        repeat(60) { edited = HybridEditCommands.setAuthoredText(edited, !edited.current.style.showAuthoredText) }
        val reset = reset(edited)
        assertEquals((edited.undo + edited.current).takeLast(50), reset.undo)
        assertEquals(edited.nextRevisionId, reset.current.id)
        assertSame(reset, reset(reset))
    }

    @Test(timeout = 3000) fun sparseOriginalProofNeverEnumeratesOutputFrames() {
        val base = editProject()
        val clip = base.current.clips.first().copy(span = FrameSpan(0, Int.MAX_VALUE),
            sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(Int.MAX_VALUE, 1_500_000))))
        val original = base.current.copy(clips = listOf(clip))
        val project = base.copy(original = original, current = original)
        val edited = HybridEditCommands.commitRevision(project, project.current.copy(
            clips = listOf(clip.copy(sourceMap = SourceTimeMap(clip.sourceMap.points))),
            style = project.current.style.copy(showAuthoredText = false)))
        assertSame(edited, reset(edited))
        val directOriginalParent = edited.copy(undo = emptyList())
        assertSame(directOriginalParent, reset(directOriginalParent))
    }

    @Test(timeout = 5000) fun largeRetainedMapProofRejectsBeforeUnboundedComparison() {
        val base = editProject(length = 25_000)
        val map = SourceTimeMap((0..25_000).map { SourceTimeMap.Point(it, 500_000L + it * 30L) })
        val original = base.current.copy(clips = base.current.clips.map { it.copy(sourceMap = map) })
        var edited = base.copy(original = original, current = original)
        repeat(15) {
            edited = HybridEditCommands.commitRevision(edited, edited.current.copy(
                clips = edited.current.clips.map { it.copy(sourceMap = SourceTimeMap(it.sourceMap.points)) },
                style = edited.current.style.copy(showAuthoredText = !edited.current.style.showAuthoredText)))
        }
        val before = edited
        val error = assertThrows(HybridEditRejected::class.java) { reset(edited) }
        assertTrue(error.reason, error.reason.contains("сравнений"))
        assertSame(before, edited)
    }

    private fun rewrite(base: HybridProject) = HybridEditCommands.commitRevision(base, base.current.copy(
        clips = base.current.clips.map { clip -> if (clip.id != "right") clip else clip.copy(
            sourceMap = SourceTimeMap(clip.sourceMap.points.mapIndexed { index, point ->
                if (index == 0) point.copy(sourceTimeUs = 300_000) else point })) }))

    private fun store(project: HybridProject): HybridProjectStore {
        val store = HybridProjectStore(temporary.newFolder())
        val sources = File(store.directory(project.id), "sources").apply { mkdirs() }
        project.assets.forEach { File(sources, it.fileName).writeText("fixture source") }
        store.create(project)
        return store
    }

    private fun reopen(project: HybridProject, useStore: Boolean): HybridProject = if (useStore) store(project).load(project.id)
        else HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder())).let { it.decode(it.encode(project)) }

    private fun bytes(store: HybridProjectStore): Map<String, List<Byte>> = store.directory("project").walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(store.directory("project")).path to it.readBytes().toList() }

    private fun assertRejectedUnchanged(project: HybridProject, identifier: String) {
        val store = store(project)
        val beforeBytes = bytes(store)
        val before = store.load(project.id)
        val error = assertThrows(HybridEditRejected::class.java) { reset(project) }
        assertTrue(error.reason, error.reason.contains(identifier))
        assertEquals(before, store.load(project.id))
        assertEquals(beforeBytes, bytes(store))
        assertEquals(before.current, project.current)
        assertEquals(before.nextRevisionId, project.nextRevisionId)
        assertEquals(before.undo, project.undo)
        assertEquals(before.redo, project.redo)
    }

    private fun project(): HybridProject {
        val base = editProject()
        val map = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(10, 600_000),
            SourceTimeMap.Point(20, 1_200_000), SourceTimeMap.Point(30, 1_500_000)))
        val clips = base.current.clips.map { it.copy(sourceMap = map) }
        val revision = base.current.copy(clips = clips)
        return base.copy(original = revision, current = revision,
            assets = base.assets.map { if (it.kind == ProjectAsset.Kind.VIDEO) it.copy(contentHash = "a".repeat(64),
                videoMetadata = VideoSourceMetadata(SourceGeometry(1920, 1080, 90, 1.2f), 1234, "video/avc", 6, true)) else it } +
                ProjectAsset("other-music", "other.wav", ProjectAsset.Kind.AUDIO, 5_000_000, "other-hash"),
            selectedVideos = listOf(SelectedVideo(SourceId("selection"), "video", SourceOwnership.IMPORTED, displayName = "Video")))
    }

    private fun authored(base: HybridProject): HybridProject {
        val settings = SourceFramingSettings(FramingMode.SMART_PERSON,
            FramingSettings(FramingMode.MANUAL, .2f, .3f, 2f),
            FramingSettings(FramingMode.SMART_PERSON, .4f, .6f, 1.5f),
            FramingSettings(FramingMode.BLURRED_FIT, .1f, .9f, 3f))
        return HybridEditCommands.commitRevision(base, base.current.copy(
            graph = base.current.graph.copy(audioTrack = MontageGraph.AudioTrack("other-music", 2f)),
            music = ProjectMusic("other-music", 123_456, 2f, 333_333, 555_555, true),
            texts = listOf(text(90)), style = ProjectStyle("other-style", 3, ProjectStyle.Mode.ADAPTIVE, false),
            visualSettings = ProjectVisualSettings(ProjectAspect.FEED_4_5, true,
                ProjectAspect.entries.associate { FramingKey(SourceId("selection"), it) to settings }),
            textState = HybridTextState(
                listOf(TextLayer("layer", "Надпись", 1_000_000, 3_000_000,
                    TextStyle(TextPosition.CENTER, TextFont.SERIF, .12f, 0xffaabbcc.toInt(), false, TextAnimation.SLIDE))),
                listOf(CaptionCue("cue", "Hello, мир", 2_100_000, 3_000_000)),
                TextStyle(TextPosition.BOTTOM, TextFont.SANS, .025f, 0xffaaffbb.toInt(), true, TextAnimation.FADE), true, "ru")))
    }

    private fun text(end: Int) = TextItem("text", "Title", FrameSpan(1, end),
        .2f, .7f, .8f, .1f, 0xffccddaa.toInt(), TextItem.Appearance.BACKGROUND, 2)
}
