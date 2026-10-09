package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/** Smooth clothing, not white-noise texture: native displacement/mask ground truth. */
class SmoothClothingMotionDiagnosticTest {
    private val width = 144
    private val height = 81
    private data class Part(val left: Int, val top: Int, val width: Int, val height: Int, val dx: Int)
    private val parts = listOf(42, 102).flatMap { center -> listOf(
        Part(center - 5, 12, 10, 12, 0), Part(center - 8, 24, 16, 24, 0),
        Part(center - 15, 26, 7, 21, -6), Part(center + 8, 26, 7, 21, 6),
        Part(center - 7, 48, 6, 21, -6), Part(center + 2, 48, 6, 21, 6)) }
    private val background = FloatArray(width * height).also { values ->
        val random = java.util.Random(914037)
        values.indices.forEach { values[it] = .2f + .5f * random.nextFloat() }
    }
    private fun textures(spacing: Int) = parts.mapIndexed { index, part ->
        // Independently seeded coarse knots and cosine interpolation in native pixels.
        val random = java.util.Random((71921 + index).toLong())
        val knotsWidth = (part.width + spacing - 1) / spacing + 1
        val knotsHeight = (part.height + spacing - 1) / spacing + 1
        val knots = FloatArray(knotsWidth * knotsHeight) { .2f + .5f * random.nextFloat() }
        FloatArray(part.width * part.height) { i ->
            val x = i % part.width; val y = i / part.width
            val fx = ((1 - cos(Math.PI * (x % spacing) / spacing)) * .5).toFloat()
            val fy = ((1 - cos(Math.PI * (y % spacing) / spacing)) * .5).toFloat()
            val left = x / spacing; val top = y / spacing
            val a = knots[top * knotsWidth + left] * (1 - fx) + knots[top * knotsWidth + left + 1] * fx
            val b = knots[(top + 1) * knotsWidth + left] * (1 - fx) + knots[(top + 1) * knotsWidth + left + 1] * fx
            a * (1 - fy) + b * fy
        }
    }
    private data class Scene(val plane: LumaMotionEstimator.Plane, val mask: FrameAttachments.Plane,
                             val movement: FloatArray)
    private fun scene(moved: Boolean, cameraDx: Int = 0, phase: Int = 0, gain: Float = 1f,
                      spacing: Int = 4): Scene {
        val textures = textures(spacing)
        val pixels = FloatArray(width * height) { i ->
            background[i / width * width + (i % width - cameraDx).coerceIn(0, width - 1)]
        }
        val mask = FloatArray(pixels.size); val movement = FloatArray(pixels.size)
        for ((index, part) in parts.withIndex()) {
            for (y in 0 until part.height) for (x in 0 until part.width) {
                val px = part.left + x + cameraDx + phase + if (moved) part.dx else 0
                val py = part.top + y
                pixels[py * width + px] = textures[index][y * part.width + x]
                mask[py * width + px] = 1f
                movement[py * width + px] = abs(part.dx / 9f)
            }
        }
        pixels.indices.forEach { pixels[it] = .5f + gain * (pixels[it] - .5f) }
        return Scene(LumaMotionEstimator.Plane(width, height, pixels),
            FrameAttachments.Plane(width, height, mask, 1f), movement)
    }

    @Test fun smooth_moving_limbs_must_not_be_replaced_by_static_torso_only_evidence() {
        for (spacing in listOf(4, 8)) for (phase in 0..2) {
            val before = scene(false, phase = phase, spacing = spacing)
            val after = scene(true, phase = phase, spacing = spacing)
            val truth = after.movement.sum() / after.mask.values.sum()
            val audit = PersonMotionEvidence.assess(before.plane, after.plane, 0, 250_000, 250_000, after.mask, 1f)
            println("smooth spacing=$spacing phase=$phase truth=$truth stage=${audit.stage} " +
                "coverage=${audit.raw?.subject?.supportedPersonFraction} measured=${audit.measurement?.subjectIntensity}")
            val measured = requireNotNull(audit.measurement)
            assertEquals(0f, measured.cameraIntensity, .00001f)
            assertEquals("Whole-person area includes smoothly textured moving limbs", truth, measured.subjectIntensity, .08f)
        }
    }

    @Test fun camera_only_pan_on_smooth_clothing_is_compensated_without_false_limb_motion() {
        for (spacing in listOf(4, 8)) {
            val before = scene(false, spacing = spacing); val after = scene(false, cameraDx = 3, spacing = spacing)
            val measured = requireNotNull(PersonMotionEvidence.observe(before.plane, after.plane,
                0, 250_000, 250_000, after.mask, 1f))
            assertEquals(1f / 3, measured.cameraX, .00001f)
            assertEquals(0f, measured.subjectIntensity, .0001f)
        }
    }

    @Test fun contrast_change_on_static_smooth_clothing_does_not_fabricate_motion() {
        for (spacing in listOf(4, 8)) {
            val before = scene(false, spacing = spacing); val after = scene(false, gain = .6f, spacing = spacing)
            val measured = requireNotNull(PersonMotionEvidence.observe(before.plane, after.plane,
                0, 250_000, 250_000, after.mask, 1f))
            assertEquals(0f, measured.cameraIntensity, .00001f)
            assertEquals(0f, measured.subjectIntensity, .0001f)
        }
    }

    @Test fun rotated_smooth_limbs_use_physical_axis_units_and_both_query_orientations() {
        fun rotate(scene: Scene): Scene {
            fun values(input: FloatArray) = FloatArray(input.size) { i ->
                val x = i % height; val y = i / height
                input[(height - 1 - x) * width + y]
            }
            return Scene(LumaMotionEstimator.Plane(height, width, values(scene.plane.luma)),
                FrameAttachments.Plane(height, width, values(scene.mask.values), 1f), values(scene.movement))
        }
        for (spacing in listOf(4, 8)) {
            val before = rotate(scene(false, spacing = spacing)); val after = rotate(scene(true, spacing = spacing))
            // Native dx=6 becomes native dy=6. Height144/reference72 => normalized magnitude1.
            val truth = after.movement.sum() * 1.5f / after.mask.values.sum()
            val audit = PersonMotionEvidence.assess(before.plane, after.plane,
                0, 250_000, 250_000, after.mask, 1f)
            println("rotated spacing=$spacing truth=$truth stage=${audit.stage} " +
                "coverage=${audit.raw?.subject?.supportedPersonFraction} measured=${audit.measurement?.subjectIntensity}")
            val sampling = MotionAnalysisGeometry.sampling(after.plane)
            val prepared = requireNotNull(LocalMotionCorrespondence.prepare(before.plane, after.plane))
            val points = (0 until after.plane.height).flatMap { y ->
                (0 until after.plane.width).map { x -> x to y }
            }.filter { (x, y) -> after.mask.values[y * after.plane.width + x] >= .5f }
            for (shape in listOf(LocalMotionCorrespondence.Sampling(),
                LocalMotionCorrespondence.Sampling(1f, 4f), LocalMotionCorrespondence.Sampling(4f, 1f))) {
                val known = LocalMotionCorrespondence.estimate(prepared, sampling = sampling,
                    patchFraction = .25f, patchShape = shape, requestedCenters = points).orEmpty()
                    .filter { it.confidence >= .5f && PersonMaskProjection.patchMinimum(after.mask,
                        after.plane.width, after.plane.height, it.centerX, it.centerY, it.patchRadiusX, it.patchRadiusY) >= .5f }
                val moving = known.filter { after.movement[it.centerY * after.plane.width + it.centerX] > 0f }
                val wrong = known.filter { abs(kotlin.math.hypot(it.x, it.y) -
                    after.movement[it.centerY * after.plane.width + it.centerX] * 1.5f) > .01f }
                println("rotated shape=$shape reliable=${known.size} moving=${moving.size} wrong=${wrong.size} " +
                    "movingConfidence=${moving.map { it.confidence }.average()}")
                assertTrue("The shape must exercise reliable queries rather than pass an empty list", known.isNotEmpty())
                if (shape.y == 1f)
                    assertTrue("Horizontal query footprints must recover rotated limb displacement", moving.isNotEmpty())
                assertTrue("Reliable representative queries must recover known native displacement", wrong.isEmpty())
            }
            val measured = requireNotNull(audit.measurement)
            assertEquals(0f, measured.cameraIntensity, .00001f)
            assertEquals(truth, measured.subjectIntensity, .08f)
        }
    }

    @Test fun periodic_clothing_remains_ambiguous_in_enlarged_queries() {
        val mask = FrameAttachments.Plane(width, height, FloatArray(width * height) { i ->
            if (i % width in 50..93 && i / width in 24..56) 1f else 0f
        }, 1f)
        val pixels = background.copyOf()
        pixels.indices.filter { mask.values[it] == 1f }.forEach { i ->
            pixels[i] = .5f + .15f * kotlin.math.sin(2 * Math.PI * (i % width) / 3).toFloat() +
                .15f * kotlin.math.sin(2 * Math.PI * (i / width) / 4).toFloat()
        }
        val plane = LumaMotionEstimator.Plane(width, height, pixels)
        val audit = PersonMotionEvidence.assess(plane, plane, 0, 250_000, 250_000, mask, 1f)
        assertTrue(audit.backgroundCells >= 8 && audit.backgroundQuadrants >= 3)
        assertNotNull(audit.localPersonSupport)
        assertEquals(0, audit.localPersonSupport!!.reliable)
        assertNull("A larger repeated query is not a unique displacement witness", audit.measurement)
    }
}
