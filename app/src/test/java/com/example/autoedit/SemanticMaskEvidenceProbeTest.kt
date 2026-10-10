package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SemanticMaskEvidenceProbeTest {
    @Test fun empty_mask_is_reported_as_actual_empty_not_missing_inference() {
        val value = SemanticMaskEvidenceProbe.summarize(FloatArray(4), 2, 2)
        assertEquals(0, value.foregroundPixels)
        assertEquals(0f, value.coverage, 0f)
        assertEquals(.48f, value.confidence, .000001f)
        assertEquals(0.0, value.mean, 0.0)
    }

    @Test fun exact_foreground_boundary_uses_actual_greater_than_or_equal_rule() {
        val value = SemanticMaskEvidenceProbe.summarize(floatArrayOf(.499f, .5f, .501f, 0f), 2, 2)
        assertEquals(2, value.foregroundPixels)
        assertEquals(.5f, value.coverage, 0f)
    }

    @Test fun confidence_separation_and_raw_summary_match_hand_calculated_values() {
        val input = floatArrayOf(.1f, .9f, .1f, .9f)
        val value = SemanticMaskEvidenceProbe.summarize(input, 2, 2)
        assertEquals(.94f, value.confidence, .000001f)
        assertEquals(.8f, value.separation, .000001f)
        assertEquals(.5, value.mean, .000001)
        assertEquals(2, value.width)
        assertEquals(2, value.height)
        assertEquals(.1f, value.minimum, 0f)
        assertEquals(.9f, value.maximum, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonfinite_mask_is_unknown_failure_not_synthetic_zero() {
        SemanticMaskEvidenceProbe.summarize(floatArrayOf(Float.NaN), 1, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatched_mask_geometry_is_rejected() {
        SemanticMaskEvidenceProbe.summarize(FloatArray(4), 1, 2)
    }
}
