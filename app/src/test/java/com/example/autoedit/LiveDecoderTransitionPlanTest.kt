package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDecoderTransitionPlanTest {
    @Test fun outgoing_decoder_continues_forward_instead_of_replaying_previous_tail() {
        val previous = (0 until 12).map { index -> frame(index * 33_333L, 1_000_000L + index * 40_000L, -1f) }
        val current = (0 until 10).map { index -> frame(
            400_000L + index * 33_333L,
            4_000_000L + index * 35_000L,
            index / 9f
        ) }

        val plan = requireNotNull(LiveDecoderTransitionPlan.forClip(previous, current, 8_000_000L))

        assertEquals(current.size, plan.outgoing.size)
        assertEquals(previous.last().sourceTimeUs, plan.outgoing.first().sourceTimeUs)
        assertEquals((0 until 10).map { 1_440_000L + it * 40_000L },
            plan.outgoing.map { it.sourceTimeUs })
        assertTrue(plan.outgoing.zipWithNext().all { (left, right) -> right.sourceTimeUs > left.sourceTimeUs })
        assertEquals(current.map { it.outputTimeUs }, plan.outgoing.map { it.outputTimeUs })
    }

    @Test fun fully_transition_covered_short_clip_has_no_second_decode_pass() {
        val previous = (0 until 12).map { index -> frame(index * 33_333L, 1_000_000L + index * 40_000L, -1f) }
        val current = (0 until 8).map { index -> frame(
            400_000L + index * 33_333L,
            4_000_000L + index * 35_000L,
            index / 20f
        ) }
        val window = requireNotNull(LiveDecoderTransitionPlan.forClip(previous, current, 8_000_000L))

        assertEquals(current.size, window.incoming.size)
        assertTrue(LiveDecoderTransitionPlan.remainingFrames(current, window).isEmpty())
    }

    @Test fun transition_prefix_is_decoded_once_and_remaining_live_frames_keep_their_clock() {
        val previous = listOf(frame(0L, 900_000L, -1f), frame(33_333L, 940_000L, -1f))
        val current = listOf(frame(66_667L, 400_000L, 0f), frame(100_000L, 440_000L, .5f),
            frame(133_333L, 480_000L, -1f), frame(166_667L, 520_000L, -1f))
        val window = requireNotNull(LiveDecoderTransitionPlan.forClip(previous, current, 960_000L))

        assertEquals(current.take(2), window.incoming)
        assertEquals(listOf(940_000L, 959_999L), window.outgoing.map { it.sourceTimeUs })
        assertEquals(listOf(66_667L, 100_000L), window.outgoing.map { it.outputTimeUs })
        assertEquals(current.drop(2), LiveDecoderTransitionPlan.remainingFrames(current, window))
        assertEquals(current, LiveDecoderTransitionPlan.remainingFrames(current, null))
        assertNull(LiveDecoderTransitionPlan.forClip(emptyList(), current, 960_000L))
        assertNull(LiveDecoderTransitionPlan.forClip(previous, current.drop(2), 960_000L))
    }

    private fun frame(outputUs: Long, sourceUs: Long, transitionProgress: Float) = HighQualityFramePlan.Frame(
        outputUs,
        sourceUs,
        0,
        0f,
        MontageGraph.ClipTransform.Keyframe(0f, 1f),
        MontageGraph.Transition.WHIP,
        transitionProgress = transitionProgress
    )
}
