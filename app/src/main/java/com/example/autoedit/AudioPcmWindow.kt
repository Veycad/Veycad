package com.veycad.app

/** Crops decoder seek preroll using complete PCM frames, independent of codec buffer sizes. */
internal object AudioPcmWindow {
    fun frames(bufferTimeUs: Long, frameCount: Int, sampleRate: Int,
        startUs: Long, durationUs: Long): IntRange {
        require(frameCount >= 0 && sampleRate > 0 && startUs >= 0 && durationUs > 0)
        fun ceilingFrame(deltaUs: Long): Int {
            if (deltaUs <= 0) return 0
            // Bound before multiplication, including the unbounded legacy decoder call.
            if (deltaUs > frameCount.toLong() * 1_000_000L / sampleRate + 1L) return frameCount
            return ((deltaUs * sampleRate + 999_999L) / 1_000_000L).toInt().coerceAtMost(frameCount)
        }
        val endUs = if (durationUs > Long.MAX_VALUE - startUs) Long.MAX_VALUE else startUs + durationUs
        return ceilingFrame(startUs - bufferTimeUs) until ceilingFrame(endUs - bufferTimeUs)
    }
}
