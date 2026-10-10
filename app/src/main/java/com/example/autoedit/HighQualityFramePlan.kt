package com.veycad.app

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
        val sourceIndex: Int = 0,
        /** Original montage clock survives rebasing an output window for proxy encoding. */
        val globalOutputTimeUs: Long = outputTimeUs,
        val secondarySourceIndex: Int? = null,
        val secondaryAttachments: FrameAttachments? = null,
        /** Authored motion/effect clock, independent of the canonical source-map PTS. */
        val originalOutputTimeUs: Long = outputTimeUs
    )

    data class Plan(val frames: List<Frame>, val durationUs: Long, val fps: Int) {
        init { require(frames.isNotEmpty()); require(durationUs > 0); require(fps > 0) }
    }

    fun build(graph: MontageGraph, fps: Int = DEFAULT_FPS): Plan {
        // Low-frame-rate schedules are used only for local candidate proxies; final exports use
        // DEFAULT_FPS or higher. Keeping the same scheduler makes proxy decisions representative.
        require(fps in 6..60)
        val editable = graph.editableTiming
        require(editable == null || editable.fps == fps) { "FPS differs from the saved editable grid" }
        val frameStepUs = 1_000_000.0 / fps
        val outputDurationUs = editable?.let { frameTimeUs(it.frameCount, fps) } ?: (graph.outputDurationMs * 1_000L)
        val boundaries = editable?.clips?.map { frameTimeUs(it.startFrame, fps) }
            ?: graph.clips.runningFold(0L) { cursor, clip -> cursor + clip.outputDurationMs * 1_000L }
        val frames = ArrayList<Frame>(editable?.frameCount?.coerceAtMost(65_536L)?.toInt()
            ?: ((outputDurationUs / frameStepUs).toInt() + 1))
        val speedTotals = mutableMapOf<String, Float>()
        var index = 0
        while (true) {
            val outputUs = if (editable != null) frameTimeUs(index.toLong(), fps) else (index * frameStepUs).roundToLong()
            if (editable != null && index.toLong() >= editable.frameCount || outputUs >= outputDurationUs) break
            val clipIndex = ((0 until graph.clips.size).lastOrNull {
                if (editable != null) index.toLong() >= editable.clips[it].startFrame else outputUs >= boundaries[it]
            } ?: 0)
                .coerceIn(0, graph.clips.lastIndex)
            val clip = graph.clips[clipIndex]
            val clipStartUs = boundaries[clipIndex]
            val timing = editable?.clips?.get(clipIndex)
            val originalFrame = timing?.let { Math.addExact(it.visible.start, index.toLong() - it.startFrame) }
            val originalOutputUs = timing?.phase?.timeUs(originalFrame!!, fps) ?: outputUs
            val progress = if (timing != null) timing.phase?.progress(originalFrame!!, fps)
                ?: (originalFrame!!.toDouble() / timing.originFrameCount).toFloat().coerceIn(0f, 1f)
                else ((outputUs - clipStartUs).toDouble() /
                    (clip.outputDurationMs * 1_000L).coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
            val sourceStartUs = clip.sourceStartMs * 1_000L
            val sourceDurationUs = (clip.sourceEndMs - clip.sourceStartMs) * 1_000L
            val animatedSourceUs = timing?.timeMap?.sourceTimeUs(originalFrame!!) ?: (sourceStartUs +
                (sourceDurationUs * sourceFractionAt(graph, clip, clipStartUs, progress, speedTotals)).roundToLong())
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
            val tracks = timing?.localTracks ?: graph.parameterTracks
            val trackTimeUs = if (timing != null && timing.phase == null) frameTimeUs(originalFrame!!, fps) else originalOutputUs
            fun sampleTrack(parameter: String): List<Float>? = tracks
                .firstOrNull { it.target == ParameterTargets.clip(clip.id, parameter) }
                ?.let { track ->
                    // Edited extensions hold endpoint keys even when the legacy HOLD sampler
                    // would select the penultimate value beyond the last timestamp.
                    val sampleUs = if (timing != null) trackTimeUs.coerceIn(
                        track.keyframes.first().timeUs, track.keyframes.last().timeUs) else trackTimeUs
                    track.sample(sampleUs)
                }
            fun scalar(parameter: String, fallback: Float): Float = sampleTrack(parameter)?.firstOrNull() ?: fallback
            val transform = legacyTransform.copy(
                scale = scalar("transform.scale", legacyTransform.scale),
                translateX = scalar("transform.translateX", legacyTransform.translateX),
                translateY = scalar("transform.translateY", legacyTransform.translateY),
                rotationDegrees = scalar("transform.rotationDegrees", legacyTransform.rotationDegrees)
            )
            val grade = sampleTrack("grade.rgbaBias")
            val currentAttachments = if (graph.sourceAttachments.isNotEmpty()) graph.sourceAttachments
                .firstOrNull { it.sourceIndex == clip.sourceIndex }?.timeline?.interpolated(sourceUs)
                else graph.frameAttachments.interpolated(sourceUs)
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
                LayerCompositorModel.sampleAtUs(graph.overlays, outputUs),
                transitionProgress,
                sourceIndex = clip.sourceIndex, originalOutputTimeUs = originalOutputUs
            )
            index++
        }
        // Subject-stage cutouts stay live just like the opening: never replace their advancing
        // decoder PTS with a retained still. Temporal two-source overlays are mapped afterwards.
        fun secondaryFrame(layer: LayerCompositorModel.Sample): Frame? {
            val secondaryStartMs = layer.secondaryTimelineStartMs ?: return null
            val targetUs = secondaryStartMs * 1_000L + (layer.progress * layer.durationMs * 1_000L).roundToLong()
            val originalSampleUs = if (editable != null) {
                val clock = ProjectClock(fps)
                val closest = clock.nearestFrame(targetUs)
                val previous = (closest - 1).coerceAtLeast(0)
                if (kotlin.math.abs(clock.timeUs(previous) - targetUs) <= kotlin.math.abs(clock.timeUs(closest) - targetUs))
                    clock.timeUs(previous) else clock.timeUs(closest)
            } else targetUs
            val secondary = frames.asSequence().filter { candidate ->
                val phase = editable?.clips?.get(candidate.clipIndex)?.phase
                phase == null || originalSampleUs in phase.startUs until (phase.startUs + phase.durationUs)
            }.minByOrNull { kotlin.math.abs(it.originalOutputTimeUs - targetUs) } ?: return null
            return secondary.takeIf { editable == null || kotlin.math.abs(it.originalOutputTimeUs - targetUs) <= frameStepUs / 2 + 1 }
        }
        val roleMappedFrames = frames.map { frame ->
            if (ReferenceMontageProfile.appliesTo(graph)) return@map frame
            if (frame.layer.secondaryTimelineStartMs == null) return@map frame
            // Removing one temporal layer must reveal any surviving lower layer, not erase it.
            val layer = if (editable != null) LayerCompositorModel.sampleAtUs(graph.overlays.filter { overlay ->
                if (overlay.secondaryTimelineStartMs == null) true else {
                    val sample = LayerCompositorModel.sampleAtUs(listOf(overlay), frame.outputTimeUs)
                    sample.secondaryTimelineStartMs == null || secondaryFrame(sample) != null
                }
            }, frame.outputTimeUs) else frame.layer
            val secondary = secondaryFrame(layer)
            frame.copy(layer = layer, secondarySourceTimeUs = secondary?.sourceTimeUs,
                secondarySourceIndex = secondary?.sourceIndex, secondaryAttachments = secondary?.attachments)
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
