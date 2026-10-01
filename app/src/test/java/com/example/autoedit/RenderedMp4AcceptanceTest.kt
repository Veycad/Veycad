package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderedMp4AcceptanceTest {
    @Test fun required_face_evidence_accepts_only_the_same_actual_image_clip_and_source() {
        val fresh = freshFaceSamples()
        val good = RenderedMp4Acceptance.evaluate(graph(), audio(), container(12_000L), fresh,
            requireFaceEvidence = true)
        assertTrue(good.issues.toString(), good.accepted)
        val target = fresh[3]
        val evidence = requireNotNull(target.decodedFaceEvidence)
        for (bad in listOf(
            target.copy(decodedFaceEvidence = null),
            target.copy(decodedFaceEvidence = evidence.copy(outputTimeUs = target.outputTimeUs - 33_333L)),
            target.copy(decodedFaceEvidence = evidence.copy(sourceIndex = 1)),
            target.copy(decodedFaceEvidence = evidence.copy(clipIndex = evidence.clipIndex + 1)),
            target.copy(decodedFaceEvidence = evidence.copy(confidence = null)),
            target.copy(decodedFaceEvidence = evidence.copy(confidence = .8f)),
            target.copy(decodedSourceIndex = null),
            target.copy(decodedClipIndex = null))) {
            val report = RenderedMp4Acceptance.evaluate(graph(), audio(), container(12_000L),
                fresh.map { if (it.outputTimeUs == target.outputTimeUs) bad else it },
                requireFaceEvidence = true)
            assertFalse(report.accepted)
            assertTrue(report.issues.toString(), report.issues.contains("face-evidence-unmeasured"))
        }
    }

    @Test fun production_tagged_unknown_face_cannot_pass_an_optional_face_requirement() {
        val samples = freshFaceSamples().mapIndexed { index, sample ->
            if (index == 3) sample.copy(decodedFaceEvidence = null) else sample
        }
        val report = RenderedMp4Acceptance.evaluate(graph(), audio(), container(12_000L), samples)
        assertFalse(report.accepted)
        assertTrue(report.issues.contains("face-evidence-unmeasured"))
        assertEquals(listOf(samples[3].outputTimeUs), RenderedMp4Acceptance.faceUnknownTimestampsUs(samples))
    }

    @Test fun successful_empty_face_is_measured_loss_not_inference_failure() {
        val samples = freshFaceSamples().mapIndexed { index, sample ->
            if (index == 3) sample.copy(faceConfidence = 0f,
                decodedFaceEvidence = requireNotNull(sample.decodedFaceEvidence).copy(confidence = 0f))
            else sample
        }
        val report = RenderedMp4Acceptance.evaluate(graph(), audio(), container(12_000L), samples,
            requireFaceEvidence = true)
        assertFalse(report.accepted)
        assertFalse(report.issues.contains("face-evidence-unmeasured"))
        assertTrue(report.issues.contains("face-loss"))
        assertEquals(listOf(samples[3].outputTimeUs), RenderedMp4Acceptance.faceLossTimestampsUs(samples))
        assertTrue(RenderedMp4Acceptance.faceUnknownTimestampsUs(samples).isEmpty())
    }

    @Test fun heartbeat_device_gate_requires_decoded_tail_even_when_all_pulses_match() {
        val graph = MontageGraph(22_000L, 21_166L, clips = listOf(
            MontageGraph.Clip("whole", 0, 21_166, 21_166, MontageGraph.ShotRole.FINALE,
                MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0)),
            overlays = HeartbeatMontageProfile.pulses.mapIndexed { index, pulse ->
                MontageGraph.Overlay("pulse-$index", pulse.startUs / 1_000L,
                    (pulse.endUs + 999L) / 1_000L, "heartbeat-measured-step",
                    overlayKind = if (pulse.kind == HeartbeatMontageProfile.PulseKind.WHITE)
                        MontageGraph.OverlayKind.FLASH else MontageGraph.OverlayKind.BLACK_FADE,
                    opacity = 1f)
            } + MontageGraph.Overlay("tail", 19_800L, 21_166L, "heartbeat-measured-step",
                overlayKind = MontageGraph.OverlayKind.BLACK_FADE, opacity = 1f),
            metadata = NleProjectMetadata(generator = "${HeartbeatMontageProfile.ID}:test"))
        val pulses = HeartbeatMontageProfile.pulses.map {
            sample(it.startUs, luma = if (it.kind == HeartbeatMontageProfile.PulseKind.WHITE) 1f else 0f)
        }
        val tail = HeartbeatPulseAudit.tailSamplingTargetsUs().map {
            sample(it, luma = if (it >= HeartbeatMontageProfile.LAST_VISUAL_END_US) 0f else 1f)
        }
        val container = container(0).copy(videoLastPtsUs = 21_166_000L, audioLastPtsUs = 21_166_000L)
        val good = (pulses + tail).distinctBy { it.outputTimeUs }.sortedBy { it.outputTimeUs }
        val accepted = RenderedMp4Acceptance.evaluate(graph, audio(), container, good)
        assertTrue(accepted.issues.toString(), accepted.accepted)
        for (bad in listOf(pulses,
            good.filterNot { it.outputTimeUs == 21_150_000L },
            good.map { if (it.outputTimeUs == 20_000_000L) it.copy(luma = .4f) else it })) {
            val report = RenderedMp4Acceptance.evaluate(graph, audio(), container, bad)
            assertFalse(report.accepted)
            assertTrue(report.issues.any { it.startsWith("heartbeat-tail-mismatch") })
            assertFalse(report.issues.any { it.startsWith("heartbeat-pulse-mismatch") })
        }
    }

    @Test fun mask_failure_reasons_are_distinct_and_fail_closed() {
        val reasons = mapOf(
            RenderedMp4Acceptance.MaskEvidence.SOURCE_MASK_EMPTY to "foreground-mask-source-empty",
            RenderedMp4Acceptance.MaskEvidence.OUTPUT_MASK_EMPTY to "foreground-mask-output-empty",
            RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED to "foreground-mask-inference-failed")
        reasons.forEach { (status, issue) ->
            val samples = goodSamples().mapIndexed { index, sample ->
                if (index == 2) sample.copy(maskExpected = true, edgeLeakRatio = 1f,
                    maskEvidence = status) else sample
            }
            val report = RenderedMp4Acceptance.evaluate(graph(), audio(), container(12_000L), samples)
            assertFalse(report.accepted)
            assertTrue(report.issues.contains(issue))
            assertFalse(report.issues.contains("foreground-mask-edge-leak"))
        }
    }

    @Test fun unrequested_samples_cannot_replace_required_foreground_measurement() {
        val foregroundGraph = graph().copy(clips = graph().clips.mapIndexed { index, clip ->
            if (index == 1) clip.copy(transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY) else clip
        })
        val samples = goodSamples().map { it.copy(maskExpected = true,
            maskTemporalIou = .99f, maskEvidence = RenderedMp4Acceptance.MaskEvidence.NOT_REQUESTED) }
        val report = RenderedMp4Acceptance.evaluate(foregroundGraph, audio(), container(12_000L), samples)
        assertFalse(report.accepted)
        assertTrue(report.issues.contains("foreground-mask-measurement-missing"))
    }

    @Test fun missing_or_bad_audio_evidence_fails_the_production_gate() {
        val missing = RenderedMp4Acceptance.evaluate(graph(), audio(), container(0), goodSamples(),
            requireDecodedAudioEvidence = true)
        assertTrue(missing.issues.contains("audio-decode-evidence-missing"))
        val badAudio = DecodedAudioQuality.evaluate(floatArrayOf(1.1f), 1_000, 1,
            1_000, 0, true)
        val clipped = RenderedMp4Acceptance.evaluate(graph(), audio(), container(0), goodSamples(),
            decodedAudio = badAudio, requireDecodedAudioEvidence = true)
        assertTrue(clipped.issues.contains("audio-over-full-scale"))
        assertFalse(clipped.accepted)
    }

    @Test fun container_errors_cannot_be_hidden_by_good_visual_samples() {
        val report = RenderedMp4Acceptance.evaluate(graph(), audio(),
            container(0).copy(integrityIssues = listOf("non_monotonic_sample_pts",
                "encoded_dimensions_do_not_match_export")), goodSamples())
        assertFalse(report.accepted)
        assertTrue(report.issues.contains("container:non_monotonic_sample_pts"))
        assertTrue(report.issues.contains("container:encoded_dimensions_do_not_match_export"))
    }

    @Test fun rejects_shifted_audio_start_even_when_track_ends_align() {
        val report = RenderedMp4Acceptance.evaluate(graph(), audio(),
            container(0).copy(audioFirstPtsUs = 21_333L), goodSamples())
        assertFalse(report.accepted)
        assertTrue(report.issues.contains("audio-not-pts-zero"))
    }

    @Test fun accepts_render_that_hits_beats_has_visible_transition_and_stable_av() {
        val report = RenderedMp4Acceptance.evaluate(graph(), audio(), container(12_000L), goodSamples())

        assertTrue(report.issues.toString(), report.accepted)
        assertEquals(1f, report.metrics.beatHitRate, .001f)
        assertEquals(0f, report.metrics.repeatedSourceRatio, .001f)
        assertTrue(report.metrics.transitionPeak >= .7f)
        assertTrue(report.metrics.referenceGrammarFit >= report.reference.minimumReferenceGrammarFit)
        assertFalse(report.issues.contains("reference-grammar-fit"))
    }

    @Test fun heartbeat_scores_measured_onsets_while_other_recipes_remain_beat_only() {
        val rate = 48_000
        val onsetAligned = AudioBeatMap(
            rate,
            160_000L,
            60f,
            listOf(24_000L, 120_000L).mapIndexed { index, sampleIndex ->
                AudioBeatMap.Beat(
                    sampleIndex,
                    .8f,
                    AudioBeatMap.FrequencyBand.LOW,
                    index == 0
                )
            },
            listOf(48_000L, 96_000L).map { sampleIndex ->
                AudioBeatMap.Onset(
                    sampleIndex,
                    .9f,
                    AudioBeatMap.FrequencyBand.BROADBAND
                )
            }
        )
        val heartbeat = graph().copy(metadata = NleProjectMetadata(
            generator = "${HeartbeatMontageProfile.ID}:test"
        ))

        assertEquals(1f, RenderedMp4Acceptance.beatHitRate(
            heartbeat,
            onsetAligned,
            goodSamples(),
            85_000L
        ), .001f)
        assertEquals(0f, RenderedMp4Acceptance.beatHitRate(
            graph(),
            onsetAligned,
            goodSamples(),
            85_000L
        ), .001f)
    }

    @Test fun rejects_weak_repeated_desynchronised_and_visually_broken_output() {
        val repeated = graph().copy(clips = graph().clips.mapIndexed { index, clip ->
            if (index == 1) clip.copy(sourceStartMs = 200L, sourceEndMs = 1_200L) else clip
        })
        val badSamples = listOf(
            sample(0L, luma = .2f, face = .9f),
            sample(900_000L, luma = .9f, artifact = .7f, face = .1f),
            sample(1_100_000L, transition = .02f, luma = .1f, artifact = .6f, face = .1f),
            sample(2_100_000L, luma = .8f, face = .1f)
        )

        val report = RenderedMp4Acceptance.evaluate(repeated, audio(offsetSamples = 8_000L), container(120_000L), badSamples)

        assertFalse(report.accepted)
        assertTrue(report.issues.contains("beat-hit-rate"))
        assertTrue(report.issues.contains("repeated-source-moments"))
        assertTrue(report.issues.contains("weak-rendered-transitions"))
        assertTrue(report.issues.contains("av-drift"))
        assertTrue(report.issues.contains("transition-artifacts"))
        assertTrue(report.issues.contains("colour-jump"))
        assertEquals(900_000L, report.metrics.maximumColourJumpTimeUs)
        assertTrue(report.issues.contains("face-loss"))
        assertEquals(
            listOf(900_000L, 1_100_000L, 2_100_000L),
            RenderedMp4Acceptance.faceLossTimestampsUs(badSamples)
        )
    }

    @Test fun authored_hard_cuts_and_heartbeat_pulses_are_not_colour_instability() {
        val authored = graph().copy(overlays = listOf(
            MontageGraph.Overlay(
                "heartbeat-pulse", 450L, 550L, "heartbeat-measured-step",
                overlayKind = MontageGraph.OverlayKind.FLASH, opacity = 1f
            )
        ))
        val samples = listOf(
            sample(0L, luma = .2f),
            sample(500_000L, luma = 1f),
            sample(600_000L, luma = .2f),
            sample(900_000L, luma = .2f),
            sample(1_000_000L, luma = .85f),
            sample(1_100_000L, luma = .85f),
            sample(1_500_000L, luma = .85f),
            sample(1_900_000L, luma = .85f),
            sample(2_000_000L, luma = .1f),
            sample(2_100_000L, luma = .1f)
        )

        val report = RenderedMp4Acceptance.evaluate(authored, audio(), container(12_000L), samples)

        assertFalse(report.issues.contains("colour-jump"))
        assertEquals(0f, report.metrics.maximumColourJump, .001f)
    }

    @Test fun rejects_missing_output_face_evidence_when_source_contains_a_face() {
        val samples = goodSamples().map { it.copy(faceExpected = false, faceConfidence = 0f) }

        val report = RenderedMp4Acceptance.evaluate(
            graph(),
            audio(),
            container(12_000L),
            samples,
            requireFaceEvidence = true
        )

        assertFalse(report.accepted)
        assertTrue(report.issues.contains("face-evidence-missing"))
    }

    @Test fun requires_clean_output_mask_evidence_for_foreground_reentry() {
        val foregroundGraph = graph().copy(clips = graph().clips.mapIndexed { index, clip ->
            if (index == 1) clip.copy(transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY) else clip
        })

        val missing = RenderedMp4Acceptance.evaluate(foregroundGraph, audio(), container(12_000L), goodSamples())
        assertTrue(missing.issues.contains("foreground-mask-evidence-missing"))

        val leaking = goodSamples().mapIndexed { index, sample ->
            if (index == 2) sample.copy(maskExpected = true, edgeLeakRatio = .08f,
                maskEvidence = RenderedMp4Acceptance.MaskEvidence.MEASURED) else sample
        }
        val report = RenderedMp4Acceptance.evaluate(foregroundGraph, audio(), container(12_000L), leaking)
        assertFalse(report.accepted)
        assertEquals(.08f, report.metrics.maximumEdgeLeakRatio, .001f)
        assertTrue(report.issues.contains("foreground-mask-edge-leak"))
    }

    @Test fun rejects_large_black_rectangles_measured_in_decoded_pixels() {
        val samples = goodSamples().mapIndexed { index, sample ->
            if (index == 2) sample.copy(blackBlockScore = .22f) else sample
        }

        val report = RenderedMp4Acceptance.evaluate(graph(), audio(), container(12_000L), samples)

        assertFalse(report.accepted)
        assertTrue(report.issues.contains("black-block-artifact"))
    }

    @Test fun rejects_unstable_decoded_foreground_matte() {
        val foregroundGraph = graph().copy(clips = graph().clips.mapIndexed { index, clip ->
            if (index == 1) clip.copy(transitionIn = MontageGraph.Transition.FOREGROUND_REENTRY) else clip
        })
        val unstable = goodSamples().mapIndexed { index, sample ->
            if (index == 2) sample.copy(maskExpected = true, maskTemporalIou = .71f) else sample
        }

        val report = RenderedMp4Acceptance.evaluate(foregroundGraph, audio(), container(12_000L), unstable)

        assertFalse(report.accepted)
        assertEquals(.71f, report.metrics.minimumMaskTemporalIou, .001f)
        assertTrue(report.issues.contains("foreground-mask-temporal-instability"))
    }

    @Test fun rejects_container_duration_beyond_one_frame() {
        val report = RenderedMp4Acceptance.evaluate(
            graph(),
            audio(),
            RenderedMp4Acceptance.ContainerSample(
                2_900_000L, 2_900_000L, "video/avc", "audio/mp4a-latm"
            ),
            goodSamples()
        )

        assertEquals(100_000L, report.metrics.durationErrorUs)
        assertTrue(report.issues.contains("duration-mismatch"))
    }

    @Test fun rejects_video_track_that_does_not_start_at_pts_zero() {
        val report = RenderedMp4Acceptance.evaluate(
            graph(),
            audio(),
            RenderedMp4Acceptance.ContainerSample(
                3_000_000L,
                3_000_000L,
                "video/avc",
                "audio/mp4a-latm",
                videoFirstPtsUs = 33_333L
            ),
            goodSamples()
        )

        assertEquals(33_333L, report.metrics.videoStartPtsUs)
        assertTrue(report.issues.contains("video-not-pts-zero"))
    }

    @Test fun sigma_grammar_threshold_does_not_gate_other_products() {
        val foreignGraph = graph().copy(metadata = NleProjectMetadata(
            generator = "${HeartbeatMontageProfile.ID}:test"
        ))
        val report = RenderedMp4Acceptance.evaluate(
            foreignGraph, audio(), container(12_000L), goodSamples(),
            reference = RenderedMp4Acceptance.ReferenceMontageCard(
                minimumReferenceGrammarFit = 1.1f
            )
        )

        assertFalse(report.issues.contains("reference-grammar-fit"))
        assertFalse(report.issues.any { it.startsWith("reference-required-event-missing:") })
    }

    @Test fun heartbeat_requires_its_decoded_pulse_schedule() {
        val heartbeat = graph().copy(metadata = NleProjectMetadata(
            generator = "${HeartbeatMontageProfile.ID}:test"
        ))
        val report = RenderedMp4Acceptance.evaluate(
            heartbeat, audio(), container(12_000L), goodSamples()
        )

        assertTrue(report.issues.any { it.startsWith("heartbeat-pulse-mismatch:") })
    }

    @Test fun duality_requires_two_balanced_source_roles() {
        val oneSource = graph().copy(metadata = NleProjectMetadata(
            generator = DualityLoopProfile.ID
        ))
        val report = RenderedMp4Acceptance.evaluate(
            oneSource, audio(), container(12_000L), goodSamples()
        )

        assertTrue(report.issues.contains("duality-clip-count"))
        assertTrue(report.issues.contains("duality-source-coverage"))
        assertTrue(report.issues.contains("duality-source-balance"))
    }

    @Test fun requires_all_nine_decoded_author_accents_within_one_frame() {
        val referenceGraph = MontageGraph(
            sourceDurationMs = ReferenceMontageProfile.OUTPUT_DURATION_MS,
            outputDurationMs = ReferenceMontageProfile.OUTPUT_DURATION_MS,
            clips = listOf(MontageGraph.Clip(
                "reference",
                0L,
                ReferenceMontageProfile.OUTPUT_DURATION_MS,
                ReferenceMontageProfile.OUTPUT_DURATION_MS,
                MontageGraph.ShotRole.OPENING,
                MontageGraph.Transition.OPEN,
                MontageGraph.Motion.HOLD,
                1f,
                0L
            )),
            metadata = NleProjectMetadata(generator = "veycad-reference-${ReferenceMontageProfile.ID}")
        )
        val exact = listOf(sample(0L, transition = 0f)) +
            ReferenceMontageProfile.ACCENT_BEATS_US.map { sample(it + 20_000L, transition = .2f) }
        val missed = exact.map { sample ->
            if (sample.outputTimeUs == ReferenceMontageProfile.ACCENT_BEATS_US[4] + 20_000L) {
                sample.copy(outputTimeUs = sample.outputTimeUs + 40_000L)
            } else sample
        }.sortedBy { it.outputTimeUs }

        val passing = RenderedMp4Acceptance.authorAccentEvidence(referenceGraph, exact)
        val failing = RenderedMp4Acceptance.authorAccentEvidence(referenceGraph, missed)

        assertEquals(1f, passing.hitRate, .001f)
        assertTrue(passing.maximumOffsetUs <= 33_334L)
        assertEquals(8f / 9f, failing.hitRate, .001f)
        assertEquals(listOf(ReferenceMontageProfile.ACCENT_BEATS_US[4]), failing.missedUs)
    }

    @Test fun texture_and_legacy_mirror_signatures_are_diagnostic_while_glitch_remains_a_gate() {
        val reference = RenderedMp4Acceptance.ReferenceMontageCard()
        val effects = listOf(
            RenderedMp4Acceptance.DecodedEffect.DOUBLE_EXPOSURE to
                .30f,
            RenderedMp4Acceptance.DecodedEffect.MIRROR_SLICE to
                .017f,
            RenderedMp4Acceptance.DecodedEffect.GLITCH to
                (reference.minimumDecodedGlitchPeak + .002f)
        )
        val visible = effects.mapIndexed { index, (effect, strength) ->
            sample(100_000L + index * 100_000L).copy(
                expectedEffect = effect,
                effectSignatureStrength = strength
            )
        }

        effects.forEach { (missingEffect, _) ->
            val report = RenderedMp4Acceptance.evaluate(
                exactReferenceGraph(),
                audio(),
                RenderedMp4Acceptance.ContainerSample(
                    ReferenceMontageProfile.OUTPUT_DURATION_MS * 1_000L,
                    ReferenceMontageProfile.OUTPUT_DURATION_MS * 1_000L,
                    "video/avc",
                    "audio/mp4a-latm"
                ),
                visible.map { sample ->
                    if (sample.expectedEffect == missingEffect) {
                        sample.copy(effectSignatureStrength = 0f)
                    } else sample
                }
            )

            assertEquals(report.issues.toString(),
                missingEffect == RenderedMp4Acceptance.DecodedEffect.GLITCH,
                report.issues.contains(
                when (missingEffect) {
                    RenderedMp4Acceptance.DecodedEffect.DOUBLE_EXPOSURE ->
                        "decoded-double-exposure-missing"
                    RenderedMp4Acceptance.DecodedEffect.MIRROR_SLICE ->
                        "decoded-mirror-slice-missing"
                    RenderedMp4Acceptance.DecodedEffect.GLITCH -> "decoded-glitch-missing"
                }
            ))
        }
    }

    @Test fun accepts_calibrated_decoded_effect_signatures_for_reference_render() {
        val reference = RenderedMp4Acceptance.ReferenceMontageCard()
        val samples = listOf(
            sample(100_000L).copy(
                expectedEffect = RenderedMp4Acceptance.DecodedEffect.DOUBLE_EXPOSURE,
                effectSignatureStrength = .28f
            ),
            sample(200_000L).copy(
                expectedEffect = RenderedMp4Acceptance.DecodedEffect.MIRROR_SLICE,
                effectSignatureStrength = .015f
            ),
            sample(300_000L).copy(
                expectedEffect = RenderedMp4Acceptance.DecodedEffect.GLITCH,
                effectSignatureStrength = reference.minimumDecodedGlitchPeak
            )
        )

        val report = RenderedMp4Acceptance.evaluate(
            exactReferenceGraph(),
            audio(),
            RenderedMp4Acceptance.ContainerSample(
                ReferenceMontageProfile.OUTPUT_DURATION_MS * 1_000L,
                ReferenceMontageProfile.OUTPUT_DURATION_MS * 1_000L,
                "video/avc",
                "audio/mp4a-latm"
            ),
            samples
        )

        assertFalse(report.issues.toString(), report.issues.any { it.startsWith("decoded-") })
    }

    @Test fun rejects_a_sigma_finale_that_erases_the_source_background() {
        val brightStage = listOf(
            sample(16_600_000L).copy(subjectStageBackgroundLuma = .0f, subjectStageBackgroundLoss = .8f),
            sample(16_900_000L).copy(subjectStageBackgroundLuma = .0f, subjectStageBackgroundLoss = .7f)
        )

        val report = RenderedMp4Acceptance.evaluate(
            exactReferenceGraph(),
            audio(),
            RenderedMp4Acceptance.ContainerSample(
                ReferenceMontageProfile.OUTPUT_DURATION_MS * 1_000L,
                ReferenceMontageProfile.OUTPUT_DURATION_MS * 1_000L,
                "video/avc",
                "audio/mp4a-latm"
            ),
            brightStage
        )

        assertEquals(.8f, report.metrics.maximumSubjectStageBackgroundLoss, .001f)
        assertTrue(report.issues.contains("subject-stage-background-erased"))
    }

    @Test fun measures_only_pixels_outside_the_subject_for_stage_darkness() {
        val luma = floatArrayOf(.03f, .05f, .70f, .75f)
        val mask = floatArrayOf(0f, .1f, .92f, 1f)

        assertEquals(.04f, RenderedVisualSampler.subjectStageBackgroundLuma(luma, mask), .001f)
        assertEquals(1f, RenderedVisualSampler.subjectStageBackgroundLuma(
            luma,
            FloatArray(4) { 1f }
        ), .001f)
    }

    private fun graph(): MontageGraph = MontageGraph(
        sourceDurationMs = 4_000L,
        outputDurationMs = 3_000L,
        clips = listOf(
            clip("a", 0L, MontageGraph.Transition.OPEN),
            clip("b", 1_000L, MontageGraph.Transition.WHIP),
            clip("c", 2_000L, MontageGraph.Transition.HARD_CUT)
        )
    )

    private fun exactReferenceGraph(): MontageGraph {
        val clips = ReferenceMontageProfile.DYNAMIC_BOUNDARIES_MS.zipWithNext()
            .mapIndexed { index, (start, end) ->
                MontageGraph.Clip(
                    id = "reference-$index",
                    sourceStartMs = start,
                    sourceEndMs = end,
                    outputDurationMs = end - start,
                    role = when (index) {
                        0 -> MontageGraph.ShotRole.OPENING
                        ReferenceMontageProfile.DYNAMIC_BOUNDARIES_MS.size - 2 ->
                            MontageGraph.ShotRole.FINALE
                        else -> MontageGraph.ShotRole.ACTION
                    },
                    transitionIn = if (index == 0) {
                        MontageGraph.Transition.FOREGROUND_REENTRY
                    } else MontageGraph.Transition.HARD_CUT,
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
            metadata = NleProjectMetadata(
                generator = "veycad-reference-${ReferenceMontageProfile.ID}"
            )
        )
        return base.copy(effectGraph = GpuEffectGraphFactory.forMontage(base))
    }

    private fun clip(id: String, start: Long, transition: MontageGraph.Transition) = MontageGraph.Clip(
        id, start, start + 1_000L, 1_000L, MontageGraph.ShotRole.ACTION, transition,
        if (transition == MontageGraph.Transition.WHIP) MontageGraph.Motion.WHIP_RIGHT else MontageGraph.Motion.HOLD,
        .9f, start
    )

    private fun audio(offsetSamples: Long = 0L): AudioBeatMap {
        val rate = 48_000
        val beats = listOf(48_000L + offsetSamples, 96_000L + offsetSamples).mapIndexed { index, sample ->
            AudioBeatMap.Beat(sample, .9f, AudioBeatMap.FrequencyBand.LOW, index == 0)
        }
        return AudioBeatMap(rate, 160_000L, 60f, beats, beats.map {
            AudioBeatMap.Onset(it.sampleIndex, it.strength, it.dominantBand)
        })
    }

    private fun container(driftUs: Long) = RenderedMp4Acceptance.ContainerSample(
        3_000_000L, 3_000_000L + driftUs, "video/avc", "audio/mp4a-latm"
    )

    private fun goodSamples() = listOf(
        sample(0L), sample(900_000L), sample(1_000_000L, transition = .7f),
        sample(1_080_000L, transition = .85f), sample(1_300_000L),
        sample(2_000_000L, transition = .72f), sample(2_100_000L, transition = .35f)
    )

    private fun freshFaceSamples() = goodSamples().mapIndexed { index, sample ->
        sample.copy(decodedSourceIndex = 0, decodedClipIndex = index,
            decodedFaceEvidence = DecodedFaceEvidence(sample.outputTimeUs, 0, index, sample.faceConfidence))
    }

    private fun sample(
        timeUs: Long,
        transition: Float = 0f,
        luma: Float = .5f,
        artifact: Float = .02f,
        face: Float = .9f
    ) = RenderedMp4Acceptance.VisualSample(
        timeUs, transition, luma, .5f, .5f, artifact, true, face,
        subjectStageBackgroundLuma = .03f,
        subjectStageBackgroundLoss = 0f
    )
}
