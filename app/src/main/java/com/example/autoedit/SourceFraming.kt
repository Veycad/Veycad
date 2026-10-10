package com.veycad.app

/** Center-crop in display-oriented source coordinates; never stretches the source image. */
internal object SourceFraming {
    data class Crop(val x: Float, val y: Float) {
        fun sourceX(outputX: Float) = .5f + (outputX - .5f) * x
        fun sourceY(outputY: Float) = .5f + (outputY - .5f) * y
    }

    fun cropPlane(values: FloatArray, width: Int, height: Int, crop: Crop): FloatArray {
        require(values.size == width * height)
        if (crop == Crop(1f, 1f)) return values
        return FloatArray(values.size) { index ->
            val x = (crop.sourceX((index % width + .5f) / width) * width).toInt().coerceIn(0, width - 1)
            val y = (crop.sourceY((index / width + .5f) / height) * height).toInt().coerceIn(0, height - 1)
            values[y * width + x]
        }
    }

    fun crop(encodedWidth: Int, encodedHeight: Int, rotation: Int,
        outputWidth: Int, outputHeight: Int, pixelAspectRatio: Float = 1f): Crop {
        require(encodedWidth > 0 && encodedHeight > 0 && outputWidth > 0 && outputHeight > 0)
        require(rotation in setOf(0, 90, 180, 270) && pixelAspectRatio > 0f && pixelAspectRatio.isFinite())
        val rect = FramingPlan.sampleForAspect(
            SourceGeometry(encodedWidth, encodedHeight, rotation, pixelAspectRatio),
            outputWidth.toDouble() / outputHeight, FramingSettings(FramingMode.MANUAL)
        ).foregroundSource
        return Crop(rect.right - rect.left, rect.bottom - rect.top)
    }
}
