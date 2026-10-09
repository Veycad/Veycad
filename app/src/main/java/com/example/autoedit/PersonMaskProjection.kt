package com.example.autoedit

/** Full-frame mask texels and motion pixels share normalized FOV, with pixel-center coordinates.
 * This is geometric sampling only, not proof of person identity or mask accuracy.
 */
internal object PersonMaskProjection {
    fun patchMinimum(mask: FrameAttachments.Plane, planeWidth: Int, planeHeight: Int,
                     x: Int, y: Int, radiusX: Int, radiusY: Int): Float {
        require(radiusX >= 0 && radiusY >= 0 && x - radiusX >= 0 && x + radiusX < planeWidth &&
            y - radiusY >= 0 && y + radiusY < planeHeight)
        var minimum = 1f
        for (py in y - radiusY..y + radiusY) for (px in x - radiusX..x + radiusX)
            minimum = minOf(minimum, sample(mask, planeWidth, planeHeight, px, py))
        return minimum
    }

    /** Maximum person probability over every native pixel used by the current query patch.
     * A background center does not make the rest of that patch background.
     */
    fun patchMaximum(mask: FrameAttachments.Plane, planeWidth: Int, planeHeight: Int,
                     x: Int, y: Int, radiusX: Int, radiusY: Int): Float {
        require(radiusX >= 0 && radiusY >= 0 && x - radiusX >= 0 && x + radiusX < planeWidth &&
            y - radiusY >= 0 && y + radiusY < planeHeight)
        var maximum = 0f
        for (py in y - radiusY..y + radiusY) for (px in x - radiusX..x + radiusX)
            maximum = maxOf(maximum, sample(mask, planeWidth, planeHeight, px, py))
        return maximum
    }

    fun sample(mask: FrameAttachments.Plane, planeWidth: Int, planeHeight: Int, x: Int, y: Int): Float {
        require(planeWidth > 0 && planeHeight > 0 && x in 0 until planeWidth && y in 0 until planeHeight)
        val mx = ((x + .5f) * mask.width / planeWidth - .5f).coerceIn(0f, mask.width - 1f)
        val my = ((y + .5f) * mask.height / planeHeight - .5f).coerceIn(0f, mask.height - 1f)
        val left = mx.toInt(); val top = my.toInt()
        val right = (left + 1).coerceAtMost(mask.width - 1)
        val bottom = (top + 1).coerceAtMost(mask.height - 1)
        val fx = mx - left; val fy = my - top
        val a = mask.values[top * mask.width + left] * (1f - fx) + mask.values[top * mask.width + right] * fx
        val b = mask.values[bottom * mask.width + left] * (1f - fx) + mask.values[bottom * mask.width + right] * fx
        return a * (1f - fy) + b * fy
    }
}
