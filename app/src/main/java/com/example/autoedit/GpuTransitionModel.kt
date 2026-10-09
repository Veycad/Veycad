package com.example.autoedit

/** Renderer-neutral parameters for compositing outgoing and incoming frames in the same GL pass. */
object GpuTransitionModel {
    data class FrameBlend(
        val incomingAlpha: Float,
        val outgoingAlpha: Float,
        val outgoingOffsetX: Float,
        val incomingOffsetX: Float,
        val outgoingOffsetY: Float,
        val incomingOffsetY: Float,
        val directionalBlur: Float,
        val blackout: Float,
        val occlusionMask: Float,
        val foregroundReentry: Float = 0f,
        val outlineStrength: Float = 0f,
        val originalBackgroundReveal: Float = 0f,
        val openingAccentPulse: Float = 0f
    )

    fun sample(transition: MontageGraph.Transition, progress: Float, directionX: Float = 1f, directionY: Float = 0f, flowStrength: Float = 1f): FrameBlend {
        val p = progress.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        val length = kotlin.math.sqrt(directionX * directionX + directionY * directionY).coerceAtLeast(.001f)
        val x = directionX / length
        val y = directionY / length
        val contentBlur = (.42f + flowStrength.coerceIn(0f, 1f) * .78f).coerceIn(.35f, 1.2f)
        return when (transition) {
            MontageGraph.Transition.WHIP -> FrameBlend(eased, 1f - eased,
                -x * eased * 1.15f, x * (1f - eased) * 1.15f,
                -y * eased * 1.15f, y * (1f - eased) * 1.15f,
                (.025f + .11f * (1f - kotlin.math.abs(2f * p - 1f))) * contentBlur, 0f, 0f)
            MontageGraph.Transition.OCCLUSION -> FrameBlend(eased, 1f - eased, 0f, 0f, 0f, 0f,
                .035f * (1f - kotlin.math.abs(2f * p - 1f)) * contentBlur, 0f,
                (1f - kotlin.math.abs(2f * p - 1f)).coerceIn(0f, 1f))
            MontageGraph.Transition.FOREGROUND_REENTRY -> {
                // Match the author's two musical beats: make the top of the retained subject
                // readable at 0.551 s, keep the full silhouette on black, then land the original
                // plate on the 2.514 s phrase accent instead of completing the reveal early.
                val subject = (
                    smoothRamp(p, .04f, .24f) * .30f +
                        smoothRamp(p, .24f, .45f) * .10f +
                        smoothRamp(p, .48f, .76f) * .60f
                    ).coerceIn(0f, 1f)
                // Reach the original plate before the transition branch is disabled. Progress
                // is clamped to one; an endpoint beyond one leaves a visible exposure jump.
                val background = smoothRamp(p, .88f, 1f)
                val accentPulse = (1f - kotlin.math.abs(p - FIRST_OPENING_ACCENT_PROGRESS) /
                    OPENING_ACCENT_HALF_WIDTH).coerceIn(0f, 1f)
                FrameBlend(
                    eased, 1f - eased, 0f, 0f, 0f, 0f,
                    .012f * (1f - kotlin.math.abs(2f * p - 1f)) * contentBlur,
                    0f, 0f, subject,
                    subject * (1f - background) * smoothRamp(p, .46f, .70f) *
                        (1f - smoothRamp(p, .82f, .98f)),
                    background,
                    accentPulse
                )
            }
            MontageGraph.Transition.BLACKOUT -> FrameBlend(eased, 1f - eased, 0f, 0f, 0f, 0f, 0f,
                (1f - kotlin.math.abs(2f * p - 1f)).coerceIn(0f, 1f), 0f)
            else -> FrameBlend(if (p >= 1f) 1f else 0f, if (p >= 1f) 0f else 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    private fun smoothRamp(value: Float, start: Float, end: Float): Float {
        val position = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return position * position * (3f - 2f * position)
    }

    private const val FIRST_OPENING_ACCENT_PROGRESS = 551_473f / 2_500_000f
    private const val OPENING_ACCENT_HALF_WIDTH = .045f
}
