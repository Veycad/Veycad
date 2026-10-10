package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FearDirectorTest {
    @Test fun profile_is_the_measured_549_frame_choreography() {
        assertEquals(18_300_000L, FearStrobeProfile.OUTPUT_DURATION_US)
        assertEquals(30, FearStrobeProfile.shutterPulses.size)
        assertEquals(15, FearStrobeProfile.shutterPulses.count { it.startUs in 10_000_000L until 11_000_000L })
        assertEquals(15, FearStrobeProfile.shutterPulses.count { it.startUs in 15_866_667L until 16_833_333L })
        val graph = FearDirector.build(pool(), "fear-audio", visual())
        val frames = HighQualityFramePlan.build(graph, FearStrobeProfile.REFERENCE_FPS).frames
        assertEquals(549, frames.size)
        val shutter = frames.filter { frame ->
            FearStrobeProfile.shutterPulses.any { it.contains(frame.outputTimeUs) }
        }
        assertEquals(30, shutter.size)
        assertTrue(shutter.all { it.layer.kind == MontageGraph.OverlayKind.BLACK_FADE && it.layer.opacity == 1f })
        val finale = frames.filter { it.outputTimeUs in 16_833_333L until 17_000_000L }
        assertEquals(5, finale.size)
        assertEquals(
            listOf(MontageGraph.OverlayKind.FLASH, MontageGraph.OverlayKind.FLASH,
                MontageGraph.OverlayKind.BLACK_FADE, MontageGraph.OverlayKind.FLASH,
                MontageGraph.OverlayKind.FLASH),
            finale.map { it.layer.kind }
        )
        assertTrue(frames.filter { it.outputTimeUs >= 17_000_000L }
            .all { it.layer.kind == MontageGraph.OverlayKind.BLACK_FADE && it.layer.opacity == 1f })
    }

    @Test fun exact_coverage_uses_unique_visible_source_ranges_and_only_hard_cuts() {
        val result = FearDirector.buildWithMode(pool(), "fear-audio", visual())
        val graph = result.graph
        assertEquals(FearDirector.CoverageMode.EXACT, result.coverageMode)
        assertTrue(graph.metadata.generator.endsWith(":exact"))
        assertEquals(30, graph.clips.size)
        assertEquals(0f, RenderedMp4Acceptance.repeatedSourceRatio(graph), .0001f)
        assertEquals(3_600L, graph.clips.first().sourceEndMs - graph.clips.first().sourceStartMs)
        val openerFrames = HighQualityFramePlan.build(graph, 30).frames.filter { it.clipIndex == 0 }
        assertTrue(openerFrames.last().sourceTimeUs - openerFrames.first().sourceTimeUs >= 3_500_000L)
        assertEquals(MontageGraph.Transition.OPEN, graph.clips.first().transitionIn)
        assertTrue(graph.clips.drop(1).all { it.transitionIn == MontageGraph.Transition.HARD_CUT })
        assertTrue(graph.frameAttachments.maskRefinements.isEmpty())
        assertFalse(graph.clips.any { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY })
        assertTrue(graph.effectGraph.nodes.any { it.kind == GpuEffectGraph.Kind.DEFOCUS_BLUR })
        assertTrue(graph.effectGraph.nodes.any { it.kind == GpuEffectGraph.Kind.GLITCH })
        assertFalse(graph.overlays.any { it.id.startsWith("fear-zoom-trail-") })
        assertEquals("fear-audio", graph.audioTrack?.sourceId)
    }

    @Test fun strong_defocus_marks_phrase_entries_not_every_cascade_cut() {
        val defocus = FearDirector.build(pool(), "fear-audio", visual()).effectGraph.nodes
            .filter { it.kind == GpuEffectGraph.Kind.DEFOCUS_BLUR }
        assertEquals(listOf("fear-defocus-2", "fear-defocus-15", "fear-defocus-25"),
            defocus.map { it.id })
        assertEquals(listOf(5_100_000L, 10_966_667L, 15_866_667L),
            defocus.map { it.startUs })
    }

    @Test fun selected_cascade_shots_accelerate_into_the_cut_without_exposing_frame_edges() {
        val graph = FearDirector.build(pool(), "fear-audio", visual())
        val punch = graph.clips[9].transform
        val earlyChange = punch.sample(.62f).scale - punch.sample(0f).scale
        val lateChange = punch.sample(1f).scale - punch.sample(.72f).scale
        assertTrue(lateChange > earlyChange * 2f)
        assertTrue(graph.clips[8].transform.keyframes.size == 2)
        assertTrue(graph.clips.all { clip ->
            clip.transform.keyframes.all { it.scale >= 1f && it.scale <= 1.15f }
        })
    }

    @Test fun opener_has_a_small_zoom_and_short_accents_without_slowing_source_playback() {
        val graph = FearDirector.build(pool(), "fear-audio", visual())
        val opener = graph.clips.first()
        assertEquals(3_600L, opener.sourceEndMs - opener.sourceStartMs)
        assertTrue(opener.transform.sample(.34f).scale > opener.transform.sample(0f).scale)
        assertTrue(opener.transform.keyframes.all { it.scale in 1f..1.08f })
        assertEquals(2, graph.effectGraph.nodes.count { it.id.startsWith("fear-opener-split-") })
    }

    @Test fun opener_prefers_a_sustained_human_portrait_over_faster_animal_footage() {
        val observations = (0 until 60).map { step ->
            val human = step < 40
            VisualEventMap.Observation(
                sourceTimeUs = step * 500_000L,
                cameraMotion = VisualEventMap.Vector(if (human) .12f else .95f, 0f),
                face = if (human) VisualEventMap.Face(.9f, 0f) else null,
                humanPresenceConfidence = if (human) .9f else 0f,
                visualQuality = .8f,
                composition = VisualEventMap.Composition(.6f, .8f, .8f, .8f, .8f)
            )
        }
        val graph = FearDirector.build(pool(), "audio", VisualEventMap(30_000_000L,
            emptyList(), observations))
        assertTrue(graph.clips.first().sourceEndMs <= 20_000L)
    }

    @Test fun chromatic_accents_follow_selected_cuts_and_phrase_entries() {
        val nodes = FearDirector.build(pool(), "fear-audio", visual()).effectGraph.nodes
        val split = nodes.filter { it.kind == GpuEffectGraph.Kind.GLITCH }
        assertTrue(split.any { it.id == "fear-split-9" && it.startUs == 8_900_000L })
        assertTrue(split.any { it.id == "fear-split-15" && it.startUs == 10_966_667L })
        assertFalse(split.any { it.id == "fear-split-3" })
    }

    @Test fun bright_footage_gets_a_bounded_fear_grade_while_dark_footage_keeps_its_exposure() {
        val bright = FearDirector.build(pool(), "audio", scoredVisual { Score(luma = .75f) })
        val dark = FearDirector.build(pool(), "audio", scoredVisual { Score(luma = .28f) })
        assertTrue(bright.clips[0].exposureBias in -.12f..0f)
        assertTrue(bright.clips[1].exposureBias in -.24f..-.20f)
        assertTrue(bright.clips[9].exposureBias in -.24f..-.20f)
        assertEquals(0f, dark.clips[0].exposureBias, .0001f)
        assertEquals(0f, dark.clips[1].exposureBias, .0001f)
        assertEquals(0f, dark.clips[9].exposureBias, .0001f)
    }

    @Test fun insufficient_coverage_is_explicitly_adaptive_but_keeps_authored_timing() {
        val result = FearDirector.buildWithMode(pool(6), "fear-audio", visual(6))
        assertEquals(FearDirector.CoverageMode.ADAPTIVE, result.coverageMode)
        assertTrue(result.graph.metadata.generator.endsWith(":adaptive"))
        assertEquals(18_300L, result.graph.outputDurationMs)
        assertEquals(549, HighQualityFramePlan.build(result.graph, 30).frames.size)
    }

    @Test fun title_profile_preserves_heartbeat_and_adds_large_fear_cue() {
        val fear = AuthoredTitleProfile.forGraph(FearDirector.build(pool(), "audio", visual()))!!
        assertEquals(listOf("FEAR"), fear.texts)
        assertTrue(fear.textSizePx > 150f)
        assertTrue(fear.sampleAt(3_600_000L)!!.opacity < fear.sampleAt(4_500_000L)!!.opacity)
        val heartbeatGraph = MontageGraph(1_000, 1_000, clips = listOf(
            MontageGraph.Clip("h", 0, 1_000, 1_000, MontageGraph.ShotRole.CLOSE,
                MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0)
        ), metadata = NleProjectMetadata(generator = HeartbeatMontageProfile.ID))
        val heartbeat = AuthoredTitleProfile.forGraph(heartbeatGraph)!!
        assertEquals(4, heartbeat.texts.size)
        assertNotEquals(fear.textSizePx, heartbeat.textSizePx)
        assertTrue(MediaCodecSpeedRampRenderer.TRANSITION_FRAGMENT_SHADER.contains(
            "(row+1.-localY)/uTitleAtlasRows"))
    }

    @Test fun title_prefers_the_clean_close_face_over_an_equally_confident_wide_face() {
        val visual = scoredVisual { index ->
            when (index) {
                0 -> Score(occlusion = 1f)
                1 -> Score(scale = .95f, face = .95f, quality = .9f)
                2 -> Score(scale = .15f, face = .95f, quality = .9f)
                else -> Score()
            }
        }
        val graph = FearDirector.build(pool(), "audio", visual)
        assertTrue(graph.clips[1].sourceStartMs in 1_500L until 2_500L)
    }

    @Test fun cascade_opens_on_a_clean_face_instead_of_the_noisiest_motion_sample() {
        val visual = scoredVisual { index ->
            when (index) {
                0 -> Score(occlusion = 1f)
                1 -> Score(scale = 1f, face = 1f, quality = 1f)
                2 -> Score(scale = .9f, face = 1f, quality = 1f)
                3 -> Score(scale = .2f, face = .2f, quality = .2f, motion = 1f)
                else -> Score(scale = .3f, face = .4f, quality = .4f)
            }
        }
        val graph = FearDirector.build(pool(), "audio", visual)
        assertTrue(graph.clips[2].sourceStartMs in 3_000L until 4_000L)
    }

    private data class Score(
        val scale: Float = .5f,
        val face: Float = .7f,
        val quality: Float = .7f,
        val motion: Float = 0f,
        val occlusion: Float = 0f,
        val luma: Float = .5f
    )

    private fun scoredVisual(score: (Int) -> Score): VisualEventMap = VisualEventMap(
        30_000_000L,
        emptyList(),
        (0 until 16).flatMap { index ->
            val value = score(index)
            listOf(100_000L, 700_000L).map { offset ->
                VisualEventMap.Observation(
                    sourceTimeUs = index * 1_500_000L + offset,
                    cameraMotion = VisualEventMap.Vector(value.motion, 0f),
                    face = VisualEventMap.Face(value.face, if (index % 2 == 0) -25f else 25f),
                    gestureConfidence = if (index % 3 == 0) .7f else .1f,
                    occlusionConfidence = value.occlusion,
                    visualQuality = value.quality,
                    meanLuma = value.luma,
                    composition = VisualEventMap.Composition(
                        value.scale, value.quality, value.quality, value.quality, value.quality
                    )
                )
            }
        }
    )

    private fun pool(count: Int = 16) = MontageGraph(
        sourceDurationMs = 30_000L,
        outputDurationMs = count * 1_000L,
        clips = (0 until count).map { index ->
            MontageGraph.Clip(
                "pool-$index", index * 1_500L, index * 1_500L + 1_000L, 1_000L,
                MontageGraph.ShotRole.CLOSE, MontageGraph.Transition.FOREGROUND_REENTRY,
                MontageGraph.Motion.PUSH_IN, .8f, index * 1_000L
            )
        }
    )

    private fun visual(count: Int = 16): VisualEventMap {
        val observations = (0 until count).flatMap { index ->
            listOf(index * 1_500_000L + 100_000L, index * 1_500_000L + 700_000L).map { timeUs ->
                VisualEventMap.Observation(
                    sourceTimeUs = timeUs,
                    cameraMotion = VisualEventMap.Vector(index * .015f, 0f),
                    subjectMotion = VisualEventMap.Vector(0f, (count - index) * .01f),
                    face = VisualEventMap.Face(.9f, (index - count / 2) * 3f),
                    gestureConfidence = (index % 4) * .2f,
                    occlusionConfidence = if (index == count - 1) .9f else 0f,
                    visualQuality = .8f,
                    composition = VisualEventMap.Composition(
                        .15f + index * .03f, .8f, .8f, .7f, .8f
                    )
                )
            }
        }.sortedBy { it.sourceTimeUs }
        return VisualEventMap(30_000_000L, emptyList(), observations)
    }
}
