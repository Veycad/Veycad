package com.veycad.app

import kotlin.math.abs

/** Renderer-neutral sample of the strongest active procedural layer for one output frame. */
object LayerCompositorModel {
    data class Sample(
        val opacity: Float = 0f,
        val red: Float = 1f, val green: Float = 1f, val blue: Float = 1f,
        val blendMode: MontageGraph.BlendMode = MontageGraph.BlendMode.SCREEN,
        val kind: MontageGraph.OverlayKind = MontageGraph.OverlayKind.FLASH,
        val progress: Float = 0f,
        val secondarySourceOffsetMs: Long = 0L,
        val secondaryTimelineStartMs: Long? = null,
        val durationMs: Long = 0L,
        val heartbeatEcho: Boolean = false,
        val finalFadeOpacity: Float = 0f
    )

    fun sample(overlays: List<MontageGraph.Overlay>, outputTimeMs: Long): Sample = sampleAtUs(overlays, outputTimeMs * 1000L)

    fun sampleAtUs(overlays: List<MontageGraph.Overlay>, outputTimeUs: Long): Sample {
        fun timeMs(layer: MontageGraph.Overlay) = (layer.sampleWindow?.authoredTimeUs(outputTimeUs) ?: outputTimeUs) / 1000
        val candidates = overlays.filter { layer -> layer.sampleWindow?.contains(outputTimeUs)
            ?: (outputTimeUs / 1000 in layer.startMs until layer.endMs) }
        // Sigma's fade composites after isolation; choosing only the stronger layer would
        // replace the matte with the original background halfway through the finale.
        val fade = candidates.firstOrNull { it.originId == "reference-final-black-release" }
        val finalFade = fade?.let { shapedOpacity(it, timeMs(it)) } ?: 0f
        val active = candidates.filterNot { it === fade }
            .maxByOrNull { shapedOpacity(it, timeMs(it)) }
            ?: return Sample(finalFadeOpacity = finalFade)
        return Sample(
            shapedOpacity(active, timeMs(active)), active.red, active.green, active.blue,
            active.blendMode, active.overlayKind,
            progress(active, timeMs(active)),
            active.secondarySourceOffsetMs,
            active.secondaryTimelineStartMs,
            active.sampleWindow?.let { (it.authoredEndUs - it.authoredStartUs) / 1000 } ?: (active.endMs - active.startMs),
            active.kind == "heartbeat-echo-experimental",
            finalFade
        )
    }

    private fun shapedOpacity(layer: MontageGraph.Overlay, timeMs: Long): Float {
        if (layer.originId == "reference-final-subject-stage") return layer.opacity
        if (layer.originId == "reference-final-black-release") {
            val elapsedMs = layer.sampleWindow?.let { (timeMs - it.authoredStartUs / 1000).toFloat() }
                ?: if (layer.phaseStart == 0f && layer.phaseEnd == 1f) (timeMs - layer.startMs).toFloat()
                else progress(layer, timeMs) * (layer.endMs - layer.startMs) / (layer.phaseEnd - layer.phaseStart)
            val fadeProgress = (elapsedMs / 350f).coerceIn(0f, 1f)
            return layer.opacity * fadeProgress * fadeProgress * (3f - 2f * fadeProgress)
        }
        // Heartbeat's measured 1–2-frame impulses are rectangular, not triangular flashes.
        if (layer.kind == "heartbeat-measured-step" || layer.kind == "authored-measured-step") {
            return layer.opacity
        }
        val progress = progress(layer, timeMs)
        // Exact 100 ms samples of the authored 14.183–15.300 s shot show several widely separated
        // contours immediately after the cut, resolving to one clean figure by the shot end.
        // Decoder-routing records therefore need a one-way resolve, not the former constant smear
        // and not a symmetric generic fade that hides the effect at the cut.
        if (layer.originId == "heartbeat-finale-echo-stutter") return layer.opacity
        if (layer.originId.startsWith("heartbeat-echo-")) {
            val index = layer.originId.removePrefix("heartbeat-echo-").toIntOrNull()
                ?: error("Heartbeat scene echo must carry its measured index: ${layer.originId}")
            return layer.opacity * HeartbeatMontageProfile.echoOpacityFactor(index, progress)
        }
        val envelope = (1f - abs(progress * 2f - 1f)).coerceIn(0f, 1f)
        return layer.opacity * when (layer.overlayKind) {
            MontageGraph.OverlayKind.FLASH -> envelope
            MontageGraph.OverlayKind.GLOW -> kotlin.math.sqrt(envelope)
            MontageGraph.OverlayKind.VIGNETTE -> envelope * envelope
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE -> kotlin.math.sqrt(envelope)
            MontageGraph.OverlayKind.MIRROR_SLICE -> envelope
            MontageGraph.OverlayKind.BLACK_FADE -> progress * progress * (3f - 2f * progress)
            MontageGraph.OverlayKind.SUBJECT_STAGE -> when {
                // The reference is fully isolated at the first 16.5 s checkpoint. A 12% ramp
                // still exposed 15% of a bright source background there; finish the ease in 6%.
                progress < .06f -> progress / .06f
                progress < .72f -> 1f
                else -> ((1f - progress) / .28f).coerceIn(0f, 1f)
            }
        }
    }

    private fun progress(layer: MontageGraph.Overlay, timeMs: Long): Float = (layer.sampleWindow?.let {
        (timeMs - it.authoredStartUs / 1000).toFloat() / ((it.authoredEndUs - it.authoredStartUs) / 1000)
    } ?: (layer.phaseStart + (layer.phaseEnd - layer.phaseStart) *
        ((timeMs - layer.startMs).toFloat() / (layer.endMs - layer.startMs)))).coerceIn(0f, 1f)
}
