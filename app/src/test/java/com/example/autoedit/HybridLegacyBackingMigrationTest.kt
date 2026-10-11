package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridLegacyBackingMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun legacyTrimThenAuthorEdit(phase: Int = 0): HybridProject {
        val initial = editProject()
        val base = initial.withInitialClips(initial.current.clips.map { if (it.id == "right")
            it.copy(originalFrameOffset = phase) else it })
        val curve = SourceTimeMap(listOf(SourceTimeMap.Point(0, 300000), SourceTimeMap.Point(10, 600000),
            SourceTimeMap.Point(20, 1200000), SourceTimeMap.Point(30, 1500000)))
        val selected = legacyCommit(base, base.current.copy(clips = base.current.clips.map {
            if (it.id == "right") it.copy(sourceMap = curve) else it
        }))
        val trimmed = HybridEditCommands.moveCut(selected, "right", 42).withoutSourceBackings()
        return legacyCommit(trimmed, trimmed.current.copy(style = trimmed.current.style.copy(showAuthoredText = false)))
    }

    private fun assertRestored(project: HybridProject, phase: Int = 0, slip: Long = 0) {
        val restored = HybridEditCommands.moveCut(project, "right", 30)
        val clip = restored.current.clips[1]
        assertEquals(phase, clip.originalFrameOffset)
        assertEquals(listOf(300000L, 450000L, 600000L, 1200000L, 1500000L).map { it + slip },
            listOf(0, 5, 10, 20, 30).map { clip.sourceMap.sample(it) })
        assertFalse(restored.current.sourceBackings.getValue("right").automatic)
    }

    @Test fun directV5ExtensionRetainsFartherProvenNonlinearLegacyWindow() {
        assertRestored(legacyTrimThenAuthorEdit())
    }

    @Test fun authorOnlyMigrationRetainsProvenHiddenWindowThroughPruningAndReopen() {
        val legacy = legacyTrimThenAuthorEdit()
        var migrated = HybridEditCommands.setAuthoredText(legacy, true)
        repeat(51) { migrated = HybridEditCommands.setAuthoredText(migrated, !migrated.current.style.showAuthoredText) }
        val root = temporary.newFolder()
        val store = HybridProjectStore(root)
        val sources = File(store.directory(migrated.id), "sources").apply { mkdirs() }
        migrated.assets.forEach { File(sources, it.fileName).writeText("synthetic source") }
        store.create(migrated)
        assertRestored(HybridProjectStore(root).load(migrated.id))
    }

    @Test fun legacyEnvelopeRetainsAccumulatedSlipAndSignedPhase() {
        val legacy = legacyTrimThenAuthorEdit(-10)
        val slipped = legacyCommit(legacy, legacy.current.copy(clips = legacy.current.clips.map { if (it.id == "right")
            it.copy(sourceMap = SourceTimeMap(it.sourceMap.points.map { point ->
                point.copy(sourceTimeUs = point.sourceTimeUs + 123456)
            })) else it }))
        val migrated = HybridEditCommands.setAuthoredText(slipped, true)
        assertEquals(123456L, migrated.current.sourceBackings.getValue("right").slipUs)
        assertRestored(migrated, phase = -10, slip = 123456)
    }

    @Test fun nonOriginalLegacyEnvelopeKeepsRetainedEndButDoesNotInventAutomaticContinuation() {
        val base = editProject()
        val right = base.current.clips[1].copy(span = FrameSpan(30, 80), sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500000), SourceTimeMap.Point(30, 1500000), SourceTimeMap.Point(50, 2000000))))
        val last = base.current.clips[2].copy(span = FrameSpan(80, 90), originalFrameOffset = 20,
            sourceMap = SourceTimeMap((20..30).map { SourceTimeMap.Point(it - 20, base.current.clips[2].sourceMap.sample(it)) }))
        val selected = legacyCommit(base, base.current.copy(clips = listOf(base.current.clips[0], right, last)))
        val trimmed = legacyCommit(selected, selected.current.copy(clips = base.original.clips))
        val migrated = HybridEditCommands.setAuthoredText(trimmed, false)
        val restored = HybridEditCommands.moveCut(migrated, "last", 80)
        assertEquals(2000000L, restored.current.clips[1].sourceMap.sample(50))
        assertFalse(restored.current.sourceBackings.getValue("right").automatic)
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.moveCut(restored, "last", 81) }
    }

    private fun partialLegacyWindows(): HybridProject {
        fun select(project: HybridProject, vararg samples: Pair<Int, Long>): HybridProject {
            val phase = samples.first().first
            val length = samples.last().first - phase
            val right = project.current.clips[1].copy(span = FrameSpan(30, 30 + length), originalFrameOffset = phase,
                sourceMap = SourceTimeMap(samples.map { (frame, time) -> SourceTimeMap.Point(frame - phase, time) }))
            val last = project.current.clips[2].copy(span = FrameSpan(30 + length, 90), originalFrameOffset = 0,
                sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500000), SourceTimeMap.Point(60 - length, 1500000))))
            return legacyCommit(project, project.current.copy(clips = listOf(project.current.clips[0], right, last)))
        }
        var legacy = select(editProject(), 0 to 300000L, 10 to 600000L, 20 to 1200000L, 30 to 1500000L)
        legacy = select(legacy, 10 to 600000L, 20 to 1200000L, 30 to 1500000L, 40 to 2000000L)
        legacy = select(legacy, 20 to 1200000L, 30 to 1500000L, 40 to 2000000L, 50 to 2600000L)
        legacy = select(legacy, 0 to 100000L, 10 to 650000L, 20 to 1200000L, 25 to 1350000L)
        return select(legacy, 22 to 1260000L, 25 to 1350000L, 30 to 1500000L)
    }

    @Test fun partialLegacyWindowsKeepCurrentAndNearestSamplesAcrossTheWholeProvenEnvelope() {
        val migrated = HybridEditCommands.setAuthoredText(partialLegacyWindows(), false)
        val restored = HybridEditCommands.moveCut(HybridEditCommands.moveCut(migrated, "right", 8), "last", 58)
        val clip = restored.current.clips[1]
        assertEquals(0, clip.originalFrameOffset)
        assertEquals(50, clip.span.length)
        assertEquals(listOf(100000L, 650000L, 1260000L, 1500000L, 2000000L, 2600000L),
            listOf(0, 10, 22, 30, 40, 50).map { clip.sourceMap.sample(it) })
        assertFalse(restored.current.sourceBackings.getValue("right").automatic)
    }

    @Test fun partialLegacyEnvelopeKeepsPositiveSlipOutsideHiddenUpperBoundThroughPruningAndReopen() {
        assertSlippedPartialEnvelope(1000000, 35, 2750000)
    }

    @Test fun partialLegacyEnvelopeKeepsNegativeSlipOutsideHiddenLowerBoundThroughPruningAndReopen() {
        assertSlippedPartialEnvelope(-200000, 10, 450000)
    }

    private fun assertSlippedPartialEnvelope(slip: Long, hiddenPhase: Int, expectedRequest: Long) {
        var legacy = partialLegacyWindows()
        // Two legacy slips plus an unrelated edit require retaining the accumulated translation.
        repeat(2) {
            legacy = legacyCommit(legacy, legacy.current.copy(clips = legacy.current.clips.map { if (it.id == "right")
                it.copy(sourceMap = SourceTimeMap(it.sourceMap.points.map { point ->
                    point.copy(sourceTimeUs = point.sourceTimeUs + slip / 2)
                })) else it }))
        }
        legacy = legacyCommit(legacy, legacy.current.copy(textState = legacy.current.textState.copy(captionsEdited = true)))
        var migrated = HybridEditCommands.setAuthoredText(legacy, false)
        assertTrue(migrated.current.textState.captionsEdited)
        repeat(51) { migrated = HybridEditCommands.setAuthoredText(migrated, !migrated.current.style.showAuthoredText) }
        val root = temporary.newFolder()
        val store = HybridProjectStore(root)
        val sources = File(store.directory(migrated.id), "sources").apply { mkdirs() }
        migrated.assets.forEach { File(sources, it.fileName).writeText("synthetic source") }
        store.create(migrated)
        val loaded = HybridProjectStore(root).load(migrated.id)
        assertEquals(50, loaded.undo.size)
        assertFalse(loaded.undo.any { it.id <= legacy.current.id })
        val backing = loaded.current.sourceBackings.getValue("right")
        assertEquals(slip, backing.slipUs)
        assertEquals(0, backing.phaseStart)
        assertFalse(backing.automatic)
        assertEquals(listOf(100000L, 650000L, 1260000L, 1500000L, 1750000L, 2600000L),
            listOf(0, 10, 22, 30, 35, 50).map { backing.map.sample(it) })
        assertEquals(1260000 + slip, loaded.current.clips[1].sourceMap.sample(0))
        val extended = if (slip > 0) HybridEditCommands.moveCut(loaded, "last", 44)
            else HybridEditCommands.moveCut(loaded, "right", 18)
        val right = extended.current.clips[1]
        assertTrue(hiddenPhase - right.originalFrameOffset in 0 until right.span.length)
        assertEquals(expectedRequest, right.sourceMap.sample(hiddenPhase - right.originalFrameOffset))
        assertThrows(HybridEditRejected::class.java) {
            if (slip > 0) HybridEditCommands.moveCut(loaded, "last", 58)
            else HybridEditCommands.moveCut(loaded, "right", 8)
        }
        // Previously unavailable hidden requests must still exist when the slip is undone.
        val unslipped = HybridEditCommands.slipClip(loaded, "right", -slip)
        val restored = HybridEditCommands.moveCut(HybridEditCommands.moveCut(unslipped, "right", 8), "last", 58)
        assertEquals(0, restored.current.clips[1].originalFrameOffset)
        assertEquals(100000L, restored.current.clips[1].sourceMap.sample(0))
        assertEquals(2600000L, restored.current.clips[1].sourceMap.sample(50))
    }

    @Test(timeout = 5000) fun oversizedProvenEnvelopeReservesComparisonsBeforeMaterializingSamples() {
        val base = editProject(length = 600000)
        val first = legacyCommit(base, base.current.copy(clips = base.current.clips.map { if (it.id == "right")
            it.copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 1000000),
                SourceTimeMap.Point(600000, 1600000)))) else it }))
        val shifted = legacyCommit(first, first.current.copy(clips = first.current.clips.map { if (it.id == "right")
            it.copy(originalFrameOffset = 500000, sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 1500000),
                SourceTimeMap.Point(600000, 2100000)))) else it }))
        val error = assertThrows(HybridEditRejected::class.java) { HybridEditCommands.setAuthoredText(shifted, false) }
        assertTrue(error.reason.contains("сравнений"))
    }
}
