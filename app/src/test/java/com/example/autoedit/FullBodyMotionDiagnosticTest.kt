package com.veycad.app

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** Independent pixel/shape ground truth, NOT real people, learned masks, or human acceptance. */
class FullBodyMotionDiagnosticTest {
    private val width = 144
    private val height = 81
    private val background = FloatArray(width * height).also { values ->
        val random = java.util.Random(8401)
        values.indices.forEach { values[it] = .2f + random.nextFloat() * .5f }
    }
    private val textures = (0..1).map { person ->
        val random = java.util.Random((221 + person).toLong())
        FloatArray(32 * height) { .2f + random.nextFloat() * .5f }
    }

    private fun shape(x: Int, y: Int): Boolean =
        (x in -5..4 && y in 12..23) || // head
        (x in -8..7 && y in 24..47) || // torso
        (x in -15..-9 && y in 26..46) || (x in 8..14 && y in 26..46) || // arms
        (x in -7..-2 && y in 48..68) || (x in 2..7 && y in 48..68) // legs

    private data class Scene(val plane: LumaMotionEstimator.Plane, val mask: FrameAttachments.Plane)

    private fun scene(left: Int = 39, right: Int = 105, cameraDx: Int = 0,
                      light: Float = 0f, occludeLeft: Boolean = false, flatBackground: Boolean = false): Scene {
        val pixels = FloatArray(width * height) { i ->
            if (flatBackground) .2f else background[i / width * width + (i % width - cameraDx).coerceIn(0, width - 1)]
        }
        val mask = FloatArray(pixels.size)
        for ((person, center) in listOf(left + cameraDx, right + cameraDx).withIndex()) {
            for (y in 0 until height) for (localX in -15..14) if (shape(localX, y)) {
                val x = center + localX
                if (x !in 0 until width) continue
                mask[y * width + x] = 1f
                pixels[y * width + x] = if (person == 0 && occludeLeft) .2f else textures[person][y * 32 + localX + 16]
            }
        }
        pixels.indices.forEach { pixels[it] += light }
        return Scene(LumaMotionEstimator.Plane(width, height, pixels), FrameAttachments.Plane(width, height, mask, 1f))
    }

    private fun assess(before: Scene, after: Scene) = PersonMotionEvidence.assess(before.plane, after.plane,
        0, 250_000, 250_000, after.mask, 1f)

    @Test fun opposed_full_body_proxies_are_not_cancelled_or_mistaken_for_camera_motion() {
        val audit = assess(scene(), scene(left = 42, right = 102))
        println("full-body opposed stage=${audit.stage} person=${audit.personCells} bg=${audit.backgroundCells} " +
            "fraction=${audit.raw?.subject?.supportedPersonFraction} subject=${audit.raw?.subject?.intensity}")
        val measured = requireNotNull(audit.measurement)
        assertEquals(0f, measured.cameraIntensity, .00001f)
        // Native ±3px = ±1 reference px = scalar 1/3. Magnitude must precede direction averaging.
        assertEquals(1f / 3, measured.subjectIntensity, .04f)
        assertTrue(measured.subjectCells >= 4)
    }

    @Test fun camera_only_pan_does_not_become_body_motion() {
        val measured = requireNotNull(assess(scene(), scene(cameraDx = 3)).measurement)
        assertEquals(1f / 3, measured.cameraX, .00001f)
        assertEquals(0f, measured.cameraY, .00001f)
        assertEquals(0f, measured.subjectIntensity, .00001f)
    }

    @Test fun static_and_additive_lighting_controls_are_measured_zero() {
        val before = scene()
        for (light in listOf(0f, .1f)) {
            val measured = requireNotNull(assess(before, scene(light = light)).measurement)
            assertEquals(0f, measured.cameraIntensity, .00001f)
            assertEquals(0f, measured.subjectIntensity, .00001f)
        }
    }

    @Test fun partial_occlusion_cannot_fabricate_a_camera_pan_or_wrong_visible_body_direction() {
        val before = scene()
        val after = scene(left = 42, right = 102, occludeLeft = true)
        val audit = assess(before, after)
        println("full-body partial occlusion stage=${audit.stage} person=${audit.personCells} " +
            "fraction=${audit.raw?.subject?.supportedPersonFraction}")
        // Occlusion may legitimately make support unknown; if reported, it must be physically right.
        assertEquals(0f, requireNotNull(audit.cameraMeasurement).intensity, .00001f)
        assertNotNull("The unoccluded person must reach the body correspondence stage", audit.localPersonSupport)
        audit.measurement?.let {
            assertEquals(0f, it.cameraIntensity, .00001f)
            assertEquals(1f / 3, it.subjectIntensity, .04f)
        }
        val cells = requireNotNull(LocalMotionCorrespondence.estimate(before.plane, after.plane,
            sampling = MotionAnalysisGeometry.sampling(before.plane)))
        val visibleTorso = cells.filter { it.centerX == 102 && it.centerY in 28..43 && it.confidence >= .5f }
        assertTrue("The unoccluded torso has known, recoverable displacement", visibleTorso.size >= 3)
        assertTrue(visibleTorso.all { abs(it.x + 1f / 3) < .00001f && it.y == 0f })
    }

    @Test fun flat_background_must_not_gain_camera_evidence_from_moving_person_boundaries() {
        val audit = assess(scene(flatBackground = true), scene(left = 42, right = 102, flatBackground = true))
        println("full-body flat-bg stage=${audit.stage} reliableBg=${audit.backgroundCells} " +
            "quadrants=${audit.backgroundQuadrants} camera=${audit.raw?.camera}")
        assertNull("No textured background exists: foreground edges cannot certify camera movement", audit.measurement)
    }

    @Test fun same_direction_bodies_on_flat_background_cannot_certify_a_camera_pan() {
        val audit = assess(scene(flatBackground = true), scene(left = 42, right = 108, flatBackground = true))
        println("full-body flat-bg same direction stage=${audit.stage} reliableBg=${audit.backgroundCells} " +
            "quadrants=${audit.backgroundQuadrants} camera=${audit.raw?.camera}")
        assertNull("Both bodies move but camera is unknown: their edge texture is not background evidence", audit.raw)
    }
}
