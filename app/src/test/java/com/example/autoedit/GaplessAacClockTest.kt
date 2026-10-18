package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class GaplessAacClockTest {
    @Test fun aosp_withheld_tail_advances_by_returned_pcm_not_current_access_unit() {
        val clock = GaplessAacClock(48_000, 0, 128, 2944)
        assertEquals(0L, clock.accept(0, 896))
        assertEquals(18_666L, clock.accept(21_333, 1024))
        assertEquals(40_000L, clock.accept(42_666, 1024))
        clock.finish()
    }

    @Test fun corrected_oem_and_zero_padding_keep_the_actual_presentation_origin() {
        for (padding in listOf(0, 128)) {
            val clock = GaplessAacClock(48_000, 500_000, padding, (2048 - padding).toLong())
            assertEquals(500_000L, clock.accept(500_000, 1024))
            assertEquals(521_333L, clock.accept(521_333, 1024 - padding))
            clock.finish()
        }
    }

    @Test fun real_gap_wrong_initial_origin_and_wrong_final_count_are_rejected() {
        val gap = GaplessAacClock(48_000, 0, 128, 1920)
        gap.accept(0, 896)
        assertThrows(IllegalStateException::class.java) { gap.accept(42_666, 1024) }
        assertThrows(IllegalStateException::class.java) { GaplessAacClock(48_000, 0, 128, 1024).accept(2666, 896) }
        val short = GaplessAacClock(48_000, 0, 128, 1920)
        short.accept(0, 896)
        assertThrows(IllegalStateException::class.java) { short.finish() }
        assertThrows(IllegalStateException::class.java) { GaplessAacClock(48_000, 0, 0, 100).accept(0, 101) }
    }
}
