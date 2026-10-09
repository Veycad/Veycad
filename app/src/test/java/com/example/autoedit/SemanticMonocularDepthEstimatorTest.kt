package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class SemanticMonocularDepthEstimatorTest {
    @Test fun person_is_nearer_than_background_and_closeup_is_nearer_than_wide_shot() {
        val mask = FrameAttachments.Plane(
            3,
            2,
            listOf(0f, 1f, 0f, 0f, 1f, 0f),
            .92f
        )

        val wide = SemanticMonocularDepthEstimator.estimate(mask, .25f, .9f)
        val close = SemanticMonocularDepthEstimator.estimate(mask, .75f, .9f)

        assertTrue(wide.values[1] < wide.values[0])
        assertTrue(close.values[1] < wide.values[1])
        assertTrue(close.confidence >= .70f)
        assertEquals(.5825f, wide.values[1], .000001f)
        assertEquals(.3075f, close.values[1], .000001f)
        assertEquals(.84f, wide.values[0], .000001f)
        assertEquals(.89f, wide.values[3], .000001f)
        assertEquals(.76544f, close.confidence, .000001f)
        assertEquals(3, wide.width)
        assertEquals(2, wide.height)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f), mask.values, 0f)
        assertNotSame(mask.values, wide.values)
    }
}
