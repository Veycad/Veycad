package com.veycad.app

import java.util.TreeSet

/** Reuses the ordered frame schedule; each decoded-sample association is O(log N). */
internal class RenderQaFrameIndex(val frames: List<HighQualityFramePlan.Frame>) {
    init { require(frames.isNotEmpty()) }

    fun atOrAfter(timeUs: Long): Int {
        var low = 0; var high = frames.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (frames[middle].outputTimeUs < timeUs) low = middle + 1 else high = middle
        }
        return low
    }

    fun nearest(timeUs: Long): Int {
        val right = atOrAfter(timeUs)
        if (right == 0) return 0
        if (right == frames.size) return frames.lastIndex
        return if (timeUs - frames[right - 1].outputTimeUs <= frames[right].outputTimeUs - timeUs) right - 1 else right
    }
}

/** Only contiguous, actually retained transition frames form an evidence window. */
internal data class RenderQaTransitionWindow(val first: Int, val last: Int) {
    companion object {
        fun inPlan(frames: List<HighQualityFramePlan.Frame>): List<RenderQaTransitionWindow> {
            val result = ArrayList<RenderQaTransitionWindow>()
            var first = -1
            frames.forEachIndexed { index, frame ->
                val active = TransitionTimeline.blendFor(frame) != null
                if (first >= 0 && (!active || frame.clipIndex != frames[first].clipIndex ||
                        frame.transitionIn != frames[first].transitionIn)) {
                    result += RenderQaTransitionWindow(first, index - 1)
                    first = -1
                }
                if (active && first < 0) first = index
            }
            if (first >= 0) result += RenderQaTransitionWindow(first, frames.lastIndex)
            return result
        }
    }
}

/** Regular QA cadence plus bounded checkpoints per authored event, including one-frame events. */
internal object ManualQaSampling {
    fun targets(graph: MontageGraph, plan: HighQualityFramePlan.Plan, intervalUs: Long): List<Long> {
        require(intervalUs > 0)
        val frames = plan.frames
        val index = RenderQaFrameIndex(frames)
        val selected = TreeSet<Int>()
        fun add(frame: Int) { if (frame in frames.indices) selected += frame }
        var timeUs = 0L
        while (timeUs < plan.durationUs) {
            add(index.nearest(timeUs))
            if (plan.durationUs - timeUs <= intervalUs) break
            timeUs += intervalUs
        }
        add(frames.lastIndex)
        frames.forEachIndexed { i, frame ->
            if (i == 0 || frame.clipIndex != frames[i - 1].clipIndex) { add(i - 1); add(i) }
        }
        RenderQaTransitionWindow.inPlan(frames).forEach { window ->
            add(window.first - 1); add(window.first); add(window.last); add(window.last + 1)
            // Foreground measurement needs witnesses within its retained .72..80 matte phase.
            for (phase in listOf(.25f, .5f, .72f, .76f, .8f, .88f)) {
                var low = window.first; var high = window.last + 1
                while (low < high) {
                    val middle = low + (high - low) / 2
                    if ((frames[middle].transitionProgress ?: -1f) < phase) low = middle + 1 else high = middle
                }
                add(low.coerceAtMost(window.last))
            }
        }
        fun effect(startUs: Long, endUs: Long, sampleWindow: EffectSampleWindow?) {
            // Frame-shift sampling can differ from the displayed bounds by one microsecond.
            var first = index.atOrAfter((startUs - 1).coerceAtLeast(0))
            var last = (index.atOrAfter(endUs + 1) - 1).coerceAtMost(frames.lastIndex)
            fun contains(i: Int): Boolean = sampleWindow?.contains(frames[i].outputTimeUs)
                ?: (frames[i].outputTimeUs in startUs until endUs)
            while (first <= last && !contains(first)) first++
            while (last >= first && !contains(last)) last--
            if (first > last) return
            add(first - 1); add(first); add(last); add(last + 1)
            val middleUs = frames[first].outputTimeUs + (frames[last].outputTimeUs - frames[first].outputTimeUs) / 2
            add(index.nearest(middleUs).coerceIn(first, last))
            sampleWindow?.let { window ->
                val peakUs = window.authoredStartUs + (window.authoredEndUs - window.authoredStartUs) / 2 - window.offsetUs
                add(index.nearest(peakUs).coerceIn(first, last))
            }
        }
        graph.renderOverlays().forEach { overlay -> effect(overlay.sampleWindow?.startUs ?: overlay.startMs * 1_000L,
            overlay.sampleWindow?.endUs ?: overlay.endMs * 1_000L, overlay.sampleWindow) }
        graph.renderNodes().forEach { node -> effect(node.sampleWindow?.startUs ?: node.startUs,
            node.sampleWindow?.endUs ?: node.endUs, node.sampleWindow) }
        return selected.map { frames[it].outputTimeUs }
    }
}
