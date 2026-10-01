package com.example.autoedit

import kotlin.math.abs

/** Pure confidence and temporal-stability rules for locally produced person masks. */
object SemanticMaskMetrics {
    data class Stats(val coverage: Float, val separation: Float, val confidence: Float)

    fun stats(values: FloatArray): Stats {
        require(values.isNotEmpty() && values.all { it.isFinite() })
        var foreground = 0
        var separationSum = 0f
        values.forEach { raw ->
            val value = raw.coerceIn(0f, 1f)
            if (value >= .5f) foreground++
            separationSum += abs(value - .5f) * 2f
        }
        val coverage = foreground.toFloat() / values.size
        val separation = separationSum / values.size
        val plausibleCoverage = coverage in .035f..0.90f
        val confidence = if (plausibleCoverage) (.74f + separation * .25f) else separation * .48f
        return Stats(coverage, separation.coerceIn(0f, 1f), confidence.coerceIn(0f, .99f))
    }

    fun temporalIou(previous: FloatArray?, current: FloatArray): Float {
        if (previous == null || previous.size != current.size) return 0f
        var intersection = 0
        var union = 0
        current.indices.forEach { index ->
            val wasPerson = previous[index] >= .5f
            val isPerson = current[index] >= .5f
            if (wasPerson && isPerson) intersection++
            if (wasPerson || isPerson) union++
        }
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    fun downsample(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        width: Int,
        height: Int
    ): FloatArray {
        require(source.size == sourceWidth * sourceHeight && width > 0 && height > 0)
        return FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val x0 = x * sourceWidth / width
            val x1 = ((x + 1) * sourceWidth / width).coerceAtLeast(x0 + 1).coerceAtMost(sourceWidth)
            val y0 = y * sourceHeight / height
            val y1 = ((y + 1) * sourceHeight / height).coerceAtLeast(y0 + 1).coerceAtMost(sourceHeight)
            var sum = 0f
            var count = 0
            for (sourceY in y0 until y1) for (sourceX in x0 until x1) {
                sum += source[sourceY * sourceWidth + sourceX]
                count++
            }
            (sum / count.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }

    /** Removes isolated pinholes/speckles and applies a small confidence-aware edge smoother. */
    fun refine(values: FloatArray, width: Int, height: Int): FloatArray {
        require(values.size == width * height && values.all { it.isFinite() })
        val repaired = FloatArray(values.size) { index ->
            val x = index % width
            val y = index / width
            val center = values[index].coerceIn(0f, 1f)
            var foreground = 0
            var sum = 0f
            var sampleCount = 0
            for (dy in -1..1) for (dx in -1..1) {
                val sampleX = x + dx
                val sampleY = y + dy
                if (sampleX in 0 until width && sampleY in 0 until height) {
                    val value = values[sampleY * width + sampleX].coerceIn(0f, 1f)
                    sum += value
                    sampleCount++
                    if (value >= .5f) foreground++
                }
            }
            when {
                center < .5f && foreground >= sampleCount - 2 -> maxOf(center, .72f)
                center >= .5f && foreground <= maxOf(1, sampleCount / 4) -> minOf(center, .28f)
                // Preserve ML Kit's soft hair alpha. Heavy box smoothing expands bright
                // background spill into a visible halo once the subject is placed on black.
                else -> center * .82f + (sum / sampleCount.coerceAtLeast(1)) * .18f
            }.coerceIn(0f, 1f)
        }
        return repaired
    }

    /**
     * Snaps the uncertain semantic fringe to a real colour edge without turning fine dark hair
     * into background. The semantic model remains authoritative in confident interiors; only
     * the soft 8%-92% band is guided by neighbouring foreground/background colours.
     */
    fun guidedRefine(
        values: FloatArray,
        luma: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        require(values.size == width * height && luma.size == values.size)
        require(values.all { it.isFinite() } && luma.all { it.isFinite() })
        val repaired = refine(values, width, height)
        return FloatArray(values.size) { index ->
            val center = repaired[index]
            if (center <= .08f || center >= .92f) return@FloatArray center
            val x = index % width
            val y = index / width
            var weightedAlpha = 0f
            var totalWeight = 0f
            var backgroundLuma = 0f
            var backgroundWeight = 0f
            var foregroundLuma = 0f
            var foregroundWeight = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val sampleX = x + dx
                val sampleY = y + dy
                if (sampleX !in 0 until width || sampleY !in 0 until height) continue
                val sampleIndex = sampleY * width + sampleX
                val alpha = repaired[sampleIndex]
                val colourWeight = (1f - abs(luma[sampleIndex] - luma[index]) * 3.2f)
                    .coerceIn(.08f, 1f)
                val spatialWeight = when {
                    dx == 0 && dy == 0 -> 1f
                    dx == 0 || dy == 0 -> .72f
                    else -> .52f
                }
                val weight = colourWeight * spatialWeight
                weightedAlpha += alpha * weight
                totalWeight += weight
                if (alpha <= .20f) {
                    backgroundLuma += luma[sampleIndex] * weight
                    backgroundWeight += weight
                } else if (alpha >= .80f) {
                    foregroundLuma += luma[sampleIndex] * weight
                    foregroundWeight += weight
                }
            }
            var guided = center * .52f + weightedAlpha / totalWeight.coerceAtLeast(.001f) * .48f
            if (backgroundWeight > 0f && foregroundWeight > 0f) {
                val background = backgroundLuma / backgroundWeight
                val foreground = foregroundLuma / foregroundWeight
                val distanceToBackground = abs(luma[index] - background)
                val distanceToForeground = abs(luma[index] - foreground)
                val colourOwnership = distanceToBackground /
                    (distanceToBackground + distanceToForeground).coerceAtLeast(.015f)
                val ambiguity = (1f - abs(center - .5f) * 2f).coerceIn(0f, 1f)
                guided = guided * (1f - ambiguity * .42f) + colourOwnership * ambiguity * .42f
            }
            guided.coerceIn(0f, 1f)
        }
    }

    /** Applies the GLES camera in pixel space; Y translations use bitmap (downward) coordinates. */
    fun transform(
        values: FloatArray,
        width: Int,
        height: Int,
        scale: Float,
        translateX: Float,
        translateY: Float,
        rotationDegrees: Float = 0f,
        outputAspect: Float = 1f,
        preTranslateY: Float = 0f
    ): FloatArray {
        require(values.size == width * height && scale > 0f && outputAspect > 0f)
        val angle = Math.toRadians(rotationDegrees.toDouble())
        val cosine = kotlin.math.cos(angle).toFloat()
        val sine = kotlin.math.sin(angle).toFloat()
        return FloatArray(values.size) { index ->
            val outputX = (index % width + .5f) / width
            val outputY = (index / width + .5f) / height
            val dx = outputX - .5f - translateX
            val dy = outputY - .5f - translateY
            val sourceX = (dx * cosine - dy * sine / outputAspect) / scale + .5f
            val sourceY = (dx * outputAspect * sine + dy * cosine) / scale + .5f - preTranslateY
            if (sourceX !in 0f..1f || sourceY !in 0f..1f) 0f else {
                val x = (sourceX * width).toInt().coerceIn(0, width - 1)
                val y = (sourceY * height).toInt().coerceIn(0, height - 1)
                values[y * width + x]
            }
        }
    }

    /** Output foreground outside a one-cell dilation of the authored matte. */
    fun edgeLeakRatio(expected: FloatArray, actual: FloatArray, width: Int, height: Int): Float {
        return edgeLeakRatio(expected, actual, width, height, 0, 0, 1)
    }

    /** Registers independently inferred source/output mattes before enforcing the same leak gate. */
    fun registeredEdgeLeakRatio(
        expected: FloatArray,
        actual: FloatArray,
        width: Int,
        height: Int,
        maximumShift: Int = 2
    ): Float {
        require(maximumShift >= 0)
        return (-maximumShift..maximumShift).minOf { shiftY ->
            (-maximumShift..maximumShift).minOf { shiftX ->
                edgeLeakRatio(expected, actual, width, height, shiftX, shiftY, 2)
            }
        }
    }

    /** Temporal IoU after compensating only for a tiny detector-registration shift. */
    fun registeredTemporalIou(
        previous: FloatArray,
        current: FloatArray,
        width: Int,
        height: Int,
        maximumShift: Int = 2
    ): Float {
        require(previous.size == width * height && current.size == previous.size)
        require(maximumShift >= 0)
        return (-maximumShift..maximumShift).maxOf { shiftY ->
            (-maximumShift..maximumShift).maxOf { shiftX ->
                temporalIou(previous, current, width, height, shiftX, shiftY)
            }
        }
    }

    private fun temporalIou(
        previous: FloatArray,
        current: FloatArray,
        width: Int,
        height: Int,
        shiftX: Int,
        shiftY: Int
    ): Float {
        var intersection = 0
        var union = 0
        repeat(height) { y -> repeat(width) { x ->
            val previousX = x - shiftX
            val previousY = y - shiftY
            val wasPerson = previousX in 0 until width && previousY in 0 until height &&
                previous[previousY * width + previousX] >= .5f
            val isPerson = current[y * width + x] >= .5f
            if (wasPerson && isPerson) intersection++
            if (wasPerson || isPerson) union++
        } }
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun edgeLeakRatio(
        expected: FloatArray,
        actual: FloatArray,
        width: Int,
        height: Int,
        shiftX: Int,
        shiftY: Int,
        dilation: Int
    ): Float {
        require(expected.size == width * height && actual.size == expected.size)
        var actualForeground = 0
        var leaked = 0
        repeat(height) { y -> repeat(width) { x ->
            val index = y * width + x
            if (actual[index] >= .5f) {
                actualForeground++
                var covered = false
                for (dy in -dilation..dilation) for (dx in -dilation..dilation) {
                    val expectedX = x - shiftX + dx
                    val expectedY = y - shiftY + dy
                    if (expectedX in 0 until width && expectedY in 0 until height &&
                        expected[expectedY * width + expectedX] >= .5f) covered = true
                }
                if (!covered) leaked++
            }
        } }
        return if (actualForeground == 0) 1f else leaked.toFloat() / actualForeground
    }
}
