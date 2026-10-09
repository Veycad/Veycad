package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class IndependentCameraMeasurementTest {
    private val camera = VisualEventMap.CameraMeasurement(.3f, -.2f, .8f, 12, 3,
        133_000, 400_000, 400_000, 267_000)

    @Test fun exact_actual_pts_and_method_are_part_of_the_value() {
        assertEquals(267_000L, camera.intervalUs)
        assertEquals(MotionAnalysisGeometry.METHOD, camera.method)
        assertEquals(.36055513f, camera.intensity, .000001f)
        assertNotEquals(camera, camera.copy(previousTimeUs = 150_000, intervalUs = 250_000))
    }

    @Test fun weak_spatial_support_stale_semantics_and_bad_actual_intervals_are_rejected() {
        val invalid: List<() -> VisualEventMap.CameraMeasurement> = listOf(
            { camera.copy(confidence = .49f) }, { camera.copy(cells = 7) },
            { camera.copy(quadrants = 2) }, { camera.copy(semanticTimeUs = 133_000) },
            { camera.copy(intervalUs = 250_000) }, { camera.copy(previousTimeUs = -1) },
            { camera.copy(currentTimeUs = 700_000, semanticTimeUs = 700_000, intervalUs = 567_000) },
            { camera.copy(method = "legacy-luma-vector") }, { camera.copy(x = Float.NaN) })
        invalid.forEach { construct ->
            try { construct(); fail("Invalid camera evidence must fail closed") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
