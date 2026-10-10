package com.veycad.app

/** Duration interpretation is independent of Android so container edge cases are testable. */
internal fun mediaInputDurationUs(firstPtsUs: Long, lastPtsUs: Long, penultimatePtsUs: Long?,
    declaredDurationUs: Long?, frameRate: Int?): Long {
    require(firstPtsUs >= 0 && lastPtsUs >= firstPtsUs) { "Некорректные PTS видео" }
    val span = lastPtsUs - firstPtsUs
    // Preserve the legacy declared metadata. Its convention is container-dependent;
    // it is NOT independent presentation-end evidence for gallery windows or policy.
    // Explicit long final holds must not be reduced to a preceding PTS gap.
    if (declaredDurationUs != null && declaredDurationUs > span) return declaredDurationUs

    // Only absent/inconsistent metadata needs an estimate. This never adds the initial
    // timestamp offset and never wraps a Long into a shorter, policy-compliant duration.
    val finalStep = penultimatePtsUs?.let { lastPtsUs - it } ?: run {
        val rate = frameRate?.takeIf { it > 0 }
        if (rate != null) 1_000_000L / rate else 0L
    }
    require(finalStep > 0 && span <= Long.MAX_VALUE - finalStep) { "Некорректная длительность видео" }
    return span + finalStep
}
