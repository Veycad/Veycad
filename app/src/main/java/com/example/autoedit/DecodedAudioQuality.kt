package com.veycad.app

import kotlin.math.abs
import kotlin.math.sqrt

/** Sample-domain evidence from the exported audio, never from the source score.
 * Not a reconstructed true-peak or psychoacoustic audibility measurement.
 */
object DecodedAudioQuality {
    data class Report(
        val sampleRate: Int,
        val channels: Int,
        val sampleCount: Int,
        val durationUs: Long,
        val peak: Float,
        val rms: Double,
        val nonFiniteSamples: Int,
        val overFullScaleSamples: Int,
        val longestFullScaleRun: Int,
        val unclampedFloatEvidence: Boolean,
        val issues: List<String>
    ) {
        val accepted: Boolean get() = issues.isEmpty()
    }

    fun evaluate(samples: FloatArray, sampleRate: Int, channels: Int,
                 expectedDurationUs: Long, durationToleranceUs: Long,
                 unclampedFloatEvidence: Boolean): Report {
        require(sampleRate > 0 && channels in 1..8)
        require(samples.size % channels == 0)
        require(expectedDurationUs > 0 && durationToleranceUs >= 0)
        var peak = 0f
        var squareSum = 0.0
        var nonFinite = 0
        var over = 0
        var longestRun = 0
        val runs = IntArray(channels)
        val signs = IntArray(channels)
        samples.forEachIndexed { index, sample ->
            val channel = index % channels
            if (!sample.isFinite()) {
                nonFinite++
                runs[channel] = 0
                signs[channel] = 0
            } else {
                val magnitude = abs(sample)
                peak = maxOf(peak, magnitude)
                squareSum += sample.toDouble() * sample
                if (magnitude > 1f) over++
                // A plateau must be consecutive within ONE channel and of the same sign.
                // Interleaved L/R samples or alternating +/- peaks are not a flat-top run.
                val sign = if (sample >= 0f) 1 else -1
                if (magnitude >= 1f) {
                    runs[channel] = if (signs[channel] == sign) runs[channel] + 1 else 1
                    signs[channel] = sign
                    longestRun = maxOf(longestRun, runs[channel])
                } else {
                    runs[channel] = 0
                    signs[channel] = 0
                }
            }
        }
        val duration = (samples.size.toLong() / channels) * 1_000_000L / sampleRate
        val rms = if (samples.isEmpty()) 0.0 else sqrt(squareSum / samples.size)
        val issues = buildList {
            if (samples.isEmpty()) add("audio-pcm-empty")
            if (!unclampedFloatEvidence) add("audio-unclamped-headroom-unavailable")
            if (nonFinite > 0) add("audio-non-finite-samples")
            if (over > 0) add("audio-over-full-scale")
            if (longestRun >= 3) add("audio-full-scale-plateau")
            if (samples.isNotEmpty() && nonFinite == 0 && peak == 0f) add("audio-silent")
            if (abs(duration - expectedDurationUs) > durationToleranceUs) add("audio-decoded-duration-mismatch")
        }
        return Report(sampleRate, channels, samples.size, duration, peak, rms,
            nonFinite, over, longestRun, unclampedFloatEvidence, issues)
    }
}
