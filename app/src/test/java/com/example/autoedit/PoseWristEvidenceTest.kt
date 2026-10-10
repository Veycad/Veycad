package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class PoseWristEvidenceTest {
    private val left = 15
    private val right = 16
    private val required = setOf(left, right)
    private val still = linkedMapOf(left to (.2f to .4f), right to (.8f to .4f))

    @Test fun no_or_one_wrist_is_unknown_not_a_measured_zero_gesture() {
        assertFalse(PoseWristEvidence.assess(required, emptyMap(), still).complete)
        assertFalse(PoseWristEvidence.assess(required, mapOf(left to still.getValue(left)), still).complete)
        assertFalse(PoseWristEvidence.assess(required, mapOf(right to still.getValue(right)), still).complete)
    }

    @Test fun both_observed_static_wrists_can_support_a_measured_zero() {
        val assessment = PoseWristEvidence.assess(required, still, still)
        assertTrue(assessment.complete)
        assertEquals(0f, assessment.maximumDisplacement, 0f)
    }

    @Test fun map_iteration_order_cannot_swap_left_and_right_identity() {
        val reversed = linkedMapOf(right to still.getValue(right), left to still.getValue(left))
        assertEquals(PoseWristEvidence.assess(required, still, still),
            PoseWristEvidence.assess(required, reversed, still))
    }

    @Test fun a_missing_wrist_cannot_be_compared_with_the_other_previous_wrist() {
        val onlyRight = mapOf(right to still.getValue(right))
        val assessment = PoseWristEvidence.assess(required, onlyRight, mapOf(left to still.getValue(left)))
        assertFalse(assessment.complete)
        assertEquals(0f, assessment.maximumDisplacement, 0f)
        assertEquals(0f, PoseWristEvidence.assess(required, still, onlyRight).maximumDisplacement, 0f)
    }

    @Test fun a_gap_has_no_previous_velocity_but_current_landmark_availability_is_independent() {
        val assessment = PoseWristEvidence.assess(required, still, null)
        assertTrue(assessment.complete)
        assertEquals(0f, assessment.maximumDisplacement, 0f)
    }

    @Test fun displacement_uses_only_the_same_observed_identity() {
        val moved = mapOf(left to (.5f to .8f), right to still.getValue(right))
        val assessment = PoseWristEvidence.assess(required, moved, still)
        assertTrue(assessment.complete)
        assertEquals(.5f, assessment.maximumDisplacement, .000001f)
    }
}
