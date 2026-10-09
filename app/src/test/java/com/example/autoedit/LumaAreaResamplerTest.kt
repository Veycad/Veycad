package com.example.autoedit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class LumaAreaResamplerTest {
    @Test fun unchanged_size_preserves_the_exact_source_plane() {
        val a = LumaMotionEstimator.Plane(8, 8, FloatArray(64) { it / 64f })
        assertSame(a, LumaAreaResampler.resize(a, 8, 8))
    }

    @Test fun constants_survive_fractional_and_anisotropic_resize() {
        val a = LumaMotionEstimator.Plane(17, 13, FloatArray(17 * 13) { .37f })
        for ((width, height) in listOf(7 to 9, 23 to 19, 11 to 21)) {
            val b = LumaAreaResampler.resize(a, width, height)
            assertEquals(width, b.width)
            assertEquals(height, b.height)
            assertEquals(width * height, b.luma.size)
            assertTrue(b.luma.all { abs(it - .37f) < .00001f })
        }
    }

    @Test fun aligned_checkerboard_reduces_to_its_area_mean_not_a_sampled_phase() {
        val a = LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { i ->
            if ((i % 64 + i / 64) % 2 == 0) 0f else 1f
        })
        val b = LumaAreaResampler.resize(a, 8, 8)
        assertTrue(b.luma.all { it == .5f })
    }

    @Test fun fractional_footprints_preserve_the_global_area_mean() {
        val random = java.util.Random(7)
        val a = LumaMotionEstimator.Plane(23, 17, FloatArray(23 * 17) { random.nextFloat() })
        val b = LumaAreaResampler.resize(a, 9, 11)
        assertEquals(a.luma.average(), b.luma.average(), .000001)
    }

    private fun source(dx: Double = 0.0, dy: Double = 0.0) =
        LumaMotionEstimator.Plane(512, 512, FloatArray(512 * 512) { i ->
            val x = i % 512 - dx; val y = i / 512 - dy
            (.5 + .12 * sin(.083 * x + .041 * y) + .09 * cos(.121 * x - .067 * y) +
                .06 * sin(.027 * x + .147 * y + .9) +
                .12 * cos(1.41 * x + .67 * y) + .10 * sin(.93 * x - 1.51 * y)).toFloat()
        })

    // Center-aligned bilinear point sampling: an explicit model, not a claim of bit-exact Android resize.
    private fun pointResize(a: LumaMotionEstimator.Plane) =
        LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { i ->
            val x = (i % 64 + .5f) * 8 - .5f; val y = (i / 64 + .5f) * 8 - .5f
            val left = x.toInt(); val top = y.toInt()
            val fx = x - left; val fy = y - top
            val first = a[left, top] * (1f - fx) + a[left + 1, top] * fx
            val second = a[left, top + 1] * (1f - fx) + a[left + 1, top + 1] * fx
            first * (1f - fy) + second * fy
        })

    @Test fun area_reduction_preserves_known_motion_under_high_frequency_detail() {
        val before = source(); val after = source(4.0, -2.0)
        fun accuracy(a: LumaMotionEstimator.Plane, b: LumaMotionEstimator.Plane): Pair<Int, Int> {
            val cells = requireNotNull(LocalMotionCorrespondence.estimate(a, b)).filter { it.confidence >= .5f }
            return cells.size to cells.count { abs(it.x * 3f - .5f) <= .125f && abs(it.y * 3f + .25f) <= .125f }
        }
        val point = accuracy(pointResize(before), pointResize(after))
        val area = accuracy(LumaAreaResampler.resize(before, 64, 64), LumaAreaResampler.resize(after, 64, 64))
        println("resampling known dx=.5 dy=-.25 point reliable=${point.first} accurate=${point.second}; " +
            "area reliable=${area.first} accurate=${area.second}")
        assertEquals("This fixture must expose the point-sampling counterexample", 0, point.second)
        assertTrue("Area integration must retain enough true correspondences", area.second >= 100)
        assertTrue("Most reliable area matches must agree with ground truth", area.second >= area.first * .9f)
    }

    @Test fun area_preprocessing_preserves_camera_compensation_in_the_production_evidence_contract() {
        val before = LumaAreaResampler.resize(source(), 64, 64)
        val after = LumaAreaResampler.resize(source(4.0, -2.0), 64, 64)
        val mask = FrameAttachments.Plane(64, 64, FloatArray(4096) { i ->
            if (i % 64 in 16..47 && i / 64 in 16..47) 1f else 0f
        }, 1f)
        val measured = requireNotNull(PersonMotionEvidence.observe(before, after,
            0, 250_000, 250_000, mask, 1f))
        assertEquals(.5f / (3f * 64 / 48), measured.cameraX, .0001f)
        assertEquals(-.25f / (3f * 64 / 72), measured.cameraY, .0001f)
        assertEquals(0f, measured.subjectIntensity, .0001f)
        assertEquals(4, measured.cameraQuadrants)
    }
}
