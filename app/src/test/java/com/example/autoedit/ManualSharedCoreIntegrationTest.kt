package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualSharedCoreIntegrationTest {
    @Test fun preparedEditAfterAutomaticResetClearsResetEventAndNeverReusesUndoIds() {
        val base = EditableMontageCompilerTest.imported(ManualMontageFixtures.generatedGraph())
        val moved = commit(base, prepared(base, TimelineCommand.Move("B", 0)))
        val automatic = moved.copy(shared = HybridEditCommands.apply(moved.shared, ProjectCommand.RestoreAutomatic))
        assertTrue(automatic.shared.current.restoresAutomaticSources)
        val candidate = prepared(automatic, TimelineCommand.Trim("A1", ClipEdge.START, 9))
        assertTrue(candidate.restoresAutomaticSources)
        val edited = commit(automatic, candidate)
        assertFalse(edited.shared.current.restoresAutomaticSources)
        assertEquals(4L, edited.shared.current.id)
        assertEquals(5L, edited.shared.nextRevisionId)
        val undo = edited.copy(shared = HybridEditCommands.apply(edited.shared, ProjectCommand.Undo))
        val branch = commit(undo, prepared(undo, TimelineCommand.Trim("A1", ClipEdge.START, 18)))
        assertEquals(5L, branch.shared.current.id)
        assertEquals(6L, branch.shared.nextRevisionId)
        assertTrue(branch.shared.redo.isEmpty())
        assertEquals(0, base.shared.current.clips.first().originalFrameOffset)
        assertEquals(9, edited.shared.current.clips.first().originalFrameOffset)
        assertEquals(18, branch.shared.current.clips.first().originalFrameOffset)
    }

    // Catches dropping unused selections and rejecting equal repeated physical files on import.
    @Test fun importerRetainsInspectedSelectionOrderOwnershipAndRepeatedAssets() {
        for (fps in listOf(30, 60)) {
            val graph = ManualMontageFixtures.generatedGraph().let { it.copy(clips = it.clips.map { clip ->
                if (clip.id == "A2") clip.copy(sourceIndex = 2) else clip
            }) }
            val a = video("a")
            val selections = listOf(selection("first", "a"), selection("second", "b"),
                SelectedVideo(SourceId("take"), "a", SourceOwnership.CAPTURE, CaptureOrigin("session", 2, true), "take two"),
                selection("unused-selection", "unused"))
            val mutableSelections = selections.toMutableList()
            val project = import(graph, fps, listOf(a, video("b"), a, video("unused")), mutableSelections)
            mutableSelections.clear()
            assertEquals(selections, project.shared.selectedVideos)
            assertEquals(listOf("a", "b", "a", "unused"), project.sources.map { it.id })
            assertEquals(listOf("a", "b", "unused", "score"), project.shared.assets.map { it.id })
            assertEquals(listOf(0, 1, 2), project.current.clips.map { it.sourceIndex })
            assertEquals(HighQualityFramePlan.build(graph, fps),
                HighQualityFramePlan.build(EditableMontageCompiler.compile(project), fps))
            val moved = commit(project, prepared(project, TimelineCommand.Move("B", 0)))
            assertEquals(listOf(1, 0, 2), moved.current.clips.map { it.sourceIndex })
            assertEquals(selections, moved.shared.selectedVideos)
        }
    }

    // Catches last-write-wins metadata and treating selection asset order as interchangeable.
    @Test fun importerRejectsConflictingDuplicatesAndSelectionMismatchAndUnknownRepeatedOrder() {
        val graph = ManualMontageFixtures.generatedGraph()
        val a = video("a")
        val sources = listOf(a, video("b"), a)
        val selections = listOf(selection("first", "a"), selection("second", "b"), selection("repeat", "a"))
        for (conflict in listOf(a.copy(durationUs = 11_000_000), a.copy(contentHash = "b".repeat(64)),
            a.copy(videoMetadata = a.videoMetadata!!.copy(sizeBytes = 2345)))) {
            assertThrows(IllegalArgumentException::class.java) { import(graph, 30, listOf(a, video("b"), conflict), selections) }
        }
        assertThrows(IllegalArgumentException::class.java) { import(graph, 30, sources, selections.take(2)) }
        assertThrows(IllegalArgumentException::class.java) { import(graph, 30, sources, listOf(selections[1], selections[0], selections[2])) }
        assertThrows(IllegalArgumentException::class.java) { import(graph, 30, sources, null) }
        val legacy = import(graph, 30, listOf(a, video("b")), null)
        assertNull(legacy.shared.selectedVideos)
        assertEquals(listOf(0, 1, 0), legacy.current.clips.map { it.sourceIndex })
    }

    // Catches copying just old revision fields and losing framing/captions when preparing edits.
    @Test fun manualEditsAndSharedSlipKeepUnrelatedRevisionChoicesAndImmutablePhase() {
        for (fps in listOf(30, 60)) {
            val base = import(ManualMontageFixtures.generatedGraph(), fps, listOf(video("a"), video("b")),
                listOf(selection("first", "a"), selection("second", "b")))
            val visuals = ProjectVisualSettings(ProjectAspect.LANDSCAPE_16_9, true,
                mapOf(FramingKey(SourceId("first"), ProjectAspect.LANDSCAPE_16_9) to
                    SourceFramingSettings(manual = FramingSettings(FramingMode.MANUAL, .4f, .6f, 1.5f))))
            val textState = HybridTextState(layers = listOf(TextLayer("label", "label", 0, 500_000)),
                captions = listOf(CaptionCue("cue", "cue", 0, 400_000)), captionsEdited = true, language = "ru")
            val choices = base.shared.current.copy(music = base.shared.current.music.copy(gain = 1.5f, startUs = 100_000,
                fadeInUs = 12_345, fadeOutUs = 54_321, repeat = false),
                texts = listOf(TextItem("text", "hello", FrameSpan(0, 4), .5f, .5f, .5f, .1f, -1, TextItem.Appearance.PLAIN, 0)),
                style = base.shared.current.style.copy(mode = ProjectStyle.Mode.ADAPTIVE, showAuthoredText = true),
                visualSettings = visuals, textState = textState)
            val project = commit(base, choices)
            val cut = if (fps == 30) 9L else 18L
            val trimmed = commit(project, prepared(project, TimelineCommand.Trim("A1", ClipEdge.START, cut)))
            val phase = trimmed.current.clips.first().phase
            val slipped = trimmed.copy(shared = HybridEditCommands.apply(trimmed.shared, ProjectCommand.SlipClip("A1", 234_567)))
            assertEquals(cut.toInt(), slipped.shared.current.clips.first().originalFrameOffset)
            assertEquals(phase, slipped.current.clips.first().phase)
            assertEquals(trimmed.current.clips.first().localTracks, slipped.current.clips.first().localTracks)
            assertEquals(trimmed.current.effects, slipped.current.effects)
            val old = HighQualityFramePlan.build(EditableMontageCompiler.compile(trimmed), fps).frames.filter { it.clipIndex == 0 }
            val frames = HighQualityFramePlan.build(EditableMontageCompiler.compile(slipped), fps).frames.filter { it.clipIndex == 0 }
            assertEquals(old.size, frames.size)
            old.zip(frames).forEach { (before, after) ->
                assertEquals(before.sourceTimeUs + 234_567, after.sourceTimeUs)
                assertEquals(before.clipProgress, after.clipProgress, 0f)
                assertEquals(before.transform, after.transform)
            }
            for (command in listOf(TimelineCommand.Move("A1", 2), TimelineCommand.Trim("A1", ClipEdge.END, 2L * fps - 1),
                TimelineCommand.SetTransition("B", MontageGraph.Transition.HARD_CUT),
                TimelineCommand.SetFlashEnabled("flash-B", false))) {
                val candidate = prepared(slipped, command)
                assertEquals(choices.music, candidate.music)
                assertEquals(choices.texts, candidate.texts)
                assertEquals(choices.style, candidate.style)
                assertEquals(visuals, candidate.visualSettings)
                assertEquals(textState, candidate.textState)
            }
            val reset = MontageTimelineEditor.prepare(slipped, TimelineCommand.RestoreBaseline) as TimelinePreparation.CoreRestoreMontage
            val restored = HybridEditCommands.apply(slipped.shared, reset.command).current
            assertEquals(choices.music, restored.music)
            assertEquals(choices.texts, restored.texts)
            assertEquals(choices.style, restored.style)
            assertEquals(visuals, restored.visualSettings)
            assertEquals(textState, restored.textState)
        }
    }

    // Catches stale payload coordinates overriding the common MoveCut offset and literal PTS.
    @Test fun commonMoveCutProjectsCanonicalOffsetWithFractionalOriginalPhase() {
        for (fps in listOf(30, 60)) {
            val base = EditableMontageCompilerTest.imported(EditableMontageCompilerTest.fractionalGraph(), fps)
            val before = HighQualityFramePlan.build(EditableMontageCompiler.compile(base), fps).frames.filter { it.clipIndex == 1 }
            val cut = if (fps == 30) 9 else 18
            val changed = HybridEditCommands.apply(base.shared,
                ProjectCommand.MoveCut("B", base.shared.current.clips[1].span.start + cut))
            assertEquals(FrameRange(0, before.size.toLong()), changed.current.graph.manualMontageState!!.clips[1].visible)
            val adapter = base.copy(shared = changed)
            assertEquals(cut.toLong(), adapter.current.clips[1].visible.start)
            assertEquals(1_201_000L, adapter.current.clips[1].phase!!.durationUs)
            val compiled = EditableMontageCompiler.compile(adapter)
            assertEquals(adapter.current.clips[1].visible, compiled.manualMontageState!!.clips[1].visible)
            val actual = HighQualityFramePlan.build(compiled, fps).frames.filter { it.clipIndex == 1 }
            assertEquals(before.size - cut, actual.size)
            before.drop(cut).zip(actual).forEach { (old, retained) ->
                assertEquals(old.sourceTimeUs, retained.sourceTimeUs)
                assertEquals(old.clipProgress, retained.clipProgress, 0f)
                assertEquals(old.transform, retained.transform)
                assertEquals(old.redBias, retained.redBias, 0f)
            }
        }
    }

    // Catches manual prep writing only a payload window, with the common offset left at zero.
    @Test fun manualTrimCommitUndoRedoAndExtensionUseOneCanonicalOffset() {
        for (fps in listOf(30, 60)) {
            val base = EditableMontageCompilerTest.imported(ManualMontageFixtures.generatedGraph(), fps)
            val cut = if (fps == 30) 9L else 18L
            val candidate = prepared(base, TimelineCommand.Trim("A1", ClipEdge.START, cut))
            assertEquals(cut.toInt(), candidate.clips.first().originalFrameOffset)
            assertEquals(1L, candidate.id)
            assertEquals(2L, base.shared.nextRevisionId)
            val committed = commit(base, candidate)
            assertEquals(2L, committed.shared.current.id)
            assertEquals(1L, committed.shared.current.parentId)
            assertEquals(3L, committed.shared.nextRevisionId)
            assertFalse(committed.shared.current.restoresAutomaticSources)
            val undo = committed.copy(shared = HybridEditCommands.apply(committed.shared, ProjectCommand.Undo))
            assertSame(base.shared.current, undo.shared.current)
            assertEquals(3L, undo.shared.nextRevisionId)
            val redo = undo.copy(shared = HybridEditCommands.apply(undo.shared, ProjectCommand.Redo))
            assertSame(committed.shared.current, redo.shared.current)
            assertEquals(3L, redo.shared.nextRevisionId)
            assertEquals(HighQualityFramePlan.build(EditableMontageCompiler.compile(committed), fps),
                HighQualityFramePlan.build(EditableMontageCompiler.compile(redo), fps))
            val extended = prepared(redo, TimelineCommand.Trim("A1", ClipEdge.START, -cut))
            assertEquals(-cut.toInt(), extended.clips.first().originalFrameOffset)
            // Extend the measured dense edge, rather than guessing an ideal FPS slope.
            assertEquals(if (fps == 30) 700_003L else 699_994L, extended.clips.first().sourceMap.sample(0))
            val recovered = prepared(redo, TimelineCommand.Trim("A1", ClipEdge.START, 0))
            assertEquals(0, recovered.clips.first().originalFrameOffset)
            assertEquals(base.shared.current.clips.first().sourceMap.points, recovered.clips.first().sourceMap.points)
            val right = prepared(redo, TimelineCommand.Trim("A1", ClipEdge.END, 2L * fps - 1))
            assertEquals(cut.toInt(), right.clips.first().originalFrameOffset)
            val moved = prepared(redo, TimelineCommand.Move("A1", 2))
            assertEquals(cut.toInt(), moved.clips.last().originalFrameOffset)
            val transition = prepared(redo, TimelineCommand.SetTransition("B", MontageGraph.Transition.HARD_CUT))
            assertEquals(cut.toInt(), transition.clips.first().originalFrameOffset)
            val flash = prepared(redo, TimelineCommand.SetFlashEnabled("flash-B", false))
            assertEquals(cut.toInt(), flash.clips.first().originalFrameOffset)
            val reset = MontageTimelineEditor.prepare(redo, TimelineCommand.RestoreBaseline) as TimelinePreparation.CoreRestoreMontage
            val restored = redo.copy(shared = HybridEditCommands.apply(redo.shared, reset.command))
            assertEquals(0, restored.shared.current.clips.first().originalFrameOffset)
            assertTrue(restored.shared.current.restoresAutomaticSources)
            assertEquals(redo.shared.nextRevisionId, restored.shared.current.id)
            assertEquals(redo.shared.current, HybridEditCommands.apply(restored.shared, ProjectCommand.Undo).current)
            assertSame(restored.shared, HybridEditCommands.apply(restored.shared, reset.command))
        }
    }

    // Catches choosing indexOfFirst(assetId) when one physical video has distinct selections.
    @Test fun repeatedAndUnusedSelectedSourcesResolveThroughDurableIndices() {
        for (fps in listOf(30, 60)) {
            val base = EditableMontageCompilerTest.imported(ManualMontageFixtures.generatedGraph(), fps)
            val physical = base.shared.assets.filter { it.kind == ProjectAsset.Kind.VIDEO }.map { inspected(it) }
            val unused = inspected(physical[0].copy(id = "unused", fileName = "unused.mp4"))
            val selections = listOf(selection("first", "a"), selection("second", "b"),
                selection("repeat", "a"), selection("unused-selection", "unused"))
            val original = base.shared.original.copy(clips = base.shared.original.clips.map { clip ->
                if (clip.id == "A2") clip.copy(original = clip.original.copy(sourceIndex = 2)) else clip
            }, graph = base.shared.original.graph.copy(clips = base.shared.original.graph.clips.map { clip ->
                if (clip.id == "A2") clip.copy(sourceIndex = 2) else clip
            }))
            val shared = base.shared.copy(assets = physical + unused + base.music, original = original,
                current = original, selectedVideos = selections)
            val adapter = base.copy(shared = shared)
            assertEquals(listOf("a", "b", "a", "unused"), adapter.sources.map { it.id })
            val moved = commit(adapter, prepared(adapter, TimelineCommand.Move("B", 0)))
            assertEquals(listOf(1, 0, 2), moved.current.clips.map { it.sourceIndex })
            assertEquals(listOf(1, 0, 2), EditableMontageCompiler.compile(moved).clips.map { it.sourceIndex })
            assertEquals(selections, moved.shared.selectedVideos)
            assertEquals(4, moved.sources.size)
            assertEquals(4, moved.shared.assets.size)
            val reset = MontageTimelineEditor.prepare(moved, TimelineCommand.RestoreBaseline) as TimelinePreparation.CoreRestoreMontage
            val restored = moved.copy(shared = HybridEditCommands.apply(moved.shared, reset.command))
            assertEquals(listOf(0, 1, 2), EditableMontageCompiler.compile(restored).clips.map { it.sourceIndex })
            assertEquals(selections, restored.shared.selectedVideos)
            assertEquals(shared.assets, restored.shared.assets)
        }
    }

    private fun inspected(asset: ProjectAsset) = asset.copy(contentHash = "a".repeat(64),
        videoMetadata = VideoSourceMetadata(SourceGeometry(1920, 1080, 0, 1f), 1234, "video/avc", 3, true))
    private fun video(id: String) = inspected(ProjectAsset(id, "$id.mp4", ProjectAsset.Kind.VIDEO, 10_000_000, "fixture"))
    private fun import(graph: MontageGraph, fps: Int, sources: List<ProjectAsset>, selections: List<SelectedVideo>?) =
        EditableMontageImporter.create("selected-fixture", graph, MontageStyleCatalog.Recipe.DUALITY_LOOP,
            ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000), sources,
            ProjectAsset("score", "score.wav", ProjectAsset.Kind.AUDIO, 10_000_000, "score-hash"), null, selections)
    private fun selection(id: String, asset: String) = SelectedVideo(SourceId(id), asset, SourceOwnership.IMPORTED, displayName = id)
    private fun prepared(project: EditableMontageProject, command: TimelineCommand): HybridRevision =
        MontageTimelineEditor.prepare(project, command).let {
            assertTrue(it.toString(), it is TimelinePreparation.Prepared)
            (it as TimelinePreparation.Prepared).candidate
        }
    private fun commit(project: EditableMontageProject, candidate: HybridRevision) =
        project.copy(shared = HybridEditCommands.apply(project.shared, ProjectCommand.CommitRevision(candidate)))
}
