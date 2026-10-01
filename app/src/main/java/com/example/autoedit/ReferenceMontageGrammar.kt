package com.example.autoedit

/** Structural comparison against the reusable, non-copyrighted reference montage card. */
object ReferenceMontageGrammar {
    data class TimelineComparison(val recall: Float, val missing: List<String>)

    fun score(graph: MontageGraph): Float {
        val averageShotMs = graph.outputDurationMs.toFloat() / graph.clips.size
        val rhythmFit = (1f - kotlin.math.abs(averageShotMs - TARGET_SHOT_MS) / 1_200f)
            .coerceIn(0f, 1f)
        val minimumMoments = kotlin.math.ceil(graph.outputDurationMs / 2_000.0)
            .toInt().coerceAtLeast(6)
        val momentFit = (graph.clips.size.toFloat() / minimumMoments).coerceIn(0f, 1f)
        val transitions = graph.clips.map { it.transitionIn }
            .filterNot { it == MontageGraph.Transition.OPEN }
        val hardCutRatio = transitions.count { it == MontageGraph.Transition.HARD_CUT }.toFloat() /
            transitions.size.coerceAtLeast(1)
        val hardCutFit = (1f - kotlin.math.abs(hardCutRatio - .68f) / .68f).coerceIn(0f, 1f)
        val vocabulary = transitions.toSet()
        val transitionFit = (
            (if (MontageGraph.Transition.FOREGROUND_REENTRY in vocabulary) .45f else 0f) +
                (if (MontageGraph.Transition.HARD_CUT in vocabulary) .30f else 0f) +
                (if (MontageGraph.Transition.WHIP in vocabulary ||
                    MontageGraph.Transition.OCCLUSION in vocabulary) .25f else 0f)
            ).coerceIn(0f, 1f)
        val desiredRoles = minOf(5, graph.clips.size).coerceAtLeast(1)
        val roleFit = (graph.clips.map { it.role }.distinct().size.toFloat() / desiredRoles)
            .coerceIn(0f, 1f)
        return rhythmFit * .32f + momentFit * .18f + hardCutFit * .16f +
            transitionFit * .20f + roleFit * .14f
    }

    /** Exact reusable event-card recall; decoded visibility remains a separate acceptance gate. */
    fun timelineComparison(graph: MontageGraph, toleranceUs: Long = 33_334L): TimelineComparison {
        if (!ReferenceMontageProfile.appliesTo(graph)) return TimelineComparison(1f, emptyList())
        require(toleranceUs >= 0L)
        val checks = ArrayList<Pair<String, Boolean>>()
        val actualBoundariesUs = graph.clips.dropLast(1).runningFold(0L) { cursor, clip ->
            cursor + clip.outputDurationMs * 1_000L
        }.drop(1)
        ReferenceMontageProfile.DYNAMIC_BOUNDARIES_MS.drop(1).dropLast(1).forEach { expectedMs ->
            val expectedUs = expectedMs * 1_000L
            checks += "cut@$expectedUs" to actualBoundariesUs.any {
                kotlin.math.abs(it - expectedUs) <= toleranceUs
            }
        }
        checks += "opening:FOREGROUND_REENTRY" to
            (graph.clips.firstOrNull()?.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY)
        ReferenceMontageProfile.authoredOverlays().forEach { expected ->
            checks += "layer:${expected.id}" to graph.overlays.any { actual ->
                actual.overlayKind == expected.overlayKind &&
                    kotlin.math.abs(actual.startMs - expected.startMs) * 1_000L <= toleranceUs &&
                    kotlin.math.abs(actual.endMs - expected.endMs) * 1_000L <= toleranceUs
            }
        }
        checks += "effect:reference-slice-glitch" to graph.effectGraph.nodes.any { node ->
            node.kind == GpuEffectGraph.Kind.GLITCH &&
                kotlin.math.abs(node.startUs - ReferenceMontageProfile.GLITCH_START_US) <= toleranceUs &&
                kotlin.math.abs(node.endUs - ReferenceMontageProfile.GLITCH_END_US) <= toleranceUs
        }
        val missing = checks.filterNot { it.second }.map { it.first }
        return TimelineComparison(
            recall = (checks.size - missing.size).toFloat() / checks.size.coerceAtLeast(1),
            missing = missing
        )
    }

    private const val TARGET_SHOT_MS = 1_150f
}
