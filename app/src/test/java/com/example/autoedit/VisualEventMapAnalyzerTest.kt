package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualEventMapAnalyzerTest {
    @Test fun separates_global_camera_motion_from_residual_subject_motion() {
        val map = VisualEventMapAnalyzer.analyze(2_000_000L, listOf(
            observation(100_000L),
            observation(500_000L, cameraX = .42f),
            observation(900_000L, subjectX = -.51f),
            observation(1_300_000L)
        ))

        val camera = map.events.single { it.type == VisualEventMap.EventType.CAMERA_MOVE }
        val subject = map.events.single { it.type == VisualEventMap.EventType.SUBJECT_MOVE }
        assertEquals(VisualEventMap.Direction.RIGHT, camera.direction)
        assertEquals(VisualEventMap.Direction.LEFT, subject.direction)
        assertEquals(500_000L, camera.peakTimeUs)
        assertEquals(900_000L, subject.peakTimeUs)
    }

    @Test fun emits_semantic_face_gesture_occlusion_and_composition_events_with_valid_windows() {
        val map = VisualEventMapAnalyzer.analyze(3_000_000L, listOf(
            observation(100_000L, yaw = -4f),
            observation(600_000L, yaw = 18f, gesture = .91f),
            observation(1_200_000L, yaw = 18f, occlusion = .88f),
            observation(2_700_000L, yaw = 18f)
        ))

        assertEquals(600_000L, map.events.single { it.type == VisualEventMap.EventType.FACE_TURN }.peakTimeUs)
        assertEquals(600_000L, map.events.single { it.type == VisualEventMap.EventType.GESTURE }.peakTimeUs)
        assertEquals(1_200_000L, map.events.single { it.type == VisualEventMap.EventType.OCCLUSION }.peakTimeUs)
        assertTrue(map.events.any { it.type == VisualEventMap.EventType.COMPOSITION_PEAK })
        assertTrue(map.events.all {
            it.usableWindow.startUs >= 0L && it.usableWindow.endUs <= map.durationUs &&
                it.peakTimeUs in it.usableWindow.startUs..it.usableWindow.endUs
        })
    }

    @Test fun static_readable_material_produces_clean_holds_for_virtual_camera() {
        val map = VisualEventMapAnalyzer.analyze(2_000_000L, listOf(
            observation(100_000L), observation(500_000L), observation(900_000L),
            observation(1_300_000L), observation(1_700_000L)
        ))

        val holds = map.events.filter { it.type == VisualEventMap.EventType.CLEAN_HOLD }
        assertEquals(listOf(100_000L, 500_000L, 900_000L, 1_300_000L, 1_700_000L), holds.map { it.peakTimeUs })
        assertTrue(holds.all { it.direction == VisualEventMap.Direction.NONE })
    }

    @Test fun subject_reveal_requires_both_confident_and_temporally_stable_person_mask() {
        val map = VisualEventMapAnalyzer.analyze(2_000_000L, listOf(
            observation(100_000L, maskConfidence = .92f, maskIou = .81f),
            observation(700_000L, maskConfidence = .92f, maskIou = .55f),
            observation(1_300_000L, maskConfidence = .62f, maskIou = .86f)
        ))

        val reveals = map.events.filter { it.type == VisualEventMap.EventType.SUBJECT_REVEAL }
        assertEquals(1, reveals.size)
        assertEquals(100_000L, reveals.single().peakTimeUs)
        assertTrue(reveals.single().confidence >= .8f)
    }

    @Test fun isolated_codec_noise_vectors_do_not_create_a_directional_camera_event() {
        val observations = (0 until 24).map { index ->
            observation(
                timeUs = index * 200_000L + 100_000L,
                cameraX = if (index in setOf(7, 19)) .48f else .01f
            )
        }

        val map = VisualEventMapAnalyzer.analyze(5_000_000L, observations)

        assertTrue(map.events.none { it.type == VisualEventMap.EventType.CAMERA_MOVE })
        val supported = observations.mapIndexed { index, value ->
            if (index == 13) value.copy(cameraMotion = VisualEventMap.Vector(x = .48f)) else value
        }
        val accepted = VisualEventMapAnalyzer.analyze(5_000_000L, supported)
        assertEquals(listOf(1_500_000L, 2_700_000L, 3_900_000L),
            accepted.events.filter { it.type == VisualEventMap.EventType.CAMERA_MOVE }.map { it.peakTimeUs })
    }

    @Test fun shuffled_observations_are_sorted_before_face_turn_and_motion_analysis() {
        val map = VisualEventMapAnalyzer.analyze(2_000_000L, listOf(
            observation(1_300_000L, yaw = 18f), observation(100_000L, yaw = 0f),
            observation(700_000L, yaw = 18f, subjectX = -.51f)))
        assertEquals(listOf(100_000L, 700_000L, 1_300_000L), map.observations.map { it.sourceTimeUs })
        assertEquals(700_000L, map.events.single { it.type == VisualEventMap.EventType.FACE_TURN }.peakTimeUs)
        assertEquals(VisualEventMap.Direction.RIGHT,
            map.events.single { it.type == VisualEventMap.EventType.FACE_TURN }.direction)
        assertEquals(700_000L, map.events.single { it.type == VisualEventMap.EventType.SUBJECT_MOVE }.peakTimeUs)
    }

    private fun observation(
        timeUs: Long,
        cameraX: Float = 0f,
        subjectX: Float = 0f,
        yaw: Float = 0f,
        gesture: Float = 0f,
        occlusion: Float = 0f,
        maskConfidence: Float = 0f,
        maskIou: Float = 0f
    ) = VisualEventMap.Observation(
        sourceTimeUs = timeUs,
        cameraMotion = VisualEventMap.Vector(x = cameraX),
        subjectMotion = VisualEventMap.Vector(x = subjectX),
        face = VisualEventMap.Face(.95f, yaw),
        gestureConfidence = gesture,
        occlusionConfidence = occlusion,
        personMaskConfidence = maskConfidence,
        personMaskTemporalIou = maskIou,
        composition = VisualEventMap.Composition(.45f, .85f, .8f, .75f, .9f)
    )
}
