package com.example.autoedit

/** Sigma's measured entrance and scene-preserving composition rules. */
internal object SigmaComposition {
    // Keep the head low until the last part of the entrance, then arrive before the plate reveal.
    fun entranceTravel(timeUs: Long): Float {
        val seconds = timeUs / 1_000_000f
        if (seconds <= 2f) return (.78f - seconds.coerceAtLeast(0f) * .255f)
        val t = ((seconds - 2f) / .45f).coerceIn(0f, 1f)
        return .27f * (1f - t * t * (3f - 2f * t))
    }

    const val OPENING_GHOST_SCALE = 1.055f
    const val OPENING_GHOST_X = .025f
    const val OPENING_GHOST_Y = -.008f

    /** Source-space projection of both authored entrance layers before the clip camera. */
    fun openingMask(values: FloatArray, width: Int, height: Int, crop: SourceFraming.Crop,
        travel: Float, includeGhost: Boolean): FloatArray {
        require(values.size == width * height)
        fun at(x: Float, glY: Float): Float {
            if (x !in 0f..1f || glY !in 0f..1f) return 0f
            val sx = (x * width).toInt().coerceIn(0, width - 1)
            val sy = ((1f - glY) * height).toInt().coerceIn(0, height - 1)
            return values[sy * width + sx]
        }
        return FloatArray(values.size) { i ->
            val x = crop.sourceX((i % width + .5f) / width)
            val y = crop.sourceY(1f - (i / width + .5f) / height) + travel * crop.y
            val main = at(x, y)
            if (!includeGhost) main else maxOf(main,
                at(.50f + (x - OPENING_GHOST_X - .50f) / OPENING_GHOST_SCALE,
                    .48f + (y - OPENING_GHOST_Y - .48f) / OPENING_GHOST_SCALE))
        }
    }

    fun visibleOpeningMask(detected: FloatArray, expected: FloatArray, luma: FloatArray): FloatArray {
        require(detected.size == expected.size && detected.size == luma.size)
        return FloatArray(detected.size) { i ->
            if (expected[i] < .15f && luma[i] < .008f) 0f else detected[i]
        }
    }

    fun faceScaleFit(faceWidth: Float, mediumOrWide: Boolean): Float {
        val limit = if (mediumOrWide) .56f else .72f
        return (1f - (faceWidth - limit).coerceAtLeast(0f) * 3f).coerceIn(.15f, 1f)
    }

    /** Fraction of visible source-background pixels erased to black; dark source is not a defect. */
    fun backgroundLoss(output: FloatArray, source: FloatArray, subject: FloatArray): Float {
        require(output.size == source.size && output.size == subject.size)
        var visible = 0
        var lost = 0
        output.indices.forEach { i ->
            if (subject[i] <= .15f && source[i] >= .20f) {
                visible++
                if (output[i] < .03f) lost++
            }
        }
        return if (visible == 0) 0f else lost.toFloat() / visible
    }
}
