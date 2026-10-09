package com.example.autoedit

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deterministic local block-flow for editorial motion blur. It is deliberately computed on a
 * compact luma pyramid: the full-resolution pixels remain in MediaCodec/GLES while this plane
 * supplies direction and confidence to the transition shader.
 */
internal object DenseOpticalFlowEstimator {
    data class Result(
        val flow: FrameAttachments.FlowPlane,
        val meanX: Float,
        val meanY: Float
    )

    fun estimate(
        previous: LumaMotionEstimator.Plane?,
        current: LumaMotionEstimator.Plane,
        gridWidth: Int = 12,
        gridHeight: Int = 18,
        searchRadius: Int = 3,
        patchRadius: Int = 2
    ): Result? {
        if (previous == null || previous.width != current.width || previous.height != current.height) return null
        require(gridWidth > 1 && gridHeight > 1 && searchRadius > 0 && patchRadius > 0)
        val vectors = ArrayList<Float>(gridWidth * gridHeight * 2)
        var confidenceSum = 0f
        var weightedX = 0f
        var weightedY = 0f
        var weight = 0f

        repeat(gridHeight) { gridY ->
            repeat(gridWidth) { gridX ->
                val centerX = ((gridX + .5f) * current.width / gridWidth).toInt()
                    .coerceIn(patchRadius + searchRadius, current.width - patchRadius - searchRadius - 1)
                val centerY = ((gridY + .5f) * current.height / gridHeight).toInt()
                    .coerceIn(patchRadius + searchRadius, current.height - patchRadius - searchRadius - 1)
                val baseline = patchError(previous, current, centerX, centerY, 0, 0, patchRadius)
                var bestError = baseline
                var bestX = 0
                var bestY = 0
                for (dy in -searchRadius..searchRadius) for (dx in -searchRadius..searchRadius) {
                    val error = patchError(previous, current, centerX, centerY, dx, dy, patchRadius)
                    if (error < bestError) {
                        bestError = error
                        bestX = dx
                        bestY = dy
                    }
                }
                val texture = patchTexture(current, centerX, centerY, patchRadius)
                val improvement = if (baseline <= 1e-4f) 0f else ((baseline - bestError) / baseline).coerceIn(0f, 1f)
                val cellConfidence = (improvement * (texture * 5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                val normalizedX = bestX.toFloat() / searchRadius
                val normalizedY = bestY.toFloat() / searchRadius
                vectors += normalizedX
                vectors += normalizedY
                confidenceSum += cellConfidence
                weightedX += normalizedX * cellConfidence
                weightedY += normalizedY * cellConfidence
                weight += cellConfidence
            }
        }
        val meanConfidence = confidenceSum / (gridWidth * gridHeight)
        val coherentMagnitude = if (weight <= 1e-5f) 0f else
            sqrt(weightedX * weightedX + weightedY * weightedY) / weight
        val confidence = (meanConfidence * .65f + coherentMagnitude.coerceIn(0f, 1f) * .35f)
            .coerceIn(0f, 1f)
        return Result(
            flow = FrameAttachments.FlowPlane(gridWidth, gridHeight, vectors, confidence),
            meanX = if (weight <= 1e-5f) 0f else weightedX / weight,
            meanY = if (weight <= 1e-5f) 0f else weightedY / weight
        )
    }

    private fun patchError(
        previous: LumaMotionEstimator.Plane,
        current: LumaMotionEstimator.Plane,
        centerX: Int,
        centerY: Int,
        dx: Int,
        dy: Int,
        radius: Int
    ): Float {
        var error = 0f
        var count = 0
        for (y in -radius..radius) for (x in -radius..radius) {
            // Search the previous frame for the patch that moved into the current position.
            error += abs(current[centerX + x, centerY + y] - previous[centerX + x - dx, centerY + y - dy])
            count++
        }
        return error / count
    }

    private fun patchTexture(
        frame: LumaMotionEstimator.Plane,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Float {
        var variation = 0f
        var count = 0
        val center = frame[centerX, centerY]
        for (y in -radius..radius) for (x in -radius..radius) {
            variation += abs(frame[centerX + x, centerY + y] - center)
            count++
        }
        return variation / count
    }
}
