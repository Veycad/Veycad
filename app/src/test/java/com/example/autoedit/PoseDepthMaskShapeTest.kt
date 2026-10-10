package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class PoseDepthMaskShapeTest {
    @Test fun posePreservesPortraitLandscapeAndLegacyMaskGeometry() {
        for ((width, height) in listOf(270 to 480, 480 to 270, 256 to 256)) {
            val values = FloatArray(width * height) { (it % 3) / 2f }
            val mask = FrameAttachments.Plane(width, height, values, .95f)
            val depth = SemanticMonocularDepthEstimator.fromPose(mask, .3f, .6f)
            assertEquals(width, depth.width)
            assertEquals(height, depth.height)
            assertEquals(1f, depth.values[0], .00001f)
            assertEquals(.65f, depth.values[1], .00001f)
            assertEquals(.3f, depth.values[2], .00001f)
            assertEquals(.6f, depth.confidence, 0f)
            assertEquals(1f, mask.values[2], 0f)
            assertNotSame(mask.values, depth.values)
        }
    }
}
