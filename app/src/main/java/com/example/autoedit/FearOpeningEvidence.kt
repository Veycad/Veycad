package com.veycad.app

/** FEAR's minimum source-motion support; not proof of all cascade roles or continuous optical flow. */
internal object FearOpeningEvidence {
    data class Report(val movingSamples: Int, val longestRun: Int, val longestRunSpanUs: Long,
                      val measuredSamples: Int, val unknownSamples: Int,
                      val subjectMeasuredSamples: Int = measuredSamples,
                      val cameraMeasuredSamples: Int = measuredSamples,
                      /** Static camera + unknown body cannot prove that neither branch moves. */
                      val conclusiveSamples: Int = measuredSamples) {
        val supported: Boolean get() = longestRun >= 3 && longestRunSpanUs >= 500_000L
        val inconclusiveSamples: Int get() = measuredSamples + unknownSamples - conclusiveSamples
    }

    fun evaluate(observations: List<VisualEventMap.Observation>): Report {
        val threshold = VisualEventMapAnalyzer.Config().motionThreshold
        var count = 0
        var run = 0
        var longest = 0
        var startUs = 0L
        var previousUs = -1L
        var longestSpan = 0L
        var measured = 0
        var subjectMeasured = 0
        var cameraMeasured = 0
        var conclusive = 0
        observations.forEach { observation ->
            val measurement = observation.motionMeasurement
            val camera = observation.cameraMeasurement
            if (measurement != null) subjectMeasured++
            if (measurement != null || camera != null) { measured++; cameraMeasured++ }
            // The old subject vector is a spatial luma-change moment, not velocity. It cannot
            // provide a fallback when correspondence, current person, or camera is unknown.
            val moving = (measurement != null && (measurement.cameraIntensity >= threshold ||
                measurement.subjectIntensity >= threshold)) || (camera != null && camera.intensity >= threshold)
            if (measurement != null || (camera != null && camera.intensity >= threshold)) conclusive++
            if (!moving) {
                run = 0
                previousUs = -1L
            } else {
                count++
                if (run == 0 || observation.sourceTimeUs - previousUs > 500_000L ||
                    (camera != null && camera.previousTimeUs != previousUs)) {
                    run = 1
                    startUs = observation.sourceTimeUs
                } else run++
                previousUs = observation.sourceTimeUs
                longest = maxOf(longest, run)
                if (run >= 3) longestSpan = maxOf(longestSpan, observation.sourceTimeUs - startUs)
            }
        }
        return Report(count, longest, longestSpan, measured, observations.size - measured,
            subjectMeasured, cameraMeasured, conclusive)
    }
}
