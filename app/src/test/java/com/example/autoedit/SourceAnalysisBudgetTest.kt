package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SourceAnalysisBudgetTest {
    @Test fun one_minute_at_default_cadence_fits_with_exact_storage_reserve() {
        val estimate = SourceAnalysisBudget.preflight(60_000_000, 250_000, 489_621_376)
        assertEquals(240L, estimate.frames)
        assertEquals(221_185_920L, estimate.rawCacheBytes)
        assertEquals(129_761_280L, estimate.attachmentBytes)
    }

    @Test fun refuses_long_source_instead_of_decoding_gigabytes() {
        refused("60") { SourceAnalysisBudget.preflight(600_000_000, 250_000, Long.MAX_VALUE) }
        refused("60") { SourceAnalysisBudget.preflight(60_000_001, 250_000, Long.MAX_VALUE) }
    }

    @Test fun faster_sampling_cannot_exceed_retained_frame_budget() {
        assertEquals(240L, SourceAnalysisBudget.preflight(24_000_000, 100_000, Long.MAX_VALUE).frames)
        refused("кадров") { SourceAnalysisBudget.preflight(24_050_001, 100_000, Long.MAX_VALUE) }
    }

    @Test fun storage_requires_projected_raw_cache_plus_reserve_before_any_work() {
        refused("места") { SourceAnalysisBudget.preflight(60_000_000, 250_000, 489_621_375) }
        refused("места") { SourceAnalysisBudget.preflight(1, 250_000, 0) }
    }

    @Test fun tiny_clip_still_requires_one_frame() {
        assertEquals(1L, SourceAnalysisBudget.preflight(1, 250_000, Long.MAX_VALUE).frames)
        assertEquals(1L, SourceAnalysisBudget.preflight(375_000, 250_000, Long.MAX_VALUE).frames)
        assertEquals(2L, SourceAnalysisBudget.preflight(375_001, 250_000, Long.MAX_VALUE).frames)
    }

    @Test fun invalid_or_overflowing_metadata_is_rejected_without_allocation() {
        for (duration in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            refused { SourceAnalysisBudget.preflight(duration, 250_000, Long.MAX_VALUE) }
        }
        for (interval in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 99_999L, 1_000_001L)) {
            refused { SourceAnalysisBudget.preflight(1_000_000, interval, Long.MAX_VALUE) }
        }
        refused { SourceAnalysisBudget.preflight(1_000_000, 250_000, -1) }
    }

    private fun refused(messagePart: String? = null, action: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { action() }
        if (messagePart != null) assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(messagePart))
    }
}
