package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPassPlannerTest {
    @Test fun legacyPassScheduleIsUnchanged() {
        val base = graphWithHeavyEffects()
        val legacy = RenderPassPlanner.plan(base, highCapabilities())
        assertEquals(listOf("source", "transition", "foreground", "motion-blur", "depth",
            "glow-extract", "glow-blur", "final", "encoder"), legacy.passes.map { it.id })
        assertEquals(legacy, RenderPassPlanner.plan(base.copy(manualOverrides = ManualRenderOverrides()), highCapabilities()))
        val disabled = base.copy(manualOverrides = ManualRenderOverrides(setOf("b", "c", "d"), setOf("glow", "glitch")))
        val edited = RenderPassPlanner.plan(disabled, highCapabilities())
        assertEquals(listOf("source", "encoder"), edited.passes.map { it.id })
        assertTrue("layer-blend" in edited.passes.first().fusedOperations)
    }

    @Test fun fuses_cheap_operations_but_keeps_blur_glow_and_depth_in_dedicated_passes() {
        val graph = graphWithHeavyEffects()
        val plan = RenderPassPlanner.plan(graph, highCapabilities())

        val source = plan.passes.first()
        assertTrue(source.fusedOperations.containsAll(setOf("transform", "grade", "layer-blend", "chromatic-split")))
        assertEquals(listOf("source", "transition", "foreground", "motion-blur", "depth",
            "glow-extract", "glow-blur", "final", "encoder"), plan.passes.map { it.id })
        assertEquals(listOf(emptyList(), listOf("source"), listOf("transition"),
            listOf("foreground"), listOf("motion-blur"), listOf("depth"), listOf("glow-extract"),
            listOf("depth", "glow-blur"), listOf("final")), plan.passes.map { it.inputIds })
        assertEquals(listOf(RenderPassPlanner.PassKind.SOURCE_AND_CHEAP_EFFECTS,
            RenderPassPlanner.PassKind.TRANSITION_COMPOSITE,
            RenderPassPlanner.PassKind.FOREGROUND_COMPOSITE, RenderPassPlanner.PassKind.DIRECTIONAL_BLUR,
            RenderPassPlanner.PassKind.DEPTH_COMPOSITE, RenderPassPlanner.PassKind.GLOW_EXTRACT,
            RenderPassPlanner.PassKind.GLOW_BLUR, RenderPassPlanner.PassKind.FINAL_COMPOSITE,
            RenderPassPlanner.PassKind.ENCODER_SURFACE), plan.passes.map { it.kind })
    }

    @Test fun chooses_intermediate_resolution_from_device_capabilities() {
        val high = RenderPassPlanner.plan(graphWithHeavyEffects(), highCapabilities())
        val low = RenderPassPlanner.plan(
            graphWithHeavyEffects(),
            RenderPassPlanner.DeviceCapabilities(2, 2_048, 192, true, false)
        )
        val balanced = RenderPassPlanner.plan(graphWithHeavyEffects(),
            RenderPassPlanner.DeviceCapabilities(2, 4_096, 256, false, false))

        assertEquals(RenderPassPlanner.Quality.HIGH, high.quality)
        assertEquals(RenderPassPlanner.Quality.COMPATIBILITY, low.quality)
        assertEquals(RenderPassPlanner.Quality.BALANCED, balanced.quality)
        assertEquals(1f, high.passes.single { it.kind == RenderPassPlanner.PassKind.DIRECTIONAL_BLUR }.resolutionScale)
        assertEquals(.5f, low.passes.single { it.kind == RenderPassPlanner.PassKind.DIRECTIONAL_BLUR }.resolutionScale)
        assertEquals(.25f, low.passes.single { it.kind == RenderPassPlanner.PassKind.GLOW_BLUR }.resolutionScale)
        assertEquals(1f, balanced.passes.single { it.kind == RenderPassPlanner.PassKind.DIRECTIONAL_BLUR }.resolutionScale)
        assertEquals(.5f, balanced.passes.single { it.kind == RenderPassPlanner.PassKind.DEPTH_COMPOSITE }.resolutionScale)
        assertEquals(.5f, balanced.passes.single { it.kind == RenderPassPlanner.PassKind.GLOW_BLUR }.resolutionScale)
    }

    @Test fun simple_hard_cut_graph_needs_no_expensive_intermediate_passes() {
        val base = graphWithHeavyEffects()
        val graph = base.copy(
            clips = base.clips.map { it.copy(transitionIn = MontageGraph.Transition.HARD_CUT) },
            effectGraph = GpuEffectGraph(),
            frameAttachments = FrameAttachmentTimeline()
        )
        val plan = RenderPassPlanner.plan(graph, highCapabilities())

        assertEquals(listOf(
            RenderPassPlanner.PassKind.SOURCE_AND_CHEAP_EFFECTS,
            RenderPassPlanner.PassKind.ENCODER_SURFACE
        ), plan.passes.map { it.kind })
    }

    @Test fun foreground_reentry_with_semantic_depth_gets_a_dedicated_depth_pass() {
        val base = graphWithHeavyEffects()
        val graph = base.copy(
            clips = base.clips.map {
                if (it.transitionIn == MontageGraph.Transition.OCCLUSION) {
                    it.copy(transitionIn = MontageGraph.Transition.HARD_CUT)
                } else it
            },
            effectGraph = GpuEffectGraph(),
            frameAttachments = FrameAttachmentTimeline(listOf(
                FrameAttachments(
                    3_400_000L,
                    mask = FrameAttachments.Plane(1, 1, listOf(1f), .92f),
                    depth = FrameAttachments.Plane(1, 1, listOf(.28f), .74f)
                )
            ))
        )

        val plan = RenderPassPlanner.plan(graph, highCapabilities())

        assertTrue(plan.passes.any { it.kind == RenderPassPlanner.PassKind.FOREGROUND_COMPOSITE })
        assertTrue(plan.passes.any { it.kind == RenderPassPlanner.PassKind.DEPTH_COMPOSITE })
    }

    private fun highCapabilities() = RenderPassPlanner.DeviceCapabilities(3, 8_192, 768, false, true)

    private fun graphWithHeavyEffects(): MontageGraph {
        val clips = listOf(
            clip("a", 0L, MontageGraph.Transition.OPEN),
            clip("b", 1_000L, MontageGraph.Transition.WHIP),
            clip("c", 2_000L, MontageGraph.Transition.OCCLUSION),
            clip("d", 3_000L, MontageGraph.Transition.FOREGROUND_REENTRY)
        )
        return MontageGraph(
            sourceDurationMs = 5_000L,
            outputDurationMs = 4_000L,
            clips = clips,
            overlays = listOf(MontageGraph.Overlay("flash", 900L, 1_000L, "flash")),
            frameAttachments = FrameAttachmentTimeline(listOf(
                FrameAttachments(2_400_000L, depth = FrameAttachments.Plane(1, 1, listOf(.7f), .9f)),
                FrameAttachments(3_400_000L, mask = FrameAttachments.Plane(1, 1, listOf(1f), .92f))
            )),
            effectGraph = GpuEffectGraph(listOf(
                GpuEffectGraph.Node("glow", GpuEffectGraph.Kind.GLOW, 900_000L, 1_100_000L, .4f),
                GpuEffectGraph.Node("glitch", GpuEffectGraph.Kind.GLITCH, 900_000L, 1_050_000L, .2f)
            ))
        )
    }

    private fun clip(id: String, start: Long, transition: MontageGraph.Transition) = MontageGraph.Clip(
        id, start, start + 1_000L, 1_000L, MontageGraph.ShotRole.ACTION, transition,
        if (transition == MontageGraph.Transition.WHIP) MontageGraph.Motion.WHIP_RIGHT else MontageGraph.Motion.HOLD,
        .9f, start
    )
}
