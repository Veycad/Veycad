package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

/** Native pixelwise ground truth: torso/head static, four limb textures move independently. */
class LocalizedLimbMotionDiagnosticTest {
    private val width = 144
    private val height = 81
    private data class Part(val left: Int, val top: Int, val width: Int, val height: Int, val dx: Int)
    private val parts = listOf(42, 102).flatMap { center -> listOf(
        Part(center - 5, 12, 10, 12, 0), Part(center - 8, 24, 16, 24, 0),
        Part(center - 15, 26, 7, 21, -6), Part(center + 8, 26, 7, 21, 6),
        Part(center - 7, 48, 6, 21, -6), Part(center + 2, 48, 6, 21, 6)) }
    private val background = FloatArray(width * height).also { values ->
        val random = java.util.Random(74601)
        values.indices.forEach { values[it] = .2f + .5f * random.nextFloat() }
    }
    private val textures = parts.mapIndexed { index, part ->
        val random = java.util.Random((628 + index).toLong())
        FloatArray(part.width * part.height) { .2f + .5f * random.nextFloat() }
    }
    private data class Scene(val plane: LumaMotionEstimator.Plane, val mask: FrameAttachments.Plane,
                             val movement: FloatArray)
    private fun scene(moved: Boolean, cameraDx: Int = 0, phase: Int = 0): Scene {
        val pixels = FloatArray(width * height) { i ->
            background[i / width * width + (i % width - cameraDx).coerceIn(0, width - 1)]
        }
        val mask = FloatArray(pixels.size)
        val movement = FloatArray(pixels.size)
        for ((index, part) in parts.withIndex()) {
            for (y in 0 until part.height) for (x in 0 until part.width) {
                val px = part.left + x + cameraDx + phase + if (moved) part.dx else 0
                val py = part.top + y
                pixels[py * width + px] = textures[index][y * part.width + x]
                mask[py * width + px] = 1f
                movement[py * width + px] = kotlin.math.abs(part.dx / 9f)
            }
        }
        return Scene(LumaMotionEstimator.Plane(width, height, pixels),
            FrameAttachments.Plane(width, height, mask, 1f), movement)
    }

    @Test fun independently_moving_limbs_are_not_reported_as_a_static_person() {
        for (phase in 0..2) {
            val before = scene(false, phase = phase); val after = scene(true, phase = phase)
            val truth = after.movement.sum() / after.mask.values.sum()
            assertTrue("Moving parts dominate enough area to be an observable challenge", truth > .3f)
            val audit = PersonMotionEvidence.assess(before.plane, after.plane, 0, 250_000, 250_000, after.mask, 1f)
            println("localized limbs phase=$phase truth=$truth stage=${audit.stage} person=${audit.personCells} " +
                "measured=${audit.measurement?.subjectIntensity}")
            val measured = requireNotNull(audit.measurement)
            assertEquals(0f, measured.cameraIntensity, .00001f)
            assertEquals("Torso-only zero is not whole-person intensity", truth, measured.subjectIntensity, .08f)
        }
    }

    @Test fun camera_only_pan_keeps_articulated_proxy_body_stationary_relative_to_camera() {
        val before = scene(false); val after = scene(false, cameraDx = 3)
        val measured = requireNotNull(PersonMotionEvidence.observe(before.plane, after.plane,
            0, 250_000, 250_000, after.mask, 1f))
        assertEquals(1f / 3, measured.cameraX, .00001f)
        assertEquals(0f, measured.subjectIntensity, .00001f)
    }
}
