package com.veycad.app

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent synthetic counterexample: zero net direction is not zero local motion. */
class OpposedMotionDiagnosticTest {
    @Test fun single_object_displacement_does_not_cancel_the_centroid() {
        val previous = scene(18, opposed = false)
        val current = scene(21, opposed = false)
        val estimate = LumaMotionEstimator.estimate(previous, current)
        val changedPixels = previous.luma.indices.count {
            abs(previous.luma[it] - current.luma[it]) > .2f }
        println("single diagnostic: changedPixels=$changedPixels camera=${estimate.cameraMotion.magnitude} " +
            "subjectCentroid=${estimate.subjectMotion.magnitude} vector=${estimate.subjectMotion}")
        assertTrue(changedPixels == 102)
        assertTrue(estimate.subjectMotion.magnitude > .01f)
    }

    @Test fun two_opposed_objects_can_cancel_the_centroid_without_being_static() {
        val previous = scene(18)
        val current = scene(21)
        val estimate = LumaMotionEstimator.estimate(previous, current)
        val changedPixels = previous.luma.indices.count {
            abs(previous.luma[it] - current.luma[it]) > .2f }
        val flow = requireNotNull(DenseOpticalFlowEstimator.estimate(previous, current))
        val activeCells = flow.flow.vectors.chunked(2).count { abs(it[0]) + abs(it[1]) > .1f }
        println("opposed diagnostic: changedPixels=$changedPixels camera=${estimate.cameraMotion.magnitude} " +
            "subjectCentroid=${estimate.subjectMotion.magnitude} activeFlowCells=$activeCells " +
            "meanFlowX=${flow.meanX} meanFlowY=${flow.meanY}")
        assertTrue("The independent scene must contain substantial physical displacement", changedPixels >= 200)
        assertTrue("Static textured background must not become a camera pan", estimate.cameraMotion.magnitude < .01f)
        assertTrue("Symmetric motion has almost zero net direction", estimate.subjectMotion.magnitude < .01f)
        assertTrue("Local block flow must still observe displacement", activeCells > 0)
    }

    @Test fun unchanged_control_has_no_motion_or_pixel_change() {
        val frame = scene(18)
        val estimate = LumaMotionEstimator.estimate(frame, frame)
        val flow = requireNotNull(DenseOpticalFlowEstimator.estimate(frame, frame))
        assertTrue(estimate.cameraMotion.magnitude == 0f)
        assertTrue(estimate.subjectMotion.magnitude == 0f)
        assertTrue(flow.flow.vectors.all { it == 0f })
    }

    private fun scene(leftCenter: Int, opposed: Boolean = true): LumaMotionEstimator.Plane {
        val width = 64
        val pixels = FloatArray(width * width) { i ->
            val x = abs(i % width - 32)
            val y = abs(i / width - 32)
            .1f + ((x * 17 + y * 31 + x * y) % 23) / 22f * .2f
        }
        for (center in if (opposed) listOf(leftCenter, 64 - leftCenter) else listOf(leftCenter)) {
            for (y in 24..40) for (x in center - 4..center + 4) pixels[y * width + x] = .9f
        }
        return LumaMotionEstimator.Plane(width, width, pixels)
    }
}
