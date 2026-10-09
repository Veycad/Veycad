package com.example.autoedit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceAnalysisTimelineTest {
    @Test fun exact_indexed_target_can_equal_declared_video_duration() {
        // The user-supplied VFR/B-frame source declares 1293959 / 90000 seconds,
        // while its actual presentation index extends to 14.4 seconds.
        val targets = SourceAnalysisTimeline.align(longArrayOf(14_375_000L),
            longArrayOf(14_360_667L, 14_377_322L, 14_394_000L, 14_400_000L))
        assertArrayEquals(longArrayOf(14_377_322L), targets)
        SourceAnalysisTimeline.validateDecodeTargets(targets, 14_377_322L, requireExactPts = true)
        SourceAnalysisTimeline.requireDecodedPts(targets.single(), 14_377_322L)
    }

    @Test fun exact_indexed_target_can_follow_declared_video_duration() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_377_322L, 14_400_000L),
            14_377_322L, requireExactPts = true)
        SourceAnalysisTimeline.requireDecodedPts(14_400_000L, 14_400_000L)
    }

    @Test(expected = IllegalStateException::class)
    fun exact_target_at_declared_duration_cannot_use_a_different_tail_frame() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_377_322L),
            14_377_322L, requireExactPts = true)
        SourceAnalysisTimeline.requireDecodedPts(14_377_322L, 14_400_000L)
    }

    @Test fun approximate_target_before_declared_duration_remains_valid() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_375_000L),
            14_377_322L, requireExactPts = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun approximate_target_equal_to_declared_duration_remains_invalid() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_377_322L),
            14_377_322L, requireExactPts = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun approximate_target_after_declared_duration_remains_invalid() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_400_000L),
            14_377_322L, requireExactPts = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exact_mode_does_not_allow_empty_targets() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(), 14_377_322L, requireExactPts = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exact_mode_does_not_allow_negative_targets() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(-1L), 14_377_322L, requireExactPts = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exact_mode_does_not_allow_reordered_targets() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_400_000L, 14_377_322L),
            14_377_322L, requireExactPts = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exact_mode_does_not_allow_duplicate_targets() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_400_000L, 14_400_000L),
            14_377_322L, requireExactPts = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exact_mode_still_requires_positive_declared_duration() {
        SourceAnalysisTimeline.validateDecodeTargets(longArrayOf(14_400_000L), 0L, requireExactPts = true)
    }

    @Test(expected = IllegalStateException::class)
    fun missing_indexed_frame_cannot_be_relabelled_at_later_pts() {
        SourceAnalysisTimeline.requireDecodedPts(375_000, 625_000)
    }

    @Test fun decoded_frame_must_match_its_actual_indexed_pts() {
        SourceAnalysisTimeline.requireDecodedPts(380_000, 380_000)
    }

    @Test fun container_tail_uses_final_real_pts_not_unreachable_request() {
        assertArrayEquals(longArrayOf(150_000, 400_000, 580_000), SourceAnalysisTimeline.align(
            longArrayOf(125_000, 375_000, 625_000), longArrayOf(0, 150_000, 400_000, 580_000)))
    }

    @Test fun multiple_tail_requests_do_not_invent_repeated_observations() {
        assertArrayEquals(longArrayOf(40_000), SourceAnalysisTimeline.align(
            longArrayOf(125_000, 375_000, 625_000), longArrayOf(0, 40_000)))
    }

    @Test fun vfr_targets_follow_actual_pts_not_nominal_frame_rate() {
        assertArrayEquals(longArrayOf(140_000, 910_000, 990_000), SourceAnalysisTimeline.align(
            longArrayOf(125_000, 375_000, 625_000, 875_000, 1_125_000),
            longArrayOf(7_000, 140_000, 910_000, 990_000)))
    }

    @Test fun exact_non_keyframe_pts_are_retained() {
        assertArrayEquals(longArrayOf(125_000, 375_000, 625_000), SourceAnalysisTimeline.align(
            longArrayOf(125_000, 375_000, 625_000), longArrayOf(0, 125_000, 375_000, 625_000)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun empty_video_index_is_not_success() {
        SourceAnalysisTimeline.align(longArrayOf(125_000), longArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun reordered_pts_must_be_normalized_before_planning() {
        SourceAnalysisTimeline.align(longArrayOf(125_000), longArrayOf(200_000, 100_000))
    }

    @Test fun invalid_plans_and_negative_or_duplicate_video_pts_are_rejected_before_alignment() {
        val cases = listOf(
            longArrayOf() to longArrayOf(0, 100_000),
            longArrayOf(-1) to longArrayOf(0, 100_000),
            longArrayOf(100_000, 0) to longArrayOf(0, 100_000),
            longArrayOf(0, 0) to longArrayOf(0, 100_000),
            longArrayOf(0) to longArrayOf(-1, 100_000),
            longArrayOf(0) to longArrayOf(0, 0))
        for ((planned, decoded) in cases) {
            assertThrows("planned=${planned.toList()}, decoded=${decoded.toList()}",
                IllegalArgumentException::class.java) { SourceAnalysisTimeline.align(planned, decoded) }
        }
    }
}
