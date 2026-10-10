package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ClipTimeMappingTest {
    @Test fun oneOutputFrameAt30And60Fps() {
        assertEquals(33_333L, frameTimeUs(1, 30))
        assertEquals(16_667L, frameTimeUs(1, 60))
        assertEquals(300_000L, frameTimeUs(9, 30))
        assertEquals(-33_333L, frameTimeUs(-1, 30))
        assertEquals(-16_667L, frameTimeUs(-1, 60))
        assertEquals(1_000_000L, frameTimeUs(30, 30))
        assertEquals(1_000_000L, frameTimeUs(60, 60))
    }

    @Test fun trimKeepsOriginalRampSamples() {
        val map = ClipTimeMapping(30, 1_000_000L, longArrayOf(100_000, 118_000, 148_000, 193_000, 221_000))
        val plan = HighQualityFramePlan.build(timedGraph(map, FrameRange(1, 4)), 30)
        assertArrayEquals(longArrayOf(118_000, 148_000, 193_000), plan.frames.map { it.sourceTimeUs }.toLongArray())
        assertEquals(listOf(.25f, .5f, .75f), plan.frames.map { it.clipProgress })
        assertEquals(listOf(1.15625f, 1.5f, 1.84375f), plan.frames.map { it.transform.scale })
        assertEquals(100_000L, plan.durationUs)
    }

    @Test fun vfrAndRepeatedPtsAreNotResampled() {
        val map = ClipTimeMapping(60, 1_000_000L, longArrayOf(0, 18_000, 18_000, 63_000, 91_000))
        val plan = HighQualityFramePlan.build(timedGraph(map, FrameRange(1, 4)), 60)
        assertArrayEquals(longArrayOf(18_000, 18_000, 63_000), plan.frames.map { it.sourceTimeUs }.toLongArray())
        assertArrayEquals(longArrayOf(0, 16_667, 33_333), plan.frames.map { it.outputTimeUs }.toLongArray())
        assertEquals(50_000L, plan.durationUs)
    }

    @Test fun legacyGraphsKeepTheirSchedule() {
        val original = ManualMontageFixtures.generatedGraph()
        val moving = original.clips.first().copy(
            transform = MontageGraph.ClipTransform(listOf(
                MontageGraph.ClipTransform.Keyframe(0f, 1f), MontageGraph.ClipTransform.Keyframe(1f, 2f))),
            speedRamp = MontageGraph.SpeedRamp(listOf(
                MontageGraph.SpeedRamp.Keyframe(0f, .6f), MontageGraph.SpeedRamp.Keyframe(.4f, 2f),
                MontageGraph.SpeedRamp.Keyframe(1f, .8f))))
        for (version in listOf(1, 2)) {
            val legacy = original.copy(clips = listOf(moving) + original.clips.drop(1),
                parameterTracks = emptyList(), version = version,
                metadata = NleProjectMetadata(schemaVersion = version, generator = "manual-contract-fixture"))
            val migrated = MontageGraphMigration.toCurrent(legacy)
            assertEquals(3, migrated.version)
            assertEquals(3, migrated.metadata.schemaVersion)
            assertEquals("manual-contract-fixture", migrated.metadata.generator)
            assertTrue(migrated.parameterTracks.isEmpty())
            for (fps in listOf(6, 30, 60)) {
                assertEquals(HighQualityFramePlan.build(legacy, fps), HighQualityFramePlan.build(migrated, fps))
            }
            assertSame(migrated, MontageGraphMigration.toCurrent(migrated))
        }
        val v2 = original.copy(version = 2, metadata = original.metadata.copy(schemaVersion = 2))
        assertEquals(HighQualityFramePlan.build(v2), HighQualityFramePlan.build(MontageGraphMigration.toCurrent(v2)))
    }

    @Test fun extensionUsesEachEdgeSpeedWithoutClamping() {
        val map = ClipTimeMapping(30, 1_000_000L, longArrayOf(100_000, 120_000, 160_000, 210_000))
        assertEquals(80_000L, map.sourceTimeUs(-1))
        assertEquals(260_000L, map.sourceTimeUs(4))
        assertEquals(-20_000L, map.sourceTimeUs(-6))
        assertEquals(1_060_000L, map.sourceTimeUs(20))
        assertThrows(IllegalArgumentException::class.java) { timedGraph(map, FrameRange(-6, 2)) }
        assertThrows(IllegalArgumentException::class.java) { timedGraph(map, FrameRange(0, 20)) }
        val extended = HighQualityFramePlan.build(timedGraph(map, FrameRange(-1, 5)), 30)
        assertArrayEquals(longArrayOf(80_000, 100_000, 120_000, 160_000, 210_000, 260_000),
            extended.frames.map { it.sourceTimeUs }.toLongArray())
    }

    @Test fun boundariesUseCumulativeFrameCounts() {
        for (fps in listOf(30, 60)) {
            val base = ManualMontageFixtures.generatedGraph()
            val clips = base.clips.map { it.copy(outputDurationMs = 1) }
            val timing = EditableFrameTiming(fps, clips.mapIndexed { index, clip ->
                EditableClipTiming(clip.id, ClipTimeMapping(fps, 10_000_000L,
                    longArrayOf(123_456L + index, 123_500L + index)), FrameRange(0, 1),
                    index.toLong(), index + 1L)
            })
            val plan = HighQualityFramePlan.build(base.copy(clips = clips, outputDurationMs = 3,
                editableTiming = timing), fps)
            assertEquals(listOf(0, 1, 2), plan.frames.map { it.clipIndex })
            assertArrayEquals(if (fps == 30) longArrayOf(0, 33_333, 66_667) else longArrayOf(0, 16_667, 33_333),
                plan.frames.map { it.outputTimeUs }.toLongArray())
            assertArrayEquals(longArrayOf(123_456, 123_457, 123_458), plan.frames.map { it.sourceTimeUs }.toLongArray())
            assertEquals(if (fps == 30) 100_000L else 50_000L, plan.durationUs)
            assertEquals(listOf(0, 1, 0), plan.frames.map { it.sourceIndex })
        }
    }

    @Test fun mapOwnsItsSamplesAndHasValueEquality() {
        val input = longArrayOf(0, 33_000)
        val map = ClipTimeMapping(30, 1_000_000L, input)
        input[0] = 900_000L
        map.sourceUsByFrame[0] = 800_000L
        assertEquals(0L, map.sourceTimeUs(0))
        assertEquals(ClipTimeMapping(30, 1_000_000L, longArrayOf(0, 33_000)), map)
        assertThrows(ArithmeticException::class.java) { map.sourceTimeUs(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { frameTimeUs(Long.MAX_VALUE, 60) }
    }

    @Test fun rejectsInvalidMapsAndRanges() {
        for (fps in listOf(0, 29, 120)) {
            assertThrows(IllegalArgumentException::class.java) { ClipTimeMapping(fps, 100, longArrayOf(0, 1)) }
            assertThrows(IllegalArgumentException::class.java) { ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000) }
        }
        for (samples in listOf(longArrayOf(0), longArrayOf(2, 1), longArrayOf(-1, 1), longArrayOf(0, 101))) {
            assertThrows(IllegalArgumentException::class.java) { ClipTimeMapping(30, 100, samples) }
        }
        assertThrows(IllegalArgumentException::class.java) { FrameRange(1, 1) }
        assertThrows(IllegalArgumentException::class.java) { FrameRange(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { TimelineRange(1, 1) }
    }

    @Test fun graphRejectsTimingMismatchAndGaps() {
        val project = ManualMontageFixtures.linearProject()
        val entries = project.current.clips.mapIndexed { index, clip ->
            EditableClipTiming(clip.id, clip.timeMap, clip.visible, index * 60L, (index + 1) * 60L)
        }
        val graph = project.originalGraph.copy(editableTiming = EditableFrameTiming(30, entries))
        assertThrows(IllegalArgumentException::class.java) { HighQualityFramePlan.build(graph, 60) }
        assertThrows(IllegalArgumentException::class.java) { graph.copy(editableTiming = EditableFrameTiming(30, entries.reversed())) }
        assertThrows(IllegalArgumentException::class.java) { EditableFrameTiming(30, entries.drop(1)) }
        assertThrows(IllegalArgumentException::class.java) { EditableFrameTiming(60, entries) }
        assertThrows(IllegalArgumentException::class.java) { entries.first().copy(endFrameExclusive = 59) }
    }

    @Test fun adapterMaterializesSparseCanonicalMapBeforeTrim() {
        val fixture = ManualMontageFixtures.linearProject()
        val sparse = SourceTimeMap(listOf(SourceTimeMap.Point(0, 1_000_000),
            SourceTimeMap.Point(3, 1_100_001), SourceTimeMap.Point(6, 1_133_335)))
        val clip = fixture.shared.original.clips.first().copy(span = FrameSpan(0, 6), sourceMap = sparse)
        val revision = fixture.shared.original.copy(clips = listOf(clip),
            graph = fixture.originalGraph.copy(clips = listOf(clip.original), outputDurationMs = 2_000,
                manualMontageState = null))
        val shared = fixture.shared.copy(original = revision, current = revision)
        val adapter = EditableMontageProject(shared, fixture.export)
        val map = adapter.current.clips.single().timeMap
        assertArrayEquals(longArrayOf(1_000_000, 1_033_334, 1_066_667, 1_100_001, 1_111_112, 1_122_224, 1_133_335),
            map.sourceUsByFrame)
        val retained = map.toSourceTimeMap(FrameRange(1, 5))
        assertEquals(listOf(1_033_334L, 1_066_667L, 1_100_001L, 1_111_112L, 1_122_224L),
            retained.points.map { it.sourceTimeUs })
        // Slicing sparse endpoints alone can change the interior sample by one microsecond.
        assertEquals(1_066_668L, sparse.slice(1, 5).sample(1))
    }

    @Test fun adapterUsesSharedAssetsIdsAndRevisionWithoutAllocatingHistory() {
        val fixture = ManualMontageFixtures.linearProject()
        val shared = fixture.shared
        val reordered = shared.current.copy(id = 2, parentId = 1,
            clips = listOf(shared.current.clips[1].copy(span = FrameSpan(0, 60)),
                shared.current.clips[0].copy(span = FrameSpan(60, 120)), shared.current.clips[2]))
        val withHistory = shared.copy(current = reordered, nextRevisionId = 3, undo = listOf(shared.current))
        val adapter = EditableMontageProject(withHistory, fixture.export)
        assertSame(withHistory, adapter.shared)
        assertEquals(2L, adapter.current.number)
        assertEquals(1L, adapter.baseline.number)
        assertEquals(listOf("B", "A1", "A2"), adapter.current.clips.map { it.id })
        assertEquals(listOf(1, 0, 0), adapter.current.clips.map { it.sourceIndex })
        assertSame(shared.assets[0], adapter.sources[0])
        assertSame(shared.assets[2], adapter.music)
        assertEquals(listOf(1L), adapter.shared.undo.map { it.id })
        assertEquals(3L, adapter.shared.nextRevisionId)
        assertTrue(adapter.shared.redo.isEmpty())
        assertTrue(adapter.shared.exports.isEmpty())
    }

    @Test fun adapterRejectsMismatchedManualPayloadAndSourceIdentity() {
        val fixture = ManualMontageFixtures.linearProject()
        val payload = fixture.shared.current.graph.manualMontageState!!
        val first = payload.clips.first()
        for (invalid in listOf(first.copy(clipId = "missing"),
            first.copy(visible = FrameRange(0, 59)), first.copy(originFrameCount = 59))) {
            val graph = fixture.shared.current.graph.copy(manualMontageState =
                payload.copy(clips = listOf(invalid) + payload.clips.drop(1)))
            val current = fixture.shared.current.copy(id = 2, parentId = 1, graph = graph)
            assertThrows(IllegalArgumentException::class.java) {
                EditableMontageProject(fixture.shared.copy(current = current, nextRevisionId = 3), fixture.export)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditableMontageProject(fixture.shared.copy(assets = listOf(fixture.sources[1], fixture.sources[0], fixture.music)), fixture.export)
        }
    }

    @Test fun adapterKeepsDenseRepeatedPtsAndSignedOriginalMotionPhase() {
        val fixture = ManualMontageFixtures.linearProject()
        val sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 0), SourceTimeMap.Point(1, 18_000),
            SourceTimeMap.Point(2, 18_000), SourceTimeMap.Point(3, 63_000), SourceTimeMap.Point(4, 91_000)))
        val originalClip = fixture.shared.original.clips.first().copy(span = FrameSpan(0, 4), sourceMap = sourceMap)
        val originGraph = fixture.originalGraph.copy(clips = listOf(originalClip.original), outputDurationMs = 2_000,
            manualMontageState = null)
        val original = fixture.shared.original.copy(clips = listOf(originalClip), graph = originGraph)
        val trimmed = originalClip.copy(span = FrameSpan(0, 3), sourceMap = sourceMap.slice(1, 4))
        val state = ManualMontageState(listOf(ManualClipState("A1", FrameRange(1, 4), 4, emptyList())), emptyList())
        val current = original.copy(id = 2, parentId = 1, clips = listOf(trimmed),
            graph = originGraph.copy(manualMontageState = state))
        val adapter = EditableMontageProject(fixture.shared.copy(original = original, current = current, nextRevisionId = 3), fixture.export)
        val projected = adapter.current.clips.single()
        assertEquals(FrameRange(1, 4), projected.visible)
        assertEquals(4L, projected.originFrameCount)
        assertArrayEquals(longArrayOf(18_000, 18_000, 63_000),
            (1L..3L).map { projected.timeMap.sourceTimeUs(it) }.toLongArray())
        assertEquals(91_000L, projected.timeMap.sourceTimeUs(4))
        val plan = HighQualityFramePlan.build(originGraph.copy(editableTiming = EditableFrameTiming(30, listOf(
            EditableClipTiming(projected.id, projected.timeMap, projected.visible, 0, 3, projected.originFrameCount)))), 30)
        assertEquals(listOf(.25f, .5f, .75f), plan.frames.map { it.clipProgress })
        assertArrayEquals(longArrayOf(18_000, 18_000, 63_000), plan.frames.map { it.sourceTimeUs }.toLongArray())
    }

    @Test fun adapterProjectsDeliberateSharedSlipExactly() {
        val fixture = ManualMontageFixtures.linearProject()
        val shifted = SourceTimeMap(fixture.shared.current.clips.first().sourceMap.points.map {
            it.copy(sourceTimeUs = it.sourceTimeUs + 123_457L)
        })
        val current = fixture.shared.current.copy(id = 2, parentId = 1,
            clips = listOf(fixture.shared.current.clips.first().copy(sourceMap = shifted)) + fixture.shared.current.clips.drop(1))
        val shared = fixture.shared.copy(current = current, nextRevisionId = 3, undo = listOf(fixture.shared.current))
        val adapter = EditableMontageProject(shared, fixture.export)
        assertEquals(1_123_457L, adapter.current.clips.first().timeMap.sourceTimeUs(0))
        assertEquals(3_123_457L, adapter.current.clips.first().timeMap.sourceTimeUs(60))
        assertSame(shifted, adapter.shared.current.clips.first().sourceMap)
        assertEquals(3L, adapter.shared.nextRevisionId)
        assertEquals(listOf(1L), adapter.shared.undo.map { it.id })
    }

    @Test fun frameClockMatchesSharedClockAtBothExportRates() {
        for (fps in listOf(30, 60)) for (frame in listOf(0, 1, 9, 30, 60, 12_345, Int.MAX_VALUE)) {
            assertEquals(ProjectClock(fps).timeUs(frame), frameTimeUs(frame.toLong(), fps))
        }
    }

    @Test fun projectRejectsDuplicateIdsAndBrokenSourceOrEffectOwners() {
        val project = ManualMontageFixtures.linearProject()
        assertEquals(listOf("A1", "B", "A2"), project.current.clips.map { it.id })
        assertEquals(listOf(0, 1, 0), project.current.clips.map { it.sourceIndex })
        assertEquals(1.25f, project.current.clips[0].localTracks.single().sample(0).single(), 0f)
        assertEquals(.1f, project.current.clips[1].localTracks.single().sample(0)[0], 0f)
        assertThrows(IllegalArgumentException::class.java) { project.current.copy(clips = listOf(project.current.clips.first(), project.current.clips.first())) }
        val flash = project.current.effects.single()
        assertThrows(IllegalArgumentException::class.java) { project.current.copy(effects = listOf(flash, flash)) }
        assertThrows(IllegalArgumentException::class.java) { project.current.copy(effects = listOf(flash.copy(anchor = EffectAnchor.Boundary("missing", 0, 100_000)))) }
        assertThrows(IllegalArgumentException::class.java) { project.copy(export = project.export.copy(fps = 60)) }
        assertThrows(IllegalArgumentException::class.java) { flash.copy(originalOverlay = null) }
        assertThrows(IllegalArgumentException::class.java) { flash.copy(phaseEnd = 0f) }
    }

    private fun timedGraph(map: ClipTimeMapping, visible: FrameRange): MontageGraph {
        val clip = ManualMontageFixtures.generatedGraph().clips.first().copy(
            transform = MontageGraph.ClipTransform(listOf(
                MontageGraph.ClipTransform.Keyframe(0f, 1f), MontageGraph.ClipTransform.Keyframe(1f, 2f))))
        return MontageGraph(10_000, clip.outputDurationMs, clips = listOf(clip),
            editableTiming = EditableFrameTiming(map.fps, listOf(
                EditableClipTiming(clip.id, map, visible, 0, visible.count))))
    }
}
