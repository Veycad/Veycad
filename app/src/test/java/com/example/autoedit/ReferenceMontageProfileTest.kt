package com.veycad.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReferenceMontageProfileTest {
    @Test fun slice_glitch_is_visible_through_the_author_window_without_changing_generic_pulses() {
        val node = GpuEffectGraph.Node("reference-slice-glitch", GpuEffectGraph.Kind.GLITCH,
            ReferenceMontageProfile.GLITCH_START_US, ReferenceMontageProfile.GLITCH_END_US,
            ReferenceMontageProfile.GLITCH_AMOUNT)
        val authored = GpuEffectGraph(listOf(node))
        // Renaming a segment retains its authored origin and therefore its flat envelope.
        val renamedAuthored = GpuEffectGraph(listOf(node.copy(id = "manual-slice-segment")))
        assertEquals(node.amount, renamedAuthored.sample(node.startUs).glitch, 0f)
        for (time in listOf(7_900_000L, 8_000_000L, 8_100_000L)) {
            assertEquals(ReferenceMontageProfile.GLITCH_AMOUNT, authored.sample(time).glitch, 0f)
            assertEquals(ReferenceMontageProfile.GLITCH_AMOUNT, renamedAuthored.sample(time).glitch, 0f)
        }
        assertEquals(0f, authored.sample(node.startUs - 1).glitch, 0f)
        assertEquals(0f, authored.sample(node.endUs).glitch, 0f)
        assertEquals(0f, renamedAuthored.sample(node.startUs - 1).glitch, 0f)
        assertEquals(0f, renamedAuthored.sample(node.endUs).glitch, 0f)
        // A generic cue has a generic origin; changing only the segment ID is insufficient.
        val generic = GpuEffectGraph(listOf(node.copy(id = "whip-glitch", originId = "whip-glitch")))
        assertEquals(0f, generic.sample(node.startUs).glitch, 0f)
        assertEquals(node.amount, generic.sample((node.startUs + node.endUs) / 2).glitch, 0f)
    }

    @Test fun recognises_author_track_by_content_and_locks_reference_duration() {
        val track = File("src/main/res/raw/leonid_reentry_phonk.m4a")

        assertTrue(ReferenceMontageProfile.matchesAudio(track))
        assertEquals(
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            ReferenceMontageProfile.outputDurationMs(track, 30_000L)
        )
    }

    @Test fun does_not_apply_reference_timing_to_unrelated_audio() {
        val file = kotlin.io.path.createTempFile("other-music", ".m4a").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }

        assertFalse(ReferenceMontageProfile.matchesAudio(file))
        assertNull(ReferenceMontageProfile.outputDurationMs(file, 30_000L))
    }

    @Test fun extends_a_product_minimum_source_to_the_complete_author_phrase() {
        val track = File("src/main/res/raw/leonid_reentry_phonk.m4a")

        assertEquals(
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            ReferenceMontageProfile.outputDurationMs(track, EditDurationPolicy.MINIMUM_MS)
        )
    }

    @Test fun rejects_source_below_the_product_minimum() {
        val track = File("src/main/res/raw/leonid_reentry_phonk.m4a")

        try {
            ReferenceMontageProfile.outputDurationMs(track, EditDurationPolicy.MINIMUM_MS - 1L)
            fail("Expected short source rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun dynamic_timeline_holds_opening_until_the_measured_first_cut() {
        val boundaries = requireNotNull(
            ReferenceMontageProfile.boundariesMs(ReferenceMontageProfile.OUTPUT_DURATION_MS)
        )

        assertEquals(listOf(0L, 2_514L, 3_500L, 4_493L, 5_480L, 6_467L, 7_465L,
            8_446L, 9_439L, 10_432L, 11_424L, 12_417L, 13_404L, 14_391L, 15_383L,
            16_376L, 18_034L), boundaries)
        assertNull(ReferenceMontageProfile.boundariesMs(18_033L))
    }

    @Test fun carries_all_nine_sample_clock_accents_from_the_author_reference() {
        assertEquals(listOf(551_473L, 2_513_560L, 4_493_061L, 6_466_757L, 8_446_258L,
            10_431_564L, 12_416_870L, 14_390_566L, 16_375_873L),
            ReferenceMontageProfile.ACCENT_BEATS_US)
    }

    @Test fun first_phrase_cut_has_a_short_authored_visual_impact() {
        val impact = ReferenceMontageProfile.authoredOverlays()
            .single { it.id == "reference-first-phrase-impact" }

        assertEquals(2_514L, impact.startMs)
        assertEquals(MontageGraph.OverlayKind.FLASH, impact.overlayKind)
        assertTrue(impact.endMs - impact.startMs <= 120L)
    }

    @Test fun finale_keeps_isolation_while_fading_and_holds_black_after_17500() {
        val overlays = ReferenceMontageProfile.authoredOverlays()
        val fading = listOf(17_150L, 17_250L, 17_350L, 17_500L, 18_000L).map {
            LayerCompositorModel.sample(overlays, it)
        }
        fading.forEach {
            assertEquals(MontageGraph.OverlayKind.SUBJECT_STAGE, it.kind)
            assertEquals(1f, it.opacity, 0f)
        }
        assertEquals(0f, fading.first().finalFadeOpacity, 0f)
        assertTrue(fading.zipWithNext().all { (a, b) -> b.finalFadeOpacity >= a.finalFadeOpacity })
        assertEquals(1f, fading[3].finalFadeOpacity, 0f)
        assertEquals(1f, fading.last().finalFadeOpacity, 0f)
        assertEquals(1f, LayerCompositorModel.sample(overlays, 16_376L).opacity, 0f)
    }
}
