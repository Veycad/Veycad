package com.example.autoedit

/** Ranks real decoded candidate MP4 reports; authored graph confidence alone cannot win. */
object RenderedCandidateSelector {
    data class Selection<T>(
        val candidate: Candidate<T>,
        val passedQualityGate: Boolean
    )

    data class Candidate<T>(
        val value: T,
        val graph: MontageGraph,
        val report: RenderedMp4Acceptance.Report
    )

    fun <T> choose(candidates: List<Candidate<T>>): Candidate<T> {
        require(candidates.isNotEmpty())
        val accepted = candidates.filter { it.report.accepted }
        require(accepted.isNotEmpty()) {
            "No rendered candidate passed acceptance: " + candidates.joinToString { candidate ->
                candidate.report.issues.joinToString(prefix = "[", postfix = "]")
            }
        }
        return accepted.maxWithOrNull(
            compareBy<Candidate<T>> { score(it) }
                .thenBy { -it.report.issues.size }
                .thenBy { matteQuality(it) }
        ) ?: error("No rendered candidate")
    }

    /**
     * Product-safe selection. A completed encode must remain usable even when every candidate
     * misses the conservative reference gate; the UI can then show an honest QA warning.
     */
    fun <T> chooseBestAvailable(candidates: List<Candidate<T>>): Selection<T> {
        require(candidates.isNotEmpty())
        val accepted = candidates.filter { it.report.accepted }
        val pool = accepted.ifEmpty { candidates }
        val winner = pool.maxWithOrNull(
            compareBy<Candidate<T>> { score(it) }
                .thenBy { -it.report.issues.size }
                .thenBy { matteQuality(it) }
        ) ?: error("No rendered candidate")
        return Selection(winner, accepted.isNotEmpty())
    }

    // Exact-PTS refinement can change only the matte, leaving the overall edit score tied.
    // Prefer cleaner, steadier decoded edges in that case instead of list order.
    private fun matteQuality(candidate: Candidate<*>): Float {
        if (candidate.graph.clips.none {
            it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        }) return 0f
        val metrics = candidate.report.metrics
        return metrics.minimumMaskTemporalIou - metrics.maximumEdgeLeakRatio
    }

    fun score(candidate: Candidate<*>): Float {
        val report = candidate.report
        val metrics = report.metrics
        val reference = report.reference
        val transition = (metrics.transitionPeak / (reference.minimumTransitionPeak * 2.5f))
            .coerceIn(0f, 1f)
        val repeated = (1f - metrics.repeatedSourceRatio /
            reference.maximumRepeatedSourceRatio.coerceAtLeast(.001f)).coerceIn(0f, 1f)
        val sync = (1f - metrics.avDriftUs.toFloat() /
            reference.maximumAvDriftUs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val artifacts = (1f - metrics.maximumArtifactScore /
            reference.maximumArtifactScore.coerceAtLeast(.001f)).coerceIn(0f, 1f)
        val colour = (1f - metrics.maximumColourJump /
            reference.maximumColourJump.coerceAtLeast(.001f)).coerceIn(0f, 1f)
        val face = (1f - metrics.faceLossRate /
            reference.maximumFaceLossRate.coerceAtLeast(.001f)).coerceIn(0f, 1f)
        val decodedQuality = metrics.beatHitRate * .25f + repeated * .12f + transition * .20f +
            sync * .13f + artifacts * .12f + colour * .09f + face * .09f
        val graphFit = ReferenceMontageGrammar.score(candidate.graph)
        return decodedQuality * .72f + graphFit * .28f - report.issues.size * .35f
    }
}
