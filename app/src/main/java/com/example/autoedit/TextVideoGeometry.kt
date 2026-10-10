package com.veycad.app

object TextVideoGeometry {
    fun oriented(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && rotation in setOf(0,90,180,270))
        return if (rotation == 90 || rotation == 270) height to width else width to height
    }
}

internal class TextPcmTimeline(val sampleRate: Int, durationUs: Long) {
    val frames = durationUs * sampleRate / 1_000_000
    fun frameAt(timeUs: Long) = timeUs * sampleRate / 1_000_000
    fun remainingAfter(frame: Long) = (frames - frame).coerceAtLeast(0)
}
