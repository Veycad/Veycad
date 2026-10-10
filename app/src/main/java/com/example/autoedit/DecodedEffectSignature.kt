package com.veycad.app

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Pixel-only signatures used after MP4 decode; no graph parameter can satisfy these functions. */
object DecodedEffectSignature {
    /** New dead regions on a visible source, rather than already-dark clothing or scenery. */
    fun introducedBlackBlocks(luma: FloatArray, sourceLuma: FloatArray, width: Int, height: Int): Float {
        requirePlane(luma, width, height)
        requirePlane(sourceLuma, width, height)
        var dead = 0
        var tiles = 0
        for (top in 0 until height step 6) for (left in 0 until width step 6) {
            var sum = 0f
            var squares = 0f
            var sourceSum = 0f
            var count = 0
            for (y in top until minOf(top + 6, height)) for (x in left until minOf(left + 6, width)) {
                val index = y * width + x
                val value = luma[index]
                sum += value
                squares += value * value
                sourceSum += sourceLuma[index]
                count++
            }
            val mean = sum / count
            // Sigma's grade legitimately moves dark input below the dead-tile threshold.
            // Use the minimum retained light under the Sigma gamma/gain/background grade.
            val minimumExpectedLight = (sourceSum / count).pow(1.38f) * .90f * .35f
            if (mean < .025f && squares / count - mean * mean < .0008f &&
                minimumExpectedLight >= .025f) dead++
            tiles++
        }
        return dead.toFloat() / tiles
    }

    fun doubleExposure(luma: FloatArray, width: Int, height: Int): Float {
        requirePlane(luma, width, height)
        val gradients = horizontalGradients(luma, width, height)
        val edgeEnergy = gradients.average().toFloat()
        val ghostCorrelation = (2..4).maxOf { offset ->
            var numerator = 0f
            var leftEnergy = 0f
            var rightEnergy = 0f
            repeat(height) { y ->
                for (x in 0 until width - offset) {
                    val left = gradients[y * width + x]
                    val right = gradients[y * width + x + offset]
                    numerator += left * right
                    leftEnergy += left * left
                    rightEnergy += right * right
                }
            }
            numerator / sqrt(leftEnergy * rightEnergy).coerceAtLeast(.000001f)
        }
        return (edgeEnergy * (1f + ghostCorrelation) * 4f).coerceIn(0f, 1f)
    }

    fun mirrorSlice(luma: FloatArray, width: Int, height: Int, progress: Float): Float {
        requirePlane(luma, width, height)
        val seamX = (width * (.62f + (.38f - .62f) * progress.coerceIn(0f, 1f)))
            .toInt().coerceIn(1, width - 1)
        var seamSum = 0f
        var baselineSum = 0f
        var baselineCount = 0
        repeat(height) { y ->
            seamSum += abs(luma[y * width + seamX] - luma[y * width + seamX - 1])
            for (x in 1 until width) {
                if (abs(x - seamX) > 2) {
                    baselineSum += abs(luma[y * width + x] - luma[y * width + x - 1])
                    baselineCount++
                }
            }
        }
        val seam = seamSum / height
        val baseline = baselineSum / baselineCount.coerceAtLeast(1)
        return ((seam - baseline).coerceAtLeast(0f) * 4.5f).coerceIn(0f, 1f)
    }

    fun glitch(
        luma: FloatArray,
        chromaU: FloatArray,
        chromaV: FloatArray,
        width: Int,
        height: Int
    ): Float {
        requirePlane(luma, width, height)
        requirePlane(chromaU, width, height)
        requirePlane(chromaV, width, height)
        var excess = 0f
        var count = 0
        repeat(height) { y ->
            for (x in 1 until width) {
                val index = y * width + x
                val left = index - 1
                val lumaEdge = abs(luma[index] - luma[left])
                val u = chromaU[index] - chromaU[left]
                val v = chromaV[index] - chromaV[left]
                val chromaEdge = sqrt(u * u + v * v)
                excess += (chromaEdge - lumaEdge * .35f).coerceAtLeast(0f)
                count++
            }
        }
        return (excess / count.coerceAtLeast(1) * 3.5f).coerceIn(0f, 1f)
    }

    /** Displaced horizontal fragments relative to the same source frame, after camera alignment.
     * A whole-frame translation has one offset and cannot satisfy this signature.
     */
    fun horizontalFragmentation(luma: FloatArray, sourceLuma: FloatArray, width: Int, height: Int): Float {
        requirePlane(luma, width, height)
        requirePlane(sourceLuma, width, height)
        require(width >= 24 && height >= 24)
        val output = horizontalGradients(luma, width, height)
        val source = horizontalGradients(sourceLuma, width, height)
        val radius = width / 6
        val margin = radius + 1
        val blockHeight = maxOf(2, height / 16)
        val blockPixels = blockHeight * (width - margin * 2)
        var textured = 0
        var matched = 0
        var offsetSum = 0f
        var offsetSquares = 0f
        for (top in blockHeight until height - blockHeight * 2 step blockHeight) {
            var edgeSum = 0f
            var outputEnergy = 0f
            for (y in top until top + blockHeight) for (x in margin until width - margin) {
                val edge = output[y * width + x]
                edgeSum += edge
                outputEnergy += edge * edge
            }
            if (edgeSum / blockPixels < .018f) continue
            outputEnergy -= edgeSum * edgeSum / blockPixels
            textured++
            var bestCorrelation = 0f
            var bestOffset = 0
            for (dy in -2..2) for (dx in -radius..radius) {
                var correlation = 0f
                var sourceEnergy = 0f
                var sourceSum = 0f
                for (y in top until top + blockHeight) for (x in margin until width - margin) {
                    val edge = source[(y + dy) * width + x + dx]
                    correlation += output[y * width + x] * edge
                    sourceEnergy += edge * edge
                    sourceSum += edge
                }
                correlation -= edgeSum * sourceSum / blockPixels
                sourceEnergy -= sourceSum * sourceSum / blockPixels
                correlation /= sqrt((outputEnergy * sourceEnergy).coerceAtLeast(0f)).coerceAtLeast(.000001f)
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation
                    bestOffset = dx
                }
            }
            // Skin/background blocks and unrelated textures must not supply arbitrary offsets.
            if (bestCorrelation <= .4f) continue
            matched++
            offsetSum += bestOffset
            offsetSquares += bestOffset * bestOffset
        }
        if (matched < maxOf(4, (textured + 1) / 2)) return 0f
        val mean = offsetSum / matched
        val variance = (offsetSquares / matched - mean * mean).coerceAtLeast(0f)
        // Allow 2.5% of frame width for resampling/registration noise before measuring fragments.
        return (sqrt(variance) / width - .025f).coerceIn(0f, 1f)
    }

    private fun horizontalGradients(values: FloatArray, width: Int, height: Int): FloatArray {
        val output = FloatArray(values.size)
        repeat(height) { y ->
            for (x in 1 until width) {
                val index = y * width + x
                output[index] = abs(values[index] - values[index - 1])
            }
        }
        return output
    }

    private fun requirePlane(values: FloatArray, width: Int, height: Int) {
        require(width > 1 && height > 1 && values.size == width * height)
        require(values.all(Float::isFinite))
    }
}
