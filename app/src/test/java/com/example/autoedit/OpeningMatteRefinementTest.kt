package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class OpeningMatteRefinementTest {
    @Test fun decoded_qa_uses_rendered_source_clock_without_moving_output_timing() {
        val g = OpeningMatteRefinement.attach(graph(), longArrayOf(1050000, 1200000),
            mapOf(1050000L to plane(.4f), 1200000L to plane(.6f)))
        val planned = HighQualityFramePlan.build(g).frames.first()
        val actual = RenderedVisualSampler.withDecodedSourceTime(planned, g.frameAttachments, 1050000)
        assertEquals(1050000L, actual.sourceTimeUs)
        assertEquals(.4f, actual.attachments!!.mask!!.values[0], 0f)
        assertEquals(planned, actual.copy(sourceTimeUs = planned.sourceTimeUs, attachments = planned.attachments))
        assertSame(planned, RenderedVisualSampler.withDecodedSourceTime(planned, g.frameAttachments, null))
        val outside = RenderedVisualSampler.withDecodedSourceTime(planned, g.frameAttachments, 1300000)
        assertEquals(g.frameAttachments.interpolated(1300000), outside.attachments)
        val otherClip = planned.copy(clipIndex = 1)
        assertEquals(otherClip.attachments,
            RenderedVisualSampler.withDecodedSourceTime(otherClip, g.frameAttachments, 1050000).attachments)
    }

    @Test fun baseline_cutout_uses_actual_clock_even_without_refinements() {
        val g = graph()
        val frame = HighQualityFramePlan.build(g).frames.first()
        val actual = RenderedVisualSampler.withDecodedSourceTime(frame, g.frameAttachments, 1125000)
        assertTrue(g.frameAttachments.maskRefinements.isEmpty())
        assertEquals(1125000L, actual.attachments!!.sourceTimeUs)
        assertEquals(g.frameAttachments.interpolated(1125000), actual.attachments)
        assertNotEquals(frame.attachments, actual.attachments)
    }

    @Test fun mask_targets_match_decoder_ceiling_including_vfr_repeats_and_eos() {
        assertArrayEquals(longArrayOf(0, 33000, 71000, 120000),
            OpeningMatteRefinement.alignToDecodedFrames(
                longArrayOf(0, 1, 16000, 33000, 34000, 70000, 71000, 119999, 130000),
                longArrayOf(0, 33000, 71000, 120000)))
    }

    @Test fun exact_mask_preserves_supported_face_without_expanding_silhouette() {
        val prior = FrameAttachments.Plane(20, 20, FloatArray(400) { 1f }, 1f)
        val raw = prior.copy(values = FloatArray(400) { index ->
            val x = index % 20
            val y = index / 20
            if (x in 5..14 && y in 4..16 && index != 210) 1f else .1f
        })
        val base = FrameAttachments(0, mask = prior,
            faceRegion = FrameAttachments.FaceRegion(.5f, .5f, .5f, .6f, .9f))
        val repaired = OpeningMatteRefinement.preserveFaceInterior(raw, base)
        assertEquals(1f, repaired.values[10 * 20 + 10], .0001f)
        assertEquals(.1f, repaired.values[0], 0f)
        assertEquals(.1f, raw.values[10 * 20 + 10], 0f)
        assertSame(raw, OpeningMatteRefinement.preserveFaceInterior(raw, base.copy(subjectOcclusion = .8f)))
        assertSame(raw, OpeningMatteRefinement.preserveFaceInterior(raw, base.copy(faceRegion = null)))
        val unsupported = base.copy(mask = prior.copy(values = FloatArray(400) { .1f }))
        assertEquals(.1f, OpeningMatteRefinement.preserveFaceInterior(raw, unsupported).values[210], 0f)
    }

    @Test fun face_protection_does_not_restore_background_inside_a_moving_face_box() {
        val prior = FrameAttachments.Plane(20, 20, FloatArray(400) { 1f }, 1f)
        val raw = prior.copy(values = FloatArray(400) { index ->
            if (index % 20 <= 9) 1f else .1f
        })
        val base = FrameAttachments(0, mask = prior,
            faceRegion = FrameAttachments.FaceRegion(.5f, .5f, .5f, .6f, .9f))
        val repaired = OpeningMatteRefinement.preserveFaceInterior(raw, base)
        assertEquals(.1f, repaired.values[210], 0f)
        assertArrayEquals(raw.values, repaired.values, 0f)
    }

    private fun plane(value: Float) = FrameAttachments.Plane(1, 1, floatArrayOf(value), 1f)
    private fun graph(transition: MontageGraph.Transition = MontageGraph.Transition.FOREGROUND_REENTRY) =
        MontageGraph(10_000, 5_000, clips = listOf(MontageGraph.Clip("a", 1000, 6000, 5000,
            MontageGraph.ShotRole.OPENING, transition, MontageGraph.Motion.HOLD, 1f, 0)),
            frameAttachments = FrameAttachmentTimeline(listOf(
                FrameAttachments(1000000, mask = plane(.2f), depth = plane(.3f), subjectQuality = .3f),
                FrameAttachments(1250000, mask = plane(.8f), depth = plane(.7f), subjectQuality = .9f))))

    @Test fun targets_are_exact_scheduled_source_times_and_bounded() {
        val g = graph()
        val actual = OpeningMatteRefinement.targets(g)
        val expected = HighQualityFramePlan.build(g).frames.filter {
            it.transitionProgress?.let { progress -> progress >= 0f } == true
        }.map { it.sourceTimeUs }.distinctBy { it / 1_000L }.take(90).toLongArray()
        assertTrue(actual.size <= OpeningMatteRefinement.MAX_FRAMES)
        assertArrayEquals(expected, actual)
    }

    @Test fun no_cutout_means_no_additional_inference() {
        assertTrue(OpeningMatteRefinement.targets(graph(MontageGraph.Transition.HARD_CUT)).isEmpty())
    }

    @Test fun targets_include_a_later_live_reentry_instead_of_only_the_opening_clip() {
        val first = MontageGraph.Clip(
            "first", 0, 1_000, 1_000, MontageGraph.ShotRole.OPENING,
            MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0
        )
        val entrance = MontageGraph.Clip(
            "entrance", 1_000, 2_000, 1_000, MontageGraph.ShotRole.CLOSE,
            MontageGraph.Transition.FOREGROUND_REENTRY, MontageGraph.Motion.HOLD, 1f, 1_000,
            transitionDurationMs = 1_000
        )
        val later = MontageGraph(2_000, 2_000, clips = listOf(first, entrance))
        val targets = OpeningMatteRefinement.targets(later)
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it in 1_000_000L..2_000_000L })
    }

    @Test fun square_and_portrait_payloads_stay_within_budget() {
        for ((w,h) in listOf(480 to 480, 270 to 480, 480 to 270)) {
            val (rw,rh) = OpeningMatteRefinement.dimensions(w,h,90)
            assertTrue(rw.toLong()*rh*90 <= OpeningMatteRefinement.MAX_PIXELS)
        }
    }

    @Test fun bounded_refinement_covers_both_entrance_and_finale() {
        val entrance = MontageGraph.Clip("entrance", 0, 3_000, 3_000,
            MontageGraph.ShotRole.OPENING, MontageGraph.Transition.FOREGROUND_REENTRY,
            MontageGraph.Motion.HOLD, 1f, 0, transitionDurationMs = 3_000)
        val finale = entrance.copy(id = "finale", sourceStartMs = 10_000, sourceEndMs = 12_000,
            outputDurationMs = 2_000, role = MontageGraph.ShotRole.FINALE,
            transitionIn = MontageGraph.Transition.HARD_CUT, beatAnchorMs = 3_000,
            transitionDurationMs = null)
        val stage = MontageGraph.Overlay("reference-final-subject-stage", 3_000, 5_000,
            "reference-subject-stage", overlayKind = MontageGraph.OverlayKind.SUBJECT_STAGE, opacity = 1f)
        val g = MontageGraph(12_000, 5_000, clips = listOf(entrance, finale), overlays = listOf(stage),
            metadata = NleProjectMetadata(generator = ReferenceMontageProfile.ID))
        val targets = OpeningMatteRefinement.targets(g)
        assertEquals(OpeningMatteRefinement.MAX_FRAMES, targets.size)
        assertTrue(targets.any { it < 3_000_000L })
        assertTrue(targets.any { it >= 10_000_000L })
        assertEquals(0L, targets.first())
        assertTrue(targets.last() > 11_900_000L)
        assertTrue(targets.toList().zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun refined_masks_preserve_all_non_mask_interpolation() {
        val original = graph()
        val refined = OpeningMatteRefinement.attach(original, longArrayOf(1050000,1200000),
            mapOf(1050000L to plane(.4f),1200000L to plane(.6f)))
        for (time in 1000000L..1250000L step 10000) {
            val before = requireNotNull(original.frameAttachments.interpolated(time))
            val after = requireNotNull(refined.frameAttachments.interpolated(time))
            assertEquals(before, after.copy(mask=before.mask, maskBlendTarget=before.maskBlendTarget,
                maskBlendProgress=before.maskBlendProgress, maskIsOpacity=before.maskIsOpacity))
            if (time < 1050000 || time > 1200000) assertEquals(before, after)
        }
        assertEquals(.4f, refined.frameAttachments.interpolated(1050000)!!.mask!!.values[0], 0f)
        assertFalse(refined.frameAttachments.interpolated(1050000)!!.maskIsOpacity)
        assertTrue(original.frameAttachments.maskRefinements.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun missing_requested_mask_is_not_silently_replaced_by_sparse_fallback() {
        OpeningMatteRefinement.attach(graph(), longArrayOf(1050000,1200000), mapOf(1050000L to plane(.4f)))
    }
}
