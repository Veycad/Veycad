package com.example.autoedit

/** Neutral source diagnostics, NOT a count of distinct shots or a material acceptance gate. */
internal object SourceDiversitySummary {
    data class Report(
        val observations: Int,
        val cameraMotionSamples: Int,
        val subjectMotionSamples: Int,
        val gestureSamples: Int,
        val faceSamples: Int,
        val yawSpanDegrees: Float?,
        val pitchSpanDegrees: Float?,
        val rollSpanDegrees: Float?,
        val compositionSamples: Int,
        val subjectScaleSpan: Float?,
        val sceneChangePeak: Float?
    )

    fun evaluate(
        observations: List<VisualEventMap.Observation>,
        config: VisualEventMapAnalyzer.Config = VisualEventMapAnalyzer.Config()
    ): Report {
        val faces = observations.mapNotNull { it.face?.takeIf { face -> face.confidence >= .65f } }
        val scales = observations.mapNotNull { it.composition?.subjectScale }
        return Report(
            observations.size,
            observations.count { it.cameraMotion.magnitude >= config.motionThreshold },
            observations.count { it.subjectMotion.magnitude >= config.motionThreshold },
            observations.count { it.gestureEvidenceAvailable && it.gestureConfidence >= config.gestureThreshold },
            faces.size,
            span(faces.map { it.yawDegrees }),
            span(faces.map { it.pitchDegrees }),
            span(faces.map { it.rollDegrees }),
            scales.size,
            span(scales),
            observations.maxOfOrNull { it.sceneChangeConfidence }
        )
    }

    // One sample cannot establish variation; missing evidence is not a measured zero.
    private fun span(values: List<Float>): Float? =
        if (values.size < 2) null else values.max() - values.min()
}
