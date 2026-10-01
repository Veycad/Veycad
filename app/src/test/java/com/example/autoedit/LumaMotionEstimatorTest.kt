package com.example.autoedit

import org.junit.Assert.assertTrue
import org.junit.Test

class LumaMotionEstimatorTest {
    @Test fun translated_frame_is_global_camera_motion_not_subject_motion() {
        val original = pattern()
        val shifted = shift(original, 2, 0)

        val estimate = LumaMotionEstimator.estimate(original, shifted)

        assertTrue("camera=${estimate.cameraMotion}", estimate.cameraMotion.magnitude > .18f)
        assertTrue(estimate.cameraMotion.direction() == VisualEventMap.Direction.RIGHT)
        assertTrue(estimate.subjectMotion.magnitude < estimate.cameraMotion.magnitude)
        assertTrue(estimate.sceneChangeConfidence < .50f)
    }

    @Test fun local_center_change_is_subject_motion_without_invented_camera_pan() {
        val original = pattern()
        val changed = original.copy(luma = original.luma.copyOf().also { luma ->
            for (y in 10 until 18) for (x in 12 until 20) luma[y * original.width + x] = 1f
        })

        val estimate = LumaMotionEstimator.estimate(original, changed)

        assertTrue(estimate.cameraMotion.magnitude < .12f)
        assertTrue(estimate.subjectMotion.magnitude > .02f)
    }

    @Test fun sudden_dark_flat_frame_is_occlusion_evidence() {
        val bright = pattern()
        val covered = LumaMotionEstimator.Plane(bright.width, bright.height, FloatArray(bright.luma.size) { .02f })

        val estimate = LumaMotionEstimator.estimate(bright, covered)

        assertTrue(estimate.occlusionConfidence > .7f)
        assertTrue(estimate.visualQuality < .2f)
    }

    @Test fun unrelated_picture_change_is_boundary_evidence_not_a_coherent_pan() {
        val original = pattern()
        val unrelated = original.copy(luma = FloatArray(original.luma.size) { index ->
            ((index * 73 + index / original.width * 47 + 61) % 255) / 255f
        })

        val estimate = LumaMotionEstimator.estimate(original, unrelated)

        assertTrue("change=${estimate.sceneChangeConfidence}", estimate.sceneChangeConfidence >= .50f)
    }

    private fun pattern(): LumaMotionEstimator.Plane {
        val width = 32
        val height = 28
        return LumaMotionEstimator.Plane(width, height, FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            ((x * 17 + y * 31 + (x * y) % 23) % 255) / 255f
        })
    }

    private fun shift(source: LumaMotionEstimator.Plane, dx: Int, dy: Int): LumaMotionEstimator.Plane {
        val output = FloatArray(source.luma.size)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val fromX = (x - dx).coerceIn(0, source.width - 1)
            val fromY = (y - dy).coerceIn(0, source.height - 1)
            output[y * source.width + x] = source[fromX, fromY]
        }
        return LumaMotionEstimator.Plane(source.width, source.height, output)
    }
}
