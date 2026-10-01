package com.example.autoedit

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class LocalMotionCorrespondenceTest {
    private fun texture(seed: Int = 1): LumaMotionEstimator.Plane {
        val random = java.util.Random(seed.toLong())
        return LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { .15f + random.nextFloat() * .5f })
    }
    private fun reliable(a: LumaMotionEstimator.Plane, b: LumaMotionEstimator.Plane) =
        requireNotNull(LocalMotionCorrespondence.estimate(a, b)).filter { it.confidence >= .5f }
    private fun shift(a: LumaMotionEstimator.Plane, dx: Int) = a.copy(luma = FloatArray(4096) { i ->
        a[(i % 64 - dx).coerceIn(0, 63), i / 64] })

    @Test fun unchanged_texture_has_reliable_zero_displacement() {
        val a = texture()
        val cells = reliable(a, a)
        assertTrue(cells.size > 100)
        assertTrue(cells.all { it.x == 0f && it.y == 0f })
    }
    @Test fun true_camera_translation_is_recovered_not_a_spatial_centroid() {
        val a = texture()
        val cells = reliable(a, shift(a, 2))
        assertTrue(cells.size > 100)
        assertTrue(cells.all { abs(it.x - 2f / 3f) < .001f && it.y == 0f })
    }
    @Test fun lighting_only_change_is_not_displacement() {
        val a = texture()
        val b = a.copy(luma = FloatArray(4096) { a.luma[it] + .15f })
        val cells = reliable(a, b)
        assertTrue(cells.size > 100)
        assertTrue(cells.all { it.x == 0f && it.y == 0f })
    }
    @Test fun unrelated_noise_does_not_supply_confident_correspondence() {
        assertTrue(reliable(texture(), texture(42)).isEmpty())
    }
    @Test fun flat_flash_is_unknown_not_confident_motion() {
        val a = LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { .2f })
        val b = a.copy(luma = FloatArray(4096) { .9f })
        assertTrue(reliable(a, b).isEmpty())
    }
    @Test fun missing_previous_frame_is_unknown() {
        assertNull(LocalMotionCorrespondence.estimate(null, texture()))
    }
    @Test fun border_clamping_never_duplicates_a_physical_center() {
        val random = java.util.Random(1)
        val a = LumaMotionEstimator.Plane(48, 72, FloatArray(48 * 72) { .15f + random.nextFloat() * .5f })
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(a, a))
        assertEquals(160, cells.size)
        assertEquals(cells.size, cells.map { it.centerX to it.centerY }.toSet().size)
    }
    @Test fun tiny_valid_plane_cannot_multiply_one_patch_into_sixteen_witnesses() {
        val random = java.util.Random(2)
        val a = LumaMotionEstimator.Plane(17, 17, FloatArray(17 * 17) { .15f + random.nextFloat() * .5f })
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(a, a, gridWidth = 4, gridHeight = 4))
        assertEquals(1, cells.size)
        assertEquals(8, cells.single().centerX)
        assertEquals(8, cells.single().centerY)
    }
    @Test fun repeating_stripes_have_no_unique_correspondence() {
        val a = LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { if (it % 64 % 3 == 0) .8f else .2f })
        assertTrue(reliable(a, a).isEmpty())
    }
    @Test fun opposed_pixel_displacements_retain_intensity_after_correspondence() {
        fun scene(left: Int): LumaMotionEstimator.Plane {
            val background = texture().luma.copyOf()
            for ((index, center) in listOf(left, 64 - left).withIndex()) {
                val random = java.util.Random((100 + index).toLong())
                for (y in 16..47) for (x in center - 8 until center + 8) {
                    background[y * 64 + x] = .5f + random.nextFloat() * .3f
                }
            }
            return LumaMotionEstimator.Plane(64, 64, background)
        }
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(scene(20), scene(22)))
        val subjectCells = cells.map { cell ->
            val inside = cell.centerY in 16..47 &&
                (cell.centerX in 14..29 || cell.centerX in 34..49)
            SubjectMotionIntensity.Cell(cell.x, cell.y, cell.confidence, if (inside) 1f else 0f)
        }
        // Synthetic scene definition proves a static camera and proxy-object masks; no ML claim.
        val result = requireNotNull(SubjectMotionIntensity.measure(subjectCells, 0f, 0f, 1f, 1f))
        println("pixel opposed intensity=${result.intensity} supportedCells=${result.supportedCells} " +
            "supportedPersonFraction=${result.supportedPersonFraction}")
        assertTrue(result.supportedCells >= 4)
        assertTrue(result.intensity in .5f.. .8f)
    }
}
