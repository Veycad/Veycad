package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CustomMusicRoutingTest {
    @Test fun custom_music_is_a_restorable_single_source_recipe_without_an_authored_score() {
        val style = MontageStyleCatalog.restore("custom_music")
        assertEquals("custom_music", style.id)
        assertTrue(style.available)
        assertEquals(1, style.sourceCount)
        assertEquals(SourceAnalysisProfile.EDITORIAL_SEMANTICS,
            SourceAnalysisProfile.requiredFor(requireNotNull(style.recipe)))
        assertTrue(VeycadAutomaticEditor.fearAudioMatchesRecipe(style.recipe, File("fear_strobe_author.m4a")))
        assertTrue(VeycadAutomaticEditor.dualityAudioMatchesRecipe(style.recipe, File("duality_loop_author.m4a")))
        assertTrue(MontageStylePresentation.forStyle(style).musicPlaceholder.contains("Выберите"))
    }

    @Test fun a_detected_drop_owns_a_cut_even_when_ordinary_beats_are_stronger() {
        val audio = AudioBeatMap(1_000, 18_000, 120f,
            (500L until 18_000 step 500).map {
                AudioBeatMap.Beat(it, 1f, AudioBeatMap.FrequencyBand.LOW, it % 2000 == 0L)
            }, emptyList(), listOf(AudioBeatMap.Drop(6_250, .5f)))
        val visual = VisualEventMapAnalyzer.analyze(25_000_000,
            (0L until 25_000_000 step 1_000_000).map { VisualEventMap.Observation(sourceTimeUs = it) })
        val graph = EventMatchingDirector.direct(EventMatchingDirector.Request(
            "video", "music", 18_000, audio, visual), EventMatchingDirector.Style.DYNAMIC).single().graph
        assertTrue(graph.clips.runningFold(0L) { start, clip -> start + clip.outputDurationMs }.contains(6_250))
    }
}
