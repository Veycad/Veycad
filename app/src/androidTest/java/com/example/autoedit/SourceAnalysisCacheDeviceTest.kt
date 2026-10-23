package com.veycad.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SourceAnalysisCacheDeviceTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun real_decoded_tail_in_an_existing_cache_reaches_the_analysis_and_audio_pipeline() {
        val source = SyntheticVideo.create(File(ui.context.cacheDir, "cached-clock.mp4"), 2_000L)
        val targets = MulticlassMatteClient.decodedTargets(source,
            SequentialBitmapDecoder.intervalTargets(2_000_000L, 250_000L), analysisClock = true)
        val observations = mutableListOf<VisualEventMap.Observation>()
        SequentialBitmapDecoder.decode(source, targets, 48, 72, requireExactPts = true) { pts, _ ->
            observations += VisualEventMap.Observation(pts, visualQuality = .9f)
        }
        assertEquals(targets.toList(), observations.map { it.sourceTimeUs })
        // Recreate an existing cache whose metadata endpoint equals its last real decoded PTS.
        val cached = MediaFrameVisualAnalyzer.Result(SourceAnalysisProfile.EDITORIAL_SEMANTICS,
            0, targets.last(), observations, FrameAttachmentTimeline(), 0, 0, 0)
        MediaFrameAnalysisCache.store(ui.context,
            MediaFrameAnalysisCache.key(source, 250_000L, cached.profile), cached)
        val analysis = MediaFrameVisualAnalyzer.analyze(ui.context, source, cached.profile)
        assertEquals(cached, analysis)
        val music = BuiltInMusicCatalog.select(ui.context, MontageStyleCatalog.heartbeat.id)
        val pipeline = VeycadEventPipeline.evidenceFromAudioFile(music.file,
            analysis.durationUs, 1_000_000L, analysis.observations)
        assertEquals(analysis.durationUs, pipeline.visualMap.durationUs)
        assertEquals(targets.dropLast(1), pipeline.visualMap.observations.map { it.sourceTimeUs })
        assertTrue(pipeline.audioMap.durationSamples > 0L)
        assertEquals(targets.last(), analysis.observations.last().sourceTimeUs)
    }
}
