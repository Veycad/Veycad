package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ManualMvpBasicGraphTest {
    @Test fun measuredNonzeroPtsAndSelectionSlotsSurviveBothOutputGrids() {
        val sources = listOf(source("first", 4_000_250, 8_000_250), source("second", 1_500_250, 5_500_250))
        val graph = ManualMvpBasicGraph.create(sources, "music")
        assertEquals(listOf(0, 1, 0), graph.clips.map { it.sourceIndex })
        assertEquals(listOf(MontageGraph.Transition.OPEN, MontageGraph.Transition.HARD_CUT,
            MontageGraph.Transition.HARD_CUT), graph.clips.map { it.transitionIn })
        assertEquals(4_001L, graph.clips.first().sourceStartMs)
        assertEquals(1_501L, graph.clips[1].sourceStartMs)
        for (fps in listOf(30, 60)) {
            val plan = HighQualityFramePlan.build(graph, fps)
            assertEquals(6 * fps, plan.frames.size)
            assertEquals(listOf(0, 1, 0), plan.frames.map { it.sourceIndex }.distinctUntilChanged())
            val flash = MontageEffectBindings.bind(graph, fps).single { it.id == "flash-B" }
            assertEquals(EffectAnchor.Boundary("B", 0, 100_000), flash.anchor)
        }
    }

    @Test fun unknownPhysicalEndCannotBecomeAnInventedDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualMvpBasicGraph.create(listOf(source("unknown", 0, null)), "music")
        }
    }

    private fun source(id: String, firstUs: Long, endUs: Long?) = MediaSource(
        id, File("$id.mp4"), id, 4_000_000, 1024, 0, 1920, 1080,
        "video/avc", 3, true, "a".repeat(64), firstUs, endUs)
}
