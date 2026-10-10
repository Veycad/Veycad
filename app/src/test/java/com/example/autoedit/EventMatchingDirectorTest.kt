package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMatchingDirectorTest {
    @Test fun selected_style_matches_automatic_alternative() {
        val input = request(dynamicVisualMap())
        val all = EventMatchingDirector.direct(input)
        EventMatchingDirector.Style.entries.forEach { style ->
            assertEquals(listOf(all.single { it.style == style }), EventMatchingDirector.direct(input, style))
        }
    }

    @Test fun produces_three_structurally_different_sample_clock_exact_edits() {
        val alternatives = EventMatchingDirector.direct(request(dynamicVisualMap()))

        assertEquals(3, alternatives.size)
        assertEquals(3, alternatives.map { it.graph.clips.map { clip -> clip.outputDurationMs } }.distinct().size)
        alternatives.forEach { alternative ->
            assertEquals(6_000L, alternative.graph.clips.sumOf { it.outputDurationMs })
            assertEquals(6_000L, alternative.graph.outputDurationMs)
            assertTrue(alternative.graph.parameterTracks.isNotEmpty())
        }
    }

    @Test fun never_reuses_source_windows_inside_an_alternative() {
        EventMatchingDirector.direct(request(dynamicVisualMap())).forEach { alternative ->
            alternative.graph.clips.forEachIndexed { index, clip ->
                alternative.graph.clips.drop(index + 1).forEach { other ->
                    assertTrue(
                        "overlap ${clip.sourceStartMs}..${clip.sourceEndMs} and ${other.sourceStartMs}..${other.sourceEndMs}",
                        clip.sourceEndMs <= other.sourceStartMs || other.sourceEndMs <= clip.sourceStartMs
                    )
                }
            }
        }
    }

    @Test fun whip_requires_confirmed_directional_camera_event() {
        val alternatives = EventMatchingDirector.direct(request(dynamicVisualMap()))

        alternatives.flatMap { it.graph.clips }.filter {
            it.transitionIn == MontageGraph.Transition.WHIP
        }.forEach { clip ->
            assertTrue(clip.motion == MontageGraph.Motion.WHIP_LEFT || clip.motion == MontageGraph.Motion.WHIP_RIGHT)
            assertTrue(clip.flowStrength > 0f)
        }
        assertTrue(alternatives.any { alternative ->
            alternative.graph.clips.any { it.transitionIn == MontageGraph.Transition.WHIP }
        })
    }

    @Test fun static_material_uses_deliberate_virtual_camera_instead_of_rejecting_edit() {
        val observations = (0 until 24).map { index ->
            VisualEventMap.Observation(
                sourceTimeUs = index * 500_000L + 100_000L,
                face = VisualEventMap.Face(.93f, 0f),
                composition = VisualEventMap.Composition(.42f, .8f, .8f, .75f, .82f)
            )
        }
        val visual = VisualEventMapAnalyzer.analyze(12_500_000L, observations)
        val alternatives = EventMatchingDirector.direct(request(visual))

        assertEquals(3, alternatives.size)
        assertTrue(alternatives.all { it.graph.clips.all { clip ->
            clip.motion == MontageGraph.Motion.PUSH_IN || clip.motion == MontageGraph.Motion.PUSH_OUT
        } })
        assertTrue(alternatives.all { it.graph.clips.none { clip -> clip.transitionIn == MontageGraph.Transition.WHIP } })
        assertTrue(alternatives.all { it.virtualCameraCount > 0 })
        val dynamic = alternatives.single { it.style == EventMatchingDirector.Style.DYNAMIC }
        val transforms = dynamic.graph.clips.map { it.transform }
        assertTrue("virtual camera must not repeat one crop", transforms.distinct().size >= 4)
        assertTrue(transforms.flatMap { it.keyframes }.all { it.translateX == 0f })
        assertTrue(transforms.flatMap { it.keyframes }.maxOf { it.scale } >= 1.10f)
        assertTrue(transforms.flatMap { it.keyframes }.maxOf { it.scale } <= 1.12f)
    }

    @Test fun finale_chooses_a_readable_face_instead_of_a_hand_over_lens() {
        val observations = (0 until 24).map { index ->
            val occludedEnding = index >= 20
            VisualEventMap.Observation(
                sourceTimeUs = index * 500_000L + 100_000L,
                cameraMotion = if (occludedEnding) VisualEventMap.Vector(.82f, .2f) else VisualEventMap.Vector(),
                subjectMotion = if (occludedEnding) VisualEventMap.Vector(y = -.75f) else VisualEventMap.Vector(),
                face = if (occludedEnding) null else VisualEventMap.Face(.94f, 0f),
                gestureConfidence = if (occludedEnding) .92f else 0f,
                occlusionConfidence = if (occludedEnding) .90f else 0f,
                visualQuality = if (occludedEnding) .18f else .88f,
                composition = if (occludedEnding) null else
                    VisualEventMap.Composition(.44f, .82f, .8f, .76f, .88f)
            )
        }
        val visual = VisualEventMapAnalyzer.analyze(12_500_000L, observations)

        EventMatchingDirector.direct(request(visual)).forEach { alternative ->
            val finale = alternative.graph.clips.last()
            val samples = observations.filter {
                it.sourceTimeUs / 1_000L in finale.sourceStartMs until finale.sourceEndMs
            }
            assertTrue("finale=${finale.sourceStartMs}..${finale.sourceEndMs}", samples.isNotEmpty())
            assertTrue(samples.count { it.face != null }.toFloat() / samples.size >= .85f)
            assertTrue(samples.maxOf { it.occlusionConfidence } < .25f)
        }
    }

    @Test fun finale_chooses_a_calm_live_window_instead_of_freezing_a_motion_blurred_peak() {
        val observations = (0 until 40).map { index ->
            val blurredPeak = index in 14..18
            VisualEventMap.Observation(
                sourceTimeUs = index * 500_000L + 100_000L,
                cameraMotion = if (blurredPeak) VisualEventMap.Vector(.72f, .16f) else
                    VisualEventMap.Vector(.025f, 0f),
                subjectMotion = if (blurredPeak) VisualEventMap.Vector(y = -.58f) else
                    VisualEventMap.Vector(y = .018f),
                face = VisualEventMap.Face(.95f, 0f),
                visualQuality = if (blurredPeak) .98f else .86f,
                composition = VisualEventMap.Composition(.44f, .84f, .82f, .78f, .88f)
            )
        }
        val visual = VisualEventMapAnalyzer.analyze(20_500_000L, observations)
        val referenceRequest = EventMatchingDirector.Request(
            "source.mp4",
            "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            visual,
            referenceProfileId = ReferenceMontageProfile.ID
        )

        EventMatchingDirector.direct(referenceRequest).forEach { alternative ->
            val finale = alternative.graph.clips.last()
            val finaleSamples = observations.filter {
                it.sourceTimeUs / 1_000L in finale.sourceStartMs until finale.sourceEndMs
            }
            assertTrue(finaleSamples.size >= 2)
            assertTrue(finaleSamples.maxOf {
                it.cameraMotion.magnitude + it.subjectMotion.magnitude
            } < .65f)
            assertTrue(finale.sourceEndMs > finale.sourceStartMs)
        }
    }

    @Test fun authors_one_foreground_reentry_for_static_material_with_a_pts_bound_mask() {
        val observations = (0 until 12).map { index ->
            VisualEventMap.Observation(
                sourceTimeUs = index * 1_000_000L + 100_000L,
                face = VisualEventMap.Face(.94f, 0f),
                personMaskConfidence = .93f,
                personMaskTemporalIou = .84f,
                composition = VisualEventMap.Composition(.44f, .82f, .8f, .76f, .88f)
            )
        }
        val visual = VisualEventMapAnalyzer.analyze(12_500_000L, observations)
        val mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .93f)
        val timeline = FrameAttachmentTimeline(observations.map {
            FrameAttachments(it.sourceTimeUs, mask = mask)
        })

        val withoutMasks = EventMatchingDirector.direct(request(visual))
        val withMasks = EventMatchingDirector.direct(request(visual).copy(frameAttachments = timeline))

        assertTrue(withoutMasks.all { it.graph.clips.none { clip ->
            clip.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        } })
        assertTrue(withMasks.any { it.graph.clips.any { clip ->
            clip.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
        } })
        assertTrue(withMasks.all { alternative ->
            alternative.graph.clips.count { clip ->
                clip.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
            } == 1
        })
        assertTrue(withMasks.joinToString { alternative ->
            "${alternative.style}:${alternative.graph.clips.indexOfFirst { it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY }}"
        }, withMasks.all { alternative ->
            alternative.graph.clips.indexOfFirst { clip ->
                clip.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY
            } == 0
        })
        assertTrue(withMasks.all { it.graph.frameAttachments == timeline })
    }

    @Test fun dense_music_still_preserves_unique_source_windows() {
        val rate = 48_000
        val period = 16_000L
        val beats = (0 until 60).map { index ->
            AudioBeatMap.Beat(index * period, .7f, AudioBeatMap.FrequencyBand.MID, index % 4 == 0)
        }
        val denseAudio = AudioBeatMap(rate, rate * 20L, 180f, beats, beats.map {
            AudioBeatMap.Onset(it.sampleIndex, it.strength, it.dominantBand)
        })
        val observations = (0 until 40).map { index ->
            VisualEventMap.Observation(
                sourceTimeUs = index * 500_000L + 100_000L,
                face = VisualEventMap.Face(.9f, 0f),
                composition = VisualEventMap.Composition(.4f, .8f, .8f, .8f, .8f)
            )
        }
        val visual = VisualEventMapAnalyzer.analyze(20_500_000L, observations)
        val alternatives = EventMatchingDirector.direct(EventMatchingDirector.Request(
            "source.mp4", "music.wav", 20_000L, denseAudio, visual
        ))

        alternatives.forEach { alternative ->
            alternative.graph.clips.forEachIndexed { index, clip ->
                assertTrue(clip.sourceEndMs > clip.sourceStartMs)
                assertTrue(alternative.graph.clips.drop(index + 1).all { other ->
                    clip.sourceEndMs <= other.sourceStartMs || other.sourceEndMs <= clip.sourceStartMs
                })
            }
        }
        val dynamic = alternatives.single { it.style == EventMatchingDirector.Style.DYNAMIC }
        assertTrue("dynamic clips=${dynamic.graph.clips.size}", dynamic.graph.clips.size in 15..16)
        assertTrue(dynamic.graph.clips.all { it.outputDurationMs >= 500L })
    }

    @Test fun reference_profile_uses_measured_opening_hold_and_cut_timeline() {
        val visual = dynamicVisualMap()
        val request = EventMatchingDirector.Request(
            "source.mp4",
            "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(20_000_000L, visual.observations),
            referenceProfileId = ReferenceMontageProfile.ID
        )

        val dynamic = EventMatchingDirector.direct(request)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }

        assertEquals(2_514L, dynamic.graph.clips.first().outputDurationMs)
        assertEquals(
            ReferenceMontageProfile.DYNAMIC_BOUNDARIES_MS,
            dynamic.graph.clips.runningFold(0L) { cursor, clip -> cursor + clip.outputDurationMs }
        )
        val contextualWhip = dynamic.graph.clips[11]
        assertEquals(MontageGraph.Transition.WHIP, contextualWhip.transitionIn)
        assertTrue(contextualWhip.motion in setOf(
            MontageGraph.Motion.WHIP_LEFT,
            MontageGraph.Motion.WHIP_RIGHT
        ))
        assertTrue(contextualWhip.flowStrength > 0f)
        assertTrue(dynamic.graph.clips.filterIndexed { index, _ -> index !in setOf(0, 11) }
            .all { it.transitionIn == MontageGraph.Transition.HARD_CUT })
    }

    @Test fun reference_profile_fills_the_author_phrase_from_a_shorter_unique_source() {
        val sourceDurationUs = 17_136_000L
        val observations = (0 until 68).map { index ->
            VisualEventMap.Observation(
                sourceTimeUs = index * 250_000L + 100_000L,
                cameraMotion = if (index in 28..31) VisualEventMap.Vector(.55f, 0f) else
                    VisualEventMap.Vector(.02f, 0f),
                subjectMotion = VisualEventMap.Vector(y = .02f),
                face = VisualEventMap.Face(.94f, 0f),
                personMaskConfidence = .93f,
                personMaskTemporalIou = .92f,
                composition = VisualEventMap.Composition(.44f, .82f, .8f, .76f, .88f)
            )
        }
        val request = EventMatchingDirector.Request(
            "short-source.mp4",
            "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(sourceDurationUs, observations),
            referenceProfileId = ReferenceMontageProfile.ID
        )

        EventMatchingDirector.direct(request).forEach { alternative ->
            assertEquals(ReferenceMontageProfile.OUTPUT_DURATION_MS, alternative.graph.outputDurationMs)
            assertEquals(sourceDurationUs / 1_000L, alternative.graph.sourceDurationMs)
            alternative.graph.clips.forEachIndexed { index, clip ->
                assertTrue(clip.sourceStartMs >= 0L)
                assertTrue(clip.sourceEndMs <= sourceDurationUs / 1_000L)
                assertTrue(alternative.graph.clips.drop(index + 1).all { other ->
                    clip.sourceEndMs <= other.sourceStartMs || other.sourceEndMs <= clip.sourceStartMs
                })
            }
            val plan = HighQualityFramePlan.build(alternative.graph)
            assertTrue(plan.frames.all { it.sourceTimeUs in 0 until sourceDurationUs })
        }
        val dynamic = EventMatchingDirector.direct(request)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }
        assertEquals(16, dynamic.graph.clips.size)
        assertTrue(dynamic.graph.clips.any {
            it.sourceEndMs - it.sourceStartMs < it.outputDurationMs
        })
    }

    @Test fun reference_opening_snaps_to_an_exact_confident_mask_pts() {
        val observations = (0 until 40).map { index ->
            VisualEventMap.Observation(
                sourceTimeUs = index * 500_000L + 100_000L,
                face = VisualEventMap.Face(.9f, 0f),
                composition = VisualEventMap.Composition(.45f, .8f, .8f, .8f, .85f)
            )
        }
        val maskPtsUs = 4_100_000L
        val mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .93f)
        val request = EventMatchingDirector.Request(
            "source.mp4",
            "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(20_000_000L, observations),
            FrameAttachmentTimeline(listOf(FrameAttachments(
                maskPtsUs,
                mask = mask,
                subjectQuality = .91f,
                subjectOcclusion = .02f,
                maskTemporalIou = .93f
            ))),
            ReferenceMontageProfile.ID
        )

        val opening = EventMatchingDirector.direct(request)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }
            .graph.clips.first()

        assertEquals(maskPtsUs / 1_000L, opening.sourceStartMs)
        assertEquals(MontageGraph.Transition.FOREGROUND_REENTRY, opening.transitionIn)
    }

    @Test fun reference_opening_prefers_headroom_and_wider_coverage_with_equally_confident_masks() {
        val observations = (0 until 80).map { index ->
            VisualEventMap.Observation(index * 250_000L + 100_000L,
                face = VisualEventMap.Face(.9f, 0f), humanPresenceConfidence = .9f,
                composition = VisualEventMap.Composition(.45f, .8f, .8f, .8f, .85f))
        }
        val mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .95f)
        fun take(startUs: Long, face: FrameAttachments.FaceRegion) = (0 until 7).map {
            FrameAttachments(startUs + it * 250_000L, mask = mask, faceRegion = face,
                subjectQuality = .9f, maskTemporalIou = .94f)
        }
        val close = take(100_000L, FrameAttachments.FaceRegion(.5f, .32f, .7f, .6f, .9f))
        val wider = take(4_100_000L, FrameAttachments.FaceRegion(.5f, .38f, .35f, .26f, .9f))
        val request = EventMatchingDirector.Request("source.mp4", "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(20_000_000L, observations),
            FrameAttachmentTimeline(close + wider), ReferenceMontageProfile.ID)
        val opening = EventMatchingDirector.direct(request)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }.graph.clips.first()
        assertTrue(opening.sourceStartMs >= 4_100L && opening.sourceEndMs <= 6_100L)
    }

    @Test fun reference_opening_prefers_a_stable_live_mask_window_over_one_clean_start_frame() {
        val observations = (0 until 80).map { index ->
            VisualEventMap.Observation(
                sourceTimeUs = index * 250_000L + 100_000L,
                face = VisualEventMap.Face(.9f, 0f),
                composition = VisualEventMap.Composition(.45f, .8f, .8f, .8f, .85f)
            )
        }
        val mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .93f)
        val unstable = (0 until 7).map { index ->
            FrameAttachments(
                100_000L + index * 250_000L,
                mask = mask.copy(confidence = if (index == 0) .93f else .42f),
                subjectQuality = if (index == 0) .9f else .4f,
                maskTemporalIou = if (index == 0) .9f else .3f
            )
        }
        val stableStartUs = 4_100_000L
        val stable = (0 until 7).map { index ->
            FrameAttachments(
                stableStartUs + index * 250_000L,
                mask = mask,
                subjectQuality = .9f,
                maskTemporalIou = .94f
            )
        }
        val request = EventMatchingDirector.Request(
            "source.mp4",
            "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(20_000_000L, observations),
            FrameAttachmentTimeline((unstable + stable).sortedBy { it.sourceTimeUs }),
            ReferenceMontageProfile.ID
        )

        val opening = EventMatchingDirector.direct(request)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }
            .graph.clips.first()

        assertEquals(stableStartUs / 1_000L, opening.sourceStartMs)
        assertEquals(MontageGraph.Transition.FOREGROUND_REENTRY, opening.transitionIn)
    }

    @Test fun scarce_one_second_wide_take_is_reserved_for_finale_before_opening() {
        val observations = (0 until 144).map { index ->
            val wide = index in 44..47
            VisualEventMap.Observation(index * 250_000L,
                face = VisualEventMap.Face(.95f, 0f), humanPresenceConfidence = .95f,
                subjectMotion = VisualEventMap.Vector(if (wide) .5f else .02f, 0f),
                visualQuality = .85f, personMaskConfidence = .95f, personMaskTemporalIou = .95f,
                composition = VisualEventMap.Composition(if (wide) .24f else .6f, .85f, .8f, .8f, .85f))
        }
        val masks = FrameAttachmentTimeline(observations.mapIndexed { index, observation ->
            val size = if (index in 44..47) .18f else .42f
            FrameAttachments(observation.sourceTimeUs,
                mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .95f), subjectQuality = .95f,
                maskTemporalIou = .95f, faceRegion = FrameAttachments.FaceRegion(.5f, .3f, size, size, .95f))
        })
        val reference = EventMatchingDirector.Request("source.mp4", "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(36_000_000L, observations), masks, ReferenceMontageProfile.ID)
        val graph = EventMatchingDirector.direct(reference)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }.graph
        assertTrue("finale=${graph.clips.last()}", graph.clips.last().sourceStartMs >= 11_000L)
        assertTrue(graph.clips.last().sourceEndMs <= 12_000L)
        assertTrue(graph.clips.first().sourceEndMs <= 11_000L || graph.clips.first().sourceStartMs >= 12_000L)
    }

    @Test fun reference_finale_reserves_a_clean_moving_wider_take_instead_of_another_close_portrait() {
        val observations = (0 until 144).map { index ->
            VisualEventMap.Observation(index * 250_000L,
                face = VisualEventMap.Face(.95f, 0f),
                cameraMotion = VisualEventMap.Vector(if (index < 72) 0f else .6f, 0f),
                subjectMotion = VisualEventMap.Vector(if (index < 72) 0f else .5f, 0f),
                visualQuality = if (index < 72) .85f else .76f,
                personMaskConfidence = .95f, personMaskTemporalIou = .95f,
                composition = VisualEventMap.Composition(if (index < 72) .6f else .3f,
                    if (index < 72) .85f else .7f, .7f, .7f, .7f))
        }
        val masks = FrameAttachmentTimeline(observations.mapIndexed { index, observation ->
            val size = if (index < 72) .42f else .18f
            FrameAttachments(observation.sourceTimeUs,
                mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .95f),
                subjectQuality = .95f, maskTemporalIou = .95f,
                faceRegion = FrameAttachments.FaceRegion(.5f, .3f, size, size, .95f))
        })
        val reference = EventMatchingDirector.Request("source.mp4", "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(36_000_000L, observations), masks, ReferenceMontageProfile.ID)
        val graph = EventMatchingDirector.direct(reference)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }.graph
        val finale = graph.clips.last()
        assertTrue("finale=${finale.sourceStartMs}..${finale.sourceEndMs}", finale.sourceStartMs >= 18_000L)
        assertTrue(finale.sourceEndMs <= 36_000L)
        val mirror = graph.clips[12]
        assertTrue("mirror=${mirror.sourceStartMs}..${mirror.sourceEndMs}", mirror.sourceStartMs >= 18_000L)
        assertTrue(graph.clips.dropLast(1).all {
            it.sourceEndMs <= finale.sourceStartMs || it.sourceStartMs >= finale.sourceEndMs
        })
    }

    @Test fun reference_normal_cuts_prefer_face_or_body_evidence_over_animal_foreground() {
        val observations = (0 until 192).map { index ->
            val time = index * 250_000L
            val animal = time in 20_000_000L until 30_000_000L
            val body = time >= 36_000_000L
            VisualEventMap.Observation(time,
                cameraMotion = VisualEventMap.Vector(if (animal) .8f else .02f, 0f),
                subjectMotion = VisualEventMap.Vector(if (body) .45f else .02f, 0f),
                face = if (animal || body) null else VisualEventMap.Face(.95f, 0f),
                personMaskConfidence = .95f, personMaskTemporalIou = .95f,
                visualQuality = if (animal) .99f else .85f,
                composition = VisualEventMap.Composition(if (animal || body) .3f else .6f,
                    .9f, .9f, .9f, .9f),
                humanPresenceConfidence = if (animal) 0f else .95f)
        }
        val masks = FrameAttachmentTimeline(observations.map { observation ->
            FrameAttachments(observation.sourceTimeUs,
                mask = FrameAttachments.Plane(2, 2, List(4) { 1f }, .95f),
                subjectQuality = .95f, maskTemporalIou = .95f,
                faceRegion = observation.face?.let {
                    FrameAttachments.FaceRegion(.5f, .3f, .42f, .42f, .95f)
                })
        })
        val reference = EventMatchingDirector.Request("source.mp4", "author-track.m4a",
            ReferenceMontageProfile.OUTPUT_DURATION_MS,
            beatMap().loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L),
            VisualEventMapAnalyzer.analyze(48_000_000L, observations), masks, ReferenceMontageProfile.ID)
        val graph = EventMatchingDirector.direct(reference)
            .single { it.style == EventMatchingDirector.Style.DYNAMIC }.graph
        assertTrue(graph.clips.filterIndexed { index, _ -> index != 11 }.all {
            it.sourceEndMs <= 20_000L || it.sourceStartMs >= 30_000L
        })
        assertTrue("A face-free body take must remain eligible", graph.clips.any {
            it.sourceStartMs >= 36_000L
        })
    }

    private fun request(visual: VisualEventMap) = EventMatchingDirector.Request(
        sourceId = "source.mp4",
        audioSourceId = "music.wav",
        outputDurationMs = 6_000L,
        audio = beatMap(),
        visual = visual
    )

    private fun beatMap(): AudioBeatMap {
        val rate = 48_000
        val beats = (0 until 12).map { index ->
            AudioBeatMap.Beat(
                sampleIndex = index * 24_000L,
                strength = if (index % 4 == 0) 1f else .55f,
                dominantBand = if (index % 4 == 0) AudioBeatMap.FrequencyBand.LOW else AudioBeatMap.FrequencyBand.MID,
                isDownbeat = index % 4 == 0
            )
        }
        return AudioBeatMap(rate, rate * 6L, 120f, beats, beats.map {
            AudioBeatMap.Onset(it.sampleIndex, it.strength, it.dominantBand)
        })
    }

    private fun dynamicVisualMap(): VisualEventMap {
        val observations = (0 until 24).map { index ->
            val moving = index in setOf(2, 6, 10, 14, 18, 22)
            VisualEventMap.Observation(
                sourceTimeUs = index * 500_000L + 100_000L,
                cameraMotion = if (moving) VisualEventMap.Vector(if (index % 4 == 2) .55f else -.55f) else VisualEventMap.Vector(),
                subjectMotion = if (index in setOf(4, 12, 20)) VisualEventMap.Vector(y = -.48f) else VisualEventMap.Vector(),
                face = VisualEventMap.Face(.94f, if (index % 5 == 0) 18f else 0f),
                gestureConfidence = if (index in setOf(4, 12, 20)) .86f else 0f,
                occlusionConfidence = if (index == 16) .9f else 0f,
                composition = VisualEventMap.Composition(.44f, .82f, .8f, .76f, .88f)
            )
        }
        return VisualEventMapAnalyzer.analyze(12_500_000L, observations)
    }
}
