package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class DrawEvidenceInspectorTest {
    @Test fun held_texture_keeps_previous_source_clip_and_decoded_pts_at_new_boundary() {
        val graph = graph()
        val scheduled = HighQualityFramePlan.build(graph).frames
        val held = scheduled.last { it.clipIndex == 0 }.copy(outputTimeUs = 400_000L,
            transitionIn = MontageGraph.Transition.HARD_CUT, transitionProgress = -1f)
        val collector = VeykadRenderInspector.Collector(graph, 1)
        collector.record(GlesFrameCompositor.DrawEvidence(held, null, false, null,
            366_667L, null, null))
        val actual = collector.evidence().single()
        assertEquals(400_000L, actual.outputTimeUs)
        assertEquals(19, actual.sourceIndex)
        assertEquals(0, actual.clipIndex)
        assertEquals(held.sourceTimeUs, actual.sourceTimeUs)
        assertEquals(366_667L, actual.decodedSourceTimeUs)
        assertNull(actual.secondarySourceIndex)
    }

    @Test fun outgoing_evidence_keeps_source_identity_after_nonsequential_cut_order() {
        assertSecondarySource(19, 266_667L)
    }

    @Test fun incoming_on_both_units_keeps_sampled_incoming_identity_and_pts() {
        assertSecondarySource(7, 33_333L)
    }

    private fun assertSecondarySource(sampledIndex: Int, sampledPts: Long) {
        val graph = graph()
        val frame = HighQualityFramePlan.build(graph).frames.first { it.clipIndex == 1 }
        val collector = VeykadRenderInspector.Collector(graph, 1)
        collector.record(GlesFrameCompositor.DrawEvidence(frame, null, true, 266_667L,
            33_333L, sampledPts, sampledIndex))
        val actual = collector.evidence().single()
        assertEquals(7, actual.sourceIndex)
        assertEquals(1, actual.clipIndex)
        assertEquals(sampledIndex, actual.secondarySourceIndex)
        assertEquals(sampledPts, actual.decodedSecondarySourceTimeUs)
        assertEquals(266_667L, actual.secondarySourceTimeUs)
    }

    private fun graph() = MontageGraph(1000L, 800L, clips = listOf(19, 7).mapIndexed { index, source ->
        MontageGraph.Clip("clip-$index", 0L, 400L, 400L, MontageGraph.ShotRole.ACTION,
            if (index == 0) MontageGraph.Transition.HARD_CUT else MontageGraph.Transition.WHIP,
            MontageGraph.Motion.HOLD, 1f, index * 400L, sourceIndex = source)
    })
}
