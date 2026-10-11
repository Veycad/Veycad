package com.veycad.app

/** Explicit render policy for a compiled manual revision; null on legacy/no-op graphs. */
data class ManualRenderOverrides(
    val disabledTransitionClipIds: Set<String> = emptySet(),
    val disabledEffectIds: Set<String> = emptySet()
)

internal fun MontageGraph.renderTransition(clip: MontageGraph.Clip): MontageGraph.Transition =
    if (clip.id in manualOverrides?.disabledTransitionClipIds.orEmpty()) MontageGraph.Transition.HARD_CUT
    else clip.transitionIn

internal fun MontageGraph.renderNodes(): List<GpuEffectGraph.Node> = effectGraph.nodes.filter {
    it.id !in manualOverrides?.disabledEffectIds.orEmpty()
}

internal fun MontageGraph.renderOverlays(): List<MontageGraph.Overlay> = overlays.filter {
    it.id !in manualOverrides?.disabledEffectIds.orEmpty()
}
