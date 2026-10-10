package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

/** Known affine brightness changes are NOT spatial displacement. No real-camera calibration claim. */
class PhotometricMotionDiagnosticTest {
    private fun texture(seed: Int = 19037): LumaMotionEstimator.Plane {
        val random = java.util.Random(seed.toLong())
        return LumaMotionEstimator.Plane(144, 81, FloatArray(144 * 81) { .25f + .5f * random.nextFloat() })
    }
    private fun after(a: LumaMotionEstimator.Plane, dx: Int = 0, gain: Float = 1f, offset: Float = 0f) =
        a.copy(luma = FloatArray(a.luma.size) { i ->
            .5f + gain * (a[(i % a.width - dx).coerceIn(0, a.width - 1), i / a.width] - .5f) + offset
        })
    private fun mask() = FrameAttachments.Plane(144, 81, FloatArray(144 * 81) { i ->
        if (i % 144 in 48..95 && i / 144 in 25..55) 1f else 0f
    }, 1f)
    private fun observe(a: LumaMotionEstimator.Plane, b: LumaMotionEstimator.Plane) =
        PersonMotionEvidence.assess(a, b, 0, 250_000, 250_000, mask(), 1f)

    @Test fun static_contrast_changes_remain_measured_zero_not_unknown_or_motion() {
        val a = texture()
        for (gain in listOf(.6f, 1.6f)) {
            val audit = observe(a, after(a, gain = gain, offset = .02f))
            println("photometric static gain=$gain stage=${audit.stage} fitted=${audit.fittedCells} " +
                "reliable=${audit.reliableCells} camera=${audit.raw?.camera}")
            val measured = requireNotNull(audit.measurement)
            assertEquals(0f, measured.cameraIntensity, .00001f)
            assertEquals(0f, measured.subjectIntensity, .00001f)
        }
    }

    @Test fun camera_translation_survives_affine_brightness_and_is_compensated_on_person() {
        val a = texture()
        for (gain in listOf(.6f, 1.6f)) {
            val audit = observe(a, after(a, dx = 3, gain = gain, offset = -.02f))
            println("photometric pan gain=$gain stage=${audit.stage} fitted=${audit.fittedCells} " +
                "reliable=${audit.reliableCells}")
            val measured = requireNotNull(audit.measurement)
            assertEquals(1f / 3, measured.cameraX, .00001f)
            assertEquals(0f, measured.cameraY, .00001f)
            assertEquals(0f, measured.subjectIntensity, .00001f)
        }
    }

    @Test fun contrast_changed_independent_noise_is_not_affine_evidence_of_displacement() {
        val a = texture()
        for (gain in listOf(.6f, 1.6f)) {
            val b = after(texture(901), gain = gain)
            val cells = requireNotNull(LocalMotionCorrespondence.estimate(a, b,
                sampling = MotionAnalysisGeometry.sampling(a)))
            assertTrue(cells.none { it.confidence >= .5f })
            assertNull(observe(a, b).measurement)
        }
    }

    @Test fun unrelated_local_brightness_changes_cannot_supply_a_coherent_false_pan() {
        val a = texture()
        val b = a.copy(luma = FloatArray(a.luma.size) { i ->
            val gain = if (i % a.width < 72) .6f else 1.6f
            .5f + gain * (a.luma[i] - .5f)
        })
        val audit = observe(a, b)
        // Boundary patches may be unknown, but textured interiors support a measured static scene.
        val measured = requireNotNull(audit.measurement)
        assertEquals(0f, measured.cameraX, .001f)
        assertEquals(0f, measured.cameraY, .001f)
        assertEquals(0f, measured.subjectIntensity, .001f)
    }
}
