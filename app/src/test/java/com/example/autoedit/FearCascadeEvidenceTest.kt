package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FearCascadeEvidenceTest {
    @Test fun timestamps_camera_motion_and_authored_zoom_do_not_invent_different_poses() {
        val report = FearCascadeEvidence.evaluate(graph(), visual { 0f })
        assertFalse(report.supported)
        assertEquals(13, report.first.sampledClips)
        assertEquals(14, report.second.sampledClips)
        val error = try {
            FearCascadeEvidence.requireSupported(graph(), visual { 0f })
            throw AssertionError("Expected material rejection")
        } catch (rejection: MaterialRejectedException) { rejection }
        assertEquals("insufficient_distinct_moments", error.code)
    }

    @Test fun each_phrase_needs_a_mutually_contrasting_triplet() {
        assertTrue(FearCascadeEvidence.evaluate(graph(), visual { (it % 3) * 12f }).supported)
        assertFalse(FearCascadeEvidence.evaluate(graph(), visual { if (it < 15) 0f else (it % 3) * 12f }).supported)
        // A near-neighbour chain does not provide three mutually distinct poses.
        assertFalse(FearCascadeEvidence.evaluate(graph(), visual { (it % 3) * 8f }).supported)
    }

    @Test fun low_confidence_faces_are_unknown_not_contrasting() {
        val source = visual { (it % 3) * 45f }
        val weak = source.copy(observations = source.observations.map {
            it.copy(face = it.face?.copy(confidence = .2f), humanPresenceConfidence = 0f)
        })
        assertFalse(FearCascadeEvidence.evaluate(graph(), weak).supported)
    }

    @Test fun full_body_scale_variation_can_supply_contrast_without_faces() {
        val source = visual { 0f }
        val body = source.copy(observations = source.observations.mapIndexed { index, observation ->
            observation.copy(face = null, humanPresenceConfidence = .9f,
                composition = VisualEventMap.Composition(.2f + (index % 3) * .2f, .8f, .8f, .8f, .8f))
        })
        assertTrue(FearCascadeEvidence.evaluate(graph(), body).supported)
    }

    @Test fun unknown_pose_is_not_zero_gesture_contrast_but_a_measured_zero_is() {
        val observations = (0..29).map { index ->
            // A=(yaw0,raised), B=(yaw0,unknown pose), C=(yaw20,raised).
            // B cannot contrast with A just because a failed model wrote a numeric zero.
            VisualEventMap.Observation(index * 600_000L + 125_000L,
                face = VisualEventMap.Face(.9f, if (index % 3 == 2) 20f else 0f),
                gestureConfidence = if (index % 3 == 1) 0f else .78f,
                gestureEvidenceAvailable = index % 3 != 1)
        }
        val unknown = VisualEventMap(20_000_000L, emptyList(), observations)
        assertFalse(FearCascadeEvidence.evaluate(graph(), unknown).supported)
        val measuredZero = unknown.copy(observations = observations.map { it.copy(gestureEvidenceAvailable = true) })
        assertTrue(FearCascadeEvidence.evaluate(graph(), measuredZero).supported)
    }

    private fun visual(yaw: (Int) -> Float) = VisualEventMap(20_000_000L, emptyList(), (0..29).map {
        VisualEventMap.Observation(it * 600_000L + 125_000L,
            cameraMotion = VisualEventMap.Vector(x = .8f), face = VisualEventMap.Face(.9f, yaw(it)))
    })

    private fun graph() = MontageGraph(20_000L, 18_300L,
        clips = FearStrobeProfile.scenes.mapIndexed { index, scene ->
            MontageGraph.Clip("cascade-$index", index * 600L, index * 600L + 600L,
                scene.endUs / 1_000L - scene.startUs / 1_000L, MontageGraph.ShotRole.CLOSE,
                if (index == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.HARD_CUT,
                MontageGraph.Motion.PUSH_IN, .9f, scene.startUs / 1_000L)
        }, metadata = NleProjectMetadata(generator = "${FearStrobeProfile.ID}:exact"))
}
