package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SubjectMotionIntensityTest {
    private fun cell(x: Float, y: Float = 0f, confidence: Float = 1f, person: Float = 1f) =
        SubjectMotionIntensity.Cell(x, y, confidence, person)
    private fun measure(cells: List<SubjectMotionIntensity.Cell>, cameraX: Float = 0f) =
        SubjectMotionIntensity.measure(cells, cameraX, 0f, 1f, 1f)

    @Test fun opposite_directions_retain_the_same_intensity_as_one_direction() {
        assertEquals(.8f, requireNotNull(measure(listOf(cell(.8f), cell(-.8f)))).intensity, .00001f)
        assertEquals(.8f, requireNotNull(measure(listOf(cell(.8f), cell(.8f)))).intensity, .00001f)
    }
    @Test fun coherent_camera_pan_is_not_relative_subject_motion() {
        assertEquals(0f, requireNotNull(measure(listOf(cell(.7f), cell(.7f)), .7f)).intensity, 0f)
        val vertical = SubjectMotionIntensity.measure(listOf(cell(0f, -.6f)), 0f, -.6f, 1f, 1f)
        assertEquals(0f, requireNotNull(vertical).intensity, 0f)
    }
    @Test fun genuinely_static_supported_cells_are_measured_zero_not_unknown() {
        assertEquals(0f, requireNotNull(measure(listOf(cell(0f), cell(0f)))).intensity, 0f)
    }
    @Test fun unsupported_background_motion_cannot_create_subject_activity() {
        val result = requireNotNull(measure(listOf(cell(0f), cell(1f, person = 0f))))
        assertEquals(0f, result.intensity, 0f)
        assertEquals(1, result.supportedCells)
    }
    @Test fun unreliable_matches_are_unknown_not_clean_static_or_moving() {
        assertNull(measure(listOf(cell(1f, confidence = .1f))))
        assertNull(measure(emptyList()))
        assertNull(measure(listOf(cell(1f, person = 0f))))
    }
    @Test fun unknown_camera_or_human_support_cannot_enable_activity() {
        assertNull(SubjectMotionIntensity.measure(listOf(cell(1f)), 0f, 0f, .1f, 1f))
        assertNull(SubjectMotionIntensity.measure(listOf(cell(1f)), 0f, 0f, 1f, .1f))
    }

    @Test fun confidence_and_pixel_area_weight_intensity_without_erasing_unmatched_person_mass() {
        val cells = listOf(
            SubjectMotionIntensity.Cell(.2f, 0f, 1f, 1f, sampleArea = 1f),
            SubjectMotionIntensity.Cell(.8f, 0f, .5f, 1f, sampleArea = 3f),
            SubjectMotionIntensity.Cell(1f, 0f, 0f, 1f, sampleArea = 4f))
        val measured = requireNotNull(measure(cells))
        // (0.2 * 1 + 0.8 * 1.5) / 2.5; four of eight person pixels were matched.
        assertEquals(.56f, measured.intensity, .000001f)
        assertEquals(2, measured.supportedCells)
        assertEquals(.5f, measured.supportedPersonFraction, 0f)
    }
}
