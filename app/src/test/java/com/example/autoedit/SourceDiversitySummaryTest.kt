package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceDiversitySummaryTest {
    @Test fun unknown_pose_does_not_supply_a_measured_gesture_sample() {
        val failed = VisualEventMap.Observation(0L, gestureConfidence = .8f,
            gestureEvidenceAvailable = false, face = VisualEventMap.Face(.9f, 0f))
        assertEquals(0, SourceDiversitySummary.evaluate(listOf(failed)).gestureSamples)
        assertEquals(1, SourceDiversitySummary.evaluate(listOf(failed.copy(gestureEvidenceAvailable = true))).gestureSamples)
    }
    @Test fun many_timestamps_do_not_invent_pose_or_motion_variation() {
        val observations = (0 until 80).map { index ->
            VisualEventMap.Observation(index * 250_000L,
                face = VisualEventMap.Face(.9f, 5f, -2f, 1f),
                composition = VisualEventMap.Composition(.6f, .8f, .8f, .8f, .8f))
        }
        val report = SourceDiversitySummary.evaluate(observations)
        assertEquals(80, report.observations)
        assertEquals(0, report.cameraMotionSamples)
        assertEquals(0, report.subjectMotionSamples)
        assertEquals(0, report.gestureSamples)
        assertEquals(0f, requireNotNull(report.yawSpanDegrees), 0f)
        assertEquals(0f, requireNotNull(report.subjectScaleSpan), 0f)
    }

    @Test fun missing_or_single_semantic_sample_is_unknown_not_static() {
        val empty = SourceDiversitySummary.evaluate(emptyList())
        assertNull(empty.yawSpanDegrees)
        assertNull(empty.subjectScaleSpan)
        assertNull(empty.sceneChangePeak)
        val single = SourceDiversitySummary.evaluate(listOf(
            VisualEventMap.Observation(0L, face = VisualEventMap.Face(.9f, 40f))))
        assertNull(single.yawSpanDegrees)
        assertEquals(1, single.faceSamples)
        val weak = SourceDiversitySummary.evaluate(listOf(
            VisualEventMap.Observation(0L, face = VisualEventMap.Face(.1f, -90f)),
            VisualEventMap.Observation(100_000L, face = VisualEventMap.Face(.2f, 90f))))
        assertEquals(0, weak.faceSamples)
        assertNull(weak.yawSpanDegrees)
    }

    @Test fun measured_ranges_and_motion_use_neutral_analyzer_thresholds() {
        val observations = listOf(
            VisualEventMap.Observation(0L,
                cameraMotion = VisualEventMap.Vector(x = .17f),
                face = VisualEventMap.Face(.9f, -10f, -4f, -2f),
                composition = VisualEventMap.Composition(.2f, .8f, .8f, .8f, .8f)),
            VisualEventMap.Observation(250_000L,
                cameraMotion = VisualEventMap.Vector(x = .2f),
                subjectMotion = VisualEventMap.Vector(y = .3f),
                gestureConfidence = .8f, sceneChangeConfidence = .6f,
                face = VisualEventMap.Face(.9f, 20f, 5f, 3f),
                composition = VisualEventMap.Composition(.8f, .8f, .8f, .8f, .8f)))
        val report = SourceDiversitySummary.evaluate(observations)
        assertEquals(1, report.cameraMotionSamples)
        assertEquals(1, report.subjectMotionSamples)
        assertEquals(1, report.gestureSamples)
        assertEquals(30f, requireNotNull(report.yawSpanDegrees), 0f)
        assertEquals(9f, requireNotNull(report.pitchSpanDegrees), 0f)
        assertEquals(5f, requireNotNull(report.rollSpanDegrees), 0f)
        assertEquals(.6f, requireNotNull(report.subjectScaleSpan), .00001f)
        assertEquals(2, report.faceSamples)
        assertEquals(2, report.compositionSamples)
        assertEquals(.6f, requireNotNull(report.sceneChangePeak), 0f)
    }

    @Test fun full_body_variation_does_not_require_face_measurements() {
        val report = SourceDiversitySummary.evaluate(listOf(
            VisualEventMap.Observation(0L, humanPresenceConfidence = .9f,
                composition = VisualEventMap.Composition(.2f, .8f, .8f, .8f, .8f)),
            VisualEventMap.Observation(250_000L, humanPresenceConfidence = .9f,
                subjectMotion = VisualEventMap.Vector(x = .4f),
                composition = VisualEventMap.Composition(.8f, .8f, .8f, .8f, .8f))))
        assertEquals(0, report.faceSamples)
        assertNull(report.yawSpanDegrees)
        assertEquals(1, report.subjectMotionSamples)
        assertEquals(.6f, requireNotNull(report.subjectScaleSpan), .00001f)
    }

    @Test fun custom_thresholds_and_weak_faces_do_not_inflate_measured_counts_or_ranges() {
        val samples = listOf(
            VisualEventMap.Observation(0L, cameraMotion = VisualEventMap.Vector(x = .3f),
                subjectMotion = VisualEventMap.Vector(y = .3f), gestureConfidence = .7f,
                face = VisualEventMap.Face(.65f, -10f)),
            VisualEventMap.Observation(250_000L, cameraMotion = VisualEventMap.Vector(x = .5f),
                subjectMotion = VisualEventMap.Vector(y = .5f), gestureConfidence = .9f,
                face = VisualEventMap.Face(.9f, 20f)),
            VisualEventMap.Observation(500_000L, gestureConfidence = 1f, gestureEvidenceAvailable = false,
                face = VisualEventMap.Face(.649f, 90f)))
        val report = SourceDiversitySummary.evaluate(samples,
            VisualEventMapAnalyzer.Config(motionThreshold = .5f, gestureThreshold = .9f))
        assertEquals(1, report.cameraMotionSamples)
        assertEquals(1, report.subjectMotionSamples)
        assertEquals(1, report.gestureSamples)
        assertEquals(2, report.faceSamples)
        assertEquals(30f, requireNotNull(report.yawSpanDegrees), 0f)
        assertEquals(0, report.compositionSamples)
        assertNull(report.subjectScaleSpan)
    }
}
