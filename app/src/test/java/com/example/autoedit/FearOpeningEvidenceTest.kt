package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FearOpeningEvidenceTest {
    @Test fun isolated_codec_peaks_do_not_supply_a_moving_opener() {
        val samples = (0..79).map { index -> observation(index * 250_000L, index in setOf(10, 50)) }
        val report = FearOpeningEvidence.evaluate(samples)
        assertEquals(2, report.movingSamples)
        assertFalse(report.supported)
        val source = VisualEventMap(20_000_000L, emptyList(), samples)
        val rejection = try {
            FearSourcePool.select(source, FrameAttachmentTimeline())
            throw AssertionError("Expected material rejection")
        } catch (error: MaterialRejectedException) { error }
        assertEquals("insufficient_motion", rejection.code)
    }

    @Test fun three_supported_samples_must_span_half_a_second_without_gaps() {
        assertTrue(FearOpeningEvidence.evaluate(listOf(
            observation(0, true), observation(250_000, true), observation(500_000, true))).supported)
        assertFalse(FearOpeningEvidence.evaluate(listOf(
            observation(0, true), observation(1_000, true), observation(2_000, true))).supported)
        assertFalse(FearOpeningEvidence.evaluate(listOf(
            observation(0, true), observation(1_000_000, true), observation(2_000_000, true))).supported)
        assertFalse(FearOpeningEvidence.evaluate(listOf(
            observation(0, true), observation(250_000, false), observation(500_000, true))).supported)
    }

    @Test fun full_body_subject_motion_can_support_the_opener_without_a_face() {
        val samples = (0..3).map { index ->
            VisualEventMap.Observation(index * 250_000L,
                humanPresenceConfidence = .9f,
                motionMeasurement = measurement(.3f, 0f))
        }
        assertTrue(FearOpeningEvidence.evaluate(samples).supported)
    }

    @Test fun legacy_vectors_cannot_turn_missing_measurement_into_a_moving_opener() {
        val samples = (0..79).map { index ->
            VisualEventMap.Observation(index * 250_000L,
                cameraMotion = VisualEventMap.Vector(x = 1f),
                subjectMotion = VisualEventMap.Vector(y = 1f), humanPresenceConfidence = 1f)
        }
        val report = FearOpeningEvidence.evaluate(samples)
        assertFalse(report.supported)
        assertEquals(0, report.measuredSamples)
        assertEquals(80, report.unknownSamples)
        val source = VisualEventMap(20_000_000L, emptyList(), samples)
        val error = try {
            FearSourcePool.select(source, FrameAttachmentTimeline())
            throw AssertionError("Expected unmeasurable motion rejection")
        } catch (rejection: MaterialRejectedException) { rejection }
        assertEquals("insufficient_motion_evidence", error.code)
    }

    @Test fun measured_static_is_distinct_from_unknown_and_both_break_the_run() {
        val static = observation(250_000, false)
        val unknown = VisualEventMap.Observation(250_000)
        for (middle in listOf(static, unknown)) {
            val report = FearOpeningEvidence.evaluate(listOf(observation(0, true), middle,
                observation(500_000, true), observation(750_000, true)))
            assertFalse(report.supported)
            assertEquals(2, report.longestRun)
            assertEquals(if (middle === unknown) 1 else 0, report.unknownSamples)
        }
    }

    @Test fun independent_camera_support_can_supply_opener_while_subject_stays_unknown() {
        val samples = (1..3).map { cameraObservation(it * 250_000L) }
        assertTrue(samples.all { it.motionMeasurement == null })
        val report = FearOpeningEvidence.evaluate(samples)
        assertTrue(report.supported)
        assertEquals(3, report.movingSamples)
        assertEquals(3, report.measuredSamples)
        assertEquals(0, report.unknownSamples)
        assertEquals(0, report.subjectMeasuredSamples)
        assertEquals(3, report.cameraMeasuredSamples)
        assertEquals(3, report.conclusiveSamples)
        assertEquals(0, report.inconclusiveSamples)
    }

    @Test fun independent_measured_static_camera_does_not_claim_subject_static_or_moving() {
        val samples = (1..3).map { cameraObservation(it * 250_000L, intensity = 0f) }
        val report = FearOpeningEvidence.evaluate(samples)
        assertFalse(report.supported)
        assertEquals(0, report.movingSamples)
        assertEquals(3, report.cameraMeasuredSamples)
        assertEquals(0, report.subjectMeasuredSamples)
        assertEquals(0, report.conclusiveSamples)
        assertEquals(3, report.inconclusiveSamples)
        assertTrue(samples.all { it.motionMeasurement == null })
    }

    @Test fun static_camera_with_unknown_subject_is_an_evidence_rejection_not_a_static_negative() {
        val samples = (1..79).map { cameraObservation(it * 250_000L, intensity = 0f).copy(
            face = VisualEventMap.Face(.9f, 0f), humanPresenceConfidence = .9f) }
        val rejection = try {
            FearSourcePool.select(VisualEventMap(20_000_000L, emptyList(), samples), FrameAttachmentTimeline())
            throw AssertionError("Expected unavailable subject-motion evidence rejection")
        } catch (error: MaterialRejectedException) { error }
        assertEquals("insufficient_motion_evidence", rejection.code)
    }

    @Test fun missing_static_or_disconnected_camera_evidence_breaks_the_sustained_run() {
        for (middle in listOf(VisualEventMap.Observation(500_000), cameraObservation(500_000, 0f))) {
            val report = FearOpeningEvidence.evaluate(listOf(cameraObservation(250_000), middle,
                cameraObservation(750_000), cameraObservation(1_000_000)))
            assertFalse(report.supported)
            assertEquals(2, report.longestRun)
        }
        val disconnected = FearOpeningEvidence.evaluate(listOf(cameraObservation(250_000),
            cameraObservation(500_000, previousTimeUs = 300_000), cameraObservation(750_000)))
        assertFalse(disconnected.supported)
        assertEquals(2, disconnected.longestRun)
        val largeGap = FearOpeningEvidence.evaluate(listOf(cameraObservation(250_000),
            cameraObservation(1_000_000), cameraObservation(1_250_000)))
        assertFalse(largeGap.supported)
        assertEquals(2, largeGap.longestRun)
    }

    @Test fun subject_and_camera_counts_do_not_double_count_one_observation() {
        val samples = (1..3).map {
            cameraObservation(it * 250_000L).copy(motionMeasurement = measurement(.3f, .8f))
        }
        val report = FearOpeningEvidence.evaluate(samples)
        assertEquals(3, report.measuredSamples)
        assertEquals(3, report.cameraMeasuredSamples)
        assertEquals(3, report.subjectMeasuredSamples)
    }

    private fun cameraObservation(timeUs: Long, intensity: Float = .3f,
        previousTimeUs: Long = timeUs - 250_000L) = VisualEventMap.Observation(timeUs,
        cameraMeasurement = VisualEventMap.CameraMeasurement(intensity, 0f, 1f, 20, 4,
            previousTimeUs, timeUs, timeUs, timeUs - previousTimeUs))

    private fun measurement(subject: Float, camera: Float) = VisualEventMap.MotionMeasurement(
        subject, camera, 0f, 1f, 12, .8f, 40, 4, 250_000L)

    private fun observation(timeUs: Long, moving: Boolean) = VisualEventMap.Observation(timeUs,
        motionMeasurement = measurement(0f, if (moving) .8f else 0f),
        face = VisualEventMap.Face(.9f, 0f), humanPresenceConfidence = .9f)
}
