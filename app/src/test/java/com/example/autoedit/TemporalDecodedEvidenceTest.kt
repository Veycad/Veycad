package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicitly synthetic shader-evidence tests; these do not certify footage or human acceptance. */
class TemporalDecodedEvidenceTest {
    private val unavailable = "temporal_layer_decoded_source_evidence_unavailable"
    private val identical = "temporal_layer_identical_decoded_source_pts"
    private val noTwoDecoders = "temporal_layer_without_two_live_decoders"
    private val decodedIssues = setOf(unavailable, identical, noTwoDecoders)

    @Test fun requested_67ms_cannot_certify_identical_decoded_frames_for_either_temporal_kind() {
        temporalKinds.forEach { kind ->
            val summary = evaluate(frame(kind, primary = 1_000_000L, secondary = 1_000_000L))
            assertEquals(listOf(identical), summary.issues)
            assertFalse(summary.accepted)
            assertFalse(summary.issues.contains("temporal_layer_sources_too_close_to_read"))
        }
    }

    @Test fun absent_or_invalid_actual_timestamps_fail_closed_without_claiming_identical_pixels() {
        temporalKinds.forEach { kind ->
            listOf(
                null to 933_334L,
                1_000_000L to null,
                null to null,
                -1L to 933_334L,
                1_000_000L to -1L,
                -1L to -1L
            ).forEach { (primary, secondary) ->
                val summary = evaluate(frame(kind, primary = primary, secondary = secondary))
                assertEquals(listOf(unavailable), summary.issues)
                assertFalse(summary.accepted)
            }
        }
    }

    @Test fun distinct_pts_alone_do_not_replace_a_live_second_decoder() {
        val summary = evaluate(frame(dualDecoder = false))
        assertEquals(listOf(noTwoDecoders), summary.issues)
        assertFalse(summary.accepted)
    }

    @Test fun source_cadence_quantisation_does_not_apply_requested_67ms_as_a_decoded_threshold() {
        temporalKinds.forEach { kind ->
            for (deltaUs in listOf(66_656L, 66_666L, 83_334L, 100_000L)) {
                val summary = evaluate(frame(kind, secondary = 1_000_000L - deltaUs))
                assertEquals(emptyList<String>(), summary.issues)
                assertTrue(summary.accepted)
            }
        }
    }

    @Test fun new_decoded_gate_is_not_an_unsubstantiated_readability_or_direction_threshold() {
        for (secondary in listOf(999_999L, 1_000_001L)) {
            val summary = evaluate(frame(secondary = secondary))
            assertTrue(summary.accepted)
        }
    }

    @Test fun visibility_boundary_is_inclusive_and_subthreshold_samples_remain_outside_new_gate() {
        val boundary = evaluate(frame(opacity = .25f, secondary = null))
        assertEquals(listOf(unavailable), boundary.issues)
        val below = evaluate(frame(opacity = .249f, primary = null, secondary = null, dualDecoder = false))
        assertTrue(below.accepted)
        assertTrue(below.issues.none { it in decodedIssues })
    }

    @Test fun existing_requested_separation_check_is_still_enforced_with_distinct_actual_pts() {
        val summary = evaluate(frame().copy(secondarySourceTimeUs = 966_667L))
        assertEquals(listOf("temporal_layer_sources_too_close_to_read"), summary.issues)
        assertFalse(summary.accepted)
    }

    @Test fun non_temporal_layers_do_not_acquire_a_second_decoder_contract() {
        val frame = frame(MontageGraph.OverlayKind.FLASH, primary = null,
            secondary = null, dualDecoder = false).copy(secondarySourceTimeUs = null)
        val summary = evaluate(frame)
        assertTrue(summary.accepted)
        assertTrue(summary.issues.none { it in decodedIssues })
    }

    @Test fun only_the_existing_reference_profile_exemption_is_retained() {
        temporalKinds.forEach { kind ->
            val sample = frame(kind, primary = null, secondary = null, dualDecoder = false)
                .copy(secondarySourceTimeUs = null)
            val referenceGraph = graph(kind).copy(
                metadata = NleProjectMetadata(generator = ReferenceMontageProfile.ID)
            )
            val summary = VeykadRenderInspector.evaluate(referenceGraph, 1, listOf(sample))
            assertTrue(summary.accepted)
            assertTrue(summary.issues.none { it in decodedIssues })
            assertFalse(evaluate(sample).accepted)
        }
    }

    @Test fun binding_selection_uses_exact_debug_and_condition_and_referential_identity() {
        val incoming = Any()
        val outgoing = Any()
        for (probe in listOf(false, true)) for (sameTexture in listOf(false, true)) {
            val selected = MediaCodecSpeedRampRenderer.boundSecondaryInput(
                incoming, outgoing, probe, sameTexture
            )
            assertSame(if (probe && sameTexture) incoming else outgoing, selected)
        }
    }

    @Test fun evidence_metadata_derives_policy_from_the_existing_profile_and_layer_contract() {
        assertEquals("decoded-texture-pts-v1", VeykadRenderInspector.TEMPORAL_DECODED_EVIDENCE_METHOD)
        temporalKinds.forEach { kind ->
            assertEquals("temporal-distinct-pts-v1", VeykadRenderInspector.temporalLayerPolicy(graph(kind)))
            assertEquals("reference-spatial-v1", VeykadRenderInspector.temporalLayerPolicy(
                graph(kind).copy(metadata = NleProjectMetadata(generator = ReferenceMontageProfile.ID))
            ))
        }
        assertEquals("no-temporal-layer-v1", VeykadRenderInspector.temporalLayerPolicy(graph(MontageGraph.OverlayKind.FLASH)))
        assertEquals("no-temporal-layer-v1", VeykadRenderInspector.temporalLayerPolicy(graph(MontageGraph.OverlayKind.FLASH).copy(overlays = emptyList())))
    }

    @Test fun bound_same_texture_provenance_exposes_identical_pts_instead_of_outgoing_pts() {
        data class TextureMarker(val actualPtsUs: Long)
        val incoming = TextureMarker(1_000_000L)
        val outgoing = TextureMarker(933_334L)
        val bound = MediaCodecSpeedRampRenderer.boundSecondaryInput(incoming, outgoing, true, true)
        val summary = evaluate(frame(primary = incoming.actualPtsUs, secondary = bound.actualPtsUs))
        assertSame(incoming, bound)
        assertEquals(listOf(identical), summary.issues)
    }

    @Test fun no_outgoing_frame_keeps_actual_secondary_evidence_absent() {
        val graph = graph(MontageGraph.OverlayKind.FLASH)
        val scheduled = HighQualityFramePlan.Frame(
            outputTimeUs = 500_000L, sourceTimeUs = 1_000_000L, clipIndex = 0,
            clipProgress = .5f, transform = MontageGraph.ClipTransform.hold().keyframes.first(),
            transitionIn = MontageGraph.Transition.HARD_CUT
        )
        val collector = VeykadRenderInspector.Collector(graph, 1)
        collector.record(scheduled, null, false, decodedSourceTimeUs = 1_000_000L)
        val recorded = collector.evidence().single()
        assertNull(recorded.secondarySourceTimeUs)
        assertNull(recorded.decodedSecondarySourceTimeUs)
    }

    @Test fun one_invalid_visible_frame_rejects_an_otherwise_valid_sequence_in_either_order() {
        val good = frame()
        val bad = frame(primary = 1_000_000L, secondary = 1_000_000L)
            .copy(outputTimeUs = 516_667L)
        val valid = good.copy(outputTimeUs = 516_667L)
        assertTrue(VeykadRenderInspector.evaluate(graph(good.layerKind), 2, listOf(good, valid)).accepted)
        for (samples in listOf(listOf(good, bad), listOf(bad, good))) {
            val summary = VeykadRenderInspector.evaluate(graph(good.layerKind), 2, samples)
            assertFalse(summary.accepted)
            assertEquals(listOf(identical), summary.issues)
        }
    }

    private fun evaluate(sample: VeykadRenderInspector.FrameEvidence) =
        VeykadRenderInspector.evaluate(graph(sample.layerKind), 1, listOf(sample))

    private fun graph(kind: MontageGraph.OverlayKind) = MontageGraph(
        sourceDurationMs = 2_000L,
        outputDurationMs = 2_000L,
        clips = listOf(MontageGraph.Clip(
            id = "explicitly-synthetic-hold", sourceStartMs = 0L, sourceEndMs = 2_000L,
            outputDurationMs = 2_000L, role = MontageGraph.ShotRole.CLOSE,
            transitionIn = MontageGraph.Transition.HARD_CUT, motion = MontageGraph.Motion.HOLD,
            confidence = .9f, beatAnchorMs = 0L
        )),
        overlays = listOf(MontageGraph.Overlay(
            id = "explicitly-synthetic-layer", startMs = 0L, endMs = 2_000L,
            kind = "synthetic", blendMode = MontageGraph.BlendMode.SCREEN,
            overlayKind = kind, opacity = .6f, secondarySourceOffsetMs = -67L
        ))
    )

    private fun frame(
        kind: MontageGraph.OverlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
        primary: Long? = 1_000_000L,
        secondary: Long? = 933_334L,
        dualDecoder: Boolean = true,
        opacity: Float = .6f
    ) = VeykadRenderInspector.FrameEvidence(
        outputTimeUs = 500_000L, sourceTimeUs = 1_000_000L, clipIndex = 0,
        transition = MontageGraph.Transition.HARD_CUT, dualDecoder = dualDecoder,
        speed = 1f, scale = 1f, translateX = 0f, translateY = 0f,
        blur = 0f, blackout = 0f, occlusion = 0f, foregroundReentry = 0f,
        glow = 0f, glitch = 0f, lensBlur = 0f, layerOpacity = opacity, layerKind = kind,
        maskConfidence = 0f, subjectQuality = 0f, subjectOcclusion = 0f, maskTemporalIou = 0f,
        secondarySourceTimeUs = 933_000L, decodedSourceTimeUs = primary,
        decodedSecondarySourceTimeUs = secondary
    )

    private val temporalKinds = listOf(
        MontageGraph.OverlayKind.DOUBLE_EXPOSURE, MontageGraph.OverlayKind.MIRROR_SLICE
    )
}
