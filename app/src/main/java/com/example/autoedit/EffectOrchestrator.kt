package com.example.autoedit

import kotlin.math.abs

/** Applies a restrained, evidence-driven effect layer after edit structure is fixed. */
object EffectOrchestrator {
    enum class Effect {
        WHIP, OCCLUSION, FOREGROUND_REENTRY, BLACKOUT, FLASH, GLOW, GLITCH, LENS_BLUR
    }

    data class Decision(
        val outputTimeUs: Long,
        val effect: Effect,
        val strength: Float,
        val reason: String
    )

    data class Result(val graph: MontageGraph, val decisions: List<Decision>)

    data class Config(
        val maximumAccentEffectsPerTenSeconds: Int = 7,
        val globalCooldownUs: Long = 220_000L,
        val sameEffectCooldownUs: Long = 900_000L,
        val blackoutCooldownUs: Long = 4_000_000L,
        val onsetToleranceUs: Long = 90_000L,
        val maximumWhipsPerTenSeconds: Int = 2,
        val whipCooldownUs: Long = 2_200_000L,
        val maximumForegroundReentriesPerTenSeconds: Int = 1,
        val foregroundReentryCooldownUs: Long = 8_000_000L
    )

    fun orchestrate(
        graph: MontageGraph,
        audio: AudioBeatMap,
        visual: VisualEventMap,
        config: Config = Config()
    ): Result {
        val maximumEffects = maxOf(
            1,
            (graph.outputDurationMs * config.maximumAccentEffectsPerTenSeconds / 10_000L).toInt()
        )
        val decisions = ArrayList<Decision>()
        val overlays = ArrayList<MontageGraph.Overlay>()
        var lastAccentUs = Long.MIN_VALUE / 2L
        var lastFlashUs = Long.MIN_VALUE / 2L
        var lastBlackoutUs = Long.MIN_VALUE / 2L
        var lastWhipUs = Long.MIN_VALUE / 2L
        var lastForegroundReentryUs = Long.MIN_VALUE / 2L
        var whipCount = 0
        var foregroundReentryCount = 0
        var cursorUs = 0L
        val referenceParity = ReferenceMontageProfile.appliesTo(graph)

        val clips = graph.clips.mapIndexed { index, original ->
            if (index == 0) {
                val openingTransition = if (
                    original.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY &&
                    graph.frameAttachments.nearest(original.sourceStartMs * 1_000L)
                        ?.mask?.confidence?.let { it >= .80f } == true
                ) {
                    decisions += Decision(
                        0L,
                        Effect.FOREGROUND_REENTRY,
                        1f,
                        "reference opening with stable person mask"
                    )
                    foregroundReentryCount++
                    lastForegroundReentryUs = 0L
                    MontageGraph.Transition.FOREGROUND_REENTRY
                } else {
                    MontageGraph.Transition.OPEN
                }
                cursorUs += original.outputDurationMs * 1_000L
                return@mapIndexed original.copy(transitionIn = openingTransition)
            }
            val boundaryUs = cursorUs
            cursorUs += original.outputDurationMs * 1_000L
            val sourceEvent = sourceEvent(original, visual)
            val onset = audio.onsets.minByOrNull { abs(audio.timestampUs(it.sampleIndex) - boundaryUs) }
                ?.takeIf { abs(audio.timestampUs(it.sampleIndex) - boundaryUs) <= config.onsetToleranceUs }
            val beat = audio.beats.minByOrNull { abs(audio.timestampUs(it.sampleIndex) - boundaryUs) }
                ?.takeIf { abs(audio.timestampUs(it.sampleIndex) - boundaryUs) <= config.onsetToleranceUs }

            var transition = validatedTransition(
                original.transitionIn,
                sourceEvent,
                graph.frameAttachments,
                original
            )
            val maximumWhips = maxOf(1, graph.outputDurationMs.toInt() * config.maximumWhipsPerTenSeconds / 10_000)
            if (transition == MontageGraph.Transition.WHIP &&
                (whipCount >= maximumWhips || boundaryUs - lastWhipUs < config.whipCooldownUs)) {
                transition = MontageGraph.Transition.HARD_CUT
            }
            val maximumForegroundReentries = maxOf(
                1,
                graph.outputDurationMs.toInt() *
                    config.maximumForegroundReentriesPerTenSeconds / 10_000
            )
            if (transition == MontageGraph.Transition.FOREGROUND_REENTRY &&
                (foregroundReentryCount >= maximumForegroundReentries ||
                    boundaryUs - lastForegroundReentryUs < config.foregroundReentryCooldownUs)) {
                transition = MontageGraph.Transition.HARD_CUT
            }
            when (transition) {
                MontageGraph.Transition.WHIP -> {
                    decisions += Decision(
                        boundaryUs, Effect.WHIP, sourceEvent?.strength ?: 0f,
                        "directional camera motion ${sourceEvent?.direction}"
                    )
                    whipCount++
                    lastWhipUs = boundaryUs
                }
                MontageGraph.Transition.OCCLUSION -> decisions += Decision(
                    boundaryUs, Effect.OCCLUSION, sourceEvent?.strength ?: 0f,
                    "confirmed occlusion mask proxy"
                )
                MontageGraph.Transition.FOREGROUND_REENTRY -> decisions += Decision(
                    boundaryUs,
                    Effect.FOREGROUND_REENTRY,
                    sourceEvent?.strength ?: 0f,
                    "stable person mask and foreground re-entry"
                ).also {
                    foregroundReentryCount++
                    lastForegroundReentryUs = boundaryUs
                }
                else -> Unit
            }

            val canAccent = decisions.count { it.effect in accentEffects } < maximumEffects &&
                boundaryUs - lastAccentUs >= config.globalCooldownUs
            if (canAccent && original.transitionIn == MontageGraph.Transition.HARD_CUT &&
                transition == MontageGraph.Transition.HARD_CUT &&
                beat?.isDownbeat == true && beat.strength >= .86f &&
                beat.dominantBand == AudioBeatMap.FrequencyBand.LOW &&
                boundaryUs - lastBlackoutUs >= config.blackoutCooldownUs) {
                transition = MontageGraph.Transition.BLACKOUT
                decisions += Decision(boundaryUs, Effect.BLACKOUT, beat.strength, "strong isolated downbeat")
                lastBlackoutUs = boundaryUs
                lastAccentUs = boundaryUs
            } else if (!referenceParity && canAccent && transition != MontageGraph.Transition.BLACKOUT &&
                onset != null && onset.strength >= .62f &&
                onset.dominantBand in setOf(AudioBeatMap.FrequencyBand.HIGH, AudioBeatMap.FrequencyBand.BROADBAND) &&
                boundaryUs - lastFlashUs >= config.sameEffectCooldownUs) {
                val durationMs = 100L
                overlays += MontageGraph.Overlay(
                    id = "onset-flash-$index",
                    startMs = boundaryUs / 1_000L,
                    endMs = boundaryUs / 1_000L + durationMs,
                    kind = "onset-flash",
                    opacity = (.28f + onset.strength * .42f).coerceAtMost(.72f)
                )
                decisions += Decision(boundaryUs, Effect.FLASH, onset.strength, "high-band onset")
                lastFlashUs = boundaryUs
                lastAccentUs = boundaryUs
            }
            original.copy(transitionIn = transition)
        }

        val authoredReferenceLayers = if (referenceParity) {
            ReferenceMontageProfile.authoredOverlays()
        } else {
            emptyList()
        }
        val structured = graph.copy(
            clips = clips,
            overlays = graph.overlays + authoredReferenceLayers + overlays
        )
        val effectGraph = GpuEffectGraphFactory.forMontage(structured)
        effectGraph.nodes.forEach { node ->
            decisions += Decision(
                node.startUs,
                when (node.kind) {
                    GpuEffectGraph.Kind.GLOW -> Effect.GLOW
                    GpuEffectGraph.Kind.GLITCH -> Effect.GLITCH
                    GpuEffectGraph.Kind.LENS_BLUR -> Effect.LENS_BLUR
                    GpuEffectGraph.Kind.DEFOCUS_BLUR -> Effect.LENS_BLUR
                },
                node.amount,
                "render graph ${node.kind.name.lowercase()}"
            )
        }
        return Result(structured.copy(effectGraph = effectGraph), decisions.sortedBy { it.outputTimeUs })
    }

    private fun validatedTransition(
        requested: MontageGraph.Transition,
        event: VisualEventMap.Event?,
        frameAttachments: FrameAttachmentTimeline,
        incomingClip: MontageGraph.Clip
    ): MontageGraph.Transition = when (requested) {
        MontageGraph.Transition.WHIP -> if (
            event?.type == VisualEventMap.EventType.CAMERA_MOVE &&
            event.direction in setOf(VisualEventMap.Direction.LEFT, VisualEventMap.Direction.RIGHT)
        ) requested else MontageGraph.Transition.HARD_CUT
        MontageGraph.Transition.OCCLUSION -> if (
            event?.type == VisualEventMap.EventType.OCCLUSION && event.confidence >= .72f
        ) requested else MontageGraph.Transition.HARD_CUT
        MontageGraph.Transition.FOREGROUND_REENTRY -> if (
            listOfNotNull(
                frameAttachments.nearest(incomingClip.sourceStartMs * 1_000L)?.mask,
                event?.let { frameAttachments.nearest(it.peakTimeUs)?.mask }
            ).any { it.confidence >= .80f }
        ) requested else MontageGraph.Transition.HARD_CUT
        else -> requested
    }

    private fun sourceEvent(clip: MontageGraph.Clip, visual: VisualEventMap): VisualEventMap.Event? {
        val startUs = clip.sourceStartMs * 1_000L
        val endUs = clip.sourceEndMs * 1_000L
        val candidates = visual.events.filter { it.peakTimeUs in startUs until endUs }
        return when (clip.transitionIn) {
            MontageGraph.Transition.WHIP -> candidates
                .filter { it.type == VisualEventMap.EventType.CAMERA_MOVE }
                .maxByOrNull { it.strength * it.confidence }
            MontageGraph.Transition.OCCLUSION -> candidates
                .filter { it.type == VisualEventMap.EventType.OCCLUSION }
                .maxByOrNull { it.strength * it.confidence }
            MontageGraph.Transition.FOREGROUND_REENTRY -> candidates
                .filter { it.type == VisualEventMap.EventType.SUBJECT_REVEAL }
                .maxByOrNull { it.strength * it.confidence }
            else -> candidates.maxByOrNull { it.strength * it.confidence }
        }
    }

    private val accentEffects = setOf(Effect.BLACKOUT, Effect.FLASH)
}
