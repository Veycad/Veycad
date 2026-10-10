package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DenseOpticalFlowEstimatorTest {
    @Test fun detects_coherent_horizontal_translation() {
        val previous = texturedPlane(48, 72)
        val current = shifted(previous, dx = 2, dy = 0)

        val nullable = DenseOpticalFlowEstimator.estimate(previous, current)
        assertNotNull(nullable)
        val result = requireNotNull(nullable)

        assertTrue("meanX=${result.meanX}", result.meanX > .35f)
        assertTrue("meanY=${result.meanY}", kotlin.math.abs(result.meanY) < .15f)
        assertTrue("confidence=${result.flow.confidence}", result.flow.confidence > .18f)
        assertEquals(12 * 18 * 2, result.flow.vectors.size)
    }

    @Test fun static_frame_has_zero_confidence_and_flow() {
        val frame = texturedPlane(48, 72)

        val nullable = DenseOpticalFlowEstimator.estimate(frame, frame)
        assertNotNull(nullable)
        val result = requireNotNull(nullable)

        assertEquals(0f, result.meanX, .001f)
        assertEquals(0f, result.meanY, .001f)
        assertEquals(0f, result.flow.confidence, .001f)
        assertEquals(12 * 18 * 2, result.flow.vectors.size)
        assertTrue("Opposite nonzero vectors must not hide behind a zero mean", result.flow.vectors.all { it == 0f })
    }

    @Test fun first_frame_has_no_invented_flow() {
        assertEquals(null, DenseOpticalFlowEstimator.estimate(null, texturedPlane(48, 72)))
    }

    private fun texturedPlane(width: Int, height: Int): LumaMotionEstimator.Plane {
        val values = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (((x * 17 + y * 31 + (x * y) % 29) % 101) / 100f).coerceIn(0f, 1f)
        }
        return LumaMotionEstimator.Plane(width, height, values)
    }

    private fun shifted(source: LumaMotionEstimator.Plane, dx: Int, dy: Int): LumaMotionEstimator.Plane {
        val values = FloatArray(source.width * source.height) { index ->
            val x = index % source.width
            val y = index / source.width
            source[(x - dx).coerceIn(0, source.width - 1), (y - dy).coerceIn(0, source.height - 1)]
        }
        return LumaMotionEstimator.Plane(source.width, source.height, values)
    }
}
