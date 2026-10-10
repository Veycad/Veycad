package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualQaSamplingTest {
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
