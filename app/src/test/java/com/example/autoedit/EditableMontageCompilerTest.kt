package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class EditableMontageCompilerTest {
    @get:Rule val temporary = TemporaryFolder()
    // Catches the HOLD sampler returning the penultimate value beyond its terminal key.
    @Test fun extendedClipHoldsTerminalTransformAndGradeKeysWithoutChangingLegacyNoOp() {
        val base = ManualMontageFixtures.generatedGraph()
        val clip = base.clips.first().copy(sourceEndMs = 2200, outputDurationMs = 1200)
        val tracks = listOf(
            ParameterTrack("terminal-scale", ParameterTargets.clip(clip.id, "transform.scale"), ParameterTrack.ValueType.SCALAR,
                listOf(ParameterTrack.Keyframe(0, listOf(1f), ParameterTrack.Interpolation.HOLD),
                    ParameterTrack.Keyframe(1_000_000, listOf(2f)))),
            ParameterTrack("terminal-grade", ParameterTargets.clip(clip.id, "grade.rgbaBias"), ParameterTrack.ValueType.VEC4,
                listOf(ParameterTrack.Keyframe(0, listOf(.1f, .2f, .3f, .4f), ParameterTrack.Interpolation.HOLD),
                    ParameterTrack.Keyframe(1_000_000, listOf(.5f, .6f, .7f, .8f)))))
        val graph = base.copy(clips = listOf(clip), outputDurationMs = 1200, parameterTracks = tracks, overlays = emptyList())
        for (fps in listOf(30, 60)) {
            val project = imported(graph, fps)
            val noOp = HighQualityFramePlan.build(EditableMontageCompiler.compile(project), fps)
            assertEquals(HighQualityFramePlan.build(graph, fps), noOp)
            assertEquals(1f, noOp.frames.first { it.outputTimeUs > 1_000_000 }.transform.scale, 0f)

            val current = project.shared.current
            val state = current.graph.manualMontageState!!.clips.single()
            val extended = state.copy(visible = FrameRange(0, state.visible.endExclusive + 3))
            val candidate = current.copy(clips = listOf(current.clips.single().copy(
                span = FrameSpan(0, extended.visible.count.toInt()),
                sourceMap = project.current.clips.single().timeMap.toSourceTimeMap(extended.visible))),
                graph = current.graph.copy(manualMontageState = current.graph.manualMontageState!!.copy(clips = listOf(extended))))
            val frames = HighQualityFramePlan.build(EditableMontageCompiler.compile(project, candidate), fps).frames
            assertEquals(2f, frames.single { it.outputTimeUs == 1_000_000L }.transform.scale, 0f)
            val beyondOriginalEnd = frames.filter { it.outputTimeUs >= 1_200_000L }
            assertEquals(3, beyondOriginalEnd.size)
            beyondOriginalEnd.forEach { frame ->
                assertEquals("terminal scale at ${frame.outputTimeUs}", 2f, frame.transform.scale, 0f)
                assertEquals(listOf(.5f, .6f, .7f, .8f), listOf(frame.redBias, frame.greenBias, frame.blueBias, frame.exposureBias))
            }
        }
    }

    @Test fun sameIdCandidateCompilesBeforeSharedAllocation() {
        val project = imported(ManualMontageFixtures.generatedGraph())
        val shared = project.shared
        var cursor = 0
        val clips = listOf(1, 0, 2).map { index -> shared.current.clips[index].let { clip ->
            clip.copy(span = FrameSpan(cursor, cursor + clip.span.length).also { cursor = it.endExclusive })
        } }
        val candidate = shared.current.copy(clips = clips)
        val graph = EditableMontageCompiler.compile(project, candidate)
        assertEquals(listOf(1, 0, 0), graph.clips.map { it.sourceIndex })
        assertEquals(.1f, HighQualityFramePlan.build(graph).frames.first().redBias, 0f)
        assertEquals(1L, candidate.id)
        assertSame(shared.original, shared.current)
        assertEquals(listOf(0, 1, 0), shared.current.clips.map { it.original.sourceIndex })
        assertEquals(2L, shared.nextRevisionId)
        assertTrue(shared.undo.isEmpty() && shared.redo.isEmpty())
    }

    // Catches schedule rounding, synthetic v1 tracks, lost authored clocks and recipe replacement.
    @Test fun unmodifiedImportIsAnExactRoundTrip() {
        for (fps in listOf(30, 60)) for (version in listOf(1, 2, 3)) {
            val graph = fractionalGraph().let { if (version == 1) it.copy(version = 1, parameterTracks = emptyList())
                else it.copy(version = version, metadata = it.metadata.copy(schemaVersion = version)) }
            val project = imported(graph, fps)
            val compiled = EditableMontageCompiler.compile(project)
            assertEquals(graph, compiled.copy(manualMontageState = null))
            assertEquals(HighQualityFramePlan.build(graph, fps), HighQualityFramePlan.build(compiled, fps))
            assertEquals(graph.parameterTracks, compiled.parameterTracks)
            assertEquals("fractional-winner", compiled.metadata.generator)
        }
    }

    @Test fun sharedMusicGainCannotSilentlyChangeOnImport() {
        for (fps in listOf(30, 60)) {
            val graph = fractionalGraph().copy(audioTrack = MontageGraph.AudioTrack("selected-music", 2f))
            val project = imported(graph, fps)
            assertEquals(2f, project.shared.current.music.gain, 0f)
            val compiled = EditableMontageCompiler.compile(project)
            assertEquals(graph.audioTrack, compiled.audioTrack)
            assertEquals(HighQualityFramePlan.build(graph, fps), HighQualityFramePlan.build(compiled, fps))
            val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
            val encoded = codec.encode(project.shared)
            assertEquals(4, java.nio.ByteBuffer.wrap(encoded).getInt(4))
            assertEquals(3, MontageGraph.CURRENT_VERSION)
            val decoded = codec.decode(encoded)
            assertEquals(3, decoded.current.graph.version)
            assertEquals(3, decoded.current.graph.metadata.schemaVersion)
            assertEquals(project.shared.current.music, decoded.current.music)
            // Manual timing/phase payload persistence is a separate owner A2 gate.
        }
    }

    // Catches sourceIndex inferred from clip order and grade evaluated on the new output clock.
    @Test fun reorderedClipKeepsSourceAndGrade() {
        val project = imported(ManualMontageFixtures.generatedGraph())
        val changed = revised(project, listOf(1, 0, 2))
        val graph = EditableMontageCompiler.compile(changed)
        assertEquals(listOf(1, 0, 0), graph.clips.map { it.sourceIndex })
        val frame = HighQualityFramePlan.build(graph).frames.first()
        assertEquals(1, frame.sourceIndex)
        assertEquals(.1f, frame.redBias, 0f)
        assertEquals(4_000_000L, frame.sourceTimeUs)
    }

    // Catches frame-normalized motion and ms rounding after a trim at fractional legacy boundaries.
    @Test fun fractionalTrimRetainsEverySourceMotionAndTrackSample() {
        for (fps in listOf(30, 60)) {
            val original = fractionalGraph()
            val baseline = HighQualityFramePlan.build(original, fps).frames.filter { it.clipIndex == 1 }
            val project = imported(original, fps)
            val graph = EditableMontageCompiler.compile(revised(project, listOf(1, 0), trimClip = "B", trimStart = 1))
            val actual = HighQualityFramePlan.build(graph, fps).frames.filter { it.clipIndex == 0 }
            assertEquals(baseline.size - 1, actual.size)
            baseline.drop(1).zip(actual).forEach { (before, after) ->
                assertEquals(before.sourceTimeUs, after.sourceTimeUs)
                assertEquals(before.clipProgress, after.clipProgress, 0f)
                assertEquals(before.transform, after.transform)
                assertEquals(before.redBias, after.redBias, 0f)
            }
        }
    }

    // Catches sharing PTS-only attachment lookup between different source files.
    @Test fun samePtsInTwoSourcesKeepDifferentMasks() {
        val base = fractionalGraph()
        val graph = base.copy(sourceAttachments = listOf(SourceAttachments(0, masks(.2f)), SourceAttachments(1, masks(.9f))))
        val frames = HighQualityFramePlan.build(EditableMontageCompiler.compile(imported(graph))).frames
        assertEquals(.2f, frames.first { it.sourceIndex == 0 }.attachments!!.mask!!.confidence, 0f)
        assertEquals(.9f, frames.first { it.sourceIndex == 1 }.attachments!!.mask!!.confidence, 0f)
    }

    // Catches sparse endpoint slicing that changes interior values by one microsecond.
    @Test fun canonicalSparseSlipIsMaterializedBeforeTrim() {
        val project = imported(fractionalGraph())
        val clip = project.shared.current.clips.first()
        val sparse = SourceTimeMap(listOf(SourceTimeMap.Point(0, 100), SourceTimeMap.Point(clip.span.length, 1000)))
        val slipped = project.shared.current.copy(id = 2, parentId = 1,
            clips = project.shared.current.clips.map { if (it.id == clip.id) it.copy(sourceMap = sparse) else it })
        val adapter = project.copy(shared = project.shared.copy(current = slipped, nextRevisionId = 3))
        val trimmed = revised(adapter, listOf(0, 1), trimClip = clip.id, trimStart = 1)
        val frames = HighQualityFramePlan.build(EditableMontageCompiler.compile(trimmed)).frames.filter { it.clipIndex == 0 }
        assertEquals(listOf(124L, 149L, 173L), frames.take(3).map { it.sourceTimeUs })
    }

    // Catches snapping a removed secondary role to a surviving edge or freezing primary decode PTS.
    @Test fun trimmedSecondaryRoleDoesNotFreezePrimary() {
        val base = ManualMontageFixtures.generatedGraph()
        val overlay = MontageGraph.Overlay("role", 0, 1000, "temporal-role",
            overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opacity = 1f, secondaryTimelineStartMs = 2000)
        val project = imported(base.copy(overlays = listOf(overlay), sourceAttachments =
            listOf(SourceAttachments(0, masks(.2f)), SourceAttachments(1, masks(.9f)))))
        val frames = HighQualityFramePlan.build(EditableMontageCompiler.compile(
            revised(project, listOf(0, 1, 2), trimClip = "B", trimStart = 15))).frames.take(30)
        assertTrue(frames.map { it.sourceTimeUs }.zipWithNext().all { (a, b) -> b > a })
        assertTrue(frames.take(15).all { it.secondarySourceTimeUs == null && it.layer.opacity == 0f })
        val live = frames[20]
        assertEquals(1, live.secondarySourceIndex)
        assertEquals(4_666_667L, live.secondarySourceTimeUs)
        assertEquals(.9f, live.secondaryAttachments!!.mask!!.confidence, 0f)
    }

    @Test fun removedSecondaryLayerRevealsSurvivingLayer() {
        val base = ManualMontageFixtures.generatedGraph()
        val role = MontageGraph.Overlay("role", 0, 1000, "role", overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
            opacity = 1f, secondaryTimelineStartMs = 2000)
        val glow = MontageGraph.Overlay("under", 0, 1000, "glow", overlayKind = MontageGraph.OverlayKind.GLOW, opacity = .1f)
        val project = imported(base.copy(overlays = listOf(role, glow)))
        val plan = HighQualityFramePlan.build(EditableMontageCompiler.compile(revised(project, listOf(0, 1, 2), "B", 15)))
        assertEquals(MontageGraph.OverlayKind.GLOW, plan.frames[6].layer.kind)
        assertTrue(plan.frames[6].layer.opacity > 0f)
        assertNull(plan.frames[6].secondarySourceTimeUs)
    }

    @Test fun extendedClipCannotStealAnotherOriginalSecondaryRole() {
        val base = ManualMontageFixtures.generatedGraph()
        val role = MontageGraph.Overlay("role", 0, 1000, "authored-measured-step",
            overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opacity = 1f, secondaryTimelineStartMs = 1967)
        val project = imported(base.copy(overlays = listOf(role)))
        val changed = revised(project, listOf(1, 0, 2), "B", -1)
        val frames = HighQualityFramePlan.build(EditableMontageCompiler.compile(changed)).frames
        val frame = frames.first { it.clipIndex == 1 }
        assertEquals(0, frame.secondarySourceIndex)
        assertEquals(2_966_667L, frame.secondarySourceTimeUs)
    }

    // Catches a mutable metadata list invalidating a validated adapter/compiler snapshot.
    @Test fun compilerSnapshotOwnsTimingMetadataAndLocalTracks() {
        val project = imported(fractionalGraph())
        val state = project.shared.current.graph.manualMontageState!!
        val mutableTracks = state.clips[1].localTracks.toMutableList()
        val mutableStates = state.clips.map { if (it.clipId == "B") it.copy(localTracks = mutableTracks) else it }.toMutableList()
        val changed = revised(project, listOf(1, 0))
        val revision = changed.shared.current.copy(graph = changed.shared.current.graph.copy(
            manualMontageState = ManualMontageState(mutableStates, state.effects)))
        val snapshot = EditableMontageCompiler.compile(changed.copy(shared = changed.shared.copy(current = revision)))
        val before = HighQualityFramePlan.build(snapshot)
        mutableTracks.clear(); mutableStates.clear()
        assertEquals(before, HighQualityFramePlan.build(snapshot))
        assertEquals(2, snapshot.manualMontageState!!.clips.size)
        val timingClips = snapshot.editableTiming!!.clips.toMutableList()
        val timing = EditableFrameTiming(30, timingClips)
        timingClips.clear()
        assertEquals(before.frames.size.toLong(), timing.frameCount)
    }

    companion object {
        internal fun imported(graph: MontageGraph, fps: Int = 30): EditableMontageProject = EditableMontageImporter.create("fixture", graph,
            MontageStyleCatalog.Recipe.DUALITY_LOOP, ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000),
            listOf(asset("a", false), asset("b", false)), asset("score", true), null)

        internal fun revised(project: EditableMontageProject, order: List<Int>, trimClip: String? = null, trimStart: Long = 0): EditableMontageProject {
            val current = project.shared.current
            var cursor = 0
            val states = current.graph.manualMontageState!!.clips.map { state ->
                if (state.clipId == trimClip) state.copy(visible = FrameRange(state.visible.start + trimStart, state.visible.endExclusive)) else state
            }
            val clips = order.map { index ->
                val clip = current.clips[index]
                val state = states.single { it.clipId == clip.id }
                val projected = project.current.clips.single { it.id == clip.id }
                clip.copy(span = FrameSpan(cursor, cursor + state.visible.count.toInt()).also { cursor = it.endExclusive },
                    sourceMap = projected.timeMap.toSourceTimeMap(state.visible), originalFrameOffset = Math.toIntExact(state.visible.start))
            }
            val next = current.copy(id = project.shared.nextRevisionId, parentId = current.id, clips = clips,
                graph = current.graph.copy(manualMontageState = current.graph.manualMontageState!!.copy(clips = states)))
            return project.copy(shared = project.shared.copy(current = next, nextRevisionId = next.id + 1))
        }

        fun fractionalGraph(): MontageGraph {
            val template = ManualMontageFixtures.generatedGraph()
            val clips = template.clips.take(2).map { it.copy(sourceStartMs = 0, sourceEndMs = 1201, outputDurationMs = 1201,
                transform = MontageGraph.ClipTransform(listOf(MontageGraph.ClipTransform.Keyframe(0f, 1f),
                    MontageGraph.ClipTransform.Keyframe(1f, 1.8f, .2f)))) }
            return template.copy(clips = clips, outputDurationMs = 2402, overlays = emptyList(),
                metadata = NleProjectMetadata(generator = "fractional-winner"), parameterTracks = listOf(
                    ParameterTrack("grade", ParameterTargets.clip("B", "grade.rgbaBias"), ParameterTrack.ValueType.VEC4,
                        listOf(ParameterTrack.Keyframe(1_190_000, listOf(.1f, 0f, 0f, 0f), ParameterTrack.Interpolation.LINEAR),
                            ParameterTrack.Keyframe(2_500_000, listOf(.9f, 0f, 0f, 0f))))))
        }

        private fun asset(id: String, audio: Boolean) = ProjectAsset(id, "$id.mp4",
            if (audio) ProjectAsset.Kind.AUDIO else ProjectAsset.Kind.VIDEO, 10_000_000, "fixture-hash")
        private fun masks(confidence: Float) = FrameAttachmentTimeline((0L..10_000_000L step 100_000L).map {
            FrameAttachments(it, mask = FrameAttachments.Plane(1, 1, listOf(1f), confidence)) })
    }
}
