package com.example.autoedit

/**
 * Converts a renderer-neutral graph into timeline coordinates. Both a future preview player and
 * the current Media3 export adapter consume this exact plan; neither is allowed to invent its
 * own cut positions.
 */
object MontageGraphInterpreter {
    data class RenderClip(val index: Int, val clip: MontageGraph.Clip, val timelineStartMs: Long)
    data class TransitionWindow(
        val incomingIndex: Int,
        val startMs: Long,
        val durationMs: Long,
        val transition: MontageGraph.Transition
    )
    data class Plan(val clips: List<RenderClip>, val transitions: List<TransitionWindow>, val durationMs: Long)

    fun interpret(graph: MontageGraph): Plan {
        var cursorMs = 0L
        val clips = graph.clips.mapIndexed { index, clip ->
            RenderClip(index, clip, cursorMs).also { cursorMs += clip.outputDurationMs }
        }
        require(cursorMs == graph.outputDurationMs) { "Montage graph duration drift" }
        val transitions = clips.drop(1).mapNotNull { incoming ->
            val durationMs = TransitionTimeline.durationMs(incoming.clip.transitionIn)
            durationMs.takeIf { it > 0L }?.let { duration ->
                TransitionWindow(
                    incomingIndex = incoming.index,
                    startMs = (incoming.timelineStartMs - duration).coerceAtLeast(0L),
                    durationMs = duration.coerceAtMost(incoming.timelineStartMs),
                    transition = incoming.clip.transitionIn
                )
            }
        }
        return Plan(clips, transitions, cursorMs)
    }
}
