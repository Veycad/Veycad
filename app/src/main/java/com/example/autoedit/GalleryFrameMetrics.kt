package com.veycad.app

import kotlin.math.abs
import kotlin.math.sqrt

data class GalleryFeatures(
    val timeUs: Long,
    val quality: Float,
    val localMotion: Float,
    val cameraInstability: Float,
    val sceneChange: Float,
    val composition: Float,
    val faceConfidence: Float?
)

data class GalleryMoment(
    val sourceId: String,
    val startUs: Long,
    val endUs: Long,
    val features: GalleryFeatures,
    val keyHash: Long
)

/** Small, neutral luma measurements. Unknown faces never disqualify a frame. */
object GalleryFrameMetrics {
    fun measure(timeUs: Long, luma: FloatArray, width: Int, height: Int,
        previous: FloatArray?): GalleryFeatures {
        require(timeUs >= 0 && width > 0 && height > 0 && width.toLong() * height == luma.size.toLong())
        require(luma.all { it.isFinite() && it in 0f..1f })
        require(previous == null || previous.size == luma.size && previous.all { it.isFinite() && it in 0f..1f })
        val mean = luma.average().toFloat()
        var edges = 0f
        var edgeCount = 0
        var centerEdges = 0f
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val edge = (if (x + 1 < width) abs(luma[i] - luma[i + 1]) else 0f) +
                (if (y + 1 < height) abs(luma[i] - luma[i + width]) else 0f)
            edges += edge
            if (x in width / 4 until width * 3 / 4 && y in height / 4 until height * 3 / 4) centerEdges += edge
            edgeCount += (if (x + 1 < width) 1 else 0) + (if (y + 1 < height) 1 else 0)
        }
        val sharpness = (edges / maxOf(1, edgeCount) * 8f).coerceIn(0f, 1f)
        val exposure = (1f - abs(mean - .5f) * 2f).coerceIn(0f, 1f)
        var local = 0f
        var instability = 0f
        var scene = 0f
        if (previous != null) {
            // A bounded global translation search removes camera movement before measuring action.
            // Both errors use the same interior pixels so cropping cannot improve the match.
            val radius = minOf(12, (minOf(width, height) - 1) / 3)
            fun error(dx: Int, dy: Int): Float {
                var total = 0f
                var count = 0
                for (y in radius until height - radius step 2) for (x in radius until width - radius step 2) {
                    total += abs(luma[y * width + x] - previous[(y + dy) * width + x + dx])
                    count++
                }
                return total / maxOf(1, count)
            }
            val unaligned = error(0, 0)
            var best = unaligned
            var bestX = 0
            var bestY = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val candidate = error(dx, dy)
                if (candidate < best - .00001f) { best = candidate; bestX = dx; bestY = dy }
            }
            local = best.coerceIn(0f, 1f)
            instability = if (radius == 0) 0f else
                (sqrt((bestX * bestX + bestY * bestY).toFloat()) / radius).coerceIn(0f, 1f)
            val exposureJump = abs(mean - previous.average().toFloat())
            scene = maxOf(best * 2f, exposureJump * 2f).coerceIn(0f, 1f)
        }
        val composition = if (edges == 0f) .5f else (centerEdges / edges * 2f).coerceIn(0f, 1f)
        val quality = ((.75f * sharpness + .25f * exposure) * (1f - .5f * instability)).coerceIn(0f, 1f)
        return GalleryFeatures(timeUs, quality, local, instability, scene, composition, null)
    }

    /** Fixed spatial signature; identical frames under different selections keep the same key. */
    internal fun keyHash(luma: FloatArray, width: Int, height: Int): Long {
        val mean = luma.average().toFloat()
        var hash = 0L
        for (y in 0..7) for (x in 0..7) {
            if (luma[minOf(height - 1, y * height / 8) * width + minOf(width - 1, x * width / 8)] >= mean)
                hash = hash or (1L shl (y * 8 + x))
        }
        return hash
    }
}
