package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class PersonLocalCorrespondenceTest {
    private fun texture(seed: Int = 4751): LumaMotionEstimator.Plane {
        val random = java.util.Random(seed.toLong())
        return LumaMotionEstimator.Plane(144, 81, FloatArray(144 * 81) { .2f + .5f * random.nextFloat() })
    }
    private fun mask(left: Int = 48, right: Int = 95, top: Int = 24, bottom: Int = 56) =
        FrameAttachments.Plane(144, 81, FloatArray(144 * 81) { i ->
            if (i % 144 in left..right && i / 144 in top..bottom) 1f else 0f
        }, 1f)

    @Test fun unique_pixel_ownership_conserves_area_and_retains_all_unmatched_person_mass() {
        val a = texture(); val mask = mask()
        val local = requireNotNull(PersonLocalCorrespondence.estimate(a, a, mask, 0f, 0f))
        assertEquals((144 * 81).toDouble(), local.cells.sumOf { it.sampleArea.toDouble() }, .00001)
        assertEquals(mask.values.sum().toDouble(),
            local.cells.sumOf { (it.personSupport * it.sampleArea).toDouble() }, .0001)
        val known = local.cells.filter { it.matchingConfidence >= .5f }
        assertTrue(known.isNotEmpty())
        assertTrue(known.all { it.x == 0f && it.y == 0f })
        assertTrue(local.witnesses <= local.support.reliable)
    }

    @Test fun overlapping_small_patches_do_not_turn_one_feature_into_four_spatial_witnesses() {
        val a = texture(); val mask = mask(68, 75, 36, 40)
        val local = requireNotNull(PersonLocalCorrespondence.estimate(a, a, mask, 0f, 0f))
        assertTrue(local.support.reliable >= 4)
        assertTrue(local.witnesses < 4)
        val audit = PersonMotionEvidence.assess(a, a, 0, 250_000, 250_000, mask, 1f)
        assertEquals(PersonMotionEvidence.Stage.PERSON_COVERAGE, audit.stage)
        assertNull(audit.measurement)
        assertNotNull(audit.rawCamera)
        assertEquals(0f, requireNotNull(audit.cameraMeasurement).intensity, 0f)
        assertEquals(250_000L, audit.cameraMeasurement?.intervalUs)
    }

    @Test fun independent_foreground_noise_stays_unknown_even_with_a_supported_static_camera() {
        val a = texture(); val mask = mask(); val random = java.util.Random(2981)
        val b = a.copy(luma = FloatArray(a.luma.size) { i ->
            if (mask.values[i] >= .5f) .2f + .5f * random.nextFloat() else a.luma[i]
        })
        val audit = PersonMotionEvidence.assess(a, b, 0, 250_000, 250_000, mask, 1f)
        assertTrue(audit.backgroundCells >= 8 && audit.backgroundQuadrants >= 3)
        assertNotNull("The local matcher must actually run, not pass via an early camera refusal", audit.localPersonSupport)
        assertNull(audit.measurement)
        assertNotNull(audit.rawCamera)
        assertEquals(0f, requireNotNull(audit.cameraMeasurement).intensity, 0f)
    }

    @Test fun validated_camera_pan_survives_insufficient_disjoint_body_witnesses() {
        val a = texture()
        val b = a.copy(luma = FloatArray(a.luma.size) { i ->
            a[(i % 144 - 3).coerceAtLeast(0), i / 144]
        })
        val audit = PersonMotionEvidence.assess(a, b, 133_000, 400_000, 400_000,
            mask(68, 75, 36, 40), 1f)
        assertEquals(PersonMotionEvidence.Stage.PERSON_COVERAGE, audit.stage)
        assertNull("Whole-body guarantee must remain unchanged", audit.measurement)
        val camera = requireNotNull(audit.cameraMeasurement)
        assertEquals(1f / 3f, camera.x, .00001f)
        assertEquals(0f, camera.y, .00001f)
        assertEquals(133_000L, camera.previousTimeUs)
        assertEquals(400_000L, camera.currentTimeUs)
        assertEquals(400_000L, camera.semanticTimeUs)
        assertEquals(267_000L, camera.intervalUs)
        assertTrue(camera.cells >= 8 && camera.quadrants >= 3)
    }
}
