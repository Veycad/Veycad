package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualQaSamplingTest {
    @Test fun disabledAndTrimmedAwayEffectWindowsAddNoTargets() {
        val base = ManualMontageFixtures.generatedGraph().copy(overlays = emptyList(),
            manualOverrides = ManualRenderOverrides(disabledEffectIds = setOf("disabled-overlay", "disabled-node")))
        val removedWindow = EffectSampleWindow(8_000_000, 8_100_000, -7_000_000, 1_000_000, 1_100_000)
        val graph = base.copy(overlays = listOf(
            MontageGraph.Overlay("disabled-overlay", 1_233, 1_234, "flash"),
            MontageGraph.Overlay("trimmed-overlay", 1_237, 1_238, "flash", sampleWindow = removedWindow)),
            effectGraph = GpuEffectGraph(listOf(
                GpuEffectGraph.Node("disabled-node", GpuEffectGraph.Kind.GLITCH, 1_233_333, 1_233_334, .5f),
                GpuEffectGraph.Node("trimmed-node", GpuEffectGraph.Kind.GLITCH, 1_237_000, 1_238_000, .5f,
                    sampleWindow = removedWindow))))
        for (fps in listOf(30, 60)) assertEquals(
            RenderedVisualSampler.samplingTargets(base, 100_000, fps),
            RenderedVisualSampler.samplingTargets(graph, 100_000, fps))
    }

    @Test fun dormantIncomingBoundaryAndTrimmedTransitionUseOnlyRetainedFrames() {
        for (fps in listOf(30, 60)) {
            val project = ManualMontageFixtures.linearProject(fps)
            val moved = (MontageTimelineEditor.prepare(project, TimelineCommand.Move("B", 0))
                as TimelinePreparation.Prepared).candidate.graph
            assertTrue(moved.manualMontageState!!.effects.any { it.enabled })
            assertTrue(moved.renderOverlays().isEmpty()) // enabled Boundary B is dormant when first
            val noDormantPayload = moved.copy(manualMontageState = null)
            assertEquals(RenderedVisualSampler.samplingTargets(noDormantPayload, 100_000, fps),
                RenderedVisualSampler.samplingTargets(moved, 100_000, fps))

            val start = (MontageTimelineEditor.prepare(project, TimelineCommand.Trim("B", ClipEdge.START, 1))
                as TimelinePreparation.Prepared).candidate
            val adapter = project.copy(shared = project.shared.copy(current = start.copy(id = 2, parentId = 1), nextRevisionId = 3))
            val trimmed = (MontageTimelineEditor.prepare(adapter, TimelineCommand.Trim("B", ClipEdge.END, 4))
                as TimelinePreparation.Prepared).candidate.graph
            val frames = HighQualityFramePlan.build(trimmed, fps).frames
            val retained = frames.filter { it.clipIndex == 1 }
            assertEquals(3, retained.size)
            assertTrue(retained.first().transitionProgress!! > 0f) // original phase never restarts
            val expected = if (fps == 30) listOf(1_966_667L, 2_000_000L, 2_033_333L, 2_066_667L, 2_100_000L)
                else listOf(1_983_333L, 2_000_000L, 2_016_667L, 2_033_333L, 2_050_000L)
            assertTrue(RenderedVisualSampler.samplingTargets(trimmed, 100_000, fps).containsAll(expected))
        }
    }

    @Test fun frameAssociationKeepsRoundingAndTieBehaviorWithBoundedReads() {
        val base = ManualMontageFixtures.generatedGraph()
        val graph = base.copy(clips = listOf(base.clips.first().copy(outputDurationMs = 60_000)),
            outputDurationMs = 60_000)
        val frames = HighQualityFramePlan.build(graph, 60).frames
        var reads = 0
        val counted = object : AbstractList<HighQualityFramePlan.Frame>() {
            override val size: Int get() = frames.size
            override fun get(index: Int): HighQualityFramePlan.Frame { reads++; return frames[index] }
        }
        val index = RenderQaFrameIndex(counted)
        for (i in listOf(0, 1, 1201, frames.lastIndex)) for (offset in listOf(-1L, 0L, 1L)) {
            reads = 0
            assertEquals(i, index.nearest(frames[i].outputTimeUs + offset))
            assertTrue("$reads frame reads for ${frames.size} frames", reads <= 16)
        }
        assertEquals(1, index.nearest(25_000)) // exactly between 16,667 and 33,333
        assertEquals(frames.size, index.atOrAfter(60_000_000))
    }

    // A manual edit must not multiply expensive steady-shot ML work by output FPS.
    @Test fun steadyShotGrowthFollowsQaCadenceRatherThanOutputFrames() {
        val base = ManualMontageFixtures.generatedGraph()
        for (fps in listOf(30, 60)) for (durationMs in listOf(10_000L, 60_000L)) {
            val graph = base.copy(clips = listOf(base.clips.first().copy(outputDurationMs = durationMs)),
                outputDurationMs = durationMs, overlays = emptyList(), parameterTracks = emptyList(),
                manualOverrides = ManualRenderOverrides())
            val targets = RenderedVisualSampler.samplingTargets(graph, 100_000, fps)
            assertTrue("${durationMs}ms at $fps fps: ${targets.size}", targets.size <= durationMs / 100 + 2)
            assertEquals(0L, targets.first())
            assertTrue(targets.zipWithNext().all { (a, b) -> b > a && b - a <= 100_001 })
        }
    }

    // A short off-cadence clip and each one-frame overlay/node must remain sampled.
    @Test fun oneFrameClipAndEffectsSurviveBoundedSamplingAtBothFps() {
        for (fps in listOf(30, 60)) {
            var project = ManualMontageFixtures.linearProject(fps)
            val first = (MontageTimelineEditor.prepare(project, TimelineCommand.Trim("A1", ClipEdge.END, 7))
                as TimelinePreparation.Prepared).candidate
            project = project.copy(shared = project.shared.copy(current = first.copy(id = 2, parentId = 1), nextRevisionId = 3))
            val second = (MontageTimelineEditor.prepare(project, TimelineCommand.Trim("B", ClipEdge.END, 1))
                as TimelinePreparation.Prepared).candidate
            val clipTime = if (fps == 30) 233_333L else 116_667L
            val nodeTime = if (fps == 30) 366_667L else 183_333L
            val graph = second.graph.copy(overlays = listOf(MontageGraph.Overlay("single-flash", 1_150, 1_160,
                "authored-measured-step", opacity = .8f)), effectGraph = GpuEffectGraph(listOf(
                GpuEffectGraph.Node("single-node", GpuEffectGraph.Kind.GLITCH, nodeTime, nodeTime + 1, .5f,
                    phaseStart = .49f, phaseEnd = .51f))))
            val targets = RenderedVisualSampler.samplingTargets(graph, 100_000, fps)
            assertTrue("one-frame clip $fps", clipTime in targets)
            assertTrue("one-frame GPU node $fps", nodeTime in targets)
            if (fps == 60) assertTrue("one-frame flash", 1_150_000L in targets)
            assertTrue(targets.size < 65)
        }
    }
}
