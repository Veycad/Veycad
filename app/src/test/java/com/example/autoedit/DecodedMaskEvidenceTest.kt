package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodedMaskEvidenceTest {
    @Test fun empty_output_is_not_measured_leak() {
        assertEquals(RenderedMp4Acceptance.MaskEvidence.OUTPUT_MASK_EMPTY,
            DecodedMaskEvidence.classify(floatArrayOf(1f, 0f), floatArrayOf(0f, 0f)))
    }
    @Test fun empty_projected_source_is_a_separate_failure() {
        assertEquals(RenderedMp4Acceptance.MaskEvidence.SOURCE_MASK_EMPTY,
            DecodedMaskEvidence.classify(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
    }
    @Test fun real_output_leak_remains_measured_for_existing_threshold() {
        assertEquals(RenderedMp4Acceptance.MaskEvidence.MEASURED,
            DecodedMaskEvidence.classify(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f)))
    }
    @Test fun invalid_values_are_not_clean_evidence() {
        for (bad in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals("invalid output=$bad", RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED,
                DecodedMaskEvidence.classify(floatArrayOf(1f), floatArrayOf(bad)))
            assertEquals("invalid source=$bad", RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED,
                DecodedMaskEvidence.classify(floatArrayOf(bad), floatArrayOf(1f)))
        }
    }
    @Test fun missing_buffer_is_not_an_empty_observed_person() {
        assertEquals(RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED,
            DecodedMaskEvidence.classify(floatArrayOf(1f), floatArrayOf()))
        assertEquals(RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED,
            DecodedMaskEvidence.classify(floatArrayOf(), floatArrayOf(1f)))
        assertEquals(RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED,
            DecodedMaskEvidence.classify(floatArrayOf(1f, 0f), floatArrayOf(1f)))
    }
    @Test fun half_opacity_counts_as_measured_person_and_empty_source_takes_precedence() {
        assertEquals(RenderedMp4Acceptance.MaskEvidence.MEASURED,
            DecodedMaskEvidence.classify(floatArrayOf(.5f), floatArrayOf(.5f)))
        assertEquals(RenderedMp4Acceptance.MaskEvidence.OUTPUT_MASK_EMPTY,
            DecodedMaskEvidence.classify(floatArrayOf(.5f), floatArrayOf(.499f)))
        assertEquals(RenderedMp4Acceptance.MaskEvidence.SOURCE_MASK_EMPTY,
            DecodedMaskEvidence.classify(floatArrayOf(.499f), floatArrayOf(.499f)))
    }
}
