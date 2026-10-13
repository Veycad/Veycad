package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderedVisualSamplingTargetsTest {
    @Test fun raw_cadence_and_duration_endpoint_survive_saved_frame_snapping() {
        val graph = steady(1_022L)
        // 73 ms cadence is deliberately off both saved frame clocks; 14 steps reach duration.
        val rawTimes = List(15) { it * 73_000L }
        for (fps in listOf(30, 60)) {
            val plan = HighQualityFramePlan.build(graph, fps)
            val targets = RenderedVisualSampler.samplingTargets(graph, 73_000L, plan = plan)
            assertTrue("fps=$fps", targets.containsAll(rawTimes))
            assertTrue(targets.containsAll(RenderQaSampling.targets(graph, plan, 73_000L)))
            assertEquals(targets.distinct().sorted(), targets)
            assertFalse(plan.frames.any { it.outputTimeUs == 1_022_000L })
        }
    }

    @Test fun reference_accents_and_raw_effect_checkpoints_remain_in_the_union() {
        val graph = steady(18_034L, ReferenceMontageProfile.ID).copy(
            overlays = listOf(
                MontageGraph.Overlay("double", 1_237, 1_811, "double",
                    overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE),
                MontageGraph.Overlay("mirror", 2_321, 2_823, "mirror",
                    overlayKind = MontageGraph.OverlayKind.MIRROR_SLICE)
            ),
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node(
                "glitch", GpuEffectGraph.Kind.GLITCH, 1_234_567L, 1_345_679L, .5f)))
        )
        val authoredTimes = listOf(551_473L, 2_513_560L, 4_493_061L, 6_466_757L,
            8_446_258L, 10_431_564L, 12_416_870L, 14_390_566L, 16_375_873L,
            1_237_000L, 1_524_000L, 2_321_000L, 2_572_000L, 1_234_567L, 1_290_123L)
        assertAnchorsRetained(graph, authoredTimes)
    }

    @Test fun heartbeat_keeps_raw_pulse_node_and_exact_tail_anchors_at_both_saved_rates() {
        val graph = steady(21_166L, HeartbeatMontageProfile.ID).copy(
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node(
                "defocus", GpuEffectGraph.Kind.DEFOCUS_BLUR, 1_234_567L, 1_345_679L, .5f)))
        )
        // Independent 60 fps clock, including the white boundary and all 82 black tail slots.
        val tailTimes = List(84) { index ->
            19_766_667L + index / 3 * 50_000L + listOf(0L, 16_666L, 33_333L)[index % 3]
        }
        assertAnchorsRetained(graph, listOf(633_333L, 650_000L, 666_667L,
            1_234_567L, 1_290_123L, 1_345_679L) + tailTimes)
        for (fps in listOf(30, 60)) {
            val targets = RenderedVisualSampler.samplingTargets(graph, 100_000L, plan = HighQualityFramePlan.build(graph, fps))
            assertFalse(targets.contains(21_166_667L))
        }
    }

    @Test fun fear_reference_clock_is_unchanged_and_excludes_duration_at_both_saved_rates() {
        val graph = steady(18_300L, FearStrobeProfile.ID)
        // 549 author frames, expressed independently of the production floating point clock.
        val referenceTimes = List(549) { index ->
            index / 3 * 100_000L + listOf(0L, 33_333L, 66_667L)[index % 3]
        }
        val pulseAndSceneTimes = FearStrobeProfile.pulses.flatMap {
            listOf(it.startUs, it.endUs - 1L)
        } + FearStrobeProfile.scenes.map { it.startUs }
        val raw = RenderedVisualSampler.samplingTargets(graph, 100_000L)
        assertEquals((referenceTimes + pulseAndSceneTimes + 18_300_000L).distinct().sorted(), raw)
        assertAnchorsRetained(graph, referenceTimes + listOf(10_033_332L, 5_566_667L, 18_299_999L))

        // The raw cadence still requests its historical endpoint; it is not a FEAR frame.
        val shorter = steady(100L, FearStrobeProfile.ID)
        assertEquals(listOf(0L, 33_333L, 66_667L, 100_000L),
            RenderedVisualSampler.samplingTargets(shorter, 100_000L))
    }

    @Test fun blackout_interior_images_and_execution_neighbors_use_the_supplied_plan() {
        val audio = MontageGraph.AudioTrack("custom-music", 1.4f, 1_234_567L)
        val graph = steady(17_500L).copy(
            clips = listOf(1_500L, 4_000L, 4_000L, 4_000L, 4_000L).mapIndexed { i, duration ->
                clip("clip-$i", duration, if (i == 0) MontageGraph.Transition.OPEN
                    else MontageGraph.Transition.BLACKOUT)
            }, audioTrack = audio)
        val cuts = listOf(1_500_000L, 5_500_000L, 9_500_000L, 13_500_000L)
        for (fps in listOf(30, 60)) {
            val plan = HighQualityFramePlan.build(graph, fps)
            val targets = RenderedVisualSampler.samplingTargets(graph, 100_000L, plan = plan)
            val offsets = if (fps == 30) listOf(-33_333L, 0L, 33_333L, 66_667L, 100_000L, 133_333L)
                else listOf(-16_667L, 0L, 16_667L, 33_333L, 50_000L, 66_667L, 83_333L, 100_000L, 116_667L)
            for (cut in cuts) assertTrue("fps=$fps cut=$cut", targets.containsAll(offsets.map { cut + it }))
            if (fps == 30) assertTrue(targets.containsAll(listOf(1_533_333L, 1_566_667L,
                5_533_333L, 5_566_667L, 9_533_333L, 9_566_667L, 13_533_333L, 13_566_667L)))

            // Remove off-cadence interior witnesses from the caller's plan. A rebuilt plan
            // would incorrectly bring them back through canonical selection.
            val excluded = if (fps == 30) 1_533_333L else 1_516_667L
            val supplied = plan.copy(frames = plan.frames.filter { it.outputTimeUs != excluded })
            assertFalse(RenderedVisualSampler.samplingTargets(graph, 100_000L, plan = supplied).contains(excluded))
            assertSame(audio, graph.audioTrack)
            assertEquals(1_234_567L, graph.audioTrack!!.sourceStartUs)
            assertEquals(1.4f, graph.audioTrack!!.gain, 0f)
            assertEquals(targets, RenderedVisualSampler.samplingTargets(graph.copy(audioTrack = null), 100_000L, plan = plan))
            assertEquals(plan, HighQualityFramePlan.build(graph.copy(audioTrack = audio.copy(sourceStartUs = 0)), fps))
        }
    }

    private fun assertAnchorsRetained(graph: MontageGraph, authoredTimes: List<Long>) {
        val raw = RenderedVisualSampler.samplingTargets(graph, 100_000L)
        for (fps in listOf(30, 60)) {
            val targets = RenderedVisualSampler.samplingTargets(graph, 100_000L, plan = HighQualityFramePlan.build(graph, fps))
            assertTrue("authored anchors fps=$fps", targets.containsAll(authoredTimes))
            assertTrue("raw anchors fps=$fps", targets.containsAll(raw))
        }
    }

    private fun steady(durationMs: Long, generator: String = "sampling-targets-test") = MontageGraph(
        62_000L, durationMs, clips = listOf(clip("steady", durationMs, MontageGraph.Transition.OPEN)),
        metadata = NleProjectMetadata(generator = generator))

    private fun clip(id: String, durationMs: Long, transition: MontageGraph.Transition) = MontageGraph.Clip(
        id, 2_000, 62_000, durationMs, MontageGraph.ShotRole.ACTION, transition,
        MontageGraph.Motion.HOLD, .8f, 0)
}
