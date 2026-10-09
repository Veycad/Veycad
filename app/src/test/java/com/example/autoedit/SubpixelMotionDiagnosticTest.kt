package com.example.autoedit

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import org.junit.Assert.*
import org.junit.Test

/** Continuous analytic texture supplies exact ground-truth displacement, not a real-video claim. */
class SubpixelMotionDiagnosticTest {
    private fun plane(dx: Double = 0.0, dy: Double = 0.0, lighting: Float = 0f) =
        LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { i ->
            val x = i % 64 - dx; val y = i / 64 - dy
            (.5 + .16 * sin(.83 * x + .41 * y) + .12 * cos(1.21 * x - .67 * y) +
                .08 * sin(.27 * x + 1.47 * y + .9)).toFloat() + lighting
        })

    @Test fun subpixel_search_recovers_known_half_pixel_translation() {
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(plane(), plane(.5, -.25)))
        val reliable = cells.filter { it.confidence >= .5f }
        val accurate = reliable.count { abs(it.x * 3f - .5f) <= .125f && abs(it.y * 3f + .25f) <= .125f }
        println("subpixel refined cells=${cells.size} reliable=${reliable.size} accurate=$accurate " +
            "fit=${cells.count { it.fit >= .5f }} unique=${cells.count { it.uniqueness >= .5f }}")
        assertTrue("Enough unique centers must support the known displacement", accurate >= 100)
        assertTrue("Reliable matches must agree with ground truth", accurate >= reliable.size * .9f)
    }

    @Test fun static_continuous_texture_is_not_itself_a_matching_failure() {
        val a = plane()
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(a, a)).filter { it.confidence >= .5f }
        println("subpixel static reliable=${cells.size}")
        assertTrue(cells.size >= 100)
        assertTrue(cells.all { it.x == 0f && it.y == 0f })
    }

    @Test fun fractional_translation_is_recovered_in_both_axes_and_signs() {
        for ((dx, dy) in listOf(.25 to -.5, .75 to .5, -1.25 to .25, 2.5 to -.75)) {
            val cells = requireNotNull(LocalMotionCorrespondence.estimate(plane(), plane(dx, dy)))
                .filter { it.confidence >= .5f }
            val accurate = cells.count { abs(it.x * 3f - dx) <= .125 && abs(it.y * 3f - dy) <= .125 }
            assertTrue("dx=$dx dy=$dy accurate=$accurate reliable=${cells.size}", accurate >= 100)
            assertTrue(accurate >= cells.size * .9f)
        }
    }

    @Test fun lighting_does_not_turn_a_static_fractional_capable_texture_into_motion() {
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(plane(), plane(lighting = .1f)))
            .filter { it.confidence >= .5f }
        assertTrue(cells.size >= 100)
        assertTrue(cells.all { it.x == 0f && it.y == 0f })
    }

    @Test fun subpixel_camera_is_independently_supported_and_compensated_in_subject_scalar() {
        val mask = FrameAttachments.Plane(64, 64, FloatArray(4096) { i ->
            if (i % 64 in 16..47 && i / 64 in 16..47) 1f else 0f
        }, 1f)
        val result = requireNotNull(PersonMotionEvidence.observe(plane(), plane(.5, -.25),
            0, 250_000, 250_000, mask, 1f))
        assertEquals(.5f / (3f * 64 / 48), result.cameraX, .0001f)
        assertEquals(-.25f / (3f * 64 / 72), result.cameraY, .0001f)
        assertEquals(0f, result.subjectIntensity, .0001f)
        assertEquals(4, result.cameraQuadrants)
    }

    @Test fun repeating_fractional_pattern_remains_ambiguous_not_trusted_motion() {
        fun stripes(shift: Double) = LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { i ->
            (.5 + .2 * cos(2.0 * Math.PI * (i % 64 - shift) / 3.0)).toFloat()
        })
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(stripes(0.0), stripes(.5)))
        assertTrue(cells.none { it.confidence >= .5f })
    }
}
