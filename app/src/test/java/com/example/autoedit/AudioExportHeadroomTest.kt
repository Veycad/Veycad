package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class AudioExportHeadroomTest {
    @Test fun loud_score_gets_one_gain_without_changing_sign_timing_or_channel_balance() {
        val source = floatArrayOf(1.2f, -.6f, .3f, -.15f)
        val original = source.copyOf()
        val prepared = AudioExportHeadroom.prepare(source)
        // The export contract reserves 1 dB of sample headroom; using TARGET_PEAK as
        // the expected value would let a change to that production constant pass.
        assertEquals(.7427091f, prepared.gain, .000001f)
        assertEquals(-1.0, 20.0 * kotlin.math.log10(prepared.samples[0].toDouble()), .00001)
        assertEquals(1.2f, prepared.sourcePeak, 0f)
        assertEquals(-.5f, prepared.samples[1] / prepared.samples[0], .000001f)
        assertEquals(.25f, prepared.samples[2] / prepared.samples[0], .000001f)
        assertEquals(-.125f, prepared.samples[3] / prepared.samples[0], .000001f)
        assertEquals(source.size, prepared.samples.size)
        assertArrayEquals(original, source, 0f)
    }

    @Test fun quiet_score_and_silence_are_not_boosted() {
        val source = floatArrayOf(.1f, -.2f, 0f)
        val prepared = AudioExportHeadroom.prepare(source)
        assertEquals(1f, prepared.gain, 0f)
        assertSame(source, prepared.samples)
        assertEquals(1f, AudioExportHeadroom.prepare(FloatArray(4)).gain, 0f)
    }

    @Test fun negative_peak_is_included_before_integer_conversion() {
        val prepared = AudioExportHeadroom.prepare(floatArrayOf(.2f, -1.5f))
        assertEquals(1.5f, prepared.sourcePeak, 0f)
        assertEquals(-.89125094f, prepared.samples[1], .000001f)
    }

    @Test fun invalid_pcm_is_not_silently_clamped_or_replaced() {
        for (invalid in arrayOf(floatArrayOf(), floatArrayOf(Float.NaN),
            floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(Float.NEGATIVE_INFINITY))) {
            try {
                AudioExportHeadroom.prepare(invalid)
                fail("Invalid PCM accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun scan_honours_cancellation() {
        var checks = 0
        try {
            AudioExportHeadroom.prepare(FloatArray(50_000) { 1f }) {
                checks++
                if (checks == 2) throw InterruptedException("cancelled")
            }
            fail("Cancellation ignored")
        } catch (_: InterruptedException) {
            assertEquals(2, checks)
        }
    }
}
