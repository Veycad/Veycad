package com.veycad.app

/**
 * One authoritative transition window shared by the GL renderer and tests.  The input is the
 * local clip progress, so it remains stable when the source clip is time-remapped.
 */
object TransitionTimeline {
    const val DURATION_FRACTION = .32f

    fun durationMs(transition: MontageGraph.Transition): Long = when (transition) {
        MontageGraph.Transition.OPEN, MontageGraph.Transition.HARD_CUT, MontageGraph.Transition.FINAL_HOLD -> 0L
        MontageGraph.Transition.WHIP -> 320L
        MontageGraph.Transition.OCCLUSION -> 180L
        MontageGraph.Transition.FOREGROUND_REENTRY -> 2_500L
        MontageGraph.Transition.BLACKOUT -> 105L
    }

    fun blendFor(frame: HighQualityFramePlan.Frame): GpuTransitionModel.FrameBlend? {
        if (frame.transitionIn !in setOf(
                MontageGraph.Transition.WHIP,
                MontageGraph.Transition.OCCLUSION,
                MontageGraph.Transition.FOREGROUND_REENTRY,
                MontageGraph.Transition.BLACKOUT
            )) return null
        val progress = frame.transitionProgress?.let {
            if (it < 0f) return null
            it
        } ?: run {
            if (frame.clipProgress > DURATION_FRACTION) return null
            frame.clipProgress / DURATION_FRACTION
        }
        val rawX = frame.transform.translateX
        val rawY = frame.transform.translateY
        val fallbackX = if (rawX < 0f) -1f else 1f
        return GpuTransitionModel.sample(
            frame.transitionIn,
            progress,
            if (kotlin.math.abs(rawX) + kotlin.math.abs(rawY) < .01f) fallbackX else rawX,
            rawY,
            frame.flowStrength
        )
    }
}
