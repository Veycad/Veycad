package com.veycad.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A2b acceptance: a newly constructed store must reopen the authored revision itself. */
class ManualMontagePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun manualEditsAndHistorySurviveFreshStoreAt30Fps() = savedEditRoundTrip(30, 9, 4_001_600L)

    @Test fun manualEditsAndHistorySurviveFreshStoreAt60Fps() = savedEditRoundTrip(60, 18, 4_008_100L)

    private fun savedEditRoundTrip(fps: Int, cut: Int, firstRetainedPts: Long) {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val projectId = "manual-$fps"
        val assets = ProjectAssetStore(store.directory(projectId))
        val videoA = assets.import(temporary.newFile().apply { writeText("synthetic A $fps") },
            ProjectAsset.Kind.VIDEO, 10_000_000)
        val videoB = assets.import(temporary.newFile().apply { writeText("synthetic B $fps") },
            ProjectAsset.Kind.VIDEO, 10_000_000)
        val music = assets.import(temporary.newFile().apply { writeText("synthetic music $fps") },
            ProjectAsset.Kind.AUDIO, 10_000_000)
        assertEquals(64, videoA.contentHash.length)
        assertNotEquals(videoA.contentHash, videoB.contentHash)

        val template = ManualMontageFixtures.generatedGraph()
        val shorterB = template.clips[1].copy(sourceEndMs = 5_201, outputDurationMs = 1_201)
        val sourcePlanes = listOf(
            SourceAttachments(0, FrameAttachmentTimeline(listOf(FrameAttachments(4_000_000,
                mask = FrameAttachments.Plane(1, 1, listOf(.2f), .2f))))),
            SourceAttachments(1, FrameAttachmentTimeline(listOf(FrameAttachments(4_000_000,
                mask = FrameAttachments.Plane(1, 1, listOf(.9f), .9f))))))
        val graph = template.copy(clips = listOf(template.clips[0], shorterB, template.clips[2]),
            outputDurationMs = 5_201, audioTrack = MontageGraph.AudioTrack("selected-score", 2f),
            sourceAttachments = sourcePlanes)
        val imported = EditableMontageImporter.create(projectId, graph,
            MontageStyleCatalog.Recipe.DUALITY_LOOP,
            ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000),
            listOf(videoA, videoB), music, null)
        val original = imported.shared.original.copy(
            music = imported.shared.original.music.copy(startUs = 123_456),
            visualSettings = ProjectVisualSettings(ProjectAspect.SQUARE_1_1, true),
            textState = HybridTextState(layers = listOf(TextLayer("authored-title", "Saved title", 0, 500_000)),
                language = "ru"))
        // Literal, repeated/nonlinear source samples make interpolation or rebasing observable.
        val b = original.clips.single { it.id == "B" }
        val nonlinear = SourceTimeMap((0..b.span.length).map { frame ->
            SourceTimeMap.Point(frame, 4_000_000L + (frame / 2L) * (frame / 2L) * 100L)
        })
        val baseline = original.copy(clips = original.clips.map { if (it.id == "B") it.copy(sourceMap = nonlinear) else it })
        val base = imported.copy(shared = imported.shared.copy(original = baseline, current = baseline))
        assertEquals(1_201_000L, base.current.clips.single { it.id == "B" }.phase!!.durationUs)
        assertEquals(2f, base.shared.current.music.gain, 0f)
        assertEquals(123_456L, base.shared.current.music.startUs)
        assertEquals(ProjectAspect.SQUARE_1_1, base.shared.current.visualSettings.aspect)
        assertEquals("Saved title", base.shared.current.textState.layers.single().text)
        store.create(base.shared)

        var edited = edit(base, TimelineCommand.Trim("B", ClipEdge.START, cut.toLong()))
        edited = edit(edited, TimelineCommand.SetTransition("B", MontageGraph.Transition.HARD_CUT))
        edited = edit(edited, TimelineCommand.MoveFlashToNext("flash-B"))
        edited = edit(edited, TimelineCommand.SetFlashEnabled("flash-B", false))
        edited = edit(edited, TimelineCommand.Move("B", 0))
        val reordered = edited.shared.current
        edited = edited.copy(shared = HybridEditCommands.apply(edited.shared, ProjectCommand.Undo))
        val saved = edited.shared
        assertEquals(listOf("A1", "B", "A2"), saved.current.clips.map { it.id })
        assertEquals(listOf("B", "A1", "A2"), saved.redo.single().clips.map { it.id })
        assertEquals(listOf(0, 1, 0), saved.current.clips.map { it.original.sourceIndex })
        assertEquals(listOf(1, 0, 0), saved.redo.single().clips.map { it.original.sourceIndex })
        assertEquals(reordered.id, saved.redo.single().id)
        assertEquals(cut, saved.current.clips.single { it.id == "B" }.originalFrameOffset)
        assertEquals(MontageGraph.Transition.HARD_CUT,
            saved.current.clips.single { it.id == "B" }.original.transitionIn)
        assertEquals(listOf("A1-scale"), saved.current.graph.manualMontageState!!.clips
            .single { it.clipId == "A1" }.localTracks.map { it.id })
        val bState = saved.current.graph.manualMontageState!!.clips.single { it.clipId == "B" }
        assertEquals(listOf("B-grade"), bState.localTracks.map { it.id })
        assertEquals(ClipPhase((2 * fps).toLong(), 2_000_000, 1_201_000), bState.phase)
        val authoredEffect = saved.current.graph.manualMontageState!!.effects.single { it.id == "flash-B" }
        assertEquals("flash-B", authoredEffect.logicalId)
        assertEquals("flash-B", authoredEffect.originId)
        assertEquals(EffectAnchor.Boundary("A2", 0, 100_000), authoredEffect.anchor)
        assertFalse(authoredEffect.enabled)
        assertEquals(0f, authoredEffect.phaseStart, 0f)
        assertEquals(1f, authoredEffect.phaseEnd, 0f)
        // The edited graph is the raw stored value, not just a compiler projection.
        assertNotNull(saved.current.graph.editableTiming)
        val enabledFlashRevision = saved.undo.first { it.id != saved.original.id &&
            it.graph.manualMontageState!!.effects.single { effect -> effect.id == "flash-B" }.enabled }
        assertNotNull(enabledFlashRevision.graph.overlays.single { it.id == "flash-B" }.sampleWindow)
        val beforePlan = HighQualityFramePlan.build(EditableMontageCompiler.compile(edited), fps)
        val firstB = beforePlan.frames.first { it.sourceIndex == 1 }
        assertEquals(firstRetainedPts, firstB.sourceTimeUs)
        assertEquals(300_000f / 1_201_000f, firstB.clipProgress, 0.000001f)
        assertEquals(listOf(4_000_000L, 4_000_000L, 4_000_100L),
            baseline.clips.single { it.id == "B" }.sourceMap.points.take(3).map { it.sourceTimeUs })
        store.save(saved, base.shared.current.id)

        // Drop all caller objects. No payload injection, re-import, or adapter repair after load.
        val reopened = HybridProjectStore(files).load(projectId)
        assertRawReopened(saved, reopened, fps)
        assertEquals(saved.original, HybridProjectStore(files).loadRevision(projectId, saved.original.id))
        assertEquals(saved.redo.single(), HybridProjectStore(files).loadRevision(projectId, reordered.id))
        assertEquals("synthetic A $fps", assets.resolve(reopened.assets[0]).readText())
        assertEquals("synthetic B $fps", assets.resolve(reopened.assets[1]).readText())
        assertEquals("synthetic music $fps", assets.resolve(reopened.assets[2]).readText())
        val reopenedView = EditableMontageProject(reopened, edited.export)
        assertEquals(beforePlan, HighQualityFramePlan.build(EditableMontageCompiler.compile(reopenedView), fps))

        val redone = HybridEditCommands.apply(reopened, ProjectCommand.Redo)
        assertEquals(listOf("B", "A1", "A2"), redone.current.clips.map { it.id })
        val continued = edit(EditableMontageProject(redone, edited.export),
            TimelineCommand.Trim("A1", ClipEdge.START, -1)).shared
        assertEquals(-1, continued.current.clips.single { it.id == "A1" }.originalFrameOffset)
        assertEquals(123_456L, continued.current.music.startUs)
        assertEquals(2f, continued.current.music.gain, 0f)
        HybridProjectStore(files).save(continued, reopened.current.id)
        val twiceReopened = HybridProjectStore(files).load(projectId)
        assertRawReopened(continued, twiceReopened, fps)
        assertEquals(-1, twiceReopened.current.clips.single { it.id == "A1" }.originalFrameOffset)
        assertEquals(continued.current.clips.single { it.id == "A1" }.sourceMap.points,
            twiceReopened.current.clips.single { it.id == "A1" }.sourceMap.points)
        val beforeSecondPlan = HighQualityFramePlan.build(EditableMontageCompiler.compile(
            EditableMontageProject(continued, edited.export)), fps)
        assertEquals(beforeSecondPlan, HighQualityFramePlan.build(EditableMontageCompiler.compile(
            EditableMontageProject(twiceReopened, edited.export)), fps))

        val undone = HybridEditCommands.apply(twiceReopened, ProjectCommand.Undo)
        assertEquals(redone.current, undone.current)
        val redoneAgain = HybridEditCommands.apply(undone, ProjectCommand.Redo)
        assertEquals(twiceReopened.current, redoneAgain.current)
        val restored = HybridEditCommands.apply(redoneAgain, ProjectCommand.RestoreMontage)
        assertTrue(restored.current.restoresAutomaticSources)
        assertEquals(restored.original.clips, restored.current.clips)
        assertEquals(restored.original.graph.manualMontageState, restored.current.graph.manualMontageState)
        HybridProjectStore(files).save(restored, twiceReopened.current.id)
        val resetReopened = HybridProjectStore(files).load(projectId)
        assertRawReopened(restored, resetReopened, fps)
        assertTrue(resetReopened.current.restoresAutomaticSources)
    }

    private fun edit(project: EditableMontageProject, command: TimelineCommand): EditableMontageProject {
        val prepared = MontageTimelineEditor.prepare(project, command)
        val candidate = prepared as? TimelinePreparation.Prepared ?: throw AssertionError(prepared)
        val changed = HybridEditCommands.apply(project.shared, ProjectCommand.CommitRevision(candidate.candidate))
        assertNotEquals(project.shared.current.id, changed.current.id)
        return project.copy(shared = changed)
    }

    private fun assertRawReopened(before: HybridProject, after: HybridProject, fps: Int) {
        assertEquals(before.id, after.id)
        assertEquals(fps, after.fps)
        assertEquals(before.nextRevisionId, after.nextRevisionId)
        assertEquals(before.assets, after.assets)
        for ((expected, actual) in (listOf(before.original, before.current) + before.undo + before.redo)
            .zip(listOf(after.original, after.current) + after.undo + after.redo)) {
            assertEquals(expected.graph.manualMontageState, actual.graph.manualMontageState)
            assertEquals(expected.graph.sourceAttachments, actual.graph.sourceAttachments)
            assertEquals(expected.graph.editableTiming, actual.graph.editableTiming)
            assertEquals(expected.graph.manualOverrides, actual.graph.manualOverrides)
            assertEquals(expected.clips.map { it.original.sourceIndex }, actual.clips.map { it.original.sourceIndex })
            assertEquals(expected.clips.map { it.originalFrameOffset }, actual.clips.map { it.originalFrameOffset })
            assertEquals(expected.clips.map { it.sourceMap.points }, actual.clips.map { it.sourceMap.points })
            assertEquals(expected.music, actual.music)
            assertEquals(expected.style, actual.style)
            assertEquals(expected.visualSettings, actual.visualSettings)
            assertEquals(expected.textState, actual.textState)
        }
        assertEquals(before.original, after.original)
        assertEquals(before.current, after.current)
        assertEquals(before.undo, after.undo)
        assertEquals(before.redo, after.redo)
        assertEquals(listOf(0, 1), after.current.graph.sourceAttachments.map { it.sourceIndex })
        assertEquals(listOf(.2f, .9f), after.current.graph.sourceAttachments.map {
            it.timeline.frames.single().mask!!.confidence })
    }
}
