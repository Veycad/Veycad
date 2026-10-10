package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualSourceRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fractionalPhaseSlipAndOneFrameTrimsRestoreLiteralNonlinearRepeatedPts() {
        for (fps in listOf(30, 60)) {
            val base = EditableMontageCompilerTest.imported(EditableMontageCompilerTest.fractionalGraph(), fps)
            val clip = base.shared.current.clips.single { it.id == "B" }
            val map = SourceTimeMap((0..clip.span.length).map {
                SourceTimeMap.Point(it, 1_000_000L + (it / 2).toLong() * (it / 2) * 100)
            })
            val original = base.shared.original.copy(clips = base.shared.original.clips.map {
                if (it.id == "B") it.copy(sourceMap = map) else it })
            val selected = base.copy(shared = base.shared.copy(original = original, current = original))
            val slipped = selected.copy(shared = HybridEditCommands.apply(selected.shared, ProjectCommand.SlipClip("B", 234_567)))
            val before = frames(slipped, fps)
            val cut = if (fps == 30) 9L else 18L
            val trimmed = apply(slipped, TimelineCommand.Trim("B", ClipEdge.START, cut))
            assertEquals(1_201_000L, trimmed.current.clips.single { it.id == "B" }.phase!!.durationUs)
            before.drop(cut.toInt()).zip(frames(trimmed, fps)).forEach { (old, retained) ->
                assertEquals(old.sourceTimeUs, retained.sourceTimeUs)
                assertEquals(old.clipProgress, retained.clipProgress, 0f)
                assertEquals(old.transform, retained.transform)
            }
            val oneLeft = apply(trimmed, TimelineCommand.Trim("B", ClipEdge.START, cut + 1))
            val restoredLeft = apply(oneLeft, TimelineCommand.Trim("B", ClipEdge.START, cut))
            assertEquals(trimmed.shared.current.clips.single { it.id == "B" }.sourceMap.points,
                restoredLeft.shared.current.clips.single { it.id == "B" }.sourceMap.points)
            val oneRight = apply(restoredLeft, TimelineCommand.Trim("B", ClipEdge.END, clip.span.length - 1L))
            val restoredRight = apply(oneRight, TimelineCommand.Trim("B", ClipEdge.END, clip.span.length.toLong()))
            val full = apply(restoredRight, TimelineCommand.Trim("B", ClipEdge.START, 0))
            assertEquals(slipped.shared.current.clips.single { it.id == "B" }.sourceMap.points,
                full.shared.current.clips.single { it.id == "B" }.sourceMap.points)
            assertEquals(slipped.current.clips.single { it.id == "B" }.localTracks, full.current.clips.single { it.id == "B" }.localTracks)
            assertEquals(before, frames(full, fps))
        }
    }

    @Test fun visibleOriginalAfterRewriteNeedsResetEventAndUndoSelectsHistoricalHiddenCurve() {
        for (fps in listOf(30, 60)) {
            val base = ManualMontageFixtures.linearProject(fps)
            val count = 2 * fps
            val extra = if (fps == 30) 9 else 18
            val longer = apply(base, TimelineCommand.Trim("A1", ClipEdge.END, (count + extra).toLong()))
            val historical = commit(longer, longer.shared.current.copy(clips = longer.shared.current.clips.map {
                if (it.id != "A1") it else it.copy(sourceMap = SourceTimeMap(it.sourceMap.points.map { point ->
                    if (point.localFrame <= count) point else point.copy(sourceTimeUs = 3_000_000L + (point.localFrame - count) * 10_000L)
                })) }))
            val visible = apply(historical, TimelineCommand.Trim("A1", ClipEdge.END, count.toLong()))
            assertEquals(base.shared.original.clips, visible.shared.current.clips)
            val reset = apply(visible, TimelineCommand.RestoreBaseline)
            assertEquals(visible.shared.nextRevisionId, reset.shared.current.id)
            assertEquals(visible.shared.nextRevisionId + 1, reset.shared.nextRevisionId)
            assertTrue(reset.shared.current.restoresAutomaticSources)
            val undo = reset.copy(shared = HybridEditCommands.apply(reset.shared, ProjectCommand.Undo))
            val editedBacking = apply(undo, TimelineCommand.Trim("A1", ClipEdge.END, (count + extra).toLong()))
            assertEquals(3_000_000L + extra * 10_000L, editedBacking.shared.current.clips.first().sourceMap.sample(count + extra))
            val redo = undo.copy(shared = HybridEditCommands.apply(undo.shared, ProjectCommand.Redo))
            assertSame(reset.shared.current, redo.shared.current)
            val authored = redo.copy(shared = HybridEditCommands.apply(redo.shared, ProjectCommand.SetAuthoredText(true)))
            assertFalse(authored.shared.current.restoresAutomaticSources)
            for (useStore in listOf(false, true)) {
                val loaded = authored.copy(shared = reopen(authored.shared, useStore))
                assertEquals(authored.shared.current.clips, loaded.shared.current.clips)
                val afterTrim = apply(loaded, TimelineCommand.Trim("A1", ClipEdge.START, extra.toLong()))
                val recovered = apply(afterTrim, TimelineCommand.Trim("A1", ClipEdge.START, 0))
                assertEquals(base.shared.original.clips.first().sourceMap.points, recovered.shared.current.clips.first().sourceMap.points)
                val extended = apply(recovered, TimelineCommand.Trim("A1", ClipEdge.END, (count + extra).toLong()))
                assertEquals(if (fps == 30) 3_299_997L else 3_300_006L,
                    extended.shared.current.clips.first().sourceMap.sample(count + extra))
                assertSame(loaded.shared, HybridEditCommands.apply(loaded.shared, ProjectCommand.RestoreMontage))
            }
        }
    }

    @Test fun canonicalRoutingRejectsRetainedLocksAndAllLateTextKindsWithoutPublishing() {
        val base = ManualMontageFixtures.linearProject()
        val moved = apply(base, TimelineCommand.Move("B", 0))
        val locked = commit(moved, moved.shared.current.copy(lockedCutIds = setOf("A1")))
        assertResetRejectedUnchanged(locked, "A1")
        for (kind in listOf("text", "layer", "cue")) {
            val longer = apply(base, TimelineCommand.Trim("A2", ClipEdge.END, 69))
            val end = ProjectClock(30).timeUs(189)
            val authored = commit(longer, longer.shared.current.copy(
                texts = if (kind == "text") listOf(TextItem("text", "late", FrameSpan(180, 189),
                    .5f, .5f, .5f, .1f, -1, TextItem.Appearance.PLAIN, 0)) else emptyList(),
                textState = HybridTextState(
                    layers = if (kind == "layer") listOf(TextLayer("layer", "late", end - 100_000, end)) else emptyList(),
                    captions = if (kind == "cue") listOf(CaptionCue("cue", "late", end - 100_000, end)) else emptyList())))
            assertResetRejectedUnchanged(authored, kind)
        }
    }

    @Test fun rewrittenFullWindowRejectsUnseenEdgeWithoutMutatingFilesOrHistory() {
        val base = ManualMontageFixtures.linearProject()
        val map = SourceTimeMap((0..60).map { SourceTimeMap.Point(it, 1_000_000L + it * it * 100L) })
        val rewritten = commit(base, base.shared.current.copy(clips = base.shared.current.clips.map {
            if (it.id == "A1") it.copy(sourceMap = map) else it }))
        val before = rewritten.shared
        val store = store(before)
        val bytes = files(store, before.id)
        val rejected = MontageTimelineEditor.prepare(rewritten, TimelineCommand.Trim("A1", ClipEdge.END, 69))
        assertTrue(rejected.toString(), rejected is TimelinePreparation.Rejected)
        assertSame(before, rewritten.shared)
        assertEquals(bytes, files(store, before.id))
        assertEquals(before.current.clips, store.load(before.id).current.clips)
        assertEquals(before.nextRevisionId, store.load(before.id).nextRevisionId)
        assertEquals(before.undo.map { it.id }, store.load(before.id).undo.map { it.id })
        assertEquals(before.redo.map { it.id }, store.load(before.id).redo.map { it.id })
    }

    @Test fun knownOriginalBarrierCannotSupplyAConflictingMotionPhase() {
        val base = EditableMontageCompilerTest.imported(ManualMontageFixtures.generatedGraph())
        val candidate = (MontageTimelineEditor.prepare(base, TimelineCommand.Trim("A1", ClipEdge.START, 9)) as TimelinePreparation.Prepared).candidate
        val payload = candidate.graph.manualMontageState!!
        val altered = candidate.copy(graph = candidate.graph.copy(manualMontageState = payload.copy(clips = payload.clips.map {
            if (it.clipId == "A1") it.copy(phase = it.phase!!.copy(durationUs = it.phase.durationUs + 1)) else it
        })))
        val selected = commit(base, altered)
        val rejected = MontageTimelineEditor.prepare(selected, TimelineCommand.Trim("A1", ClipEdge.START, 0))
        assertTrue(rejected.toString(), rejected is TimelinePreparation.Rejected)
        assertTrue((rejected as TimelinePreparation.Rejected).reason, rejected.reason.contains("phase"))
        val repaired = apply(selected, TimelineCommand.RestoreBaseline)
        assertEquals(base.current.clips.first().phase, repaired.current.clips.first().phase)
    }

    @Test(timeout = 3000) fun hugeRequestedExtensionRejectsBeforeDenseWork() {
        val base = ManualMontageFixtures.linearProject()
        val result = MontageTimelineEditor.prepare(base, TimelineCommand.Trim("A1", ClipEdge.END, Int.MAX_VALUE.toLong()))
        assertTrue(result.toString(), result is TimelinePreparation.Rejected)
        assertTrue((result as TimelinePreparation.Rejected).reason, result.reason.contains("point limit"))
        assertSame(base.shared.original, base.shared.current)
    }

    @Test(timeout = 5000) fun selectedHistoryComparisonBudgetRejectsBeforeUnboundedDenseWork() {
        val base = ManualMontageFixtures.linearProject()
        val count = 25_000
        val map = SourceTimeMap((0..count).map { SourceTimeMap.Point(it, 1_000_000L + it * 30L) })
        val original = base.shared.original.copy(clips = base.shared.original.clips.mapIndexed { index, clip ->
            if (index == 0) clip.copy(span = FrameSpan(0, count), sourceMap = map)
            else clip.copy(span = FrameSpan(count + (index - 1) * 60, count + index * 60))
        }, graph = base.shared.original.graph.copy(manualMontageState = null))
        var project = base.shared.copy(original = original, current = original)
        repeat(41) {
            project = HybridEditCommands.apply(project, ProjectCommand.CommitRevision(project.current.copy(
                clips = project.current.clips.map { clip ->
                    if (clip.id == "A1") clip.copy(sourceMap = SourceTimeMap(clip.sourceMap.points)) else clip },
                style = project.current.style.copy(showAuthoredText = !project.current.style.showAuthoredText))))
        }
        val adapter = base.copy(shared = project)
        val result = MontageTimelineEditor.prepare(adapter, TimelineCommand.Trim("A1", ClipEdge.END, count + 1L))
        assertTrue(result.toString(), result is TimelinePreparation.Rejected)
        assertTrue((result as TimelinePreparation.Rejected).reason, result.reason.contains("\u0441\u0440\u0430\u0432\u043d\u0435\u043d\u0438\u0439"))
        assertSame(project, adapter.shared)
    }

    private fun assertResetRejectedUnchanged(project: EditableMontageProject, id: String) {
        val store = store(project.shared)
        val bytes = files(store, project.id)
        val command = (MontageTimelineEditor.prepare(project, TimelineCommand.RestoreBaseline) as TimelinePreparation.CoreRestoreMontage).command
        val error = assertThrows(HybridEditRejected::class.java) { HybridEditCommands.apply(project.shared, command) }
        assertTrue(error.reason, error.reason.contains(id))
        assertEquals(bytes, files(store, project.id))
        assertEquals(project.shared.current.clips, store.load(project.id).current.clips)
        assertEquals(project.shared.nextRevisionId, store.load(project.id).nextRevisionId)
        assertEquals(project.shared.undo.map { it.id }, store.load(project.id).undo.map { it.id })
    }
    private fun frames(project: EditableMontageProject, fps: Int) =
        HighQualityFramePlan.build(EditableMontageCompiler.compile(project), fps).frames.filter { it.clipIndex == 1 }
    private fun apply(project: EditableMontageProject, command: TimelineCommand): EditableMontageProject {
        val result = MontageTimelineEditor.prepare(project, command)
        val core = when (result) {
            is TimelinePreparation.Prepared -> ProjectCommand.CommitRevision(result.candidate)
            is TimelinePreparation.CoreRestoreMontage -> result.command
            else -> throw AssertionError(result)
        }
        return project.copy(shared = HybridEditCommands.apply(project.shared, core))
    }
    private fun commit(project: EditableMontageProject, candidate: HybridRevision) =
        project.copy(shared = HybridEditCommands.apply(project.shared, ProjectCommand.CommitRevision(candidate)))
    private fun store(project: HybridProject): HybridProjectStore {
        val store = HybridProjectStore(temporary.newFolder())
        val sources = File(store.directory(project.id), "sources").apply { mkdirs() }
        project.assets.forEach { File(sources, it.fileName).writeText("fixture source") }
        store.create(project)
        return store
    }
    private fun reopen(project: HybridProject, useStore: Boolean) = if (useStore) store(project).load(project.id)
        else HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder())).let { it.decode(it.encode(project)) }
    private fun files(store: HybridProjectStore, id: String) = store.directory(id).walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(store.directory(id)).path to it.readBytes().toList() }
}
