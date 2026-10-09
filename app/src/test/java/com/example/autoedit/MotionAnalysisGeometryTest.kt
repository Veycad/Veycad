package com.example.autoedit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class MotionAnalysisGeometryTest {
    private val sizes = listOf(48 to 72, 96 to 144, 144 to 81, 81 to 144)

    /** Same continuous full-frame field, sampled at different densities; no ML/probability claim. */
    private fun plane(width: Int, height: Int, dx: Double = 0.0, dy: Double = 0.0, light: Float = 0f) =
        LumaMotionEstimator.Plane(width, height, FloatArray(width * height) { i ->
            val x = (i % width + .5) * 48 / width - .5 - dx
            val y = (i / width + .5) * 72 / height - .5 - dy
            (.5 + .22 * sin(.83 * x + .41 * y) + .15 * cos(1.21 * x - .67 * y) +
                .10 * sin(.27 * x + 1.47 * y + .9)).toFloat() + light
        })

    @Test fun full_frame_size_preserves_aspect_and_does_not_upsample() {
        val cases = listOf(
            (480 to 270) to (144 to 81), (270 to 480) to (81 to 144),
            (1920 to 1080) to (144 to 81), (720 to 1280) to (81 to 144),
            (201 to 113) to (144 to 81), (64 to 64) to (64 to 64), (48 to 72) to (48 to 72))
        for ((source, expected) in cases) {
            val (width, height) = source
            val size = MotionAnalysisGeometry.size(width, height)
            assertEquals("Full-frame width for $source", expected.first, size.width)
            assertEquals("Full-frame height for $source", expected.second, size.height)
            assertTrue(size.width <= width && size.height <= height)
            assertTrue(maxOf(size.width, size.height) <= 144)
        }
        assertEquals(MotionAnalysisGeometry.Size(144, 81), MotionAnalysisGeometry.size(480, 270))
        assertEquals(MotionAnalysisGeometry.Size(81, 144), MotionAnalysisGeometry.size(720, 1280))
        assertEquals(MotionAnalysisGeometry.Size(480, 5), MotionAnalysisGeometry.size(480, 5))
    }

    @Test fun equal_fov_translation_does_not_grow_with_pixel_density_or_aspect() {
        for ((width, height) in sizes) {
            val before = plane(width, height)
            val cells = requireNotNull(LocalMotionCorrespondence.estimate(before,
                plane(width, height, .75, -.5), sampling = MotionAnalysisGeometry.sampling(before)))
                .filter { it.confidence >= .5f }
            val accurate = cells.count { abs(it.x - .75f / 3) <= .04f && abs(it.y + .5f / 3) <= .04f }
            println("FOV $width x $height reliable=${cells.size} accurate=$accurate " +
                "meanX=${cells.map { it.x }.average()} meanY=${cells.map { it.y }.average()}")
            assertTrue("Known translation must have spatial support at $width x $height", accurate >= 40)
            assertTrue("Resolution must not multiply motion units", accurate >= cells.size * .9f)
        }
    }

    @Test fun lighting_only_and_independent_noise_cannot_gain_motion_from_resolution() {
        for ((width, height) in sizes) {
            val before = plane(width, height)
            val sampling = MotionAnalysisGeometry.sampling(before)
            val static = requireNotNull(LocalMotionCorrespondence.estimate(before,
                plane(width, height, light = .02f), sampling = sampling)).filter { it.confidence >= .5f }
            assertTrue(static.size >= 40)
            assertTrue(static.all { it.x == 0f && it.y == 0f })
            val random = java.util.Random(42)
            val noise = before.copy(luma = FloatArray(width * height) { random.nextFloat() })
            assertTrue(requireNotNull(LocalMotionCorrespondence.estimate(before, noise, sampling = sampling))
                .none { it.confidence >= .5f })
        }
    }

    @Test fun production_mask_camera_and_subject_compensation_use_reference_units() {
        for ((width, height) in sizes) {
            val before = plane(width, height)
            val mask = FrameAttachments.Plane(width, height, FloatArray(width * height) { i ->
                val x = (i % width + .5f) / width; val y = (i / width + .5f) / height
                if (x in .3f.. .7f && y in .3f.. .7f) 1f else 0f
            }, 1f)
            val measured = requireNotNull(PersonMotionEvidence.observe(before, plane(width, height, .75, -.5),
                0, 250_000, 250_000, mask, 1f))
            assertEquals(.75f / 3, measured.cameraX, .04f)
            assertEquals(-.5f / 3, measured.cameraY, .04f)
            assertTrue(measured.subjectIntensity <= .04f)
            assertEquals(4, measured.cameraQuadrants)
        }
    }

    @Test fun thin_frame_remains_unknown_instead_of_becoming_a_padded_subject() {
        val thin = plane(144, 5)
        assertNull(LocalMotionCorrespondence.estimate(thin, thin, sampling = MotionAnalysisGeometry.sampling(thin)))
    }

    @Test fun below_threshold_camera_displacement_does_not_pass_fear_at_higher_resolution() {
        for ((width, height) in sizes) {
            val before = plane(width, height)
            val mask = FrameAttachments.Plane(width, height, FloatArray(width * height) { i ->
                val x = (i % width + .5f) / width; val y = (i / width + .5f) / height
                if (x in .3f.. .7f && y in .3f.. .7f) 1f else 0f
            }, 1f)
            val measured = requireNotNull(PersonMotionEvidence.observe(before, plane(width, height, .25, 0.0),
                0, 250_000, 250_000, mask, 1f))
            assertTrue(measured.cameraIntensity < .18f)
            assertTrue(measured.subjectIntensity < .18f)
            val report = FearOpeningEvidence.evaluate((1..3).map { index ->
                VisualEventMap.Observation(index * 250_000L, motionMeasurement = measured)
            })
            assertEquals(3, report.measuredSamples)
            assertFalse("Pixel density is not motion evidence", report.supported)
        }
    }
}
