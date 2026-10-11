package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class RenderQaGraphEventsTest {
    @Test fun translatedMembershipKeepsFrameOneMicrosecondOutsideDisplayBounds() {
        val base = ManualMontageFixtures.generatedGraph()
        val window = EffectSampleWindow(233_334, 233_335, 33_333, 266_667, 266_668, 1, 30)
        val graph = base.copy(overlays = listOf(MontageGraph.Overlay("shifted", 233, 234, "flash", sampleWindow = window)),
            effectGraph = GpuEffectGraph())
        val plan = HighQualityFramePlan.build(graph, 30)
        val index = RenderQaFrameIndex(plan.frames)
        val event = graph.renderQaEvents(index).single()
        assertEquals(233_333L, index.frames[event.first].outputTimeUs)
        assertEquals(event.first, event.last)
        assertTrue(RenderQaSampling.targets(graph, plan, 100_000)
            .containsAll(listOf(200_000L, 233_333L, 266_667L)))
    }

    @Test fun authoredPeakSurvivesClippedWindowAndDisabledPayloadIsFiltered() {
        val base = ManualMontageFixtures.generatedGraph()
        val window = EffectSampleWindow(100_000, 600_000, 0, 0, 400_000)
        val graph = base.copy(overlays = listOf(MontageGraph.Overlay("disabled", 100, 600, "flash")),
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("retained", GpuEffectGraph.Kind.GLOW,
                100_000, 600_000, .5f, sampleWindow = window))),
            manualOverrides = ManualRenderOverrides(disabledEffectIds = setOf("disabled")))
        val plan = HighQualityFramePlan.build(graph, 60)
        val event = graph.renderQaEvents(RenderQaFrameIndex(plan.frames)).single()
        assertEquals(200_000L, plan.frames[event.authoredPeak!!].outputTimeUs)
        assertEquals(100_000L, plan.frames[event.first].outputTimeUs)
        assertEquals(583_333L, plan.frames[event.last].outputTimeUs)
        assertTrue(200_000L in RenderQaSampling.targets(graph, plan, 1_000_000))
    }
}
