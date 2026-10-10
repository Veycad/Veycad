package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualCanonicalCommandTest {
    @Test fun resetAlwaysRoutesToCoreEvenAtOriginalWithRedo() {
        val base = ManualMontageFixtures.linearProject()
        val moved = apply(base, TimelineCommand.Move("B", 0))
        val undone = base.copy(shared = HybridEditCommands.apply(moved.shared, ProjectCommand.Undo))
        val preparation = MontageTimelineEditor.prepare(undone, TimelineCommand.RestoreBaseline)
        assertTrue(preparation is TimelinePreparation.CoreRestoreMontage)
        val reset = preparation as TimelinePreparation.CoreRestoreMontage
        assertSame(ProjectCommand.RestoreMontage, reset.command)
        assertEquals(setOf("A1", "B", "A2"), reset.changedClipIds)
        assertSame(undone.shared, HybridEditCommands.apply(undone.shared, reset.command))
        assertFalse(undone.shared.redo.isEmpty())
    }

    @Test fun canonicalResetRepairsAmbiguousTrimRatherThanProjectingIt() {
        val base = ManualMontageFixtures.linearProject()
        val trimmed = apply(base, TimelineCommand.Trim("A1", ClipEdge.START, 9))
        val legacy = trimmed.copy(shared = trimmed.shared.copy(current = trimmed.shared.current.copy(
            graph = trimmed.shared.current.graph.copy(manualMontageState = null))))
        val restored = apply(legacy, TimelineCommand.RestoreBaseline).shared
        assertEquals(base.shared.original.clips, restored.current.clips)
        assertTrue(restored.current.restoresAutomaticSources)
    }

    @Test fun resetBarrierCannotResurrectEditedHiddenCurveAfterAuthorOnlyCommit() {
        for (fps in listOf(30, 60)) {
            val base = ManualMontageFixtures.linearProject(fps)
            val count = 2 * fps
            val rewrite = SourceTimeMap((0..count).map { SourceTimeMap.Point(it, 1_000_000L + it * it * 100L) })
            val external = commit(base, base.shared.current.copy(clips = base.shared.current.clips.map {
                if (it.id == "A1") it.copy(sourceMap = rewrite) else it }))
            val reset = apply(external, TimelineCommand.RestoreBaseline)
            assertTrue(reset.shared.current.restoresAutomaticSources)
            val authored = reset.copy(shared = HybridEditCommands.apply(reset.shared, ProjectCommand.SetAuthoredText(true)))
            assertFalse(authored.shared.current.restoresAutomaticSources)
            val trim = apply(authored, TimelineCommand.Trim("A1", ClipEdge.START, if (fps == 30) 9 else 18))
            val extended = apply(trim, TimelineCommand.Trim("A1", ClipEdge.START, 0))
            assertEquals(base.shared.original.clips.first().sourceMap.points, extended.shared.current.clips.first().sourceMap.points)
        }
    }

    @Test fun gapNeverRecoversFromArbitraryOlderCompatibleMapOrRedo() {
        val base = ManualMontageFixtures.linearProject()
        val trimmed = apply(base, TimelineCommand.Trim("A1", ClipEdge.START, 9))
        val authored = trimmed.copy(shared = HybridEditCommands.apply(trimmed.shared, ProjectCommand.SetAuthoredText(true)))
        val gap = authored.copy(shared = authored.shared.copy(undo = listOf(base.shared.original), redo = listOf(trimmed.shared.current)))
        val result = MontageTimelineEditor.prepare(gap, TimelineCommand.Trim("A1", ClipEdge.START, 0))
        assertTrue(result.toString(), result is TimelinePreparation.Rejected)
    }

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
}
