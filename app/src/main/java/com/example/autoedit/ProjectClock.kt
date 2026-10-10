package com.veycad.app

/** Absolute frame clock: rounding is performed once, never accumulated per frame. */
data class ProjectClock(val fps: Int) {
    init { require(fps == 30 || fps == 60) }

    fun timeUs(frame: Int): Long {
        require(frame >= 0)
        return (frame * 1_000_000L + fps / 2) / fps
    }

    fun nearestFrame(timeUs: Long): Int {
        require(timeUs >= 0)
        // Divide first so even Long.MAX_VALUE cannot overflow intermediate arithmetic.
        val seconds = timeUs / 1_000_000L
        require(seconds <= Int.MAX_VALUE.toLong() / fps)
        val frame = seconds * fps + ((timeUs % 1_000_000L) * fps + 500_000L) / 1_000_000L
        require(frame <= Int.MAX_VALUE)
        return frame.toInt()
    }
}

data class FrameSpan(val start: Int, val endExclusive: Int) {
    init { require(start >= 0 && endExclusive > start) }
    val length: Int get() = endExclusive - start
}
