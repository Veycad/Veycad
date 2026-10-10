package com.veycad.app

/** Measurement availability is separate from a measured contour defect. */
internal object DecodedMaskEvidence {
    fun classify(expected: FloatArray, actual: FloatArray): RenderedMp4Acceptance.MaskEvidence {
        if (expected.isEmpty() || expected.size != actual.size ||
            (expected.asSequence() + actual.asSequence()).any { !it.isFinite() || it !in 0f..1f }) {
            return RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED
        }
        if (expected.none { it >= .5f }) return RenderedMp4Acceptance.MaskEvidence.SOURCE_MASK_EMPTY
        if (actual.none { it >= .5f }) return RenderedMp4Acceptance.MaskEvidence.OUTPUT_MASK_EMPTY
        return RenderedMp4Acceptance.MaskEvidence.MEASURED
    }
}
