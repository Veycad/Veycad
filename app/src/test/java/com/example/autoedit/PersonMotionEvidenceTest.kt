package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class PersonMotionEvidenceTest {
    private fun texture(seed: Int = 1): LumaMotionEstimator.Plane {
        val random = java.util.Random(seed.toLong())
        return LumaMotionEstimator.Plane(64, 64, FloatArray(4096) { .15f + random.nextFloat() * .5f })
    }
    private fun mask(left: Int = 22) = FrameAttachments.Plane(64, 64, FloatArray(4096) { i ->
        val x = i % 64; val y = i / 64
        if (y in 16..47 && (x in left - 8 until left + 8 || x in 64 - left - 8 until 64 - left + 8)) 1f else 0f
    }, 1f)
    private fun scene(left: Int): LumaMotionEstimator.Plane {
        val pixels = texture().luma.copyOf()
        for ((index, center) in listOf(left, 64 - left).withIndex()) {
            val random = java.util.Random((100 + index).toLong())
            for (y in 16..47) for (x in center - 8 until center + 8) pixels[y * 64 + x] = .5f + random.nextFloat() * .3f
        }
        return LumaMotionEstimator.Plane(64, 64, pixels)
    }
    @Test fun opposed_objects_survive_actual_mask_and_background_camera_estimation() {
        val result = requireNotNull(PersonMotionEvidence.measure(scene(20), scene(22), mask(), 1f))
        assertEquals(0f, result.camera.x, 0f)
        assertTrue(result.camera.coveredQuadrants >= 3)
        // Two native pixels at width 64 = 1.5 reference pixels, hence intensity .5.
        assertEquals(.5f, result.subject.intensity, .1f)
    }
    @Test fun camera_pan_does_not_become_relative_subject_movement() {
        val a = texture()
        val b = a.copy(luma = FloatArray(4096) { i -> a[(i % 64 - 2).coerceAtLeast(0), i / 64] })
        val result = requireNotNull(PersonMotionEvidence.measure(a, b, mask(), 1f))
        assertEquals(2f / (3f * 64 / 48), result.camera.x, .00001f)
        assertEquals(0f, result.subject.intensity, .00001f)
    }
    @Test fun lighting_only_is_measured_zero_with_real_correspondence() {
        val a = texture()
        val b = a.copy(luma = FloatArray(4096) { a.luma[it] + .15f })
        assertEquals(0f, requireNotNull(PersonMotionEvidence.measure(a, b, mask(), 1f)).subject.intensity, 0f)
    }
    @Test fun independent_noise_is_unknown_not_supported_motion() {
        assertNull(PersonMotionEvidence.measure(texture(), texture(42), mask(), 1f))
    }
    @Test fun missing_person_or_full_mask_without_background_is_unknown() {
        val a = texture()
        assertNull(PersonMotionEvidence.measure(a, a, null, 1f))
        assertNull(PersonMotionEvidence.measure(a, a, mask(), .1f))
        assertNull(PersonMotionEvidence.measure(a, a, FrameAttachments.Plane(64, 64, FloatArray(4096) { 1f }, 1f), 1f))
    }

    @Test fun production_observation_requires_fresh_semantics_and_bounded_actual_pts_gap() {
        val a = texture()
        fun observe(previousTime: Long?, currentTime: Long, semanticTime: Long?) =
            PersonMotionEvidence.observe(a, a, previousTime, currentTime, semanticTime, mask(), 1f)
        val measured = requireNotNull(observe(0, 250_000, 250_000))
        assertEquals(0f, measured.subjectIntensity, 0f)
        assertEquals(250_000L, measured.intervalUs)
        assertNull(observe(null, 250_000, 250_000))
        assertNull(observe(0, 250_000, null))
        assertNull(observe(0, 250_000, 0)) // Previous mask must not certify the current frame.
        assertNull(observe(0, 600_000, 600_000))
        assertNull(observe(250_000, 250_000, 250_000))
        assertNull(observe(250_000, 0, 0))
    }

    @Test fun actual_opposed_pixel_motion_can_supply_fear_without_a_direction_vector() {
        val measurement = requireNotNull(PersonMotionEvidence.observe(scene(20), scene(22),
            0, 250_000, 250_000, mask(), 1f))
        val report = FearOpeningEvidence.evaluate((1..3).map { index ->
            VisualEventMap.Observation(index * 250_000L, motionMeasurement = measurement)
        })
        assertTrue(report.supported)
        assertEquals(3, report.measuredSamples)
    }

    @Test fun audit_names_the_first_failed_stage_without_inventing_a_measurement() {
        val a = texture()
        fun audit(mask: FrameAttachments.Plane?, human: Float = 1f,
                  semanticTime: Long? = 250_000, previousTime: Long? = 0) =
            PersonMotionEvidence.assess(a, a, previousTime, 250_000, semanticTime, mask, human)
        assertEquals(PersonMotionEvidence.Stage.FIRST_FRAME, audit(mask(), previousTime = null).stage)
        assertEquals(PersonMotionEvidence.Stage.STALE_SEMANTICS, audit(mask(), semanticTime = 0).stage)
        assertEquals(PersonMotionEvidence.Stage.PTS_GAP, audit(mask(), previousTime = -500_000).stage)
        assertEquals(PersonMotionEvidence.Stage.MASK_UNAVAILABLE, audit(null).stage)
        assertEquals(PersonMotionEvidence.Stage.MASK_UNAVAILABLE, audit(mask().copy(confidence = .1f)).stage)
        assertEquals(PersonMotionEvidence.Stage.HUMAN_UNAVAILABLE, audit(mask(), human = .1f).stage)
        val fullMask = FrameAttachments.Plane(64, 64, FloatArray(4096) { 1f }, 1f)
        val missingBackground = audit(fullMask)
        assertEquals(PersonMotionEvidence.Stage.CAMERA_UNSUPPORTED, missingBackground.stage)
        assertEquals(0, missingBackground.backgroundCells)
        assertNull(missingBackground.measurement)
    }

    @Test fun audit_preserves_measured_static_and_pixel_support_counters() {
        val a = texture()
        val result = PersonMotionEvidence.assess(a, a, 0, 250_000, 250_000, mask(), 1f)
        assertEquals(PersonMotionEvidence.Stage.MEASURED, result.stage)
        assertEquals(PersonMotionEvidence.observe(a, a, 0, 250_000, 250_000, mask(), 1f), result.measurement)
        assertEquals(0f, requireNotNull(result.measurement).subjectIntensity, 0f)
        assertTrue(result.backgroundCells >= 8)
        assertTrue(result.backgroundQuadrants >= 3)
        assertTrue(result.personCells >= 4)
        assertEquals(result.uniqueCenters, result.cells)
        assertEquals(result.cells, result.reliableCells)
        assertEquals(result.cells, result.centerBackground.candidates + result.personSupport.candidates)
        assertEquals(result.backgroundCells, result.cleanQueryBackground.reliable)
        assertEquals(result.personCells, result.personSupport.reliable)
        assertEquals(result.mixedBackgroundPatches,
            result.centerBackground.candidates - result.cleanQueryBackground.candidates)
        assertTrue(result.centerBackground.reliable >= result.cleanQueryBackground.reliable)
        assertTrue(result.mixedBackgroundPatches > 0)
    }

    @Test fun camera_evidence_requires_the_same_fresh_semantics_human_mask_and_pts_gates() {
        val a = texture()
        fun audit(previousTime: Long? = 0, time: Long = 250_000, semantic: Long? = time,
            currentMask: FrameAttachments.Plane? = mask(), human: Float = 1f) =
            PersonMotionEvidence.assess(a, a, previousTime, time, semantic, currentMask, human)
        assertNotNull(audit().cameraMeasurement)
        for (invalid in listOf(
            audit(previousTime = null), audit(semantic = null), audit(semantic = 0),
            audit(time = 600_000), audit(time = 0), audit(previousTime = 250_000, time = 0),
            audit(currentMask = null), audit(currentMask = mask().copy(confidence = .79f)),
            audit(human = .54f), audit(currentMask = FrameAttachments.Plane(64, 64,
                FloatArray(4096) { 1f }, 1f)))) {
            assertNull("Failed stage ${invalid.stage} must not carry camera evidence", invalid.cameraMeasurement)
            assertNull(invalid.rawCamera)
            assertNull(invalid.measurement)
        }
        val unsupported = PersonMotionEvidence.assess(a, texture(42), 0, 250_000, 250_000, mask(), 1f)
        assertEquals(PersonMotionEvidence.Stage.CAMERA_UNSUPPORTED, unsupported.stage)
        assertNull(unsupported.cameraMeasurement)
        assertNull(unsupported.rawCamera)
    }
}
