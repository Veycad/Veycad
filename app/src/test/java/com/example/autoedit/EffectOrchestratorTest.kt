package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectOrchestratorTest {
    @Test fun keeps_masked_foreground_reentry_as_the_opening_effect() {
        val mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .91f)
        val opening = MontageGraph(
            sourceDurationMs = 2_000L,
            outputDurationMs = 2_000L,
            clips = listOf(clip("a", 0L, MontageGraph.Transition.FOREGROUND_REENTRY).copy(
                outputDurationMs = 2_000L,
                sourceEndMs = 2_000L
            )),
            frameAttachments = FrameAttachmentTimeline(listOf(FrameAttachments(0L, mask = mask)))
        )

        val result = EffectOrchestrator.orchestrate(
            opening,
            audio(lowBand = true),
            visual(camera = false, stableMask = true)
        )

        assertEquals(MontageGraph.Transition.FOREGROUND_REENTRY, result.graph.clips.first().transitionIn)
        assertTrue(result.decisions.any {
            it.outputTimeUs == 0L && it.effect == EffectOrchestrator.Effect.FOREGROUND_REENTRY
        })
    }

    @Test fun removes_whip_when_no_directional_camera_evidence_exists() {
        val graph = graph(MontageGraph.Transition.WHIP)
        val result = EffectOrchestrator.orchestrate(graph, audio(lowBand = true), visual(camera = false))

        assertEquals(MontageGraph.Transition.HARD_CUT, result.graph.clips[1].transitionIn)
        assertTrue(result.decisions.none { it.effect == EffectOrchestrator.Effect.WHIP })
    }

    @Test fun keeps_whip_and_builds_gpu_nodes_only_for_directional_camera_motion() {
        val graph = graph(MontageGraph.Transition.WHIP)
        val result = EffectOrchestrator.orchestrate(graph, audio(lowBand = true), visual(camera = true))

        assertEquals(MontageGraph.Transition.WHIP, result.graph.clips[1].transitionIn)
        assertTrue(result.decisions.any { it.effect == EffectOrchestrator.Effect.WHIP })
        assertTrue(result.graph.effectGraph.nodes.any { it.kind == GpuEffectGraph.Kind.GLITCH })
    }

    @Test fun high_band_onset_creates_short_flash_with_cooldown_and_budget() {
        val boundaries = listOf(1_000L, 1_100L, 1_899L, 1_900L, 2_800L, 3_700L,
            4_600L, 5_500L, 6_400L, 7_300L, 8_200L, 9_100L)
        val graph = accentGraph(boundaries, 10_000L)
        val result = EffectOrchestrator.orchestrate(graph, accentAudio(boundaries, 10_000L),
            VisualEventMap(10_000_000L, emptyList(), listOf(VisualEventMap.Observation(0L))))

        // 899 ms is too soon; 900 ms is eligible. Later eligible onsets exceed the
        // seven-accent/10s budget. Each control catches removal of a different guard.
        assertEquals(listOf(1_000L, 1_900L, 2_800L, 3_700L, 4_600L, 5_500L, 6_400L),
            result.graph.overlays.map { it.startMs })
        assertTrue(result.graph.overlays.all { it.endMs - it.startMs == 100L })
        assertEquals(result.graph.overlays.map { it.startMs * 1_000L },
            result.decisions.filter { it.effect == EffectOrchestrator.Effect.FLASH }.map { it.outputTimeUs })
    }

    @Test fun global_cooldown_applies_between_different_accents_and_allows_its_exact_boundary() {
        val boundaries = listOf(1_000L, 1_219L, 1_220L, 2_000L)
        val graph = accentGraph(boundaries, 3_000L)
        val map = accentAudio(boundaries.drop(1), 3_000L).copy(beats = listOf(
            AudioBeatMap.Beat(1_000L, 1f, AudioBeatMap.FrequencyBand.LOW, true)))
        val result = EffectOrchestrator.orchestrate(graph, map,
            VisualEventMap(3_000_000L, emptyList(), listOf(VisualEventMap.Observation(0L))))

        assertEquals(MontageGraph.Transition.BLACKOUT, result.graph.clips[1].transitionIn)
        assertEquals(listOf(1_220L), result.graph.overlays.map { it.startMs })
        val accents = result.decisions.filter {
            it.effect in setOf(EffectOrchestrator.Effect.BLACKOUT, EffectOrchestrator.Effect.FLASH)
        }
        assertEquals(listOf(1_000_000L, 1_220_000L), accents.map { it.outputTimeUs })
        assertEquals(listOf(EffectOrchestrator.Effect.BLACKOUT, EffectOrchestrator.Effect.FLASH),
            accents.map { it.effect })
    }

    @Test fun reference_profile_replaces_random_flash_with_measured_glitch_window() {
        val reference = graph(MontageGraph.Transition.HARD_CUT).copy(
            metadata = NleProjectMetadata(generator = "veycad-reference-${ReferenceMontageProfile.ID}-dynamic")
        )

        val result = EffectOrchestrator.orchestrate(
            reference,
            audio(lowBand = false),
            visual(camera = false)
        )

        assertTrue(result.graph.overlays.none { it.kind == "onset-flash" })
        val glitch = result.graph.effectGraph.nodes.single { it.id == "reference-slice-glitch" }
        assertEquals(7_900_000L, glitch.startUs)
        assertEquals(8_180_000L, glitch.endUs)
        assertEquals(
            listOf(
                MontageGraph.OverlayKind.FLASH,
                MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                MontageGraph.OverlayKind.MIRROR_SLICE,
                MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                MontageGraph.OverlayKind.SUBJECT_STAGE,
                MontageGraph.OverlayKind.BLACK_FADE
            ),
            result.graph.overlays.map { it.overlayKind }
        )
        val finalFade = result.graph.overlays.last()
        assertEquals(17_150L, finalFade.startMs)
        assertEquals(ReferenceMontageProfile.OUTPUT_DURATION_MS, finalFade.endMs)
        assertEquals(
            listOf(5_280L, 10_280L, 11_340L),
            result.graph.overlays.slice(2..4).map { it.startMs }
        )
        assertEquals(
            List<Long?>(6) { null },
            result.graph.overlays.drop(1).take(6).map { it.secondaryTimelineStartMs }
        )
        val subjectStage = result.graph.overlays[result.graph.overlays.lastIndex - 1]
        assertEquals(16_376L, subjectStage.startMs)
        assertEquals(MontageGraph.OverlayKind.SUBJECT_STAGE, subjectStage.overlayKind)
    }

    @Test fun blackout_is_sparse_musical_punctuation_and_never_stacks_with_flash() {
        val graph = graph(MontageGraph.Transition.HARD_CUT)
        val result = EffectOrchestrator.orchestrate(graph, audio(lowBand = true), visual(camera = false))

        assertEquals(MontageGraph.Transition.BLACKOUT, result.graph.clips[1].transitionIn)
        assertTrue(result.graph.overlays.isEmpty())
        assertEquals(1, result.decisions.count { it.effect == EffectOrchestrator.Effect.BLACKOUT })
    }

    @Test fun foreground_reentry_requires_semantic_event_and_actual_confident_mask_plane() {
        val requested = graph(MontageGraph.Transition.FOREGROUND_REENTRY)
        val withoutPlane = EffectOrchestrator.orchestrate(
            requested,
            audio(lowBand = true),
            visual(camera = false, stableMask = true)
        )
        val mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .91f)
        val withPlane = EffectOrchestrator.orchestrate(
            requested.copy(frameAttachments = FrameAttachmentTimeline(listOf(
                FrameAttachments(2_400_000L, mask = mask)
            ))),
            audio(lowBand = true),
            visual(camera = false, stableMask = true)
        )

        assertEquals(MontageGraph.Transition.HARD_CUT, withoutPlane.graph.clips[1].transitionIn)
        assertEquals(MontageGraph.Transition.FOREGROUND_REENTRY, withPlane.graph.clips[1].transitionIn)
        assertTrue(withPlane.decisions.any { it.effect == EffectOrchestrator.Effect.FOREGROUND_REENTRY })
    }

    @Test fun foreground_reentry_uses_incoming_subject_mask_without_requiring_matching_old_pose() {
        val requested = graph(MontageGraph.Transition.FOREGROUND_REENTRY)
        val left = FrameAttachments.Plane(3, 2, listOf(1f, 0f, 0f, 1f, 0f, 0f), .92f)
        val right = FrameAttachments.Plane(3, 2, listOf(0f, 0f, 1f, 0f, 0f, 1f), .92f)
        val timeline = FrameAttachmentTimeline(listOf(
            FrameAttachments(999_000L, mask = left),
            FrameAttachments(2_000_000L, mask = right),
            FrameAttachments(2_400_000L, mask = right)
        ))

        val result = EffectOrchestrator.orchestrate(
            requested.copy(frameAttachments = timeline),
            audio(lowBand = false),
            visual(camera = false, stableMask = true)
        )

        assertEquals(MontageGraph.Transition.FOREGROUND_REENTRY, result.graph.clips[1].transitionIn)
        assertTrue(result.decisions.any { it.effect == EffectOrchestrator.Effect.FOREGROUND_REENTRY })
    }

    private fun graph(transition: MontageGraph.Transition): MontageGraph = MontageGraph(
        sourceDurationMs = 4_000L,
        outputDurationMs = 2_000L,
        clips = listOf(clip("a", 0L, MontageGraph.Transition.OPEN), clip("b", 2_000L, transition))
    )

    private fun clip(id: String, sourceStartMs: Long, transition: MontageGraph.Transition) = MontageGraph.Clip(
        id = id,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceStartMs + 1_000L,
        outputDurationMs = 1_000L,
        role = if (id == "a") MontageGraph.ShotRole.OPENING else MontageGraph.ShotRole.ACTION,
        transitionIn = transition,
        motion = if (transition == MontageGraph.Transition.WHIP) MontageGraph.Motion.WHIP_RIGHT else MontageGraph.Motion.PUSH_IN,
        confidence = .9f,
        beatAnchorMs = if (id == "a") 0L else 1_000L,
        flowStrength = if (transition == MontageGraph.Transition.WHIP) .8f else 0f
    )

    private fun audio(lowBand: Boolean): AudioBeatMap {
        val rate = 48_000
        val band = if (lowBand) AudioBeatMap.FrequencyBand.LOW else AudioBeatMap.FrequencyBand.HIGH
        val beat = AudioBeatMap.Beat(48_000L, 1f, band, true)
        return AudioBeatMap(rate, 96_000L, 60f, listOf(beat), listOf(AudioBeatMap.Onset(48_000L, 1f, band)))
    }

    private fun accentGraph(boundariesMs: List<Long>, durationMs: Long): MontageGraph {
        val clips = (listOf(0L) + boundariesMs + durationMs).zipWithNext().mapIndexed { index, (start, end) ->
            MontageGraph.Clip("accent-$index", start, end, end - start, MontageGraph.ShotRole.ACTION,
                if (index == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.HARD_CUT,
                MontageGraph.Motion.HOLD, 1f, start)
        }
        return MontageGraph(durationMs, durationMs, clips = clips)
    }

    private fun accentAudio(onsetsMs: List<Long>, durationMs: Long) = AudioBeatMap(
        1_000, durationMs, null, emptyList(), onsetsMs.map {
            AudioBeatMap.Onset(it, 1f, AudioBeatMap.FrequencyBand.HIGH)
        })

    private fun visual(camera: Boolean, stableMask: Boolean = false): VisualEventMap {
        val observation = VisualEventMap.Observation(
            sourceTimeUs = 2_400_000L,
            cameraMotion = if (camera) VisualEventMap.Vector(.8f) else VisualEventMap.Vector(),
            personMaskConfidence = if (stableMask) .92f else 0f,
            personMaskTemporalIou = if (stableMask) .84f else 0f,
            face = VisualEventMap.Face(.9f, 0f),
            composition = VisualEventMap.Composition(.4f, .8f, .8f, .8f, .8f)
        )
        return VisualEventMapAnalyzer.analyze(4_000_000L, listOf(
            observation.copy(sourceTimeUs = 100_000L), observation
        ))
    }
}
