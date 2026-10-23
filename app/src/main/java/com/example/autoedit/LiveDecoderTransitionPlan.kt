package com.veycad.app

/** Pure temporal contract for feeding both live decoders during a transition. */
internal object LiveDecoderTransitionPlan {
    /** Immutable bindings for random direct display, derived from the export's existing rules.
     * This changes neither the canonical frame grid nor effect/attachment sampling. */
    data class DirectDraw(val incoming: HighQualityFramePlan.Frame,
        val outgoing: HighQualityFramePlan.Frame? = null, val temporal: Boolean = false)

    fun directDraws(frames: List<HighQualityFramePlan.Frame>, sourceDurationsUs: List<Long>,
        sigmaProfile: Boolean = false): List<DirectDraw> {
        require(sourceDurationsUs.isNotEmpty() && sourceDurationsUs.all { it > 0 })
        val byClip = frames.groupBy { it.clipIndex }
        val draws = HashMap<Long, DirectDraw>()
        for ((clipIndex, current) in byClip) {
            val previous = byClip[clipIndex - 1]
            val window = previous?.let {
                forClip(it, current, sourceDurationsUs[it.last().sourceIndex])
            }
            if (window != null) {
                val start = overlapStart(window, sigmaProfile)
                if (start > 0) {
                    val held = MediaCodecSpeedRampRenderer.heldBoundaryFrame(previous!!.last(), current.first())
                    draws[held.outputTimeUs] = DirectDraw(held)
                }
                for (index in start until window.incoming.size) {
                    val incoming = window.incoming[index]
                    draws[incoming.outputTimeUs] = DirectDraw(incoming, window.outgoing[index])
                }
            }
            for (frame in remainingFrames(current, window)) {
                val temporal = MediaCodecSpeedRampRenderer.isTemporalLayer(frame.layer.kind, sigmaProfile)
                val secondary = if (temporal) {
                    val source = frame.secondarySourceIndex ?: frame.sourceIndex
                    val pts = MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(frame, sourceDurationsUs[source])
                    MediaCodecSpeedRampRenderer.temporalLayerFrame(frame, pts)
                } else null
                draws[frame.outputTimeUs] = DirectDraw(frame, secondary, temporal)
            }
        }
        return montageSnapshot(frames.map { draws.getValue(it.outputTimeUs) })
    }

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
