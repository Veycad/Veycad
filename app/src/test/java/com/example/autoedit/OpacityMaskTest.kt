package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class OpacityMaskTest {
    private fun frame(time: Long, alpha: Float, opacity: Boolean) = FrameAttachments(
        sourceTimeUs = time, mask = FrameAttachments.Plane(1, 1, floatArrayOf(alpha), 1f),
        maskIsOpacity = opacity)

    @Test fun opacity_semantics_and_fractional_values_survive_frame_scheduling() {
        val timeline = FrameAttachmentTimeline(listOf(frame(0, .2f, true), frame(100_000, .8f, true)))
        val sample = requireNotNull(timeline.interpolated(50_000))
        assertTrue(sample.maskIsOpacity)
        assertEquals(.2f, sample.mask!!.values[0], 0f)
        assertEquals(.8f, sample.maskBlendTarget!!.values[0], 0f)
        assertEquals(.5f, sample.maskBlendProgress, 0f)
    }

    @Test fun confidence_and_opacity_are_not_interpolated_as_if_interchangeable() {
        val timeline = FrameAttachmentTimeline(listOf(frame(0, .2f, false), frame(100_000, .8f, true)))
        val left = requireNotNull(timeline.interpolated(25_000))
        val right = requireNotNull(timeline.interpolated(75_000))
        assertFalse(left.maskIsOpacity)
        assertTrue(right.maskIsOpacity)
        assertNull(left.maskBlendTarget)
        assertNull(right.maskBlendTarget)
    }

    @Test fun existing_masks_remain_confidence_masks_by_default() {
        assertFalse(FrameAttachments(0, mask = FrameAttachments.Plane(1, 1, listOf(.5f), 1f)).maskIsOpacity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun depth_only_frame_cannot_claim_an_opacity_mask() {
        FrameAttachments(0, depth = FrameAttachments.Plane(1, 1, listOf(.5f), 1f), maskIsOpacity = true)
    }
}
