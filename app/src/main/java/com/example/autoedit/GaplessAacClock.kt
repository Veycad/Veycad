package com.veycad.app

/** Only for independently proven continuous 1024-frame AAC-LC with gapless metadata.
 * AOSP's SkipCutBuffer carries tail PCM into the next output without moving its timestamp.
 * Retain the PCM clock, accepting only corrected timestamps or that metadata-derived lag. */
internal class GaplessAacClock(
    val sampleRate: Int,
    private val originUs: Long,
    private val paddingFrames: Int,
    private val expectedFrames: Long
) {
    private var emittedFrames = 0L

    init { require(sampleRate > 0 && originUs >= 0 && paddingFrames in 0..1023 && expectedFrames > 0) }

    fun accept(codecPtsUs: Long, frames: Int): Long {
        require(frames > 0)
        val expectedUs = Math.addExact(originUs, AudioExportPlan.presentationTimeUs(emittedFrames, sampleRate))
        val laggedUs = Math.addExact(originUs,
            AudioExportPlan.presentationTimeUs(Math.addExact(emittedFrames, paddingFrames.toLong()), sampleRate))
        fun near(value: Long) = codecPtsUs >= value - 2 && codecPtsUs <= value + 2
        check(near(expectedUs) || emittedFrames > 0 && near(laggedUs)) {
            "Gapless AAC output clock mismatch: codec=$codecPtsUs pcm=$expectedUs padding=$paddingFrames"
        }
        emittedFrames = Math.addExact(emittedFrames, frames.toLong())
        check(emittedFrames <= expectedFrames) { "Gapless AAC returned excess PCM: $emittedFrames > $expectedFrames" }
        return expectedUs
    }

    fun finish() {
        check(emittedFrames == expectedFrames) { "Gapless AAC PCM count mismatch: $emittedFrames != $expectedFrames" }
    }
}
