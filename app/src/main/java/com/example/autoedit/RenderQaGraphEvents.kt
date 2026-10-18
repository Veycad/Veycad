package com.veycad.app

/** Main graph adapter: effective payload, exact membership and the original authored peak. */
internal fun MontageGraph.renderQaEvents(index: RenderQaFrameIndex): List<RenderQaEventWindow> = buildList {
    fun event(startUs: Long, endUs: Long, window: EffectSampleWindow?) {
        val resolved = if (window == null) RenderQaEventWindow.resolve(index, startUs, endUs)
        else RenderQaEventWindow.resolve(index, window.startUs, window.endUs,
            window.authoredStartUs + (window.authoredEndUs - window.authoredStartUs) / 2 - window.offsetUs,
            window::contains)
        resolved?.let { add(it) }
    }
    renderOverlays().forEach { event(it.startMs * 1_000L, it.endMs * 1_000L, it.sampleWindow) }
    renderNodes().forEach { event(it.startUs, it.endUs, it.sampleWindow) }
}
