package com.example.autoedit

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Lightweight fallback evidence extractor; local ML adapters can replace its individual fields. */
internal object LumaMotionEstimator {
    data class Plane(val width: Int, val height: Int, val luma: FloatArray) {
        init { require(width > 4 && height > 4 && luma.size == width * height && luma.all { it in 0f..1f }) }
        operator fun get(x: Int, y: Int): Float = luma[y * width + x]
    }

    data class Estimate(
        val cameraMotion: VisualEventMap.Vector,
        val subjectMotion: VisualEventMap.Vector,
        val occlusionConfidence: Float,
        val visualQuality: Float,
        val meanLuma: Float,
        val composition: VisualEventMap.Composition,
        val sceneChangeConfidence: Float = 0f
    )

    fun estimate(previous: Plane?, current: Plane, maxShift: Int = 3): Estimate {
        require(maxShift in 1..minOf(current.width, current.height) / 4)
        val mean = current.luma.average().toFloat()
        val variance = current.luma.sumOf { value ->
            val delta = value - mean
            (delta * delta).toDouble()
        }.toFloat() / current.luma.size
        val contrast = (sqrt(variance) * 4f).coerceIn(0f, 1f)
        val edgeDensity = edgeDensity(current)
        val composition = VisualEventMap.Composition(
            subjectScale = centerEdgeRatio(current),
            headroomScore = .58f,
            lookRoomScore = .58f,
            backgroundSimplicity = (1f - edgeDensity * 1.8f).coerceIn(0f, 1f),
            contrastScore = contrast
        )
        if (previous == null || previous.width != current.width || previous.height != current.height) {
            val occlusion = darkOcclusion(mean, variance, null)
            return Estimate(VisualEventMap.Vector(), VisualEventMap.Vector(), occlusion,
                visualQuality(mean, composition.quality, occlusion), mean, composition)
        }

        val baseline = alignmentError(previous, current, 0, 0)
        var bestError = baseline
        var bestX = 0
        var bestY = 0
        for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            val error = alignmentError(previous, current, dx, dy)
            if (error < bestError) {
                bestError = error
                bestX = dx
                bestY = dy
            }
        }
        val improvement = if (baseline <= 1e-5f) 0f else ((baseline - bestError) / baseline).coerceIn(0f, 1f)
        val cameraStrength = (hypot(bestX.toFloat(), bestY.toFloat()) / maxShift * improvement * 1.8f)
            .coerceIn(0f, 1f)
        val residual = residualMotion(previous, current, bestX, bestY)
        val occlusion = darkOcclusion(mean, variance, previous.luma.average().toFloat())
        return Estimate(
            cameraMotion = VisualEventMap.Vector(
                x = bestX.sign() * cameraStrength,
                y = bestY.sign() * cameraStrength
            ),
            subjectMotion = residual,
            occlusionConfidence = occlusion,
            visualQuality = visualQuality(mean, composition.quality, occlusion),
            meanLuma = mean,
            composition = composition,
            // A coherent pan is explained by alignment. Large unexplained residual changes
            // are only candidate shot boundaries; the style decides whether to avoid them.
            sceneChangeConfidence = ((bestError - .08f) / .16f).coerceIn(0f, 1f) * (1f - improvement)
        )
    }

    private fun visualQuality(mean: Float, composition: Float, occlusion: Float): Float {
        val exposure = (1f - abs(mean - .5f) * 1.9f).coerceIn(0f, 1f)
        return ((exposure * .55f + composition * .45f) * (1f - occlusion * .85f)).coerceIn(0f, 1f)
    }

    private fun alignmentError(previous: Plane, current: Plane, dx: Int, dy: Int): Float {
        var sum = 0f
        var count = 0
        for (y in 3 until current.height - 3) for (x in 3 until current.width - 3) {
            val previousX = x - dx
            val previousY = y - dy
            if (previousX in 0 until previous.width && previousY in 0 until previous.height) {
                sum += abs(current[x, y] - previous[previousX, previousY])
                count++
            }
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun residualMotion(previous: Plane, current: Plane, dx: Int, dy: Int): VisualEventMap.Vector {
        var weightedX = 0f
        var weightedY = 0f
        var weight = 0f
        val left = current.width / 5
        val right = current.width * 4 / 5
        val top = current.height / 6
        val bottom = current.height * 5 / 6
        for (y in top until bottom) for (x in left until right) {
            val px = x - dx
            val py = y - dy
            if (px !in 0 until previous.width || py !in 0 until previous.height) continue
            val difference = abs(current[x, y] - previous[px, py])
            weightedX += difference * (x - current.width / 2f) / current.width
            weightedY += difference * (y - current.height / 2f) / current.height
            weight += difference
        }
        val strength = (weight / ((right - left) * (bottom - top)).coerceAtLeast(1) * 3.5f).coerceIn(0f, 1f)
        if (weight <= 1e-5f) return VisualEventMap.Vector()
        val directionX = (weightedX / weight * 5f).coerceIn(-1f, 1f)
        val directionY = (weightedY / weight * 5f).coerceIn(-1f, 1f)
        return VisualEventMap.Vector(directionX * strength, directionY * strength)
    }

    private fun darkOcclusion(mean: Float, variance: Float, previousMean: Float?): Float {
        val darkness = ((.2f - mean) / .2f).coerceIn(0f, 1f)
        val flatness = ((.02f - variance) / .02f).coerceIn(0f, 1f)
        val suddenDrop = previousMean?.let { ((it - mean - .18f) / .35f).coerceIn(0f, 1f) } ?: 0f
        return maxOf(darkness * flatness, suddenDrop * .85f).coerceIn(0f, 1f)
    }

    private fun edgeDensity(frame: Plane): Float {
        var edges = 0f
        var count = 0
        for (y in 1 until frame.height - 1) for (x in 1 until frame.width - 1) {
            edges += abs(frame[x + 1, y] - frame[x - 1, y]) + abs(frame[x, y + 1] - frame[x, y - 1])
            count++
        }
        return (edges / count.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun centerEdgeRatio(frame: Plane): Float {
        val left = frame.width / 4
        val right = frame.width * 3 / 4
        val top = frame.height / 5
        val bottom = frame.height * 4 / 5
        var center = 0f
        var outer = 0f
        var centerCount = 0
        var outerCount = 0
        for (y in 1 until frame.height - 1) for (x in 1 until frame.width - 1) {
            val edge = abs(frame[x + 1, y] - frame[x - 1, y]) + abs(frame[x, y + 1] - frame[x, y - 1])
            if (x in left until right && y in top until bottom) { center += edge; centerCount++ }
            else { outer += edge; outerCount++ }
        }
        val centerAverage = center / centerCount.coerceAtLeast(1)
        val outerAverage = outer / outerCount.coerceAtLeast(1)
        return (centerAverage / (centerAverage + outerAverage + 1e-5f)).coerceIn(0f, 1f)
    }

    private fun Int.sign(): Float = when {
        this > 0 -> 1f
        this < 0 -> -1f
        else -> 0f
    }
}
