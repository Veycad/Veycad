package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class MontageTimelineEditorTest {
    // A trim must slice literal samples and preserve the authored motion clock.
    @Test fun trimNineFramesKeepsRemainingMotion() {
        for (fps in listOf(30, 60)) {
            val project = EditableMontageCompilerTest.imported(ManualMontageFixtures.generatedGraph(), fps)
            val before = HighQualityFramePlan.build(EditableMontageCompiler.compile(project), fps)
            val cut = if (fps == 30) 9L else 18L
            val prepared = prepare(project, TimelineCommand.Trim("A1", ClipEdge.START, cut))
            val clip = project.revisionView(prepared.candidate).clips.first()
            assertEquals(1_300_000L, clip.timeMap.sourceTimeUs(clip.visible.start))
            assertEquals(2L * fps - cut, clip.visible.count)
            val after = HighQualityFramePlan.build(prepared.candidate.graph, fps)
            val retained = before.frames.filter { it.clipIndex == 0 }.drop(cut.toInt())
            after.frames.filter { it.clipIndex == 0 }.zip(retained).forEach { (actual, expected) ->
                assertEquals(expected.sourceTimeUs, actual.sourceTimeUs)
                assertEquals(expected.transform, actual.transform)
            }
            assertEquals(setOf("A1"), prepared.changedClipIds)
            assertEquals(project.shared.current.id, prepared.candidate.id)
            assertEquals(project.shared.current.parentId, prepared.candidate.parentId)
            assertSame(project.shared.original, project.shared.current)
            assertEquals(2L, project.shared.nextRevisionId)
        }
    }

    @Test fun extendNineFramesRestoresSourceMaterial() {
        for (fps in listOf(30, 60)) {
            val project = ManualMontageFixtures.linearProject(fps)
            val end = if (fps == 30) 69L else 138L
            val candidate = prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, end)).candidate
            val clip = project.revisionView(candidate).clips.first()
            assertEquals(end, clip.visible.count)
            // Edge extrapolation uses the saved last frame's exact integer PTS delta.
            assertEquals(if (fps == 30) 3_299_997L else 3_300_006L, clip.timeMap.sourceTimeUs(end))
        }
    }

    @Test fun extensionBeyondSourceIsRejected() {
        val project = ManualMontageFixtures.linearProject()
        assertTrue(MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.START, -100)) is TimelinePreparation.Rejected)
        assertTrue(MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, 1000)) is TimelinePreparation.Rejected)
        assertSame(project.shared.original, project.shared.current)
    }

    @Test fun aBaBecomesBaa() {
        val project = ManualMontageFixtures.linearProject()
        val candidate = prepare(project, TimelineCommand.Move("B", 0)).candidate
        assertEquals(listOf("B", "A1", "A2"), candidate.clips.map { it.id })
        assertEquals(listOf("video-1", "video-0", "video-0"), candidate.clips.map { it.assetId })
        assertEquals(listOf("B", "A1", "A2"), candidate.graph.clips.map { it.id })
        assertEquals(listOf(0, 60, 120), candidate.clips.map { it.span.start })
        assertEquals(1, candidate.graph.manualMontageState!!.effects.count { it.logicalId == "flash-B" })
    }

    @Test fun oneFrameClipIsValidButZeroIsRejected() {
        val project = ManualMontageFixtures.linearProject()
        assertEquals(1, prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, 1)).candidate.clips.first().span.length)
        assertTrue(MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, 0)) is TimelinePreparation.Rejected)
    }

    @Test fun movingLastFlashIsRejected() {
        val project = ManualMontageFixtures.linearProject()
        val moved = fixtureCommit(project, prepare(project, TimelineCommand.MoveFlashToNext("flash-B")).candidate)
        val flash = moved.current.effects.single()
        assertEquals(EffectAnchor.Boundary("A2", 0, 100_000), flash.anchor)
        assertEquals(project.current.effects.single().originalOverlay, flash.originalOverlay)
        assertTrue(MontageTimelineEditor.prepare(moved, TimelineCommand.MoveFlashToNext("flash-B")) is TimelinePreparation.Rejected)
        assertEquals(1, moved.current.effects.size)
    }

    @Test fun firstClipKeepsDormantIncomingTransition() {
        val project = ManualMontageFixtures.linearProject()
        val first = fixtureCommit(project, prepare(project, TimelineCommand.Move("B", 0)).candidate)
        assertEquals(MontageGraph.Transition.WHIP, first.shared.current.clips.first().original.transitionIn)
        assertTrue(first.current.effects.single().enabled)
        assertTrue(first.shared.current.graph.overlays.isEmpty())
        val back = prepare(first, TimelineCommand.Move("B", 1)).candidate
        assertEquals("flash-B", back.graph.overlays.single().id)
    }

    @Test fun noOpAndRejectionPreserveHistoryRedoAndCounter() {
        val project = ManualMontageFixtures.linearProject()
        val edited = fixtureCommit(project, prepare(project, TimelineCommand.Move("B", 0)).candidate)
        val restored = project.copy(shared = edited.shared.copy(current = project.shared.current,
            undo = emptyList(), redo = listOf(edited.shared.current)))
        val before = restored.shared
        for (command in listOf(TimelineCommand.Move("A1", 0), TimelineCommand.Trim("A1", ClipEdge.START, 0),
            TimelineCommand.SetFlashEnabled("flash-B", true), TimelineCommand.RestoreBaseline,
            TimelineCommand.SetTransition("B", MontageGraph.Transition.WHIP))) {
            assertSame(TimelinePreparation.Unchanged, MontageTimelineEditor.prepare(restored, command))
        }
        for (command in listOf(TimelineCommand.Move("missing", 0), TimelineCommand.Move("A1", 99),
            TimelineCommand.SetFlashEnabled("missing", false))) {
            assertTrue(MontageTimelineEditor.prepare(restored, command) is TimelinePreparation.Rejected)
        }
        assertSame(before, restored.shared)
        assertEquals(3L, restored.shared.nextRevisionId)
        assertEquals(listOf(2L), restored.shared.redo.map { it.id })
    }

    @Test fun restoreBaselinePreservesUnrelatedEditorChoices() {
        val base = ManualMontageFixtures.linearProject()
        val moved = fixtureCommit(base, prepare(base, TimelineCommand.Move("A2", 1)).candidate)
        val text = TextItem("text", "hello", FrameSpan(0, 10), .5f, .5f, .5f, .1f, -1, TextItem.Appearance.PLAIN, 0)
        val current = moved.shared.current.copy(music = moved.shared.current.music.copy(gain = .4f, startUs = 20_000),
            texts = listOf(text), style = moved.shared.current.style.copy(mode = ProjectStyle.Mode.ADAPTIVE), lockedCutIds = setOf("B"))
        val project = fixtureCommit(moved, current)
        val reset = prepare(project, TimelineCommand.RestoreBaseline).candidate
        assertEquals(listOf("A1", "B", "A2"), reset.clips.map { it.id })
        assertEquals(project.shared.current.music, reset.music)
        assertEquals(project.shared.current.texts, reset.texts)
        assertEquals(project.shared.current.style, reset.style)
        assertEquals(setOf("B"), reset.lockedCutIds)
        assertEquals(base.baseline.clips, project.revisionView(reset).clips)
    }

    @Test fun editsCannotSilentlyDropLockedCutsOrTextOutsideShortenedDuration() {
        val base = ManualMontageFixtures.linearProject()
        val current = base.shared.current.copy(lockedCutIds = setOf("B"), texts = listOf(
            TextItem("tail", "tail", FrameSpan(170, 180), .5f, .5f, .5f, .1f, -1, TextItem.Appearance.PLAIN, 0)))
        val project = fixtureCommit(base, current)
        assertTrue(MontageTimelineEditor.prepare(project, TimelineCommand.Move("B", 0)) is TimelinePreparation.Rejected)
        assertTrue(MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, 59)) is TimelinePreparation.Rejected)
    }

    @Test fun explicitTransitionEditOnlyTogglesExistingOwnedBundle() {
        val base = ManualMontageFixtures.generatedGraph()
        val graph = base.copy(effectGraph = GpuEffectGraphFactory.forMontage(base))
        val imported = EditableMontageCompilerTest.imported(graph)
        val moved = fixtureCommit(imported, prepare(imported, TimelineCommand.MoveFlashToNext("flash-B")).candidate)
        val off = fixtureCommit(moved, prepare(moved, TimelineCommand.SetTransition("B", MontageGraph.Transition.HARD_CUT)).candidate)
        val owned = off.current.effects.filter { (it.anchor as? EffectAnchor.Clip)?.clipId == "B" }
        assertEquals(2, owned.size)
        assertTrue(owned.none { it.enabled })
        assertEquals(EffectAnchor.Boundary("A2", 0, 100_000), off.current.effects.single { it.id == "flash-B" }.anchor)
        val on = prepare(off, TimelineCommand.SetTransition("B", MontageGraph.Transition.WHIP)).candidate
        assertEquals(2, on.graph.manualMontageState!!.effects.count { it.id.startsWith("whip-") && (it.anchor as? EffectAnchor.Clip)?.clipId == "B" && it.enabled })
        assertEquals(moved.current.effects.map { it.id }, on.graph.manualMontageState!!.effects.map { it.id })
    }

    @Test fun disabledFlashStaysDisabledThroughTrimAndReorder() {
        val base = ManualMontageFixtures.linearProject(60)
        val off = fixtureCommit(base, prepare(base, TimelineCommand.SetFlashEnabled("flash-B", false)).candidate)
        val trim = fixtureCommit(off, prepare(off, TimelineCommand.Trim("A1", ClipEdge.START, 18)).candidate)
        val moved = prepare(trim, TimelineCommand.Move("A1", 2)).candidate
        assertFalse(moved.graph.manualMontageState!!.effects.single().enabled)
        assertTrue(moved.graph.overlays.isEmpty())
        assertEquals(moved.graph, EditableMontageCompiler.compile(trim, moved))
    }

    // Extrapolating a cropped edge or choosing the linear baseline destroys saved ramp samples.
    @Test fun latestSavedExternalNonlinearMapSurvivesTrimThenExtend() {
        val base = ManualMontageFixtures.linearProject()
        val samples = LongArray(61) { 1_000_000L + (it / 2).toLong() * (it / 2) * 1000 }
        val external = fixtureCommit(base, replaceMap(base.shared.current, SourceTimeMap(samples.mapIndexed { i, pts -> SourceTimeMap.Point(i, pts) })))
        val start = fixtureCommit(external, prepare(external, TimelineCommand.Trim("A1", ClipEdge.START, 9)).candidate)
        val both = fixtureCommit(start, prepare(start, TimelineCommand.Trim("A1", ClipEdge.END, 51)).candidate)
        val end = fixtureCommit(both, prepare(both, TimelineCommand.Trim("A1", ClipEdge.END, 60)).candidate)
        val restored = prepare(end, TimelineCommand.Trim("A1", ClipEdge.START, 0)).candidate
        assertArrayEquals(samples, projectSamples(restored))
        assertArrayEquals(samples.copyOfRange(9, 52), projectSamples(both.shared.current))
    }

    @Test fun obsoleteMetadataLessTrimDoesNotBlockNewerCompleteMapRecovery() {
        val complete = completeMapAfterMetadataLessTrim()
        val trimmed = fixtureCommit(complete, prepare(complete, TimelineCommand.Trim("A1", ClipEdge.START, 9)).candidate)
        val restored = prepare(trimmed, TimelineCommand.Trim("A1", ClipEdge.START, 0)).candidate
        assertArrayEquals(LongArray(61) { 1_000_000L + (it / 2).toLong() * (it / 2) * 1000 }, projectSamples(restored))
    }

    @Test fun obsoleteMetadataLessTrimDoesNotBlockKnownEdgeExtension() {
        val complete = completeMapAfterMetadataLessTrim()
        val extended = prepare(complete, TimelineCommand.Trim("A1", ClipEdge.END, 69)).candidate
        assertArrayEquals(LongArray(61) { 1_000_000L + (it / 2).toLong() * (it / 2) * 1000 }, projectSamples(extended).copyOfRange(0, 61))
        assertEquals(2_431_000L, extended.clips.first().sourceMap.sample(69))
    }

    @Test fun ambiguousNewerHistoryStillCannotFallBackToOlderCompleteMap() {
        val base = ManualMontageFixtures.linearProject()
        val short = fixtureCommit(base, prepare(base, TimelineCommand.Trim("A1", ClipEdge.START, 9)).candidate)
        val current = prepare(short, TimelineCommand.Trim("A1", ClipEdge.START, 18)).candidate
        val ambiguous = short.shared.current.copy(graph = short.shared.current.graph.copy(manualMontageState = null))
        val history = short.copy(shared = short.shared.copy(current = ambiguous))
        val project = fixtureCommit(history, current)
        assertTrue(MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.START, 0)) is TimelinePreparation.Rejected)
    }

    private fun completeMapAfterMetadataLessTrim(): EditableMontageProject {
        val base = ManualMontageFixtures.linearProject()
        val short = fixtureCommit(base, prepare(base, TimelineCommand.Trim("A1", ClipEdge.END, 40)).candidate)
        val old = short.shared.current.copy(graph = short.shared.current.graph.copy(manualMontageState = null))
        val history = short.copy(shared = short.shared.copy(current = old))
        val map = SourceTimeMap((0..60).map { SourceTimeMap.Point(it, 1_000_000L + (it / 2).toLong() * (it / 2) * 1000) })
        return fixtureCommit(history, replaceMap(base.shared.original, map))
    }

    @Test fun slipTrimExtendRestoresNonlinearRepeatedAndSparseSamples() {
        for (fps in listOf(30, 60)) {
            val base = ManualMontageFixtures.linearProject(fps)
            val count = 2 * fps
            val map = SourceTimeMap(listOf(SourceTimeMap.Point(0, 1_000_000), SourceTimeMap.Point(9, 1_123_457),
                SourceTimeMap.Point(18, 1_123_457), SourceTimeMap.Point(count, 3_000_000)))
            val original = replaceMap(base.shared.original, map)
            val project = base.copy(shared = base.shared.copy(original = original, current = original))
            val shifted = SourceTimeMap(map.points.map { it.copy(sourceTimeUs = it.sourceTimeUs + 234_567) })
            val slipped = fixtureCommit(project, replaceMap(project.shared.current, shifted))
            val trimmed = fixtureCommit(slipped, prepare(slipped, TimelineCommand.Trim("A1", ClipEdge.START, 18)).candidate)
            val restored = prepare(trimmed, TimelineCommand.Trim("A1", ClipEdge.START, 0)).candidate
            assertArrayEquals(LongArray(count + 1) { map.sample(it) + 234_567 }, projectSamples(restored))
            assertEquals(1_358_024L, restored.clips.first().sourceMap.sample(9))
            assertEquals(1_358_024L, restored.clips.first().sourceMap.sample(18))
        }
    }

    @Test fun incompatibleHistoryCannotInventMissingOriginalMaterial() {
        val base = ManualMontageFixtures.linearProject()
        val external = fixtureCommit(base, replaceMap(base.shared.current, SourceTimeMap((0..60).map {
            SourceTimeMap.Point(it, 1_000_000L + it.toLong() * it * 100)
        })))
        val trimmed = fixtureCommit(external, prepare(external, TimelineCommand.Trim("A1", ClipEdge.START, 9)).candidate)
        val missingHistory = trimmed.copy(shared = trimmed.shared.copy(undo = listOf(base.shared.original)))
        assertTrue(MontageTimelineEditor.prepare(missingHistory, TimelineCommand.Trim("A1", ClipEdge.START, 0)) is TimelinePreparation.Rejected)
    }

    @Test fun preparedDescriptorsOwnTheirAuthoredKeyframeLists() {
        val base = ManualMontageFixtures.linearProject()
        val keyframes = base.shared.current.clips.first().original.transform.keyframes.toMutableList()
        val revision = base.shared.current.copy(clips = base.shared.current.clips.map { clip ->
            if (clip.id == "A1") clip.copy(original = clip.original.copy(transform = clip.original.transform.copy(keyframes = keyframes))) else clip
        })
        val project = fixtureCommit(base, revision)
        val candidate = prepare(project, TimelineCommand.Move("A2", 1)).candidate
        val expected = keyframes.toList()
        keyframes.clear()
        assertEquals(expected, candidate.clips.first().original.transform.keyframes)
        assertEquals(expected, candidate.graph.clips.first().transform.keyframes)
    }

    @Test fun sparseSavedEdgeKeepsItsExactRationalSlopeOnExtension() {
        val base = ManualMontageFixtures.linearProject()
        val original = replaceMap(base.shared.original, SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 1_000_000), SourceTimeMap.Point(60, 3_000_000))))
        val project = base.copy(shared = base.shared.copy(original = original, current = original))
        val extended = prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, 69)).candidate
        assertEquals(3_300_000L, extended.clips.first().sourceMap.sample(69))
        assertEquals(2_966_667L, extended.clips.first().sourceMap.sample(59))
        assertEquals(3_000_000L, extended.clips.first().sourceMap.sample(60))
    }

    @Test fun metadataLessSavedTrimIsRejectedRatherThanInventingPhase() {
        val base = ManualMontageFixtures.linearProject()
        val trimmed = fixtureCommit(base, prepare(base, TimelineCommand.Trim("A1", ClipEdge.START, 9)).candidate)
        val current = trimmed.shared.current.copy(graph = trimmed.shared.current.graph.copy(manualMontageState = null))
        val project = trimmed.copy(shared = trimmed.shared.copy(current = current))
        assertTrue(MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, 60)) is TimelinePreparation.Rejected)
    }

    @Test fun nextCutCommandRejectsMusicAndSceneFlashWithoutRetimingThem() {
        val base = ManualMontageFixtures.linearProject()
        for (anchor in listOf(EffectAnchor.Music(2_000_000, 2_100_000), EffectAnchor.Clip("B", 0, 100_000))) {
            val payload = base.shared.current.graph.manualMontageState!!
            val flash = payload.effects.single().copy(anchor = anchor)
            val revision = base.shared.current.copy(graph = base.shared.current.graph.copy(
                manualMontageState = payload.copy(effects = listOf(flash))))
            val project = fixtureCommit(base, revision)
            val result = MontageTimelineEditor.prepare(project, TimelineCommand.MoveFlashToNext("flash-B"))
            assertTrue(result is TimelinePreparation.Rejected)
            assertTrue((result as TimelinePreparation.Rejected).reason.contains("boundary flash"))
            val disabled = prepare(project, TimelineCommand.SetFlashEnabled("flash-B", false)).candidate
            assertEquals(anchor, disabled.graph.manualMontageState!!.effects.single().anchor)
            assertFalse(disabled.graph.manualMontageState!!.effects.single().enabled)
            assertEquals(flash, project.current.effects.single())
        }
    }

    private fun replaceMap(revision: HybridRevision, map: SourceTimeMap) = revision.copy(clips = revision.clips.map {
        if (it.id == "A1") it.copy(sourceMap = map) else it
    })

    private fun projectSamples(revision: HybridRevision): LongArray = revision.clips.first { it.id == "A1" }.let { clip ->
        LongArray(clip.span.length + 1) { clip.sourceMap.sample(it) }
    }

    private fun prepare(project: EditableMontageProject, command: TimelineCommand): TimelinePreparation.Prepared =
        MontageTimelineEditor.prepare(project, command).let { assertTrue(it.toString(), it is TimelinePreparation.Prepared); it as TimelinePreparation.Prepared }

    /** Fixtures model saved shared revisions only; production allocation/history belongs to Task3B. */
    private fun fixtureCommit(project: EditableMontageProject, candidate: HybridRevision): EditableMontageProject {
        val shared = project.shared
        val saved = candidate.copy(id = shared.nextRevisionId, parentId = shared.current.id)
        return project.copy(shared = shared.copy(current = saved, nextRevisionId = shared.nextRevisionId + 1,
            undo = shared.undo + shared.current, redo = emptyList()))
    }
}
