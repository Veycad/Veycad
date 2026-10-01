package com.example.autoedit

/** Pure temporal contract for feeding both live decoders during a transition. */
internal object LiveDecoderTransitionPlan {
    data class Window(
        val outgoing: List<HighQualityFramePlan.Frame>,
        val incoming: List<HighQualityFramePlan.Frame>
    )

    fun forClip(
        previous: List<HighQualityFramePlan.Frame>,
        current: List<HighQualityFramePlan.Frame>,
        sourceDurationUs: Long
    ): Window? {
        val requested = current.takeWhile { TransitionTimeline.blendFor(it) != null }
        if (requested.isEmpty() || previous.isEmpty()) return null
        val finalPrevious = previous.last()
        val sourceStepUs = previous.takeLast(6).zipWithNext { left, right ->
            (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
        }.average().takeIf { it.isFinite() && it > 0.0 }?.toLong() ?: 33_333L
        val outgoing = requested.mapIndexed { index, incomingFrame ->
            finalPrevious.copy(
                outputTimeUs = incomingFrame.outputTimeUs,
                sourceTimeUs = (finalPrevious.sourceTimeUs + sourceStepUs * index)
                    .coerceAtMost(sourceDurationUs - 1L),
                transitionProgress = incomingFrame.transitionProgress
            )
        }
        return Window(outgoing, requested)
    }

    /** A short beat-sized clip may be fully consumed by its transition window. */
    fun remainingFrames(current: List<HighQualityFramePlan.Frame>, window: Window?): List<HighQualityFramePlan.Frame> =
        current.drop(window?.incoming?.size ?: 0)
}
