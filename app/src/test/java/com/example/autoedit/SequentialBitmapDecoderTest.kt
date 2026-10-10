package com.veycad.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SequentialBitmapDecoderTest {
    @Test fun interval_targets_match_existing_half_interval_clock() {
        assertArrayEquals(
            longArrayOf(125_000L, 375_000L, 625_000L, 875_000L),
            SequentialBitmapDecoder.intervalTargets(1_000_000L, 250_000L)
        )
    }

    @Test fun short_source_still_has_one_target_inside_duration() {
        assertArrayEquals(
            longArrayOf(49_999L),
            SequentialBitmapDecoder.intervalTargets(50_000L, 250_000L)
        )
    }

    @Test fun odd_interval_and_non_multiple_duration_keep_the_last_target_inside_source() {
        assertArrayEquals(longArrayOf(2L, 7L, 12L), SequentialBitmapDecoder.intervalTargets(13L, 5L))
        assertArrayEquals(longArrayOf(2L, 7L), SequentialBitmapDecoder.intervalTargets(12L, 5L))
        assertArrayEquals(longArrayOf(0L), SequentialBitmapDecoder.intervalTargets(1L, 1L))
    }

    @Test fun zero_or_negative_duration_or_interval_is_rejected() {
        for ((duration, interval) in listOf(0L to 1L, -1L to 1L, 1L to 0L, 1L to -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                SequentialBitmapDecoder.intervalTargets(duration, interval)
            }
        }
    }
}
