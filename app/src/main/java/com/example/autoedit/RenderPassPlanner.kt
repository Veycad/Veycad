package com.veycad.app

/** Deterministic render topology chosen before allocating GLES textures or decoder surfaces. */
object RenderPassPlanner {
    enum class Quality { COMPATIBILITY, BALANCED, HIGH }
    enum class PassKind {
        SOURCE_AND_CHEAP_EFFECTS,
        TRANSITION_COMPOSITE,
        FOREGROUND_COMPOSITE,
        DIRECTIONAL_BLUR,
        DEPTH_COMPOSITE,
        GLOW_EXTRACT,
        GLOW_BLUR,
        FINAL_COMPOSITE,
        ENCODER_SURFACE
    }

    data class DeviceCapabilities(
        val glesMajor: Int,
        val maxTextureSize: Int,
        val memoryClassMb: Int,
        val lowRam: Boolean,
        val halfFloatRenderTargets: Boolean
    ) {
        init { require(glesMajor >= 2 && maxTextureSize >= 1024 && memoryClassMb > 0) }

        companion object {
            fun conservative() = DeviceCapabilities(2, 2_048, 256, true, false)
        }
    }

    data class Pass(
        val id: String,
        val kind: PassKind,
        val inputIds: List<String>,
        val resolutionScale: Float,
        val fusedOperations: Set<String> = emptySet()
    ) {
        init {
            require(id.isNotBlank() && resolutionScale in .25f..1f)
            require(id !in inputIds)
        }
    }

    data class Plan(val quality: Quality, val passes: List<Pass>) {
        init {
            require(passes.isNotEmpty())
            require(passes.map { it.id }.distinct().size == passes.size)
            val available = HashSet<String>()
            passes.forEach { pass ->
                require(pass.inputIds.all { it in available }) { "Pass ${pass.id} has unresolved input" }
                available += pass.id
            }
            require(passes.last().kind == PassKind.ENCODER_SURFACE)
        }
    }

    fun plan(graph: MontageGraph, capabilities: DeviceCapabilities): Plan {
        val quality = selectQuality(capabilities)
        val hasTransition = graph.clips.drop(1).any { it.transitionIn !in setOf(
            MontageGraph.Transition.HARD_CUT, MontageGraph.Transition.OPEN, MontageGraph.Transition.FINAL_HOLD
        ) }
        val hasDirectionalBlur = graph.clips.any { it.transitionIn == MontageGraph.Transition.WHIP }
        val coverage = graph.frameAttachments.coverage()
        val hasForegroundReentry = coverage.masks > 0 && graph.clips.any {
            it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        }
        val hasDepth = coverage.depths > 0 && graph.clips.any {
            it.transitionIn == MontageGraph.Transition.OCCLUSION ||
                it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        }
        val hasGlow = graph.effectGraph.nodes.any { it.kind == GpuEffectGraph.Kind.GLOW }
        val cheap = buildSet {
            add("transform")
            add("grade")
            if (graph.effectGraph.nodes.any { it.kind == GpuEffectGraph.Kind.DEFOCUS_BLUR }) add("defocus-sampling")
            if (graph.overlays.isNotEmpty()) add("layer-blend")
            if (graph.effectGraph.nodes.any { it.kind == GpuEffectGraph.Kind.GLITCH }) add("chromatic-split")
            if (graph.effectGraph.nodes.any { it.kind == GpuEffectGraph.Kind.LENS_BLUR }) add("lens-warp")
        }
        val passes = ArrayList<Pass>()
        passes += Pass("source", PassKind.SOURCE_AND_CHEAP_EFFECTS, emptyList(), 1f, cheap)
        var current = "source"
        if (hasTransition) {
            passes += Pass("transition", PassKind.TRANSITION_COMPOSITE, listOf(current), 1f)
            current = "transition"
        }
        if (hasForegroundReentry) {
            passes += Pass("foreground", PassKind.FOREGROUND_COMPOSITE, listOf(current), 1f)
            current = "foreground"
        }
        if (hasDirectionalBlur) {
            val scale = if (quality == Quality.COMPATIBILITY) .5f else 1f
            passes += Pass("motion-blur", PassKind.DIRECTIONAL_BLUR, listOf(current), scale)
            current = "motion-blur"
        }
        if (hasDepth) {
            val scale = if (quality == Quality.HIGH) 1f else .5f
            passes += Pass("depth", PassKind.DEPTH_COMPOSITE, listOf(current), scale)
            current = "depth"
        }
        if (hasGlow) {
            val scale = when (quality) {
                Quality.HIGH -> .5f
                Quality.BALANCED -> .5f
                Quality.COMPATIBILITY -> .25f
            }
            passes += Pass("glow-extract", PassKind.GLOW_EXTRACT, listOf(current), scale)
            passes += Pass("glow-blur", PassKind.GLOW_BLUR, listOf("glow-extract"), scale)
            passes += Pass("final", PassKind.FINAL_COMPOSITE, listOf(current, "glow-blur"), 1f)
            current = "final"
        }
        passes += Pass("encoder", PassKind.ENCODER_SURFACE, listOf(current), 1f)
        return Plan(quality, passes)
    }

    fun selectQuality(capabilities: DeviceCapabilities): Quality = when {
        capabilities.lowRam || capabilities.memoryClassMb < 256 || capabilities.maxTextureSize < 4_096 ->
            Quality.COMPATIBILITY
        capabilities.glesMajor >= 3 && capabilities.halfFloatRenderTargets && capabilities.memoryClassMb >= 512 ->
            Quality.HIGH
        else -> Quality.BALANCED
    }
}
