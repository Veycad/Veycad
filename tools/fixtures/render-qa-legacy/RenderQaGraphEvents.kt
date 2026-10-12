package com.veycad.app

/** Music20d64 handoff only. Compile INSTEAD OF the main adapter, never alongside it. */
internal fun MontageGraph.renderQaEvents(index: RenderQaFrameIndex): List<RenderQaEventWindow> = buildList {
    overlays.forEach { RenderQaEventWindow.resolve(index, it.startMs * 1_000L, it.endMs * 1_000L)?.let { add(it) } }
    effectGraph.nodes.forEach { RenderQaEventWindow.resolve(index, it.startUs, it.endUs)?.let { add(it) } }
}
