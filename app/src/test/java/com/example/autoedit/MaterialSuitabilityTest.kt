package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialSuitabilityTest {
    @Test fun custom_music_accepts_a_nonhuman_scene_for_rhythm_based_cuts() {
        MaterialSuitability.checkHumanEvidence(MontageStyleCatalog.Recipe.CUSTOM_MUSIC,
            listOf(visual(human = 0f, face = 0f)))
    }
    @Test fun short_input_is_a_named_material_rejection_for_each_product() {
        MontageStyleCatalog.Recipe.entries.forEach { recipe ->
            val sources = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP)
                listOf(7_000_000L, 5_999_000L) else listOf(14_999_000L)
            val rejection = capture { MaterialSuitability.checkDuration(recipe, sources) }
            assertEquals("insufficient_duration", rejection.code)
        }
    }

    @Test fun full_body_evidence_does_not_require_detected_face() {
        MaterialSuitability.checkHumanEvidence(MontageStyleCatalog.Recipe.FEAR_STROBE,
            listOf(visual(human = .8f, face = 0f)))
        assertEquals("insufficient_human_evidence", capture {
            MaterialSuitability.checkHumanEvidence(MontageStyleCatalog.Recipe.FEAR_STROBE,
                listOf(visual(human = .54f, face = 0f)))
        }.code)
    }

    @Test fun duration_accepts_the_exact_minimum_but_rejects_one_microsecond_less() {
        for ((recipe, minimumUs) in listOf(
            MontageStyleCatalog.Recipe.SIGMA to 15_000_000L,
            MontageStyleCatalog.Recipe.HEARTBEAT to 15_000_000L,
            MontageStyleCatalog.Recipe.FEAR_STROBE to 15_000_000L,
            MontageStyleCatalog.Recipe.DUALITY_LOOP to 6_000_000L)) {
            val valid = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP)
                listOf(minimumUs, minimumUs) else listOf(minimumUs)
            MaterialSuitability.checkDuration(recipe, valid)
            for (shortIndex in valid.indices) {
                assertEquals("$recipe source $shortIndex", "insufficient_duration", capture {
                    MaterialSuitability.checkDuration(recipe,
                        valid.mapIndexed { index, value -> if (index == shortIndex) value - 1L else value })
                }.code)
            }
        }
    }

    @Test fun human_evidence_needs_two_samples_at_the_person_or_face_threshold() {
        val recipe = MontageStyleCatalog.Recipe.HEARTBEAT
        val person = VisualEventMap.Observation(0L, humanPresenceConfidence = .55f)
        val face = VisualEventMap.Observation(250_000L, face = VisualEventMap.Face(.65f, 0f))
        fun map(samples: List<VisualEventMap.Observation>) = VisualEventMap(2_000_000L, emptyList(), samples)
        MaterialSuitability.checkHumanEvidence(recipe, listOf(map(listOf(person, face))))
        for (samples in listOf(listOf(VisualEventMap.Observation(0L)), listOf(person), listOf(face),
            listOf(person.copy(humanPresenceConfidence = .549f), face),
            listOf(person, face.copy(face = VisualEventMap.Face(.649f, 0f))))) {
            assertEquals("insufficient_human_evidence", capture {
                MaterialSuitability.checkHumanEvidence(recipe, listOf(map(samples)))
            }.code)
        }
        assertEquals("insufficient_human_evidence", capture {
            MaterialSuitability.checkHumanEvidence(recipe, emptyList())
        }.code)
    }

    @Test fun no_people_is_a_named_rejection_but_a_mixed_duality_pair_remains_eligible() {
        val noPeople = visual(human = 0f, face = 0f)
        val person = visual(human = .8f, face = .8f)
        val rejection = capture {
            MaterialSuitability.checkHumanEvidence(MontageStyleCatalog.Recipe.SIGMA,
                listOf(noPeople))
        }
        assertEquals("insufficient_human_evidence", rejection.code)
        MaterialSuitability.checkHumanEvidence(MontageStyleCatalog.Recipe.DUALITY_LOOP,
            listOf(noPeople, person))
        assertEquals("insufficient_human_evidence", capture {
            MaterialSuitability.checkHumanEvidence(MontageStyleCatalog.Recipe.DUALITY_LOOP,
                listOf(noPeople, noPeople))
        }.code)
    }

    private fun capture(action: () -> Unit): MaterialRejectedException = try {
        action()
        throw AssertionError("Expected a named material rejection")
    } catch (rejection: MaterialRejectedException) {
        rejection
    }

    private fun visual(human: Float, face: Float) = VisualEventMap(
        2_000_000L, emptyList(), (0..4).map { index ->
            VisualEventMap.Observation(index * 300_000L,
                humanPresenceConfidence = human,
                face = VisualEventMap.Face(face, 0f))
        })
}
