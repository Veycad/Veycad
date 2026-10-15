package com.veycad.app

/** Pure temporal contract for feeding both live decoders during a transition. */
internal object LiveDecoderTransitionPlan {
    data class Window(
        val outgoing: List<HighQualityFramePlan.Frame>,
        val incoming: List<HighQualityFramePlan.Frame>
    )

    /** A driver warm-up hold may not consume the only retained transition sample. */
    fun overlapStart(window: Window, sigmaProfile: Boolean): Int =
        if (window.incoming.size == 1 || (sigmaProfile &&
            window.incoming.first().transitionIn == MontageGraph.Transition.WHIP)) 0 else 1

    /** Adapts the authoritative frame plan to the exact primary-image draw schedule.
     * No timing is recompiled; only the existing first-overlap hold changes image provenance. */
    fun drawFrames(frames: List<HighQualityFramePlan.Frame>, sigmaProfile: Boolean = false): List<HighQualityFramePlan.Frame> {
        val byClip = frames.groupBy { it.clipIndex }
        val held = byClip.values.mapNotNull { current ->
            val previous = byClip[current.first().clipIndex - 1] ?: return@mapNotNull null
            val incoming = current.takeWhile { TransitionTimeline.blendFor(it) != null }
            if (incoming.isEmpty() || overlapStart(Window(emptyList(), incoming), sigmaProfile) == 0) null
            else incoming.first().outputTimeUs to MediaCodecSpeedRampRenderer.heldBoundaryFrame(previous.last(), incoming.first())
        }.toMap()
        return frames.map { held[it.outputTimeUs] ?: it }
    }

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
