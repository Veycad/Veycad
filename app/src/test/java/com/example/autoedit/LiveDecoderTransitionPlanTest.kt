package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDecoderTransitionPlanTest {
    @Test fun directDrawBindingsMatchExportWarmupAndKeepBothRolesForRandomAccess() {
        val previous = listOf(frame(0, 800_000, -1f), frame(33_333, 900_000, -1f))
            .map { it.copy(clipIndex = 0, sourceIndex = 2) }
        val incoming = (2..4).map { index -> frame(index * 33_333L, 200_000L + index * 1000, .25f * (index - 1))
            .copy(clipIndex = 1, sourceIndex = 1) }
        val frames = previous + incoming
        val draws = LiveDecoderTransitionPlan.directDraws(frames, listOf(1_000_000, 1_000_000, 950_000))
        assertEquals(LiveDecoderTransitionPlan.drawFrames(frames), draws.map { it.incoming })
        assertNull(draws[2].outgoing)
        assertEquals(2, draws[3].outgoing!!.sourceIndex)
        assertEquals(949_999L, draws[3].outgoing!!.sourceTimeUs)
        assertEquals(incoming[1], draws[3].incoming)
        assertTrue(!draws[3].temporal)
        // A random reverse seek consumes the same immutable two-role record, without replaying
        // a second timeline or borrowing whichever decoder texture happened to be current.
        assertEquals(listOf(draws[4], draws[0], draws[3]), listOf(4, 0, 3).map(draws::get))
    }

    @Test fun directTemporalBindingsKeepExplicitSecondarySourceAndSameSourceOffsets() {
        val first = frame(0, 500_000, -1f).copy(transitionIn = MontageGraph.Transition.HARD_CUT,
            layer = LayerCompositorModel.Sample(kind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE),
            secondarySourceIndex = 2, secondarySourceTimeUs = 900_000)
        val second = first.copy(outputTimeUs = 33_333, secondarySourceIndex = null,
            secondarySourceTimeUs = null)
        val draws = LiveDecoderTransitionPlan.directDraws(listOf(first, second), listOf(1_000_000, 1_000_000, 1_000_000))
        assertTrue(draws.all { it.temporal })
        assertEquals(2, draws[0].outgoing!!.sourceIndex)
        assertEquals(900_000L, draws[0].outgoing!!.sourceTimeUs)
        assertEquals(0, draws[1].outgoing!!.sourceIndex)
        assertEquals(80_000L, draws[1].outgoing!!.sourceTimeUs)
        assertTrue(LiveDecoderTransitionPlan.directDraws(listOf(first), listOf(1_000_000, 1_000_000, 1_000_000), true)
            .all { it.outgoing == null })
    }

    @Test fun transitionKeepsNSourceIndicesAndClampsOnlyOutgoingSourceAtItsEof() {
        val previous = listOf(frame(0, 800_000, -1f), frame(33_333, 900_000, -1f))
            .map { it.copy(sourceIndex = 19) }
        val current = listOf(frame(66_667, 2_000_000, 0f), frame(100_000, 2_040_000, .5f))
            .map { it.copy(sourceIndex = 7) }
        val window = requireNotNull(LiveDecoderTransitionPlan.forClip(previous, current, 950_000))
        assertEquals(listOf(19, 19), window.outgoing.map { it.sourceIndex })
        assertEquals(listOf(7, 7), window.incoming.map { it.sourceIndex })
        assertEquals(listOf(900_000L, 949_999L), window.outgoing.map { it.sourceTimeUs })
        assertEquals(listOf(2_000_000L, 2_040_000L), window.incoming.map { it.sourceTimeUs })
    }
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

    @Test fun singletonRetainedPhaseIsDrawnAndNeverReplacedByBoundaryHoldAtBothFps() {
        for (fps in listOf(30, 60)) for (type in listOf(MontageGraph.Transition.WHIP, MontageGraph.Transition.BLACKOUT)) {
            val clock = ProjectClock(fps)
            val previous = frame(0, 900_000, -1f).copy(clipIndex = 0, sourceIndex = 19)
            val only = frame(clock.timeUs(1), 2_000_000, .5f).copy(clipIndex = 1, sourceIndex = 7, transitionIn = type)
            val window = requireNotNull(LiveDecoderTransitionPlan.forClip(listOf(previous), listOf(only), 3_000_000))
            // This offset is consumed by the actual renderer, not an independent fixture schedule.
            assertEquals(0, LiveDecoderTransitionPlan.overlapStart(window, false))
            assertEquals(listOf(previous, only), LiveDecoderTransitionPlan.drawFrames(listOf(previous, only)))
            assertTrue(LiveDecoderTransitionPlan.remainingFrames(listOf(only), window).isEmpty())
        }
    }

    @Test fun boundaryHoldIsLimitedToFirstGenericOverlapAndKeepsLegacyAndSigmaPolicies() {
        for (fps in listOf(30, 60)) {
            val clock = ProjectClock(fps)
            val previous = frame(0, 900_000, -1f).copy(clipIndex = 0, sourceIndex = 19)
            val incoming = (1..3).map { frame(clock.timeUs(it), 2_000_000 + it * 1000L, it / 4f)
                .copy(clipIndex = 1, sourceIndex = 7) }
            val planned = listOf(previous) + incoming
            val drawn = LiveDecoderTransitionPlan.drawFrames(planned)
            assertEquals(19, drawn[1].sourceIndex)
            assertEquals(0, drawn[1].clipIndex)
            assertEquals(previous.sourceTimeUs, drawn[1].sourceTimeUs)
            assertEquals(incoming[0].outputTimeUs, drawn[1].outputTimeUs)
            assertEquals(incoming.drop(1), drawn.drop(2))
            assertEquals(planned, LiveDecoderTransitionPlan.drawFrames(planned, true))
        }
    }

    @Test fun hardCutNeverBorrowsThePreviousClipEvenAtItsFirstFrame() {
        val previous = frame(0, 900_000, -1f).copy(clipIndex = 0, sourceIndex = 19)
        val current = (1..3).map { frame(it * 33_333L, 2_000_000, -1f)
            .copy(clipIndex = 1, sourceIndex = 7, transitionIn = MontageGraph.Transition.HARD_CUT) }
        assertEquals(listOf(previous) + current, LiveDecoderTransitionPlan.drawFrames(listOf(previous) + current))
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
