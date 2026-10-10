package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RenderedCandidateSelectorTest {
    @Test fun tied_edit_scores_prefer_cleaner_live_matte_independent_of_order() {
        val base = candidate("dynamic", .4f, .08f, .01f)
        val sparse = base.copy(value = "sparse", report = base.report.copy(
            metrics = base.report.metrics.copy(maximumEdgeLeakRatio = .019f, minimumMaskTemporalIou = .89f)))
        val refined = base.copy(value = "refined", report = base.report.copy(
            metrics = base.report.metrics.copy(maximumEdgeLeakRatio = .005f, minimumMaskTemporalIou = .98f)))
        assertEquals(RenderedCandidateSelector.score(sparse), RenderedCandidateSelector.score(refined), 0f)
        for (order in listOf(listOf(sparse, refined), listOf(refined, sparse))) {
            assertEquals("refined", RenderedCandidateSelector.choose(order).value)
            assertEquals("refined", RenderedCandidateSelector.chooseBestAvailable(order).candidate.value)
        }
    }

    @Test fun selects_strongest_accepted_decoded_candidate_not_first_style() {
        val weak = candidate("dynamic", transition = .17f, colour = .24f, faceLoss = .11f)
        val strong = candidate("balanced", transition = .42f, colour = .08f, faceLoss = .01f)
        val broken = candidate("cinematic", transition = .8f, colour = .04f, faceLoss = 0f, issues = listOf("av-drift"))

        val winner = RenderedCandidateSelector.choose(listOf(weak, strong, broken))

        assertEquals("balanced", winner.value)
        assertTrue(RenderedCandidateSelector.score(strong) > RenderedCandidateSelector.score(weak))
        for (order in listOf(listOf(broken, weak, strong), listOf(strong, weak, broken))) {
            val selection = RenderedCandidateSelector.chooseBestAvailable(order)
            assertEquals("balanced", selection.candidate.value)
            assertTrue(selection.passedQualityGate)
        }
    }

    @Test fun never_returns_a_candidate_rejected_by_decoded_mp4_acceptance() {
        val first = candidate("dynamic", .5f, .1f, .02f, listOf("black-block-artifact"))
        val second = candidate("balanced", .7f, .08f, .01f, listOf("av-drift"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            RenderedCandidateSelector.choose(listOf(first, second))
        }

        assertTrue(error.message.orEmpty().contains("No rendered candidate passed acceptance"))
    }

    @Test fun product_selection_returns_best_completed_render_with_failed_gate_warning() {
        val weaker = candidate("dynamic", .20f, .30f, .18f, listOf("colour-jump"))
        val stronger = candidate("balanced", .55f, .08f, .02f, listOf("beat-hit-rate"))

        val selection = RenderedCandidateSelector.chooseBestAvailable(listOf(weaker, stronger))

        assertEquals("balanced", selection.candidate.value)
        assertTrue(!selection.passedQualityGate)
    }

    private fun candidate(
        name: String,
        transition: Float,
        colour: Float,
        faceLoss: Float,
        issues: List<String> = emptyList()
    ): RenderedCandidateSelector.Candidate<String> {
        val reference = RenderedMp4Acceptance.ReferenceMontageCard()
        val metrics = RenderedMp4Acceptance.Metrics(
            beatHitRate = 1f,
            repeatedSourceRatio = 0f,
            transitionPeak = transition,
            avDriftUs = if (issues.contains("av-drift")) 80_000L else 10_000L,
            maximumArtifactScore = 0f,
            maximumColourJump = colour,
            faceLossRate = faceLoss
        )
        return RenderedCandidateSelector.Candidate(
            name,
            graph(name),
            RenderedMp4Acceptance.Report(issues.isEmpty(), metrics, reference, issues)
        )
    }

    private fun graph(style: String): MontageGraph {
        val count = when (style) {
            "dynamic" -> 13
            "balanced" -> 10
            else -> 7
        }
        val duration = 18_000L
        val base = duration / count
        val clips = (0 until count).map { index ->
            val output = if (index == count - 1) duration - base * (count - 1) else base
            MontageGraph.Clip(
                id = "$style-$index",
                sourceStartMs = index * base,
                sourceEndMs = index * base + output,
                outputDurationMs = output,
                role = MontageGraph.ShotRole.entries[index % MontageGraph.ShotRole.entries.size],
                transitionIn = when {
                    index == 0 -> MontageGraph.Transition.OPEN
                    index == 1 -> MontageGraph.Transition.FOREGROUND_REENTRY
                    style == "dynamic" && index == 2 -> MontageGraph.Transition.WHIP
                    else -> MontageGraph.Transition.HARD_CUT
                },
                motion = MontageGraph.Motion.PUSH_IN,
                confidence = .9f,
                beatAnchorMs = index * base
            )
        }
        return MontageGraph(
            sourceDurationMs = duration,
            outputDurationMs = duration,
            clips = clips
        )
    }
}
