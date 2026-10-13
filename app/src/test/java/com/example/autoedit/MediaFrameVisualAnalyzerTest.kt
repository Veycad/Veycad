package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class MediaFrameVisualAnalyzerTest {
    private val pixels = LumaMotionEstimator.Plane(12, 18, FloatArray(12 * 18) { .5f })
    private val estimate = LumaMotionEstimator.estimate(null, pixels)
    private fun semantics(face: VisualEventMap.Face? = VisualEventMap.Face(.9f, 24f),
        faceSucceeded: Boolean = true, human: Float = if (face != null) .9f else 0f) =
        LocalSemanticFrameAnalyzer.Result(null, null, 0f, 0f, face, null,
            if (face == null) null else VisualEventMap.Composition(.6f, .9f, .9f, .9f, .9f),
            .8f, 0f, if (faceSucceeded) 1 else 0, human, faceSucceeded,
            gestureEvidenceAvailable = true)

    @Test fun a_failed_frame_cannot_carry_a_prior_portrait_human_or_scale() {
        val first = MediaFrameVisualAnalyzer.observationForFrame(0, estimate, semantics())
        assertNotNull(first.face)
        assertTrue(first.faceInferenceSucceeded)
        assertEquals(.9f, first.humanPresenceConfidence, 0f)
        for (timeUs in listOf(250_000L, 500_000L, 750_000L)) {
            val failed = MediaFrameVisualAnalyzer.observationForFrame(timeUs, estimate, null)
            assertNull(failed.face)
            assertNull(failed.composition)
            assertNull(failed.motionMeasurement)
            assertFalse(failed.faceInferenceSucceeded)
            assertFalse(failed.gestureEvidenceAvailable)
            assertEquals(0f, failed.humanPresenceConfidence, 0f)
            assertEquals(0f, failed.gestureConfidence, 0f)
            assertEquals(estimate.meanLuma, failed.meanLuma, 0f)
        }
    }

    @Test fun a_failed_pose_cannot_invent_a_positive_gesture_event() {
        val failedPose = MediaFrameVisualAnalyzer.observationForFrame(0, estimate,
            semantics().copy(gestureEvidenceAvailable = false))
        assertNotNull(failedPose.face)
        assertFalse(failedPose.gestureEvidenceAvailable)
        assertEquals(0f, failedPose.gestureConfidence, 0f)
        val map = VisualEventMapAnalyzer.analyze(1_000_000, listOf(failedPose))
        assertTrue(map.events.none { it.type == VisualEventMap.EventType.GESTURE })
    }

    @Test fun one_portrait_plus_failed_frames_cannot_supply_two_human_witnesses_for_any_product() {
        val observations = listOf(MediaFrameVisualAnalyzer.observationForFrame(0, estimate, semantics())) +
            (1..3).map { MediaFrameVisualAnalyzer.observationForFrame(it * 250_000L, estimate, null) }
        val visual = VisualEventMap(2_000_000L, emptyList(), observations)
        for (recipe in MontageStyleCatalog.Recipe.entries.filter { it != MontageStyleCatalog.Recipe.GALLERY_MONTAGE }) {
            val maps = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) listOf(visual, visual) else listOf(visual)
            val rejection = try {
                MaterialSuitability.checkHumanEvidence(recipe, maps)
                throw AssertionError("A failed frame must not add a human witness for $recipe")
            } catch (error: MaterialRejectedException) { error }
            assertEquals("insufficient_human_evidence", rejection.code)
        }
    }

    @Test fun measured_empty_face_detection_and_inference_failure_stay_distinct() {
        val empty = MediaFrameVisualAnalyzer.observationForFrame(250_000, estimate,
            semantics(face = null, faceSucceeded = true))
        val failed = MediaFrameVisualAnalyzer.observationForFrame(500_000, estimate,
            semantics(face = null, faceSucceeded = false))
        assertNull(empty.face)
        assertNull(failed.face)
        assertTrue(empty.faceInferenceSucceeded)
        assertFalse(failed.faceInferenceSucceeded)
        assertEquals(0f, empty.humanPresenceConfidence, 0f)
        assertEquals(0f, failed.humanPresenceConfidence, 0f)
    }

    @Test fun a_face_after_an_unknown_gap_does_not_invent_a_measured_face_turn_across_the_gap() {
        val before = MediaFrameVisualAnalyzer.observationForFrame(0, estimate,
            semantics(VisualEventMap.Face(.9f, -24f)))
        val failed = MediaFrameVisualAnalyzer.observationForFrame(250_000, estimate, null)
        val after = MediaFrameVisualAnalyzer.observationForFrame(500_000, estimate,
            semantics(VisualEventMap.Face(.9f, 24f)))
        val map = VisualEventMapAnalyzer.analyze(1_000_000, listOf(before, failed, after))
        assertTrue(map.events.none { it.type == VisualEventMap.EventType.FACE_TURN })
        assertTrue(map.events.none { it.type == VisualEventMap.EventType.COMPOSITION_PEAK &&
            it.peakTimeUs == failed.sourceTimeUs })
    }

    @Test fun missing_motion_measurement_is_unknown_even_with_large_legacy_vectors() {
        assertNull(VisualEventMap.Observation(0, cameraMotion = VisualEventMap.Vector(1f),
            subjectMotion = VisualEventMap.Vector(-1f)).motionMeasurement)
    }

    @Test fun source_observation_keeps_independent_camera_but_never_carries_it_through_semantic_failure() {
        val camera = VisualEventMap.CameraMeasurement(.3f, 0f, 1f, 20, 4,
            0, 250_000, 250_000, 250_000)
        val measured = MediaFrameVisualAnalyzer.observationForFrame(250_000, estimate, semantics(),
            cameraMeasurement = camera)
        assertEquals(camera, measured.cameraMeasurement)
        assertNull(measured.motionMeasurement)
        val failed = MediaFrameVisualAnalyzer.observationForFrame(250_000, estimate, null,
            cameraMeasurement = camera)
        assertNull(failed.cameraMeasurement)
        assertNull(failed.motionMeasurement)
    }

    @Test fun editorial_profile_preserves_every_non_correspondence_observation_field() {
        val camera = VisualEventMap.CameraMeasurement(.3f, 0f, 1f, 20, 4,
            0, 250_000, 250_000, 250_000)
        val body = VisualEventMap.MotionMeasurement(.4f, .3f, 0f, 1f, 12, .8f, 20, 4, 250_000)
        val current = semantics()
        val full = MediaFrameVisualAnalyzer.observationForFrame(250_000, estimate, current, body, camera)
        val editorial = MediaFrameVisualAnalyzer.observationForFrame(250_000, estimate, current,
            SourceAnalysisProfile.EDITORIAL_SEMANTICS.correspondence { body },
            SourceAnalysisProfile.EDITORIAL_SEMANTICS.correspondence { camera })
        assertEquals(full.copy(motionMeasurement = null, cameraMeasurement = null), editorial)
        assertEquals(current.face, editorial.face)
        assertTrue(editorial.gestureEvidenceAvailable)
        assertEquals(current.humanPresenceConfidence, editorial.humanPresenceConfidence, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun independent_camera_evidence_cannot_be_attached_to_a_different_current_frame() {
        VisualEventMap.Observation(500_000, cameraMeasurement = VisualEventMap.CameraMeasurement(
            .3f, 0f, 1f, 20, 4, 0, 250_000, 250_000, 250_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_single_matched_patch_cannot_certify_the_subject() {
        VisualEventMap.MotionMeasurement(1f, 0f, 0f, 1f, 1, .8f, 40, 4, 250_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun one_percent_of_person_support_cannot_certify_the_subject() {
        VisualEventMap.MotionMeasurement(1f, 0f, 0f, 1f, 12, .01f, 40, 4, 250_000)
    }
}
