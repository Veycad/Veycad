package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodedFaceEvidenceTest {
    @Test fun earlier_image_cannot_certify_intervening_frame_before_old_100ms_clock() {
        val previous = DecodedFaceEvidence.fromAttempts(1_000_000L, 0, 4, listOf(.92f, .90f))
        assertTrue(previous.isFreshFor(1_000_000L, 0, 4))
        assertFalse(previous.isFreshFor(1_033_333L, 0, 4))
        assertFalse(previous.isFreshFor(1_066_667L, 0, 4))
    }

    @Test fun even_same_pts_must_not_borrow_evidence_from_another_clip_or_source() {
        val measured = DecodedFaceEvidence.fromAttempts(1_000_000L, 0, 4, listOf(.92f))
        assertFalse(measured.isFreshFor(1_000_000L, 0, 5))
        assertFalse(measured.isFreshFor(1_000_000L, 1, 4))
        assertFalse(measured.isFreshFor(1_000_000L, null, null))
    }

    @Test fun successful_empty_result_is_measured_zero_not_unknown() {
        val empty = DecodedFaceEvidence.fromAttempts(1_033_333L, 1, 5, listOf(0f, 0f))
        assertEquals(0f, empty.confidence!!, 0f)
        assertTrue(empty.isFreshFor(1_033_333L, 1, 5))
    }

    @Test fun inference_failure_is_unknown_even_if_other_scale_detects_a_face() {
        for (attempts in listOf(listOf(null, 0f), listOf(.92f, null), listOf(null, null), emptyList())) {
            val unknown = DecodedFaceEvidence.fromAttempts(1_033_333L, 1, 5, attempts)
            assertNull(unknown.confidence)
            assertFalse(unknown.isFreshFor(1_033_333L, 1, 5))
        }
    }

    @Test fun successful_qa_scales_keep_existing_maximum_confidence() {
        val measured = DecodedFaceEvidence.fromAttempts(1_033_333L, 1, 5, listOf(.71f, .92f))
        assertEquals(.92f, measured.confidence!!, 0f)
    }

    @Test fun malformed_attempt_cannot_be_hidden_by_a_valid_higher_confidence() {
        for (bad in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(DecodedFaceEvidence.fromAttempts(1L, 0, 0, listOf(.92f, bad)).confidence)
        }
    }
}
