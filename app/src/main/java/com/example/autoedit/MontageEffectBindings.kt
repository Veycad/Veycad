package com.veycad.app

import kotlin.math.abs

/** Exact translated sampling clock; rounded display bounds never restart an authored envelope. */
data class EffectSampleWindow(val startUs: Long, val endUs: Long, val offsetUs: Long,
    val authoredStartUs: Long, val authoredEndUs: Long,
    val frameShift: Long? = null, val fps: Int? = null) {
    init { require(startUs >= 0 && endUs > startUs && authoredEndUs > authoredStartUs) }
    init { require((frameShift == null) == (fps == null)); fps?.let { ProjectClock(it) } }
    fun authoredTimeUs(outputTimeUs: Long): Long {
        if (frameShift == null) return Math.addExact(outputTimeUs, offsetUs)
        val clock = ProjectClock(fps!!)
        val frame = clock.nearestFrame(outputTimeUs)
        return frameTimeUs(frame + frameShift, fps) + (outputTimeUs - clock.timeUs(frame))
    }
    fun contains(outputTimeUs: Long): Boolean = authoredTimeUs(outputTimeUs) in
        (startUs + offsetUs) until (endUs + offsetUs)
}

object MontageEffectBindings {
    fun bind(graph: MontageGraph, fps: Int): List<AnchoredMontageEffect> {
        ProjectClock(fps)
        val starts = graph.clips.runningFold(0L) { time, clip -> time + clip.outputDurationMs * 1_000L }
        val result = ArrayList<AnchoredMontageEffect>()
        fun bindOne(id: String, start: Long, end: Long, overlay: MontageGraph.Overlay?, node: GpuEffectGraph.Node?) {
            fun add(segmentId: String, anchor: EffectAnchor, left: Long = start, right: Long = end) {
                result += AnchoredMontageEffect(segmentId, id, overlay?.originId ?: node!!.originId, anchor, true,
                    overlay, node, (left - start).toFloat() / (end - start), (right - start).toFloat() / (end - start))
            }
            // Identity is authored, never inferred from beat proximity. Heartbeat tail is a scene.
            if (id.startsWith("heartbeat-pulse-") || id.startsWith("fear-step-") || id.startsWith("onset-flash-") ||
                id == "reference-first-phrase-impact") {
                add(id, EffectAnchor.Music(start, end)); return
            }
            if (node != null && isTransitionNode(id)) {
                val index = graph.clips.indices.lastOrNull { starts[it] <= start } ?: 0
                add(id, EffectAnchor.Clip(graph.clips[index].id, start - starts[index], end - starts[index])); return
            }
            // Generic short flashes follow a nearby cut. Authored scene IDs retain scene ownership.
            if (overlay != null && overlay.overlayKind == MontageGraph.OverlayKind.FLASH &&
                !id.startsWith("heartbeat-") && !id.startsWith("reference-")) {
                val boundary = (1 until graph.clips.size).minByOrNull { abs(starts[it] - start) }
                if (boundary != null && abs(starts[boundary] - start) <= 100_000L) {
                    add(id, EffectAnchor.Boundary(graph.clips[boundary].id, start - starts[boundary], end - start)); return
                }
            }
            graph.clips.forEachIndexed { index, clip ->
                val left = maxOf(start, starts[index]); val right = minOf(end, starts[index + 1])
                if (right > left) add("$id@${clip.id}", EffectAnchor.Clip(clip.id, left - starts[index], right - starts[index]), left, right)
            }
        }
        graph.overlays.forEach { bindOne(it.id, it.startMs * 1_000L, it.endMs * 1_000L, it, null) }
        graph.effectGraph.nodes.forEach { bindOne(it.id, it.startUs, it.endUs, null, it) }
        return montageSnapshot(result)
    }

    internal fun isTransitionNode(id: String): Boolean =
        id.startsWith("whip-glow-") || id.startsWith("whip-glitch-") || id.startsWith("impact-glow-")
}
