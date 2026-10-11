package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioExportPlanTest {
    @Test fun window_starts_at_global_phase_and_restarts_local_output_clock() {
        assertEquals(listOf(
            AudioExportPlan.Segment(800_000L, 1_000_000L, 0L),
            AudioExportPlan.Segment(0L, 1_000_000L, 200_000L),
            AudioExportPlan.Segment(0L, 300_000L, 1_200_000L)
        ), AudioExportPlan.window(1_000_000L, 2_800_000L, 1_500_000L))
    }

    @Test fun window_inside_track_does_not_split_or_round_microseconds() {
        assertEquals(listOf(AudioExportPlan.Segment(7L, 10L, 0L)),
            AudioExportPlan.window(11L, 29L, 3L))
    }

    @Test fun exact_wrap_end_does_not_add_an_empty_segment() {
        assertEquals(listOf(AudioExportPlan.Segment(7L, 11L, 0L)),
            AudioExportPlan.window(11L, 29L, 4L))
    }

    @Test fun several_wraps_preserve_each_source_and_local_output_interval() {
        assertEquals(listOf(
            AudioExportPlan.Segment(1L, 3L, 0L),
            AudioExportPlan.Segment(0L, 3L, 2L),
            AudioExportPlan.Segment(0L, 3L, 5L),
            AudioExportPlan.Segment(0L, 1L, 8L)
        ), AudioExportPlan.window(3L, 7L, 9L))
    }

    @Test fun long_track_and_global_offset_do_not_overflow_source_end() {
        assertEquals(listOf(AudioExportPlan.Segment(Long.MAX_VALUE - 5L, Long.MAX_VALUE, 0L)),
            AudioExportPlan.window(Long.MAX_VALUE, Long.MAX_VALUE - 5L, 5L))
    }

    @Test fun zero_duration_is_empty_even_at_large_global_offset() {
        assertEquals(emptyList<AudioExportPlan.Segment>(),
            AudioExportPlan.window(1L, Long.MAX_VALUE, 0L))
    }

    @Test fun invalid_track_or_negative_times_are_rejected_even_for_empty_window() {
        for (args in listOf(
            longArrayOf(0L, 0L, 0L), longArrayOf(-1L, 0L, 1L),
            longArrayOf(1L, -1L, 0L), longArrayOf(1L, 0L, -1L)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                AudioExportPlan.window(args[0], args[1], args[2])
            }
        }
    }

    @Test fun overflowing_global_end_is_rejected_before_building_segments() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioExportPlan.window(10L, Long.MAX_VALUE, 1L)
        }
    }

    @Test fun exact_segment_limit_is_supported_without_truncating_duration() {
        val result = AudioExportPlan.window(1L, 0L, 100_000L)
        assertEquals(100_000, result.size)
        assertEquals(AudioExportPlan.Segment(0L, 1L, 0L), result.first())
        assertEquals(AudioExportPlan.Segment(0L, 1L, 99_999L), result.last())
    }

    @Test fun one_segment_over_limit_and_billions_of_wraps_are_rejected() {
        for (args in listOf(
            longArrayOf(1L, 0L, 100_001L),
            longArrayOf(2L, 1L, 200_000L),
            longArrayOf(1L, 0L, Long.MAX_VALUE)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                AudioExportPlan.window(args[0], args[1], args[2])
            }
        }
    }

    @Test fun legacy_loop_still_cuts_last_segment_at_output_duration() {
        assertEquals(listOf(
            AudioExportPlan.Segment(0L, 1_500_000L, 0L),
            AudioExportPlan.Segment(0L, 1_500_000L, 1_500_000L),
            AudioExportPlan.Segment(0L, 1_000_000L, 3_000_000L)
        ), AudioExportPlan.loop(1_500_000L, 4_000_000L))
        assertEquals(1_000_000L, AudioExportPlan.presentationTimeUs(48_000L, 48_000))
    }
}
