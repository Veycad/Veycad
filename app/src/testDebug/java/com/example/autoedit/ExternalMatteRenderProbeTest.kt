package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

/** This fixture exercises a debug-only probe and must not enter release unit compilation. */
class ExternalMatteRenderProbeTest {
    private fun frame(time: Long, alpha: Float) = FrameAttachments(time,
        mask = FrameAttachments.Plane(1, 1, floatArrayOf(alpha), 1f))

    @Test fun replacement_is_limited_to_its_source_window_and_has_no_duplicate_pts() {
        val original = FrameAttachmentTimeline(listOf(frame(0, .1f), frame(100, .2f),
            frame(200, .3f), frame(300, .4f)))
        val replacements = listOf(frame(100, .8f), frame(150, .9f), frame(200, 1f))
        val merged = ExternalMatteRenderProbe.mergeTimelines(original, replacements)
        assertEquals(listOf(0L, 100L, 150L, 200L, 300L), merged.frames.map { it.sourceTimeUs })
        assertSame(original.frames.first(), merged.frames.first())
        assertSame(original.frames.last(), merged.frames.last())
        assertSame(replacements[1], merged.frames[2])
        assertEquals(.2f, original.frames[1].mask!!.values[0], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicate_external_pts_are_rejected() {
        ExternalMatteRenderProbe.mergeTimelines(FrameAttachmentTimeline(),
            listOf(frame(100, .2f), frame(100, .3f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun empty_override_is_not_a_successful_experiment() {
        ExternalMatteRenderProbe.mergeTimelines(FrameAttachmentTimeline(), emptyList())
    }
}
