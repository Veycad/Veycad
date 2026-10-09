package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorSelectionEvidenceTest {
    @Test fun exact_authored_intervals_and_ids_are_not_inferred_from_sampled_frames() {
        val graph = graph()
        val selected = VeykadRenderInspector.selectionEvidence(graph)
        assertEquals(2, selected.size)
        assertEquals(VeykadRenderInspector.SelectionEvidence(0, "first", 0,
            11_627L, 12_127L, 0L, 500L, MontageGraph.ShotRole.CLOSE), selected[0])
        assertEquals(VeykadRenderInspector.SelectionEvidence(1, "second", 1,
            11_827L, 12_927L, 500L, 1_600L, MontageGraph.ShotRole.ACTION), selected[1])
    }

    @Test fun source_identity_index_and_clip_order_survive_reversal() {
        val graph = graph()
        val selected = VeykadRenderInspector.selectionEvidence(graph.copy(clips = graph.clips.reversed()))
        assertEquals(listOf("second", "first"), selected.map { it.clipId })
        assertEquals(listOf(1, 0), selected.map { it.sourceIndex })
        assertEquals(listOf(0L, 1_100L), selected.map { it.outputStartMs })
        assertEquals(1_600L, selected.last().outputEndMs)
    }

    @Test fun deliberately_repeated_source_ranges_remain_visible_in_diagnostics() {
        val graph = graph()
        val repeated = graph.clips.first().copy(id = "reprise", outputDurationMs = 1_100L)
        val selected = VeykadRenderInspector.selectionEvidence(graph.copy(clips =
            listOf(graph.clips.first(), repeated)))
        assertEquals(selected[0].sourceStartMs, selected[1].sourceStartMs)
        assertEquals(selected[0].sourceEndMs, selected[1].sourceEndMs)
        assertEquals(listOf("first", "reprise"), selected.map { it.clipId })
        assertEquals(1_600L, selected.last().outputEndMs)
    }

    private fun graph(): MontageGraph = MontageGraph(30_000L, 1_600L, clips = listOf(
        MontageGraph.Clip("first", 11_627L, 12_127L, 500L,
            MontageGraph.ShotRole.CLOSE, MontageGraph.Transition.OPEN,
            MontageGraph.Motion.HOLD, .9f, 0L),
        MontageGraph.Clip("second", 11_827L, 12_927L, 1_100L,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT,
            MontageGraph.Motion.HOLD, .9f, 500L, sourceIndex = 1)
    ))
}
