package com.example.autoedit

import kotlin.math.abs

/** One constant gain for the whole score and all channels, never a limiter or auto-volume pump.
 * Leaves 1 dB of sample headroom before AAC; only decoded-output QA can prove the encoded peak.
 * Cannot restore clipping already present in the recording or imposed by an integer decoder.
 */
internal object AudioExportHeadroom {
    const val TARGET_PEAK = .89125094f

    data class Prepared(val samples: FloatArray, val sourcePeak: Float, val gain: Float)

    fun prepare(samples: FloatArray, checkCancelled: () -> Unit = {}): Prepared {
        require(samples.isNotEmpty()) { "Empty music PCM" }
        var peak = 0f
        samples.forEachIndexed { index, sample ->
            if (index % 16_384 == 0) checkCancelled()
            require(sample.isFinite()) { "Non-finite music PCM" }
            peak = maxOf(peak, abs(sample))
        }
        val gain = if (peak > TARGET_PEAK) TARGET_PEAK / peak else 1f
        val prepared = if (gain == 1f) samples else FloatArray(samples.size) { index ->
            if (index % 16_384 == 0) checkCancelled()
            samples[index] * gain
        }
        return Prepared(prepared, peak, gain)
    }
}
