package com.example.autoedit

import kotlin.math.roundToLong

/**
 * Renderer-neutral frame schedule for the high-quality VME path. It deliberately deals only in
 * integer presentation timestamps: the MediaCodec/GLES backend must render exactly these frames,
 * while tests can verify montage timing without an Android device.
 */
object HighQualityFramePlan {
    const val DEFAULT_FPS = 30

    data class Frame(
        val outputTimeUs: Long,
        val sourceTimeUs: Long,
        val clipIndex: Int,
        /** Progress within the selected output clip. Kept with the frame so GL never guesses time. */
        val clipProgress: Float,
        val transform: MontageGraph.ClipTransform.Keyframe,
        val transitionIn: MontageGraph.Transition,
        val exposureBias: Float = 0f,
        val redBias: Float = 0f,
        val greenBias: Float = 0f,
        val blueBias: Float = 0f
        ,/** Content motion from the local dense-flow field, not virtual camera motion. */
        val flowStrength: Float = 0f,
        /** PTS-synchronised ML planes; GPU upload is intentionally a renderer concern. */
        val attachments: FrameAttachments? = null,
        val effects: GpuEffectGraph.Sample = GpuEffectGraph.Sample(),
        val layer: LayerCompositorModel.Sample = LayerCompositorModel.Sample(),
        /** Exact progress inside the type-specific transition window; null outside it. */
        val transitionProgress: Float? = null,
        /** Source PTS of another director-selected timeline role for temporal layer composition. */
        val secondarySourceTimeUs: Long? = null,
        /** Source-file index carried from the owning clip to the decoder scheduler. */
        val sourceIndex: Int = 0
    )

    data class Plan(val frames: List<Frame>, val durationUs: Long, val fps: Int) {
        init { require(frames.isNotEmpty()); require(durationUs > 0); require(fps > 0) }
    }

    fun build(graph: MontageGraph, fps: Int = DEFAULT_FPS): Plan {
        // Low-frame-rate schedules are used only for local candidate proxies; final exports use
        // DEFAULT_FPS or higher. Keeping the same scheduler makes proxy decisions representative.
        require(fps in 6..60)
        val frameStepUs = 1_000_000.0 / fps
        val outputDurationUs = graph.outputDurationMs * 1_000L
        val boundaries = graph.clips.runningFold(0L) { cursor, clip -> cursor + clip.outputDurationMs * 1_000L }
        val frames = ArrayList<Frame>((outputDurationUs / frameStepUs).toInt() + 1)
        val speedTotals = mutableMapOf<String, Float>()
        var index = 0
        while (true) {
            val outputUs = (index * frameStepUs).roundToLong()
            if (outputUs >= outputDurationUs) break
            val clipIndex = ((0 until graph.clips.size).lastOrNull { outputUs >= boundaries[it] } ?: 0)
                .coerceIn(0, graph.clips.lastIndex)
            val clip = graph.clips[clipIndex]
            val clipStartUs = boundaries[clipIndex]
            val progress = ((outputUs - clipStartUs).toDouble() /
                (clip.outputDurationMs * 1_000L).coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
            val sourceStartUs = clip.sourceStartMs * 1_000L
            val sourceDurationUs = (clip.sourceEndMs - clip.sourceStartMs) * 1_000L
            val animatedSourceUs = sourceStartUs +
                (sourceDurationUs * sourceFractionAt(graph, clip, clipStartUs, progress, speedTotals)).roundToLong()
            // Foreground re-entry is an animated cutout. The texture and its PTS-aligned matte
            // advance through the selected source window while the screen-space entrance motion
            // remains independent. Freezing this PTS made the person look paused.
            val sourceUs = animatedSourceUs
            val transitionDurationUs = (clip.transitionDurationMs
                ?: TransitionTimeline.durationMs(clip.transitionIn)) * 1_000L
            val transitionElapsedUs = outputUs - clipStartUs
            val transitionProgress = if (transitionDurationUs > 0L && transitionElapsedUs <= transitionDurationUs) {
                (transitionElapsedUs.toFloat() / transitionDurationUs).coerceIn(0f, 1f)
            } else {
                // A non-null negative sentinel tells TransitionTimeline that this frame came
                // from the exact scheduler and is outside the window. Null remains legacy-test
                // compatibility for manually constructed frames.
                -1f
            }
            val legacyTransform = clip.transform.sample(progress)
            fun scalar(parameter: String, fallback: Float): Float = graph.parameterTracks
                .firstOrNull { it.target == ParameterTargets.clip(clip.id, parameter) }
                ?.sample(outputUs)?.firstOrNull() ?: fallback
            val transform = legacyTransform.copy(
                scale = scalar("transform.scale", legacyTransform.scale),
                translateX = scalar("transform.translateX", legacyTransform.translateX),
                translateY = scalar("transform.translateY", legacyTransform.translateY),
                rotationDegrees = scalar("transform.rotationDegrees", legacyTransform.rotationDegrees)
            )
            val grade = graph.parameterTracks
                .firstOrNull { it.target == ParameterTargets.clip(clip.id, "grade.rgbaBias") }
                ?.sample(outputUs)
            val currentAttachments = graph.frameAttachments.interpolated(sourceUs)
            val frameAttachments = currentAttachments
            frames += Frame(
                outputUs, sourceUs, clipIndex, progress, transform, clip.transitionIn,
                grade?.getOrNull(3) ?: clip.exposureBias,
                grade?.getOrNull(0) ?: clip.redBias,
                grade?.getOrNull(1) ?: clip.greenBias,
                grade?.getOrNull(2) ?: clip.blueBias,
                clip.flowStrength,
                frameAttachments,
                graph.effectGraph.sample(outputUs),
                LayerCompositorModel.sample(graph.overlays, outputUs / 1_000L),
                transitionProgress,
                sourceIndex = clip.sourceIndex
            )
            index++
        }
        // Subject-stage cutouts stay live just like the opening: never replace their advancing
        // decoder PTS with a retained still. Temporal two-source overlays are mapped afterwards.
        val roleMappedFrames = frames.map { frame ->
            if (ReferenceMontageProfile.appliesTo(graph)) return@map frame
            val secondaryStartMs = frame.layer.secondaryTimelineStartMs ?: return@map frame
            val secondaryOutputUs = secondaryStartMs * 1_000L +
                (frame.layer.progress * frame.layer.durationMs * 1_000L).roundToLong()
            val secondary = frames.minByOrNull { candidate ->
                kotlin.math.abs(candidate.outputTimeUs - secondaryOutputUs)
            } ?: return@map frame
            frame.copy(secondarySourceTimeUs = secondary.sourceTimeUs)
        }
        return Plan(roleMappedFrames, outputDurationUs, fps)
    }

    private fun sourceFractionAt(graph: MontageGraph, clip: MontageGraph.Clip, clipStartUs: Long, progress: Float,
        speedTotals: MutableMap<String, Float>): Float {
        val track = graph.parameterTracks.firstOrNull { it.target == ParameterTargets.clip(clip.id, "speed") }
            ?: return clip.speedRamp.sourceFractionAt(progress)
        fun integral(until: Float): Float {
            val steps = 80
            var sum = 0f
            repeat(steps) { index ->
                val left = until * index / steps
                val right = until * (index + 1) / steps
                val timeUs = clipStartUs + (clip.outputDurationMs * 1_000L * (left + right) * .5f).toLong()
                sum += track.sample(timeUs).first().coerceAtLeast(.05f) * (right - left)
            }
            return sum
        }
        return (integral(progress) / speedTotals.getOrPut(clip.id) { integral(1f).coerceAtLeast(.001f) }).coerceIn(0f, 1f)
    }

    /** Returns only the source seek points required by a decoder, in monotonically increasing order. */
    fun decoderTargets(plan: Plan, clipIndex: Int): LongArray = plan.frames
        .asSequence()
        .filter { it.clipIndex == clipIndex }
        .map { it.sourceTimeUs }
        .distinct()
        .toList()
        .toLongArray()

}
