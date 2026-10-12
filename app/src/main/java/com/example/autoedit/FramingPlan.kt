package com.veycad.app

/** Coordinates in the already display-oriented source or project, never encoded UVs. */
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Shared framing geometry for video and oriented scalar analysis planes. */
object FramingPlan {
    private val whole = NormalizedRect(0f, 0f, 1f, 1f)

    data class Sample(
        val foregroundSource: NormalizedRect,
        val foregroundDestination: NormalizedRect,
        val backgroundSource: NormalizedRect?,
        /** Gaussian kernel radius (3 * sigma) as a fraction of the project's short side. */
        val blurRadiusFraction: Float
    )

    // First-release renderer policy: separable Gaussian, half-resolution background,
    // sigma = .02 * project short edge; radius = ceil(3 * sigma) in that pass's pixels.
    const val BACKGROUND_RESOLUTION_SCALE = .5f
    const val BLUR_SIGMA_FRACTION = .02f
    const val BLUR_RADIUS_FRACTION = .06f

    fun sample(geometry: SourceGeometry, aspect: ProjectAspect, settings: FramingSettings): Sample {
        val size = aspect.size(360)
        return sampleForAspect(geometry, size.width.toDouble() / size.height, settings)
    }

    internal fun sampleForAspect(geometry: SourceGeometry, outputAspect: Double, settings: FramingSettings): Sample {
        val sourceAspect = VideoDisplayOrientation.displayAspect(geometry)
        val fillWidth = minOf(1.0, outputAspect / sourceAspect).toFloat()
        val fillHeight = minOf(1.0, sourceAspect / outputAspect).toFloat()
        val centeredFill = window(fillWidth, fillHeight, .5f, .5f)
        if (settings.mode == FramingMode.BLURRED_FIT) {
            val fitWidth = minOf(1.0, sourceAspect / outputAspect).toFloat()
            val fitHeight = minOf(1.0, outputAspect / sourceAspect).toFloat()
            return Sample(whole, window(fitWidth, fitHeight, .5f, .5f), centeredFill, BLUR_RADIUS_FRACTION)
        }
        return Sample(window(fillWidth / settings.zoom, fillHeight / settings.zoom,
            settings.centerX, settings.centerY), whole, null, 0f)
    }

    fun clampManual(geometry: SourceGeometry, aspect: ProjectAspect, settings: FramingSettings): FramingSettings {
        val rect = sample(geometry, aspect, settings.copy(mode = FramingMode.MANUAL)).foregroundSource
        return settings.copy(centerX = (rect.left + rect.right) / 2f, centerY = (rect.top + rect.bottom) / 2f)
    }

    private fun window(width: Float, height: Float, centerX: Float, centerY: Float): NormalizedRect {
        val left = (centerX - width / 2f).coerceIn(0f, 1f - width)
        val top = (centerY - height / 2f).coerceIn(0f, 1f - height)
        return NormalizedRect(left, top, left + width, top + height)
    }

    /**
     * Nearest-neighbor mapping of already oriented scalar planes at project pixel centers.
     * Foreground QA leaves fit bars zero. With foregroundOnly=false a supplied background
     * fills the project; without a background this uses the ordinary foreground mapping.
     * This deliberately does not rotate planes or transform flow vectors.
     */
    fun mapPlane(plane: FrameAttachments.Plane, sample: Sample, foregroundOnly: Boolean = true): FrameAttachments.Plane {
        val background = if (foregroundOnly) null else sample.backgroundSource
        val source = background ?: sample.foregroundSource
        val destination = if (background != null) whole else sample.foregroundDestination
        if (source == whole && destination == whole) return plane
        val values = FloatArray(plane.values.size) { index ->
            val x = (index % plane.width + .5f) / plane.width
            val y = (index / plane.width + .5f) / plane.height
            if (x < destination.left || x >= destination.right || y < destination.top || y >= destination.bottom) 0f
            else {
                val u = source.left + (x - destination.left) / (destination.right - destination.left) * (source.right - source.left)
                val v = source.top + (y - destination.top) / (destination.bottom - destination.top) * (source.bottom - source.top)
                val sx = (u * plane.width).toInt().coerceIn(0, plane.width - 1)
                val sy = (v * plane.height).toInt().coerceIn(0, plane.height - 1)
                plane.values[sy * plane.width + sx]
            }
        }
        return FrameAttachments.Plane(plane.width, plane.height, values, plane.confidence)
    }
}
