package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class SigmaCompositionTest {
    @Test fun entrance_is_monotonic_and_keeps_head_low_until_the_plate_reveal() {
        val travel = (0..75).map { SigmaComposition.entranceTravel(it * 33_333L) }
        assertTrue(travel.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(.78f, travel.first(), .0001f)
        assertEquals(.27f, SigmaComposition.entranceTravel(2_000_000), .0001f)
        assertEquals(0f, SigmaComposition.entranceTravel(2_450_000), .0001f)
    }

    @Test fun background_gate_accepts_a_graded_scene_but_rejects_a_black_cutout() {
        val source = floatArrayOf(.5f, .4f, .01f, .7f)
        val subject = floatArrayOf(0f, 0f, 0f, 1f)
        assertEquals(0f, SigmaComposition.backgroundLoss(floatArrayOf(.15f,.12f,0f,.5f), source, subject), 0f)
        assertEquals(1f, SigmaComposition.backgroundLoss(floatArrayOf(0f,0f,0f,.5f), source, subject), 0f)
        // An originally dark scene must not be forced bright just to satisfy QA.
        assertEquals(0f, SigmaComposition.backgroundLoss(FloatArray(4), FloatArray(4), subject), 0f)
    }

    @Test fun opening_expected_mask_includes_authored_ghost_but_not_arbitrary_spill() {
        val w = 200; val h = 200
        val source = FloatArray(w * h) { i -> if (i % w in 60..130 && i / w in 30..140) 1f else 0f }
        val main = SigmaComposition.openingMask(source, w, h, SourceFraming.Crop(1f, 1f), .30f, false)
        val both = SigmaComposition.openingMask(source, w, h, SourceFraming.Crop(1f, 1f), .30f, true)
        assertTrue(both.indices.any { both[it] > main[it] })
        assertTrue(both.indices.all { both[it] >= main[it] })
        assertEquals(0f, both[150 * w + 10], 0f)
        assertEquals(0f, both[10 * w + 100], 0f)
    }

    @Test fun black_stage_hallucinations_are_removed_but_visible_leaks_remain() {
        val actual = SigmaComposition.visibleOpeningMask(floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, .1f, 0f))
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), actual, 0f)
    }

    @Test fun authored_entrance_travel_does_not_count_as_mask_flicker() {
        val w = 100; val h = 100
        val person = FloatArray(w * h) { i -> if (i % w in 35..64 && i / w in 10..50) 1f else 0f }
        val a = SigmaComposition.entranceTravel(2_000_000)
        val b = SigmaComposition.entranceTravel(2_100_000)
        val previous = SemanticMaskMetrics.transform(person, w, h, 1f, 0f, 0f, preTranslateY = a)
        val current = SemanticMaskMetrics.transform(person, w, h, 1f, 0f, 0f, preTranslateY = b)
        val aligned = SemanticMaskMetrics.transform(previous, w, h, 1f, 0f, 0f, preTranslateY = b - a)
        assertTrue(SemanticMaskMetrics.registeredTemporalIou(aligned, current, w, h) > .97f)
    }

    @Test fun medium_roles_penalize_extreme_face_crops_more_than_close_roles() {
        assertEquals(1f, SigmaComposition.faceScaleFit(.45f, true), 0f)
        assertTrue(SigmaComposition.faceScaleFit(.74f, true) < .5f)
        assertTrue(SigmaComposition.faceScaleFit(.74f, false) > .9f)
    }
}
