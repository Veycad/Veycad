package com.veycad.app

import android.media.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecodedAudioQualityTest {
    private fun report(samples: FloatArray, channels: Int = 1, unclamped: Boolean = true,
                       expectedUs: Long = samples.size.toLong() / channels * 1_000L) =
        DecodedAudioQuality.evaluate(samples, 1_000, channels, expectedUs, 0, unclamped)

    @Test fun normal_pcm_passes_with_measured_peak_and_rms() {
        val audio = report(floatArrayOf(.5f, -.5f, .5f, -.5f))
        assertTrue(audio.issues.toString(), audio.accepted)
        assertEquals(.5f, audio.peak, 0f)
        assertEquals(.5, audio.rms, .00001)
        assertEquals(4_000L, audio.durationUs)
    }

    @Test fun unclamped_float_conversion_preserves_overshoot_and_invalid_evidence() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1.2f).putFloat(-1.3f).putFloat(Float.NaN).array()
        val raw = PcmSampleConverter.decode(bytes, AudioFormat.ENCODING_PCM_FLOAT, false)
        assertEquals(1.2f, raw[0], 0f)
        assertEquals(-1.3f, raw[1], 0f)
        val audio = report(raw)
        assertEquals(2, audio.overFullScaleSamples)
        assertEquals(1, audio.nonFiniteSamples)
        assertTrue(audio.issues.contains("audio-over-full-scale"))
        assertTrue(audio.issues.contains("audio-non-finite-samples"))
        // Legacy analysis/mux callers keep their original normalized conversion.
        assertEquals(1f, PcmSampleConverter.decode(bytes, AudioFormat.ENCODING_PCM_FLOAT)[0], 0f)
    }

    @Test fun flat_top_runs_are_per_channel_not_interleaved_or_alternating_signs() {
        assertEquals(1, report(floatArrayOf(1f, 1f, .5f, .5f), channels = 2).longestFullScaleRun)
        assertEquals(1, report(floatArrayOf(1f, -1f, 1f, -1f)).longestFullScaleRun)
        val plateau = report(floatArrayOf(1f, .1f, 1f, .2f, 1f, .3f), channels = 2)
        assertEquals(3, plateau.longestFullScaleRun)
        assertTrue(plateau.issues.contains("audio-full-scale-plateau"))
    }

    @Test fun empty_silent_short_and_integer_clamped_evidence_fail() {
        assertTrue(report(floatArrayOf(), expectedUs = 1_000).issues.contains("audio-pcm-empty"))
        assertTrue(report(FloatArray(4)).issues.contains("audio-silent"))
        assertTrue(report(floatArrayOf(.5f), expectedUs = 10_000).issues.contains("audio-decoded-duration-mismatch"))
        assertTrue(report(floatArrayOf(.5f), unclamped = false).issues.contains("audio-unclamped-headroom-unavailable"))
    }
}
