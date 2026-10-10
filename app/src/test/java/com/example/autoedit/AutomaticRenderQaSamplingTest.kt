package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class AutomaticRenderQaSamplingTest {
    // Regression: 100ms cadence missed these eight literal interior images of 105ms cuts.
    @Test fun automaticBlackoutRequestsEveryShortWindowImageAt30fps() {
        val graph = fourCuts()
        assertNull(graph.manualOverrides)
        assertNull(graph.editableTiming)
        val targets = RenderedVisualSampler.samplingTargets(graph, 100_000, 30)
        val interior = listOf(1_533_333L, 1_566_667L, 5_533_333L, 5_566_667L,
            9_533_333L, 9_566_667L, 13_533_333L, 13_566_667L)
        assertEquals("missing interior output PTS", emptyList<Long>(), interior.filterNot { it in targets })
        for (cut in cuts) assertTrue(targets.containsAll(listOf(-33_333L, 0L, 33_333L,
            66_667L, 100_000L, 133_333L).map { cut + it }))
    }

    @Test fun automaticBlackoutRequestsEveryShortWindowImageAndNeighborsAt60fps() {
        val targets = RenderedVisualSampler.samplingTargets(fourCuts(), 100_000, 60)
        // Independently specified 60fps clock, seven retained samples and adjacent images.
        for (cut in cuts) for (offset in listOf(-16_667L, 0L, 16_667L, 33_333L,
                50_000L, 66_667L, 83_333L, 100_000L, 116_667L)) {
            val target = cut + offset
            assertTrue("missing $target", target in targets)
            for (rounding in listOf(-1L, 0L, 1L)) {
                assertTrue(RenderedVisualSampler.shouldMeasureDecodedFrame(target + rounding, target, null))
            }
            assertFalse(RenderedVisualSampler.shouldMeasureDecodedFrame(target - 2, target, null))
        }
        assertEquals(targets.distinct().sorted(), targets)
    }

    @Test fun suppliedSavedFpsPlanProvidesItsActualWitnessGrid() {
        val graph = fourCuts()
        val savedPlan = HighQualityFramePlan.build(graph, 60)
        // Default graph FPS is 30. A supplied 60fps plan must provide the 16,667us witnesses.
        val targets = RenderedVisualSampler.samplingTargets(graph, 100_000, plan = savedPlan)
        assertTrue(1_516_667L in targets)
        val disabledPlan = savedPlan.copy(frames = savedPlan.frames.map { it.copy(transitionEffectsAllowed = false) })
        val disabledTargets = RenderedVisualSampler.samplingTargets(graph, 100_000, plan = disabledPlan)
        assertFalse(1_516_667L in disabledTargets)
        assertTrue(1_483_333L in disabledTargets) // adjacent clip boundary still uses the saved grid
    }

    @Test fun eightFrameCapIncludesTheWholeWindowButNineFramesStayBounded() {
        val base = fourCuts()
        fun targets(durationMs: Long) = RenderedVisualSampler.samplingTargets(base.copy(clips =
            base.clips.mapIndexed { i, clip -> if (i == 1) clip.copy(transitionDurationMs = durationMs) else clip }),
            100_000, 60)
        val eightFrames = targets(120)
        assertTrue(eightFrames.containsAll(listOf(-16_667L, 0L, 16_667L, 33_333L, 50_000L,
            66_667L, 83_333L, 100_000L, 116_667L, 133_333L).map { 1_500_000L + it }))
        val nineFrames = targets(140)
        assertFalse(1_516_667L in nineFrames)
        assertTrue(nineFrames.containsAll(listOf(1_483_333L, 1_500_000L, 1_633_333L, 1_650_000L)))
    }

    @Test fun automaticSteadyShotsScaleWithCadenceRatherThanFps() {
        for (fps in listOf(30, 60)) for (durationMs in listOf(10_000L, 60_000L)) {
            val targets = RenderedVisualSampler.samplingTargets(steady(durationMs), 100_000, fps)
            assertEquals(durationMs / 100 + 2, targets.size.toLong())
            assertTrue((0L..durationMs * 1_000 step 100_000L).all { it in targets })
            assertEquals(if (fps == 30) durationMs * 1_000 - 33_333 else durationMs * 1_000 - 16_667,
                targets[targets.lastIndex - 1])
        }
    }

    @Test fun automaticOneFrameOverlayAndNodeHaveImmediateNeighbors() {
        for (fps in listOf(30, 60)) {
            val time = if (fps == 30) 233_333L else 116_667L
            val previous = if (fps == 30) 200_000L else 100_000L
            val next = if (fps == 30) 266_667L else 133_333L
            val graph = steady(1_000).copy(overlays = listOf(MontageGraph.Overlay("one-overlay",
                if (fps == 30) 233 else 116, if (fps == 30) 234 else 117, "flash")),
                effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("one-node", GpuEffectGraph.Kind.GLITCH,
                    time, time + 1, .5f))))
            for (singleEffect in listOf(graph.copy(effectGraph = GpuEffectGraph()), graph.copy(overlays = emptyList()))) {
                val targets = RenderedVisualSampler.samplingTargets(singleEffect, 100_000, fps)
                assertTrue(targets.containsAll(listOf(previous, time, next)))
                assertTrue(targets.size < 20)
            }
        }
    }

    @Test fun longOverlayAndNodeRemainBoundedAtBothExportFps() {
        val base = steady(60_000)
        val graph = base.copy(overlays = listOf(MontageGraph.Overlay("long-overlay", 1_237, 50_456, "glow")),
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("long-node", GpuEffectGraph.Kind.GLOW,
                2_237_000, 51_456_000, .3f))))
        for (fps in listOf(30, 60)) {
            val targets = RenderedVisualSampler.samplingTargets(graph, 100_000, fps)
            assertTrue("long effect expanded at $fps: ${targets.size}", targets.size <= 614)
            assertTrue(targets.containsAll((0L..60_000_000L step 100_000L).toList()))
            val expectedFirst = if (fps == 30) 1_266_667L else 1_250_000L
            assertTrue(expectedFirst in targets)
        }
    }

    @Test fun longForegroundKeepsBoundedMatteAndPhaseWitnesses() {
        val graph = fourCuts().copy(clips = fourCuts().clips.mapIndexed { i, clip ->
            clip.copy(transitionIn = if (i == 1) MontageGraph.Transition.FOREGROUND_REENTRY
                else MontageGraph.Transition.HARD_CUT) })
        val targets = RenderedVisualSampler.samplingTargets(graph, 100_000, 60)
        assertTrue(targets.containsAll(listOf(1_483_333L, 1_500_000L, 2_133_333L,
            2_750_000L, 3_300_000L, 3_400_000L, 3_500_000L, 3_700_000L, 4_000_000L, 4_016_667L)))
        assertTrue("long effects must remain bounded: ${targets.size}", targets.size < 210)
        assertFalse(1_516_667L in targets)
    }

    @Test fun automaticAuthorTargetsRemainLiteralAlongsideGridWitnesses() {
        val base = steady(22_000)
        val mirror = MontageGraph.Overlay("mirror", 1_237, 1_392, "mirror",
            overlayKind = MontageGraph.OverlayKind.MIRROR_SLICE)
        val glitch = GpuEffectGraph.Node("glitch", GpuEffectGraph.Kind.GLITCH, 1_234_567, 1_345_678, .4f)
        val reference = base.copy(metadata = NleProjectMetadata(generator = ReferenceMontageProfile.ID),
            overlays = listOf(mirror), effectGraph = GpuEffectGraph(listOf(glitch)))
        val referenceTargets = RenderedVisualSampler.samplingTargets(reference, 100_000, 60)
        assertTrue(referenceTargets.containsAll(ReferenceMontageProfile.ACCENT_BEATS_US))
        assertTrue(referenceTargets.containsAll(listOf(1_237_000L, 1_314_500L, 1_234_567L, 1_290_122L)))
        val heartbeat = base.copy(metadata = NleProjectMetadata(generator = HeartbeatMontageProfile.ID),
            effectGraph = GpuEffectGraph(listOf(glitch)))
        val heartbeatTargets = RenderedVisualSampler.samplingTargets(heartbeat, 100_000, 30)
        HeartbeatMontageProfile.pulses.forEach { pulse -> assertTrue(heartbeatTargets.containsAll(
            listOf((pulse.startUs - 16_667).coerceAtLeast(0), pulse.startUs, pulse.endUs))) }
        assertTrue(heartbeatTargets.containsAll(listOf(glitch.startUs, (glitch.startUs + glitch.endUs) / 2, glitch.endUs)))
        assertTrue(heartbeatTargets.containsAll(HeartbeatPulseAudit.tailSamplingTargetsUs()))
        val fear = base.copy(metadata = NleProjectMetadata(generator = FearStrobeProfile.ID))
        val fearTargets = RenderedVisualSampler.samplingTargets(fear, 100_000, 60)
        val authorGrid = (0 until 660).map { frame -> kotlin.math.round(frame * 1_000_000.0 / 30).toLong() }
        assertTrue(fearTargets.containsAll(authorGrid))
        FearStrobeProfile.pulses.forEach { pulse -> assertTrue(fearTargets.containsAll(
            listOf(pulse.startUs, (pulse.endUs - 1).coerceAtLeast(pulse.startUs)))) }
        assertTrue(fearTargets.containsAll(FearStrobeProfile.scenes.map { it.startUs }))
    }

    @Test fun manualOverridesNeverRegenerateDisabledAuthorSchedule() {
        val node = GpuEffectGraph.Node("disabled-node", GpuEffectGraph.Kind.GLITCH, 1_234_567, 1_345_678, .4f)
        for (generator in listOf(ReferenceMontageProfile.ID, HeartbeatMontageProfile.ID, FearStrobeProfile.ID)) {
            val graph = steady(22_000).copy(metadata = NleProjectMetadata(generator = generator),
                effectGraph = GpuEffectGraph(listOf(node)),
                manualOverrides = ManualRenderOverrides(disabledEffectIds = setOf(node.id)))
            val targets = RenderedVisualSampler.samplingTargets(graph, 100_000, 60)
            assertEquals(RenderedVisualSampler.samplingTargets(steady(22_000).copy(
                manualOverrides = ManualRenderOverrides()), 100_000, 60), targets)
            assertFalse(node.startUs in targets)
            assertFalse(1_766_667L in targets)
        }
    }

    private val cuts = listOf(1_500_000L, 5_500_000L, 9_500_000L, 13_500_000L)
    private fun fourCuts(): MontageGraph {
        val base = steady(17_500)
        return base.copy(clips = listOf(1_500L, 4_000L, 4_000L, 4_000L, 4_000L).mapIndexed { i, duration ->
            base.clips.single().copy(id = "clip-$i", outputDurationMs = duration,
                transitionIn = if (i == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.BLACKOUT) })
    }
    private fun steady(durationMs: Long): MontageGraph {
        val base = ManualMontageFixtures.generatedGraph()
        return base.copy(outputDurationMs = durationMs,
            clips = listOf(base.clips.first().copy(outputDurationMs = durationMs)),
            overlays = emptyList(), effectGraph = GpuEffectGraph(), parameterTracks = emptyList())
    }
}
