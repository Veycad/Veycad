package com.example.autoedit

import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMontageGrammarTest {
    @Test fun rejects_a_long_uniform_storyboard_and_rewards_reference_vocabulary() {
        val weak = graph(
            roles = listOf(MontageGraph.ShotRole.ACTION, MontageGraph.ShotRole.ACTION),
            transitions = listOf(MontageGraph.Transition.OPEN, MontageGraph.Transition.HARD_CUT)
        )
        val strong = graph(
            roles = listOf(
                MontageGraph.ShotRole.OPENING,
                MontageGraph.ShotRole.ESTABLISHING,
                MontageGraph.ShotRole.ACTION,
                MontageGraph.ShotRole.CLOSE,
                MontageGraph.ShotRole.DETAIL,
                MontageGraph.ShotRole.FINALE,
                MontageGraph.ShotRole.ACTION,
                MontageGraph.ShotRole.CLOSE,
                MontageGraph.ShotRole.FINALE,
                MontageGraph.ShotRole.ESTABLISHING,
                MontageGraph.ShotRole.ACTION,
                MontageGraph.ShotRole.FINALE,
                MontageGraph.ShotRole.CLOSE
            ),
            transitions = listOf(
                MontageGraph.Transition.OPEN,
                MontageGraph.Transition.FOREGROUND_REENTRY,
                MontageGraph.Transition.HARD_CUT,
                MontageGraph.Transition.WHIP
            ) + List(9) { MontageGraph.Transition.HARD_CUT }
        )

        assertTrue(ReferenceMontageGrammar.score(weak) < .55f)
        assertTrue(ReferenceMontageGrammar.score(strong) > .75f)
    }

    @Test fun exact_reference_event_card_has_full_timeline_recall() {
        val comparison = ReferenceMontageGrammar.timelineComparison(referenceGraph())

        assertTrue(comparison.missing.toString(), comparison.missing.isEmpty())
        assertTrue(comparison.recall == 1f)
    }

    @Test fun missing_required_layer_is_named_even_when_aggregate_recall_exceeds_95_percent() {
        val graph = referenceGraph().let { reference ->
            reference.copy(overlays = reference.overlays.filterNot {
                it.id == "reference-mirror-slice-peak"
            })
        }

        val comparison = ReferenceMontageGrammar.timelineComparison(graph)

        assertTrue(comparison.recall >= .95f)
        assertTrue(comparison.missing.contains("layer:reference-mirror-slice-peak"))
    }

    private fun referenceGraph(): MontageGraph {
        val boundaries = ReferenceMontageProfile.DYNAMIC_BOUNDARIES_MS
        val clips = boundaries.zipWithNext().mapIndexed { index, (start, end) ->
            MontageGraph.Clip(
                id = "reference-$index",
                sourceStartMs = start,
                sourceEndMs = end,
                outputDurationMs = end - start,
                role = when (index) {
                    0 -> MontageGraph.ShotRole.OPENING
                    boundaries.size - 2 -> MontageGraph.ShotRole.FINALE
                    else -> MontageGraph.ShotRole.ACTION
                },
                transitionIn = if (index == 0) {
                    MontageGraph.Transition.FOREGROUND_REENTRY
                } else {
                    MontageGraph.Transition.HARD_CUT
                },
                motion = MontageGraph.Motion.HOLD,
                confidence = 1f,
                beatAnchorMs = start
            )
        }
        val base = MontageGraph(
            sourceDurationMs = ReferenceMontageProfile.OUTPUT_DURATION_MS,
            outputDurationMs = ReferenceMontageProfile.OUTPUT_DURATION_MS,
            clips = clips,
            overlays = ReferenceMontageProfile.authoredOverlays(),
            metadata = NleProjectMetadata(generator = "veycad-reference-${ReferenceMontageProfile.ID}")
        )
        return base.copy(effectGraph = GpuEffectGraphFactory.forMontage(base))
    }

    private fun graph(
        roles: List<MontageGraph.ShotRole>,
        transitions: List<MontageGraph.Transition>
    ): MontageGraph {
        val duration = 18_000L
        val base = duration / roles.size
        val clips = roles.indices.map { index ->
            val clipDuration = if (index == roles.lastIndex) duration - base * index else base
            MontageGraph.Clip(
                id = "clip-$index",
                sourceStartMs = index * base,
                sourceEndMs = index * base + clipDuration,
                outputDurationMs = clipDuration,
                role = roles[index],
                transitionIn = transitions[index],
                motion = MontageGraph.Motion.HOLD,
                confidence = .9f,
                beatAnchorMs = index * base
            )
        }
        return MontageGraph(sourceDurationMs = duration, outputDurationMs = duration, clips = clips)
    }
}
