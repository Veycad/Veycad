package com.example.autoedit

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualityLoopDirectorTest {
    @Test fun measured_timeline_uses_distinct_moments_from_exactly_two_sources() {
        val calm = visualMap(motion = .04f, faceConfidence = .92f, luma = .66f)
        val kinetic = visualMap(motion = .48f, faceConfidence = .74f, luma = .24f)

        val graph = DualityLoopDirector.build(calm, kinetic, "author-score")

        assertEquals(DualityLoopProfile.OUTPUT_DURATION_US / 1_000L, graph.outputDurationMs)
        assertEquals(DualityLoopProfile.boundariesMs.size - 1, graph.clips.size)
        assertEquals(setOf(0, 1), graph.clips.map { it.sourceIndex }.toSet())
        assertEquals(0, graph.clips.first().sourceIndex)
        assertEquals(DualityLoopProfile.ID, graph.metadata.generator)
        assertEquals(
            DualityLoopProfile.boundariesMs.zipWithNext { left, right -> right - left },
            graph.clips.map { it.outputDurationMs }
        )
        val frames = HighQualityFramePlan.build(graph).frames
        assertTrue(frames.all { frame ->
            frame.sourceIndex == graph.clips[frame.clipIndex].sourceIndex
        })
        (0..1).forEach { sourceIndex ->
            val sourceClips = graph.clips.filter { it.sourceIndex == sourceIndex }
            assertTrue(sourceClips.size >= 8)
            assertTrue(sourceClips.map { it.sourceStartMs to it.sourceEndMs }.distinct().size >= 8)
            assertTrue(sourceClips.all { it.sourceStartMs >= 0L &&
                it.sourceEndMs <= 10_000L - DualityLoopProfile.SOURCE_END_GUARD_MS })
        }
    }

    @Test fun bundled_author_score_matches_only_the_duality_profile() {
        val audio = File("src/main/res/raw/duality_loop_author.m4a")

        assertTrue(DualityLoopProfile.matchesAudio(audio))
        assertTrue(VeycadAutomaticEditor.dualityAudioMatchesRecipe(
            MontageStyleCatalog.Recipe.DUALITY_LOOP,
            audio
        ))
        assertTrue(!VeycadAutomaticEditor.dualityAudioMatchesRecipe(
            MontageStyleCatalog.Recipe.SIGMA,
            audio
        ))
    }

    @Test fun calmer_second_video_becomes_the_opening_anchor() {
        val kinetic = visualMap(motion = .55f, faceConfidence = .70f, luma = .22f)
        val calm = visualMap(motion = .03f, faceConfidence = .95f, luma = .70f)

        val graph = DualityLoopDirector.build(kinetic, calm, "author-score")

        assertEquals(1, graph.clips.first().sourceIndex)
        assertTrue(graph.clips.zipWithNext().any { (left, right) ->
            left.sourceIndex == right.sourceIndex
        })
        assertTrue(graph.clips.windowed(4).none { run -> run.map { it.sourceIndex }.distinct().size == 1 })
    }

    @Test fun opening_is_scored_over_the_whole_hold_not_one_clean_frame() {
        val template = visualMap(.02f, .90f, .45f).observations.first()
        val observations = (0 until 120).map { index ->
            template.copy(
                sourceTimeUs = 125_000L + index * 250_000L,
                visualQuality = if (index < 40 && index != 20) .08f else .85f,
                face = VisualEventMap.Face(if (index < 40 && index != 20) .10f else .90f, 0f)
            )
        }
        val source = VisualEventMap(30_000_000L, emptyList(), observations)
        val graph = DualityLoopDirector.build(source, visualMap(.55f, .70f, .30f), "score")

        assertEquals(0, graph.clips.first().sourceIndex)
        assertTrue(graph.clips.first().sourceStartMs >= 10_000L)
    }

    @Test fun cut_pairs_prefer_continuing_motion_over_an_unrelated_higher_quality_moment() {
        val template = visualMap(.04f, .90f, .45f).observations.first()
        val anchor = VisualEventMap(60_000_000L, emptyList(), (0 until 240).map { index ->
            template.copy(
                sourceTimeUs = 125_000L + index * 250_000L,
                cameraMotion = VisualEventMap.Vector(x = when {
                    index < 40 -> .01f
                    index < 120 -> .18f
                    else -> -.18f
                }),
                subjectMotion = VisualEventMap.Vector(),
                face = VisualEventMap.Face(.90f, if (index < 40) 0f else 35f),
                visualQuality = if (index < 120) .90f else .86f
            )
        })
        val counterpoint = VisualEventMap(60_000_000L, emptyList(), (0 until 240).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                cameraMotion = VisualEventMap.Vector(x = -.55f),
                subjectMotion = VisualEventMap.Vector())
        })

        val graph = DualityLoopDirector.build(anchor, counterpoint, "score")

        assertEquals(MontageGraph.ShotRole.DETAIL, graph.clips[3].role)
        assertEquals(0, graph.clips[3].sourceIndex)
        assertTrue(graph.clips.take(4).toString(), graph.clips[3].sourceStartMs >= 30_000L)

        val outgoingAction = graph.clips[2]
        val boundary = counterpoint.observations.last {
            it.sourceTimeUs < outgoingAction.sourceEndMs * 1_000L
        }.sourceTimeUs
        val noisy = counterpoint.copy(observations = counterpoint.observations.map {
            if (it.sourceTimeUs == boundary) it.copy(cameraMotion = VisualEventMap.Vector(x = .55f)) else it
        })
        val noiseResistant = DualityLoopDirector.build(anchor, noisy, "score")
        assertEquals(0, noiseResistant.clips[3].sourceIndex)
        assertTrue(noiseResistant.clips.take(4).map { "${it.sourceIndex}:${it.sourceStartMs}-${it.sourceEndMs}" }.toString(),
            noiseResistant.clips[3].sourceStartMs >= 30_000L)
    }

    @Test fun action_prefers_coherent_motion_to_stronger_opposing_local_flow() {
        val template = visualMap(.02f, .90f, .45f).observations.first().copy(gestureConfidence = 0f)
        val wobble = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                cameraMotion = VisualEventMap.Vector(x = if (index < 40) .02f else if (index % 2 == 0) .40f else -.40f),
                subjectMotion = VisualEventMap.Vector())
        })
        val movement = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                cameraMotion = VisualEventMap.Vector(x = .35f), subjectMotion = VisualEventMap.Vector())
        })
        val graph = DualityLoopDirector.build(wobble, movement, "score")
        assertEquals(0, graph.clips.first().sourceIndex)
        assertEquals(graph.clips.take(4).map { "${it.sourceIndex}:${it.sourceStartMs}-${it.sourceEndMs}" }.toString(),
            1, graph.clips.first { it.role == MontageGraph.ShotRole.ACTION }.sourceIndex)
    }

    @Test fun low_key_source_is_not_darkened_and_grade_reaches_the_frame_plan() {
        val graph = DualityLoopDirector.build(
            visualMap(.03f, .90f, .76f), visualMap(.48f, .74f, .10f), "score")

        assertTrue(graph.clips.filter { it.sourceIndex == 1 }.all { it.exposureBias >= 0f })
        assertTrue(graph.clips.all { it.exposureBias in -.18f.. .12f })
        val frames = HighQualityFramePlan.build(graph).frames
        assertTrue(frames.all { frame ->
            abs(frame.exposureBias - graph.clips[frame.clipIndex].exposureBias) < .0001f
        })
    }

    @Test fun framing_role_outweighs_a_higher_confidence_frontal_face() {
        val template = visualMap(.04f, .90f, .45f).observations.first()
        val portraits = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                face = VisualEventMap.Face(.99f, 0f),
                composition = template.composition!!.copy(subjectScale = .78f))
        })
        val coverage = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                face = VisualEventMap.Face(.70f, if (index < 60) 35f else 0f),
                composition = template.composition!!.copy(subjectScale = if (index < 60) .45f else .30f))
        })
        val graph = DualityLoopDirector.build(portraits, coverage, "score")
        val wide = graph.clips.first { it.role == MontageGraph.ShotRole.ESTABLISHING }
        val profile = graph.clips.first { it.role == MontageGraph.ShotRole.DETAIL }
        val close = graph.clips.first { it.role == MontageGraph.ShotRole.CLOSE }
        assertEquals(1, wide.sourceIndex)
        assertTrue(wide.sourceStartMs >= 15_000L)
        assertEquals(graph.clips.take(4).toString(), 1, profile.sourceIndex)
        assertTrue(profile.sourceEndMs <= 15_125L)
        assertEquals(0, close.sourceIndex)
    }

    @Test fun coverage_balance_does_not_defer_the_weaker_source_to_a_long_tail() {
        val strong = visualMap(.03f, .99f, .45f)
        val weak = visualMap(.03f, .20f, .45f).let { source ->
            source.copy(observations = source.observations.map { it.copy(visualQuality = .20f) })
        }
        val graph = DualityLoopDirector.build(strong, weak, "score")
        assertTrue(graph.clips.groupingBy { it.sourceIndex }.eachCount().values.all { it >= 8 })
        assertTrue(graph.clips.windowed(4).none { run -> run.map { it.sourceIndex }.distinct().size == 1 })
    }

    @Test fun body_evidence_keeps_a_faceless_human_wide_shot_but_rejects_an_unrelated_foreground() {
        val template = visualMap(.03f, .90f, .45f).observations.first()
        val coverage = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            val unrelated = index in 20..35
            val body = index in 60..90
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                face = if (unrelated || body) null else VisualEventMap.Face(.90f, 0f),
                humanPresenceConfidence = if (body) .80f else 0f,
                gestureConfidence = 0f,
                personMaskConfidence = .98f,
                composition = template.composition!!.copy(subjectScale = if (unrelated || body) .30f else .80f),
                visualQuality = if (unrelated) 1f else .85f)
        })
        val graph = DualityLoopDirector.build(coverage, visualMap(.48f, .90f, .45f), "score")
        val wide = graph.clips.first { it.role == MontageGraph.ShotRole.ESTABLISHING }
        assertEquals(0, wide.sourceIndex)
        assertTrue(wide.sourceStartMs >= 15_000L && wide.sourceEndMs <= 23_000L)
        assertTrue(graph.clips.filter { it.sourceIndex == 0 }.none {
            it.sourceStartMs >= 5_000L && it.sourceEndMs <= 9_000L
        })
    }

    @Test fun finale_keeps_selected_video_music_and_duration_without_a_graphic() {
        val primary = visualMap(.03f, .95f, .60f)
        val secondary = visualMap(.48f, .90f, .30f)
        val graph = DualityLoopDirector.build(primary, secondary, "score")
        val frames = HighQualityFramePlan.build(graph).frames
        assertTrue(graph.overlays.isEmpty())
        assertTrue(frames.all { it.layer.opacity == 0f })
        assertEquals(18_300L, graph.outputDurationMs)
        assertEquals("score", graph.audioTrack!!.sourceId)
        assertEquals(549, frames.size)
        assertTrue(frames.all {
            RenderedVisualSampler.expectedFaceConfidence(it, primary, secondary) >= .90f
        })
        assertTrue(graph.effectGraph.nodes.none { it.endUs > 16_467_000L })
    }

    @Test fun sufficient_coverage_produces_no_exact_repeated_windows() {
        val template = visualMap(.04f, .90f, .45f).observations.first()
        val source = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L)
        })
        val graph = DualityLoopDirector.build(source, source, "score")

        (0..1).forEach { sourceIndex ->
            val clips = graph.clips.filter { it.sourceIndex == sourceIndex }
            assertEquals(clips.size, clips.map { it.sourceStartMs to it.sourceEndMs }.distinct().size)
            assertTrue(clips.indices.all { index ->
                clips.take(index).none { previous ->
                    maxOf(previous.sourceStartMs, clips[index].sourceStartMs) <
                        minOf(previous.sourceEndMs, clips[index].sourceEndMs)
                }
            })
        }
    }

    @Test fun face_expectation_uses_the_selected_source_without_primary_fallback() {
        val primary = visualMap(.03f, .95f, .60f)
        val secondary = visualMap(.48f, .10f, .30f)
        val graph = DualityLoopDirector.build(primary, secondary, "score")
        val frames = HighQualityFramePlan.build(graph).frames
        val primaryFrame = frames.first { it.sourceIndex == 0 }
        val secondaryFrame = frames.first { it.sourceIndex == 1 }

        assertEquals(.95f, RenderedVisualSampler.expectedFaceConfidence(primaryFrame, primary, secondary), .0001f)
        assertEquals(.10f, RenderedVisualSampler.expectedFaceConfidence(secondaryFrame, primary, secondary), .0001f)
        assertEquals(0f, RenderedVisualSampler.expectedFaceConfidence(secondaryFrame, primary, null), .0001f)
    }

    @Test fun switching_import_order_preserves_the_directed_moments_and_roles() {
        val calm = visualMap(.03f, .90f, .60f)
        val kinetic = visualMap(.48f, .74f, .30f)
        val forward = DualityLoopDirector.build(calm, kinetic, "score")
        val reversed = DualityLoopDirector.build(kinetic, calm, "score")

        assertEquals(25, forward.clips.size)
        assertEquals(forward.clips.size, reversed.clips.size)
        forward.clips.zip(reversed.clips).forEach { (left, right) ->
            assertEquals(1 - left.sourceIndex, right.sourceIndex)
            assertEquals(left.sourceStartMs, right.sourceStartMs)
            assertEquals(left.sourceEndMs, right.sourceEndMs)
            assertEquals(left.exposureBias, right.exposureBias, .0001f)
            assertEquals(left.speedRamp, right.speedRamp)
            assertEquals(left.role, right.role)
            assertEquals(left.outputDurationMs, right.outputDurationMs)
            assertEquals(left.transitionIn, right.transitionIn)
        }
    }

    @Test fun long_opening_avoids_embedded_source_cuts_when_a_continuous_hold_exists() {
        val template = visualMap(.02f, .95f, .45f).observations.first()
        val source = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                visualQuality = if (index < 40) .98f else .80f,
                sceneChangeConfidence = if (index < 40 && index % 6 == 0) .90f else 0f)
        })
        val graph = DualityLoopDirector.build(source, visualMap(.55f, .70f, .30f), "score")

        assertEquals(0, graph.clips.first().sourceIndex)
        val opening = graph.clips.first()
        assertTrue(opening.sourceStartMs >= 9_125L)
        assertTrue(source.observations.none {
            it.sourceTimeUs > opening.sourceStartMs * 1_000L &&
                it.sourceTimeUs < opening.sourceEndMs * 1_000L && it.sceneChangeConfidence >= .50f
        })
    }

    @Test fun genuinely_quiet_footage_is_not_given_a_decorative_ramp_or_effect_train() {
        val template = visualMap(.02f, .95f, .45f).observations.first()
        val quiet = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                cameraMotion = VisualEventMap.Vector(), subjectMotion = VisualEventMap.Vector(),
                face = VisualEventMap.Face(.95f, 0f), gestureConfidence = .76f)
        })
        val graph = DualityLoopDirector.build(quiet, quiet, "score")
        assertTrue(graph.effectGraph.nodes.isEmpty())
        assertTrue(graph.clips.all { it.speedRamp == MontageGraph.SpeedRamp.constant() })
        assertTrue(graph.clips.all { it.sourceEndMs - it.sourceStartMs == it.outputDurationMs })
    }

    @Test fun moving_edit_has_live_ramps_sparse_effects_and_music_snap_without_retiming_cuts() {
        val graph = movingGraph()
        val plan = HighQualityFramePlan.build(graph)
        val ramps = graph.clips.filter { it.speedRamp.keyframes.size > 2 }
        assertTrue(ramps.size >= 3)
        assertTrue(ramps.all { it.speedRamp.keyframes[1].speed > it.speedRamp.keyframes.first().speed })
        assertTrue(ramps.map { it.speedRamp }.distinct().size >= 3)
        val actualRates = plan.frames.zipWithNext().filter { (left, right) -> left.clipIndex == right.clipIndex }
            .map { (left, right) ->
                (right.sourceTimeUs - left.sourceTimeUs).toFloat() / (right.outputTimeUs - left.outputTimeUs)
            }
        assertTrue(actualRates.min() < .90f)
        assertTrue(actualRates.max() > 1.20f)
        assertTrue(actualRates.all { it in .68f..1.85f })
        assertTrue(plan.frames.groupBy { it.clipIndex }.values.all { frames ->
            frames.zipWithNext().all { (left, right) -> right.sourceTimeUs > left.sourceTimeUs }
        })
        assertEquals(549, plan.frames.size)
        assertEquals(18_300_000L, plan.durationUs)

        // The three previous cases rebuilt the same expensive 120-observation edit four
        // times. Exercise ramps, effect spacing and music snapping on this one baseline.
        val frames = plan.frames
        val clusters = graph.effectGraph.nodes.filter { it.kind == GpuEffectGraph.Kind.GLOW }
        assertTrue(clusters.isNotEmpty() && clusters.size <= 6)
        assertTrue(frames.count { it.effects == GpuEffectGraph.Sample() } > frames.size * .75f)
        assertTrue(graph.effectGraph.nodes.all { it.startUs >= 3_700_000L && it.endUs <= 16_467_000L })
        assertTrue(graph.overlays.isEmpty())
        assertEquals("score", graph.audioTrack!!.sourceId)

        val original = graph
        val cues = original.effectGraph.nodes.filter { it.kind == GpuEffectGraph.Kind.GLOW }
            .map { (it.startUs + it.endUs) / 2L + 30_000L }.sorted()
        val music = AudioBeatMap(1_000, 18_300, null, emptyList(), cues.map {
            AudioBeatMap.Onset(it / 1_000L, .8f, AudioBeatMap.FrequencyBand.BROADBAND)
        })
        val synced = movingGraph(music)
        assertEquals(original.clips, synced.clips)
        val originalLights = original.effectGraph.nodes.filter { it.kind == GpuEffectGraph.Kind.GLOW }
        val actualPeaks = synced.effectGraph.nodes.filter { it.kind == GpuEffectGraph.Kind.GLOW }
            .associate { it.id to (it.startUs + it.endUs) / 2L }
        var eligible = 0
        var excluded = 0
        val expectedPeaks = originalLights.associate { node ->
            val oldPeak = (node.startUs + node.endUs) / 2L
            val cueUs = (oldPeak + 30_000L) / 1_000L * 1_000L
            val clipIndex = node.id.substringAfterLast('-').toInt()
            val startUs = original.clips.take(clipIndex).sumOf { it.outputDurationMs } * 1_000L
            val endUs = startUs + original.clips[clipIndex].outputDurationMs * 1_000L
            // Music snapping reserves 160 ms after the entry and 100 ms before the cut.
            // An onset inside that protected margin must leave the original motion peak.
            val expectedPeak = if (cueUs >= startUs + 160_000L && cueUs <= endUs - 100_000L) {
                eligible++
                cueUs
            } else {
                excluded++
                oldPeak
            }
            node.id to expectedPeak
        }
        assertTrue("fixture needs both eligible and excluded cues", eligible > 0 && excluded > 0)
        assertEquals(expectedPeaks, actualPeaks)
    }

    private fun movingGraph(music: AudioBeatMap? = null): MontageGraph {
        val template = visualMap(.02f, .95f, .45f).observations.first()
        fun source(offset: Int) = VisualEventMap(30_000_000L, emptyList(), (0 until 120).map { index ->
            val phase = (index + offset) % 9
            template.copy(sourceTimeUs = 125_000L + index * 250_000L,
                cameraMotion = VisualEventMap.Vector(x = if (phase < 3) .03f else .32f),
                subjectMotion = VisualEventMap.Vector(),
                face = VisualEventMap.Face(.95f, (phase - 4) * 7f),
                gestureConfidence = if (phase == 6) .85f else .15f)
        })
        return DualityLoopDirector.build(source(0), source(4), "score", music)
    }

    private fun visualMap(motion: Float, faceConfidence: Float, luma: Float): VisualEventMap {
        val durationUs = 10_000_000L
        val observations = (0 until 40).map { index ->
            VisualEventMap.Observation(
                sourceTimeUs = 125_000L + index * 250_000L,
                cameraMotion = VisualEventMap.Vector(x = if (index % 2 == 0) motion else -motion),
                subjectMotion = VisualEventMap.Vector(y = motion * (index % 3) / 3f),
                face = VisualEventMap.Face(faceConfidence, (index % 7 - 3) * 4f),
                gestureConfidence = if (index % 5 == 0) .76f else .12f,
                visualQuality = .82f + (index % 4) * .03f,
                meanLuma = (luma + (index % 3 - 1) * .04f).coerceIn(0f, 1f),
                composition = VisualEventMap.Composition(.46f + (index % 4) * .06f, .82f, .78f, .74f, .80f)
            )
        }
        return VisualEventMapAnalyzer.analyze(durationUs, observations)
    }
}
