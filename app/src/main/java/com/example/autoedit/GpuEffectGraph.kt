package com.veycad.app

/** Ordered GPU post-processing graph shared by preview/export schedules. */
data class GpuEffectGraph(val nodes: List<Node> = emptyList()) {
    enum class Kind { GLOW, GLITCH, LENS_BLUR, DEFOCUS_BLUR }

    data class Node(
        val id: String,
        val kind: Kind,
        val startUs: Long,
        val endUs: Long,
        val amount: Float,
        val secondary: Float = 0f
    ) {
        init { require(id.isNotBlank() && startUs >= 0L && endUs > startUs && amount in 0f..1f && secondary in -1f..1f) }
    }

    data class Sample(val glow: Float = 0f, val glitch: Float = 0f, val lensBlur: Float = 0f, val direction: Float = 0f, val defocus: Float = 0f)

    init { require(nodes.map { it.id }.distinct().size == nodes.size) }

    fun sample(timeUs: Long): Sample {
        var glow = 0f; var glitch = 0f; var lens = 0f; var direction = 0f
        var defocus = 0f
        nodes.filter { timeUs in it.startUs until it.endUs }.forEach { node ->
            val progress = (timeUs - node.startUs).toFloat() / (node.endUs - node.startUs)
            // Sigma's first measured glitch frame already contains pronounced fragments.
            val envelope = if (node.id == "reference-slice-glitch") 1f else
                (1f - kotlin.math.abs(progress * 2f - 1f)).coerceIn(0f, 1f)
            when (node.kind) {
                Kind.GLOW -> glow = maxOf(glow, node.amount * envelope)
                Kind.GLITCH -> { glitch = maxOf(glitch, node.amount * envelope); direction = node.secondary }
                Kind.LENS_BLUR -> lens = maxOf(lens, node.amount * envelope)
                Kind.DEFOCUS_BLUR -> defocus = maxOf(defocus, node.amount * (1f-progress)*(1f-progress))
            }
        }
        return Sample(glow, glitch, lens, direction, defocus)
    }
}

object GpuEffectGraphFactory {
    fun forMontage(graph: MontageGraph): GpuEffectGraph {
        var cursorUs = 0L
        return GpuEffectGraph(buildList {
            if (ReferenceMontageProfile.appliesTo(graph)) {
                add(GpuEffectGraph.Node(
                    "reference-slice-glitch",
                    GpuEffectGraph.Kind.GLITCH,
                    ReferenceMontageProfile.GLITCH_START_US,
                    ReferenceMontageProfile.GLITCH_END_US,
                    ReferenceMontageProfile.GLITCH_AMOUNT,
                    1f
                ))
            }
            graph.clips.forEachIndexed { index, clip ->
                val durationUs = clip.outputDurationMs * 1_000L
                if (index > 0) when (clip.transitionIn) {
                    MontageGraph.Transition.WHIP -> {
                        add(GpuEffectGraph.Node("whip-glow-$index", GpuEffectGraph.Kind.GLOW, cursorUs, cursorUs + 240_000L, .28f))
                        add(GpuEffectGraph.Node("whip-glitch-$index", GpuEffectGraph.Kind.GLITCH, cursorUs, cursorUs + 120_000L, .16f,
                            if (clip.motion == MontageGraph.Motion.WHIP_LEFT) -1f else 1f))
                    }
                    MontageGraph.Transition.BLACKOUT -> add(GpuEffectGraph.Node(
                        "impact-glow-$index", GpuEffectGraph.Kind.GLOW, cursorUs, cursorUs + 150_000L, .35f
                    ))
                    else -> Unit
                }
                if (clip.motion == MontageGraph.Motion.PUSH_IN || clip.motion == MontageGraph.Motion.PUSH_OUT) {
                    add(GpuEffectGraph.Node("lens-$index", GpuEffectGraph.Kind.LENS_BLUR,
                        cursorUs, cursorUs + durationUs, .07f))
                }
                cursorUs += durationUs
            }
        })
    }
}
