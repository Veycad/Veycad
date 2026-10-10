package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class HybridCutConstraintsTest {
    @Test fun sourceBoundsLimitBothDirectionsAndAcceptExactEndBoundary() {
        val project = editProject()
        assertEquals(15..59, HybridCutConstraints.range(project, "right"))
        val tighter = project.copy(assets = project.assets.map {
            if (it.kind == ProjectAsset.Kind.VIDEO) it.copy(durationUs = 1_500_000) else it })
        assertEquals(15..30, HybridCutConstraints.range(tighter.current, "right", tighter.assets, 30))
        for (frame in listOf(14, 31)) assertThrows(HybridEditRejected::class.java) {
            HybridEditCommands.moveCut(tighter, "right", frame)
        }
        assertEquals(0L, HybridEditCommands.moveCut(tighter, "right", 15).current.clips[1].sourceMap.sample(0))
    }

    @Test fun transitionWindowUsesActualFpsAndProtectsAdjacentClips() {
        for ((fps, expected) in listOf(30 to (15..50), 60 to (20..40))) {
            val base = editProject(fps)
            val project = base.withInitialClips(base.current.clips.map {
                if (it.id == "right") it.copy(original = it.original.copy(transitionIn = MontageGraph.Transition.WHIP)) else it })
            assertEquals(expected, HybridCutConstraints.range(project, "right"))
        }
        val base = editProject()
        val project = base.withInitialClips(base.current.clips.map {
            if (it.id == "right") it.copy(original = it.original.copy(transitionIn = MontageGraph.Transition.WHIP, transitionDurationMs = 101)) else it })
        assertEquals(15..56, HybridCutConstraints.range(project, "right"))
    }

    @Test fun neighboringTransitionWindowsAlsoLimitTheRollingPair() {
        val base = editProject()
        val project = base.withInitialClips(base.current.clips.map {
            if (it.id == "last") it.copy(original = it.original.copy(transitionIn = MontageGraph.Transition.WHIP, transitionDurationMs = 800)) else it })
        assertEquals(15..36, HybridCutConstraints.range(project, "right"))
    }

    @Test fun transitionTooLongForThePairHasNoLegalCut() {
        val base = editProject()
        val project = base.withInitialClips(base.current.clips.map {
            if (it.id == "right") it.copy(original = it.original.copy(transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY)) else it })
        assertTrue(HybridCutConstraints.range(project, "right").isEmpty())
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.moveCut(project, "right", 31) }
    }

    @Test fun heldSamplesAtSourceEndAndSignedPhaseOverflowCannotBeExtended() {
        val base = editProject()
        val project = base.withInitialClips(base.current.clips.map {
            if (it.id == "left") it.copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 1_000_000),
                SourceTimeMap.Point(29, 2_999_999), SourceTimeMap.Point(30, 3_000_000)))) else it })
        assertEquals(30, HybridCutConstraints.range(project, "right").last)
        val upper = base.withInitialClips(base.current.clips.map {
            if (it.id == "right") it.copy(originalFrameOffset = Int.MAX_VALUE) else it })
        assertEquals(30, HybridCutConstraints.range(upper, "right").last)
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.moveCut(upper, "right", 31) }
        val lower = base.withInitialClips(base.current.clips.map {
            if (it.id == "right") it.copy(originalFrameOffset = Int.MIN_VALUE) else it })
        assertEquals(30, HybridCutConstraints.range(lower, "right").first)
    }

    @Test fun invalidCutIdsRejectAndManualLocksRemainEditable() {
        val base = editProject()
        for (id in listOf("left", "unknown")) assertThrows(HybridEditRejected::class.java) {
            HybridCutConstraints.range(base, id)
        }
        val edited = HybridEditCommands.moveCut(base, "right", 32)
        assertEquals(31, HybridEditCommands.moveCut(edited, "right", 31).current.clips[1].span.start)
    }

    @Test fun zeroSlopeEdgesCannotSilentlyCreateNewFrozenFrames() {
        val base = editProject()
        val project = base.withInitialClips(base.current.clips.map { it.copy(sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500_000), SourceTimeMap.Point(30, 500_000)))) })
        assertEquals(30..30, HybridCutConstraints.range(project, "right"))
        for (frame in listOf(29, 31)) assertThrows(HybridEditRejected::class.java) {
            HybridEditCommands.moveCut(project, "right", frame)
        }
    }
}
