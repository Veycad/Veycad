package com.example.autoedit

import kotlin.math.roundToInt

/** Full-frame FOV coordinates. Resolution is not an extra motion multiplier. */
internal object MotionAnalysisGeometry {
    const val METHOD = "person-adaptive-affine-background-query-fov-v9"
    const val REFERENCE_WIDTH = 48
    const val REFERENCE_HEIGHT = 72
    const val MAX_LONG_SIDE = 144

    data class Size(val width: Int, val height: Int)

    fun size(width: Int, height: Int): Size {
        require(width > 4 && height > 4)
        val scale = minOf(1.0, MAX_LONG_SIDE.toDouble() / maxOf(width, height))
        val resized = Size((width * scale).roundToInt(), (height * scale).roundToInt())
        // Do not pad/crop a pathological thin frame into a different FOV. Matching will fail
        // closed if there is insufficient spatial support; provider bitmaps are already bounded.
        return if (minOf(resized.width, resized.height) <= 4) Size(width, height) else resized
    }

    fun sampling(plane: LumaMotionEstimator.Plane) = LocalMotionCorrespondence.Sampling(
        plane.width.toFloat() / REFERENCE_WIDTH, plane.height.toFloat() / REFERENCE_HEIGHT)
}
