package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SourceAnalysisPipelineTest {
    @Test fun indexed_tail_survives_cache_but_cannot_escape_the_editorial_timeline() {
        val durationUs = 14_377_000L
        val indexed = (0..57).map { 125_000L + it * 250_000L }.toLongArray()
            .let { planned -> SourceAnalysisTimeline.align(planned,
                planned.dropLast(1).toLongArray() + 14_377_322L) }
        SourceAnalysisTimeline.validateDecodeTargets(indexed, durationUs, requireExactPts = true)
        indexed.forEach { SourceAnalysisTimeline.requireDecodedPts(it, it) }
        val observations = indexed.map { VisualEventMap.Observation(it, visualQuality = .9f,
            face = VisualEventMap.Face(.9f, 0f), humanPresenceConfidence = .9f) }
        val result = MediaFrameVisualAnalyzer.Result(SourceAnalysisProfile.EDITORIAL_SEMANTICS,
            0, durationUs, observations, FrameAttachmentTimeline(), 0, 0, 0)
        val file = Files.createTempFile("source-tail-pipeline", ".gz").toFile()
        try {
            MediaFrameAnalysisCache.write(result, file)
            val cached = MediaFrameAnalysisCache.read(file, result.profile)
            assertEquals(result, cached)
            // Both fresh analysis and existing caches enter these production consumers.
            for (analysis in listOf(result, cached)) {
                val map = VisualEventMapAnalyzer.analyze(analysis.durationUs, analysis.observations)
                assertEquals(durationUs, map.durationUs)
                assertEquals(indexed.dropLast(1), map.observations.map { it.sourceTimeUs })
                assertTrue(map.events.all { it.peakTimeUs < durationUs })
                val pipeline = VeycadEventPipeline.fromPcm("video", "music", FloatArray(8_000),
                    8_000, analysis.durationUs, 6_000L, analysis.observations)
                assertEquals(map, pipeline.visualMap)
                assertTrue(pipeline.alternatives.isNotEmpty())
                assertTrue(pipeline.alternatives.all { alternative ->
                    alternative.graph.clips.all { it.sourceEndMs <= durationUs / 1_000L }
                })
            }
            assertEquals(14_377_322L, result.observations.last().sourceTimeUs)
        } finally { file.delete() }
    }

    @Test fun exact_boundary_is_excluded_without_relabelling_or_extending_the_source() {
        val inside = VisualEventMap.Observation(999_999L, visualQuality = .8f)
        val map = VisualEventMapAnalyzer.analyze(1_000_000L,
            listOf(VisualEventMap.Observation(1_000_000L), inside))
        assertEquals(listOf(inside), map.observations)
        assertEquals(1_000_000L, map.durationUs)
    }

    @Test fun tail_only_evidence_still_fails_instead_of_inventing_an_in_range_frame() {
        assertThrows(IllegalArgumentException::class.java) {
            VisualEventMapAnalyzer.analyze(1_000_000L,
                listOf(VisualEventMap.Observation(1_000_000L), VisualEventMap.Observation(1_000_001L)))
        }
    }
}
