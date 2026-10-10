package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StylePickerSelectionTest {
    @Test fun eight_choices_can_select_last_without_mutating_catalogue() {
        val styles=(1..8).map { MontageStyleCatalog.heartbeat.copy(id="test-$it",title="Стиль $it") }
        val state=StylePickerSelection(styles,"test-1")
        assertTrue(state.select("test-8"))
        assertEquals("test-8",state.selected.id)
        assertEquals((1..8).map { "test-$it" }, styles.map { it.id })
        val independent = StylePickerSelection(styles,"test-1")
        assertEquals("test-1", independent.selected.id)
        assertTrue(independent.select("test-3"))
        assertEquals("test-8", state.selected.id)
    }
    @Test fun unavailable_or_unknown_choice_cannot_replace_current_selection() {
        val state=StylePickerSelection(MontageStyleCatalog.all,"heartbeat")
        assertEquals(MontageStyleCatalog.heartbeat,state.selected)
        assertTrue(state.select("heartbeat"))
        assertFalse(state.select("missing"))
        assertFalse(state.select("upcoming"))
        assertFalse(state.select("sigma"))
        assertEquals(MontageStyleCatalog.heartbeat,state.selected)
    }

    @Test fun third_available_choice_selects_fear_recipe_only() {
        val available = MontageStyleCatalog.available
        assertEquals(
            listOf(
                MontageStyleCatalog.heartbeat,
                MontageStyleCatalog.fearStrobe,
                MontageStyleCatalog.dualityLoop
            ),
            available
        )
        assertEquals(MontageStyleCatalog.fearStrobe, MontageStyleCatalog.all[2])

        val state = StylePickerSelection(MontageStyleCatalog.all, MontageStyleCatalog.sigma.id)
        assertEquals(MontageStyleCatalog.heartbeat, state.selected)
        assertTrue(state.select(available[1].id))
        assertEquals("FEAR", state.selected.title)
        assertEquals(MontageStyleCatalog.Recipe.FEAR_STROBE, state.selected.recipe)
        assertEquals(FearStrobeProfile.AUTHOR_TRACK_ID,
            BuiltInMusicCatalog.trackForStyle(state.selected.id).id)
    }

    @Test fun fear_profile_track_and_title_do_not_leak_into_other_styles() {
        val nonFear = MontageStyleCatalog.available.filterNot { it == MontageStyleCatalog.fearStrobe }
        assertTrue(nonFear.all { it.recipe != MontageStyleCatalog.Recipe.FEAR_STROBE })
        assertTrue(nonFear.all {
            BuiltInMusicCatalog.trackForStyle(it.id).id != FearStrobeProfile.AUTHOR_TRACK_ID
        })

        val sigmaGraph = graph(ReferenceMontageProfile.ID)
        val heartbeatGraph = graph(HeartbeatMontageProfile.ID)
        assertFalse(FearStrobeProfile.appliesTo(sigmaGraph))
        assertFalse(FearStrobeProfile.appliesTo(heartbeatGraph))
        assertNull(AuthoredTitleProfile.forGraph(sigmaGraph))
        assertFalse(AuthoredTitleProfile.forGraph(heartbeatGraph)!!.texts.contains("FEAR"))
    }

    @Test fun editor_boundary_rejects_fear_audio_for_every_other_recipe() {
        val fearAudio = listOf(
            File("app/src/main/res/raw/fear_strobe_author.m4a"),
            File("src/main/res/raw/fear_strobe_author.m4a")
        ).first { it.isFile }

        assertTrue(VeycadAutomaticEditor.fearAudioMatchesRecipe(
            MontageStyleCatalog.Recipe.FEAR_STROBE, fearAudio))
        assertFalse(VeycadAutomaticEditor.fearAudioMatchesRecipe(
            MontageStyleCatalog.Recipe.SIGMA, fearAudio))
        assertFalse(VeycadAutomaticEditor.fearAudioMatchesRecipe(
            MontageStyleCatalog.Recipe.HEARTBEAT, fearAudio))
        assertFalse(VeycadAutomaticEditor.fearAudioMatchesRecipe(
            MontageStyleCatalog.Recipe.DUALITY_LOOP, fearAudio))
    }

    @Test fun selecting_duality_then_heartbeat_updates_source_count_and_music_together() {
        val state = StylePickerSelection(MontageStyleCatalog.all, "heartbeat")
        assertTrue(state.select("duality_loop"))
        assertEquals(2, state.selected.sourceCount)
        assertEquals(MontageStyleCatalog.Recipe.DUALITY_LOOP, state.selected.recipe)
        assertEquals(DualityLoopProfile.AUTHOR_TRACK_ID,
            BuiltInMusicCatalog.trackForStyle(state.selected.id).id)
        assertFalse(state.select("sigma"))
        assertEquals("duality_loop", state.selected.id)
        assertTrue(state.select("heartbeat"))
        assertEquals(1, state.selected.sourceCount)
        assertEquals(MontageStyleCatalog.Recipe.HEARTBEAT, state.selected.recipe)
        assertNotEquals(DualityLoopProfile.AUTHOR_TRACK_ID,
            BuiltInMusicCatalog.trackForStyle(state.selected.id).id)
    }

    private fun graph(generator: String) = MontageGraph(
        sourceDurationMs = 1_000,
        outputDurationMs = 1_000,
        clips = listOf(MontageGraph.Clip(
            id = "selection-contract",
            sourceStartMs = 0,
            sourceEndMs = 1_000,
            outputDurationMs = 1_000,
            role = MontageGraph.ShotRole.CLOSE,
            transitionIn = MontageGraph.Transition.OPEN,
            motion = MontageGraph.Motion.HOLD,
            confidence = 1f,
            beatAnchorMs = 0
        )),
        metadata = NleProjectMetadata(generator = generator)
    )
}
