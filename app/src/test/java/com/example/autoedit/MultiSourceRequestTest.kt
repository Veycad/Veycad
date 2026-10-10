package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MultiSourceRequestTest {
    @Test fun rendererPreservesTwentyOrderedSelectionsIncludingEqualFiles() {
        val files = (19 downTo 0).map { File("source-$it.mp4") }.toMutableList()
        files[1] = files[0]
        val request = request(files)
        files.clear()
        assertEquals(20, request.sourceFiles.size)
        assertEquals(File("source-19.mp4"), request.sourceFiles[0])
        assertEquals(File("source-19.mp4"), request.sourceFiles[1])
        assertEquals(File("source-0.mp4"), request.sourceFiles[19])
        assertThrows(UnsupportedOperationException::class.java) {
            (request.sourceFiles as MutableList<File>).clear()
        }
    }

    @Test fun rendererRejectsEmptyOversizedAndInvalidGraphIndices() {
        assertThrows(IllegalArgumentException::class.java) { request(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { request(List(21) { File("$it.mp4") }) }
        assertThrows(IllegalArgumentException::class.java) { request(listOf(File("a.mp4")), 1) }
        assertThrows(IllegalArgumentException::class.java) { request(listOf(File("a.mp4")), -1) }
        assertEquals(19, request(List(20) { File("$it.mp4") }, 19).graph.clips.single().sourceIndex)
    }

    @Test fun legacyRendererWrappersKeepOneAndTwoSourceOrderAndOptions() {
        val a = File("a.mp4")
        val b = File("b.mp4")
        val one = MediaCodecSpeedRampRenderer.Request(masterFile = a, graph = graph(0),
            outputFile = File("out.mp4"), width = 720, height = 1280, bitrate = 5_000_000)
        val two = request(listOf(a, b), 1)
        val legacy = MediaCodecSpeedRampRenderer.Request(masterFile = a, secondaryFile = b,
            graph = graph(1), outputFile = File("out.mp4"), width = 720, height = 1280,
            bitrate = 5_000_000, fps = 60, debugFaceRegionProbe = true)
        assertEquals(listOf(a), one.sourceFiles)
        assertEquals(two.sourceFiles, legacy.sourceFiles)
        assertEquals(60, legacy.fps)
        assertTrue(legacy.debugFaceRegionProbe)
    }

    @Test fun automaticRequestKeepsLegacyRecipeCardinality() {
        MontageStyleCatalog.Recipe.entries.forEach { recipe ->
            val expected = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) 2 else 1
            VeycadAutomaticEditor.Request.validateRecipeSources(sourceSet(expected), recipe)
            listOf(0, 3, 20, if (expected == 1) 2 else 1).forEach { count ->
                assertThrows(IllegalArgumentException::class.java) {
                    VeycadAutomaticEditor.Request.validateRecipeSources(sourceSet(count), recipe)
                }
            }
        }
    }

    @Test fun samplerResolvesFaceEvidenceByOrderedSourceIndex() {
        val frame = HighQualityFramePlan.build(graph(19)).frames.first()
        val absent = VisualEventMap(1_000_000, emptyList(), listOf(observation(null)))
        val present = VisualEventMap(1_000_000, emptyList(), listOf(observation(VisualEventMap.Face(.92f, 0f))))
        val maps = MutableList<VisualEventMap?>(20) { absent }.apply { this[19] = present }
        assertEquals(.92f, RenderedVisualSampler.expectedFaceConfidence(frame, maps), 0f)
        assertEquals(0f, RenderedVisualSampler.expectedFaceConfidence(frame.copy(sourceIndex = 7), maps), 0f)
        assertEquals(0f, RenderedVisualSampler.expectedFaceConfidence(frame, emptyList()), 0f)
    }

    @Test fun inspectorRecordsActualSourceIndicesForBothDecoderRoles() {
        val graph = graph(19)
        val collector = VeykadRenderInspector.Collector(graph, 1)
        collector.record(HighQualityFramePlan.build(graph).frames.first(), null, true,
            secondarySourceTimeUs = 800_000, decodedSourceTimeUs = 33_333,
            decodedSecondarySourceTimeUs = 833_333, secondarySourceIndex = 7)
        val evidence = collector.evidence().single()
        assertEquals(19, evidence.sourceIndex)
        assertEquals(7, evidence.secondarySourceIndex)
        assertEquals(33_333L, evidence.decodedSourceTimeUs)
        assertEquals(833_333L, evidence.decodedSecondarySourceTimeUs)
    }

    private fun observation(face: VisualEventMap.Face?) = VisualEventMap.Observation(
        sourceTimeUs = 0, face = face, composition = VisualEventMap.Composition(.5f, .5f, .5f, .5f, .5f))

    private fun request(files: List<File>, index: Int = 0) = MediaCodecSpeedRampRenderer.Request(
        sourceFiles = files, graph = graph(index), outputFile = File("out.mp4"),
        width = 720, height = 1280, bitrate = 5_000_000)

    private fun graph(index: Int) = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip(
        id = "clip", sourceStartMs = 0, sourceEndMs = 1000, outputDurationMs = 1000,
        role = MontageGraph.ShotRole.OPENING, transitionIn = MontageGraph.Transition.HARD_CUT,
        motion = MontageGraph.Motion.HOLD, confidence = 1f, beatAnchorMs = 0,
        sourceIndex = index)))
}
