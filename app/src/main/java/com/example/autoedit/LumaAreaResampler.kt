package com.veycad.app

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Exact separable area integration of piecewise-constant source pixels, not point decimation. */
internal object LumaAreaResampler {
    fun resize(source: LumaMotionEstimator.Plane, width: Int, height: Int): LumaMotionEstimator.Plane {
        require(width > 4 && height > 4)
        if (width == source.width && height == source.height) return source
        val xScale = source.width.toDouble() / width
        val yScale = source.height.toDouble() / height
        val horizontal = FloatArray(width * source.height)
        for (y in 0 until source.height) for (x in 0 until width) {
            val start = x * xScale; val end = (x + 1) * xScale
            var total = 0.0
            for (sx in floor(start).toInt() until ceil(end).toInt().coerceAtMost(source.width)) {
                val overlap = max(0.0, min(end, sx + 1.0) - max(start, sx.toDouble()))
                total += source[sx, y] * overlap
            }
            horizontal[y * width + x] = (total / xScale).toFloat().coerceIn(0f, 1f)
        }
        val result = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val start = y * yScale; val end = (y + 1) * yScale
            var total = 0.0
            for (sy in floor(start).toInt() until ceil(end).toInt().coerceAtMost(source.height)) {
                val overlap = max(0.0, min(end, sy + 1.0) - max(start, sy.toDouble()))
                total += horizontal[sy * width + x] * overlap
            }
            result[y * width + x] = (total / yScale).toFloat().coerceIn(0f, 1f)
        }
        return LumaMotionEstimator.Plane(width, height, result)
    }
}
