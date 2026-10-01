package com.example.autoedit

import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMaskMetricsTest {
    @Test fun video_matte_keeps_native_model_grid_and_temporal_stream_mode() {
        assertEquals(SelfieSegmenterOptions.STREAM_MODE, LocalSemanticFrameAnalyzer.DETECTOR_MODE)
        assertEquals(256, LocalSemanticFrameAnalyzer.MASK_WIDTH)
        assertEquals(256, LocalSemanticFrameAnalyzer.MASK_HEIGHT)
    }

    @Test fun accepts_a_crisp_plausibly_sized_person_mask() {
        val values = FloatArray(100) { index -> if (index in 25 until 70) .97f else .02f }

        val stats = SemanticMaskMetrics.stats(values)

        assertEquals(.45f, stats.coverage, .001f)
        assertTrue(stats.confidence >= .80f)
    }

    @Test fun rejects_a_full_frame_mask_as_transition_evidence() {
        val stats = SemanticMaskMetrics.stats(FloatArray(100) { .99f })

        assertEquals(1f, stats.coverage, .001f)
        assertTrue(stats.confidence < .80f)
    }

    @Test fun temporal_iou_requires_the_same_subject_region() {
        val previous = floatArrayOf(0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f)
        val stable = floatArrayOf(0f, .9f, 1f, 0f, 0f, .8f, 1f, 0f)
        val moved = floatArrayOf(1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f)

        assertEquals(1f, SemanticMaskMetrics.temporalIou(previous, stable), .001f)
        assertEquals(0f, SemanticMaskMetrics.temporalIou(previous, moved), .001f)
        assertEquals(0f, SemanticMaskMetrics.temporalIou(null, stable), .001f)
    }

    @Test fun matte_refinement_repairs_a_pinhole_and_removes_an_isolated_speckle() {
        val mask = FloatArray(7 * 7)
        for (y in 2..4) for (x in 2..4) mask[y * 7 + x] = 1f
        mask[3 * 7 + 3] = 0f
        mask[0] = 1f

        val refined = SemanticMaskMetrics.refine(mask, 7, 7)

        assertTrue(refined[3 * 7 + 3] >= .7f)
        assertTrue(refined[0] < .5f)
    }

    @Test fun edge_leak_ignores_one_cell_antialiasing_but_rejects_detached_foreground() {
        val expected = floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f
        )
        val aligned = expected.copyOf().apply { this[1 * 5 + 3] = 1f }
        val leaked = aligned.copyOf().apply { this[3 * 5] = 1f }

        assertEquals(0f, SemanticMaskMetrics.edgeLeakRatio(expected, aligned, 5, 4), .001f)
        assertTrue(SemanticMaskMetrics.edgeLeakRatio(expected, leaked, 5, 4) > .2f)
    }

    @Test fun registered_edge_leak_corrects_small_inference_shift_but_not_detached_regions() {
        val expected = FloatArray(12 * 10).apply {
            for (y in 3..6) for (x in 4..6) this[y * 12 + x] = 1f
        }
        val shifted = FloatArray(12 * 10).apply {
            for (y in 3..6) for (x in 6..8) this[y * 12 + x] = 1f
        }
        val detached = shifted.copyOf().apply { this[0] = 1f; this[lastIndex] = 1f }

        assertEquals(0f, SemanticMaskMetrics.registeredEdgeLeakRatio(expected, shifted, 12, 10), .001f)
        assertTrue(SemanticMaskMetrics.registeredEdgeLeakRatio(expected, detached, 12, 10) > 0f)
    }

    @Test fun registered_temporal_iou_ignores_small_detector_shift_but_catches_shape_instability() {
        val previous = FloatArray(12 * 10).apply {
            for (y in 2..7) for (x in 4..7) this[y * 12 + x] = 1f
        }
        val shifted = FloatArray(12 * 10).apply {
            for (y in 2..7) for (x in 6..9) this[y * 12 + x] = 1f
        }
        val damaged = shifted.copyOf().apply {
            for (y in 2..5) for (x in 6..9) this[y * 12 + x] = 0f
        }

        assertEquals(1f, SemanticMaskMetrics.registeredTemporalIou(previous, shifted, 12, 10), .001f)
        assertTrue(SemanticMaskMetrics.registeredTemporalIou(previous, damaged, 12, 10) < .88f)
    }

    @Test fun mask_transform_matches_gles_zoom_geometry() {
        val source = floatArrayOf(
            0f, 0f, 0f, 0f,
            0f, 1f, 1f, 0f,
            0f, 1f, 1f, 0f,
            0f, 0f, 0f, 0f
        )

        val zoomed = SemanticMaskMetrics.transform(source, 4, 4, 2f, 0f, 0f)

        assertTrue(zoomed.count { it >= .5f } > source.count { it >= .5f })
    }

    @Test fun guided_refinement_snaps_soft_matte_to_the_image_edge() {
        val width = 7
        val height = 5
        val mask = FloatArray(width * height) { index ->
            when (index % width) {
                in 0..1 -> .02f
                2 -> .38f
                3 -> .62f
                else -> .98f
            }
        }
        val luma = FloatArray(width * height) { index -> if (index % width <= 2) .88f else .18f }

        val refined = SemanticMaskMetrics.guidedRefine(mask, luma, width, height)

        assertTrue(refined[2 * width + 2] < mask[2 * width + 2])
        assertTrue(refined[2 * width + 3] > mask[2 * width + 3])
    }

    @Test fun foreground_entrance_motion_is_live_monotonic_and_finishes_at_rest() {
        assertEquals(.78f, ForegroundReentryMotion.verticalTravel(0f), .0001f)
        assertTrue(ForegroundReentryMotion.verticalTravel(.5f) < .78f)
        assertTrue(
            ForegroundReentryMotion.verticalTravel(.75f) <
                ForegroundReentryMotion.verticalTravel(.5f)
        )
        assertEquals(0f, ForegroundReentryMotion.verticalTravel(1f), .0001f)
    }

    @Test fun qa_uses_authored_subject_envelope_after_the_opening_has_settled() {
        val subjectEnvelope = GpuTransitionModel.sample(
            MontageGraph.Transition.FOREGROUND_REENTRY,
            .72f
        ).foregroundReentry

        assertTrue(subjectEnvelope > .96f)
        assertTrue(ForegroundReentryMotion.verticalTravel(subjectEnvelope) < .004f)
    }
}
