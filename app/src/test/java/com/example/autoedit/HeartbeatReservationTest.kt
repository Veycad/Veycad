package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

/** Pure planner regressions, not substitutes for decoded MP4/AAC or human acceptance. */
class HeartbeatReservationTest {
    @Test fun source_boundary_planning_preserves_every_v23_authored_overlay_and_pulse() {
        // Independent recipe construction read from the immutable v23 source archive.
        // Keep this baseline separate from production authoredOverlays() to detect retiming.
        fun ms(us: Long) = (us + 500L) / 1_000L
        for (offset in listOf(-67L, 0L, 67L)) {
            val expected = HeartbeatMontageProfile.pulses.mapIndexed { index, pulse ->
                MontageGraph.Overlay("heartbeat-pulse-$index", pulse.startUs / 1_000L,
                    pulse.endUs / 1_000L, "heartbeat-measured-step",
                    overlayKind = if (pulse.kind == HeartbeatMontageProfile.PulseKind.WHITE)
                        MontageGraph.OverlayKind.FLASH else MontageGraph.OverlayKind.BLACK_FADE,
                    opacity = 1f)
            } + HeartbeatMontageProfile.scenes.filter { it.echo }.mapIndexed { index, scene ->
                MontageGraph.Overlay("heartbeat-echo-$index", ms(scene.startUs), ms(scene.endUs),
                    "heartbeat-echo-experimental", overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                    opacity = .62f, secondarySourceOffsetMs = offset)
            } + MontageGraph.Overlay("heartbeat-finale-echo-stutter",
                HeartbeatMontageProfile.FINALE_ECHO_START_US / 1_000L,
                (HeartbeatMontageProfile.FINALE_ECHO_END_US + 999L) / 1_000L,
                "heartbeat-echo-experimental", overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                opacity = .62f, secondarySourceOffsetMs = offset) +
                MontageGraph.Overlay("heartbeat-tail", ms(HeartbeatMontageProfile.LAST_VISUAL_END_US),
                    HeartbeatMontageProfile.OUTPUT_DURATION_US / 1_000L, "heartbeat-measured-step",
                    overlayKind = MontageGraph.OverlayKind.BLACK_FADE, opacity = 1f)
            val graph = HeartbeatDirector.build(pool(List(15) { 1_500L }), "audio", echoOffsetMs = offset)
            assertEquals(expected, graph.overlays)
            val frames = HighQualityFramePlan.build(graph, 60).frames
            frames.forEach { frame ->
                assertEquals(LayerCompositorModel.sample(expected, frame.outputTimeUs / 1_000L), frame.layer)
            }
            assertUnclampedEcho(graph)
            assertLiveAndDistinct(graph)
        }
    }

    @Test fun zero_start_echo_role_reserves_real_preroll_without_changing_its_live_slot() {
        // Actual C interval shape: first/reprise role7 [0,450) and four clamped -67ms requests.
        // This is a pure planner fixture, not a claim about a newly decoded C export.
        val original = boundaryPool(7, 0L, 450L, readable = true)
        val graph = HeartbeatDirector.build(original, "audio")
        assertTrue(graph.clips[18].id.endsWith("role-7"))
        assertTrue(graph.clips[18].sourceStartMs >= 67L)
        assertEquals(graph.clips[7].sourceStartMs, graph.clips[18].sourceStartMs)
        assertTrue(225L in graph.clips[18].sourceStartMs until graph.clips[18].sourceEndMs)
        assertUnclampedEcho(graph)
        assertLiveAndDistinct(graph)
        assertEquals(27, graph.clips.size)
        assertEquals(1_270, HighQualityFramePlan.build(graph, 60).frames.size)
        assertEquals(21_166L, graph.outputDurationMs)
    }

    @Test fun positive_offset_reserves_postroll_at_the_real_end_of_an_echo_role() {
        val original = boundaryPool(7, 39_550L, 40_000L, readable = true)
        val graph = HeartbeatDirector.build(original, "audio", echoOffsetMs = 67L)
        assertTrue(graph.clips[18].id.endsWith("role-7"))
        assertTrue(graph.clips[18].sourceEndMs < 40_000L)
        assertUnclampedEcho(graph)
        assertLiveAndDistinct(graph)
    }

    @Test fun finale_footprint_uses_its_late_echo_not_a_blanket_sixty_seven_ms_margin() {
        val footprint = HeartbeatDirector.echoFootprint(4, 1_133L)!!
        // The finale has a whole-slot BUILD echo as well as the late explicit stutter.
        // Its first 60fps request is 16.333ms into the rounded 18.667s slot.
        assertEquals(16_333L, footprint.firstRelativeUs)
        assertEquals(1_083_000L, footprint.lastRelativeUs)
        val shortReprise = HeartbeatDirector.echoFootprint(4, 1_133L, includeFinale = false)!!
        assertEquals(316_000L, shortReprise.firstRelativeUs)
        assertEquals(799_333L, shortReprise.lastRelativeUs)
        assertNull(HeartbeatDirector.echoFootprint(0, 650L))
        assertNull(HeartbeatDirector.echoFootprint(14, 1_100L))
        val atStart = HeartbeatDirector.build(boundaryPool(4, 0L, 1_133L, readable = true), "audio")
        assertEquals(75L, atStart.clips[25].sourceStartMs)
        assertEquals(391L, atStart.clips[15].sourceStartMs)
        assertUnclampedEcho(atStart)
        val atEnd = HeartbeatDirector.build(boundaryPool(4, 38_867L, 40_000L, readable = true),
            "audio", echoOffsetMs = 67L)
        assertEquals(39_975L, atEnd.clips[25].sourceEndMs)
        assertTrue(atEnd.clips[25].sourceEndMs > 40_000L - 67L)
        assertUnclampedEcho(atEnd)
        assertLiveAndDistinct(atEnd)
    }

    @Test fun synchronous_control_keeps_footage_and_zero_offset_needs_no_preroll() {
        val original = boundaryPool(7, 0L, 450L, readable = false)
        val zero = HeartbeatDirector.build(original, "audio", echoOffsetMs = 0L)
        assertEquals(0L, zero.clips[18].sourceStartMs)
        assertUnclampedEcho(zero)
        val temporal = HeartbeatDirector.build(original.copy(frameAttachments = readableTimeline()), "audio")
        val control = HeartbeatDirector.synchronousEchoControl(temporal)
        assertEquals(temporal.clips, control.clips)
        assertEquals(temporal.copy(overlays = control.overlays), control)
        assertUnclampedEcho(control)
    }

    @Test fun boundary_event_without_readable_extension_or_compatible_assignment_is_refused() {
        val rejection = assertThrows(MaterialRejectedException::class.java) {
            HeartbeatDirector.build(boundaryPool(7, 0L, 450L, readable = false), "audio")
        }
        assertEquals("insufficient_distinct_moments", rejection.code)
        for (offset in listOf(-40_000L, 40_000L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val impossible = assertThrows(MaterialRejectedException::class.java) {
                HeartbeatDirector.build(pool(List(15) { 1_500L }), "audio", echoOffsetMs = offset)
            }
            assertEquals("insufficient_distinct_moments", impossible.code)
        }
    }

    @Test fun optional_interlude_respects_its_own_reprise_footprint_not_the_hero_finale() {
        val original = pool(List(15) { 1_500L }).let { graph -> graph.copy(
            clips = graph.clips.map { it.copy(sourceStartMs = it.sourceStartMs + 2_000L,
                sourceEndMs = it.sourceEndMs + 2_000L) }) }
        val observations = (0L until 40_000_000L step 250_000L).map { timeUs ->
            VisualEventMap.Observation(timeUs, faceInferenceSucceeded = true,
                face = if (timeUs <= 1_000_000L) null else VisualEventMap.Face(.9f, 0f),
                visualQuality = .9f, meanLuma = .5f,
                composition = VisualEventMap.Composition(.35f, .8f, .8f, .8f, .8f))
        }
        val visual = VisualEventMap(40_000_000L, emptyList(), observations)
        val interlude = HeartbeatDirector.selectDistinctSubjectlessInterlude(original, visual,
            echoOffsetMs = -500L)!!
        assertTrue(interlude.sourceStartMs >= 283L)
        val graph = HeartbeatDirector.build(original, "audio", echoOffsetMs = -500L, visualMap = visual)
        assertTrue(graph.clips[15].id.endsWith("role-15"))
        assertTrue(graph.clips[25].id.endsWith("role-4"))
        assertUnclampedEcho(graph)
        assertLiveAndDistinct(graph)
    }

    @Test fun role_assignment_contract_requires_the_actual_fifteen_source_role_indices() {
        val original = pool(HeartbeatDirector.liveRoleLengths())
        for (invalid in listOf((1..15).toList(), List(15) { 0 }, (-1 until 14).toList(),
            (0 until 14).toList())) {
            assertThrows(IllegalArgumentException::class.java) {
                HeartbeatDirector.reserveLiveRoles(original, original.clips, invalid, true, true)
            }
        }
    }

    @Test fun final_override_reserves_the_long_hero_use_not_the_authored_action_role() {
        val lengths = HeartbeatDirector.liveRoleLengths()
        assertEquals(1_133L, lengths[HeartbeatMontageProfile.FINALE_PORTRAIT_ROLE])
        assertEquals(1_100L, lengths[14])
        assertEquals(15, lengths.size)
        val graph = HeartbeatDirector.build(pool(lengths), "audio")
        assertLiveAndDistinct(graph)
        assertEquals(27, graph.clips.size)
        assertEquals(1_270, HighQualityFramePlan.build(graph, 60).frames.size)
        assertEquals(21_166L, graph.outputDurationMs)
    }

    @Test fun unequal_legacy_windows_try_a_compatible_assignment_instead_of_slowing() {
        val original = pool(HeartbeatDirector.liveRoleLengths())
        val preferred = (0 until 15).toMutableList().also { order ->
            order[1] = 6 // 517ms cannot honestly fill a 1117ms role without new evidence.
            order[6] = 1
        }
        val reserved = HeartbeatDirector.reserveLiveRoles(original, original.clips, preferred, true, true)
        assertNotEquals(6, reserved[1].sourceRole)
        assertEquals(15, reserved.map { it.sourceRole }.distinct().size)
        reserved.forEachIndexed { target, reservation ->
            assertEquals(HeartbeatDirector.liveRoleLengths()[target], reservation.endMs - reservation.startMs)
            val source = original.clips[reservation.sourceRole]
            assertTrue(reservation.startMs >= source.sourceStartMs)
            assertTrue(reservation.endMs <= source.sourceEndMs)
        }
        assertDisjoint(reserved)
    }

    @Test fun measured_short_action_does_not_force_an_unreadable_extended_hero_finale() {
        val original = pool(HeartbeatDirector.liveRoleLengths())
        val preferred = (0 until 15).toMutableList().also { order ->
            order[4] = 11
            order[11] = 4
        }
        val reserved = HeartbeatDirector.reserveLiveRoles(original, original.clips, preferred, true, true)
        // The original independently selected hero is a legitimate compatible fallback.
        assertEquals(4, reserved[4].sourceRole)
        assertEquals(1_133L, reserved[4].endMs - reserved[4].startMs)
        assertDisjoint(reserved)
    }

    @Test fun joint_shifts_repair_trimmed_interval_conflicts_without_discarding_events() {
        val original = pool(List(15) { 1_500L }).let { graph -> graph.copy(
            clips = graph.clips.mapIndexed { index, clip -> when (index) {
                1 -> clip.copy(sourceStartMs = 2_000L, sourceEndMs = 3_500L)
                2 -> clip.copy(sourceStartMs = 2_900L, sourceEndMs = 4_400L)
                else -> clip
            } }) }
        val reserved = HeartbeatDirector.reserveLiveRoles(original, original.clips,
            (0 until 15).toList(), true, true)
        assertEquals((0 until 15).toList(), reserved.map { it.sourceRole })
        assertTrue(listOf(1, 2).any { role ->
            val source = original.clips[role]
            reserved[role].startMs != (source.sourceStartMs + source.sourceEndMs) / 2L -
                HeartbeatDirector.liveRoleLengths()[role] / 2L
        })
        assertDisjoint(reserved)
        reserved.forEach { reservation ->
            val source = original.clips[reservation.sourceRole]
            val centre = (source.sourceStartMs + source.sourceEndMs) / 2L
            assertTrue(centre in reservation.startMs until reservation.endMs)
        }
    }

    @Test fun missing_live_handles_are_material_refusal_not_a_slow_padded_graph() {
        val short = pool(List(15) { 1_000L })
        val rejection = assertThrows(MaterialRejectedException::class.java) {
            HeartbeatDirector.build(short, "audio")
        }
        assertEquals("insufficient_distinct_moments", rejection.code)
    }

    @Test fun profile_gap_control_remains_a_real_strict_evidence_comparison() {
        val original = pool(HeartbeatDirector.liveRoleLengths())
        val hero = original.clips[4]
        val centre = (hero.sourceStartMs + hero.sourceEndMs) / 2L
        val gapUs = centre / 250L * 250_000L
        val face = FrameAttachments.FaceRegion(.5f, .4f, .3f, .3f, .9f)
        val frames = (0L..original.sourceDurationMs * 1_000L step 250_000L).map { timeUs ->
            FrameAttachments(timeUs,
                mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .96f),
                subjectQuality = if (timeUs == gapUs) .53f else .8f,
                maskTemporalIou = .8f,
                faceRegion = if (timeUs == gapUs) null else face)
        }
        val shortHero = original.copy(frameAttachments = FrameAttachmentTimeline(frames),
            clips = original.clips.mapIndexed { index, clip ->
                if (index == 4) clip.copy(sourceStartMs = centre - 200L, sourceEndMs = centre + 200L)
                else clip
            })
        assertLiveAndDistinct(HeartbeatDirector.build(shortHero, "audio", allowProfileGap = true))
        val strict = assertThrows(MaterialRejectedException::class.java) {
            HeartbeatDirector.build(shortHero, "audio", allowProfileGap = false)
        }
        assertEquals("insufficient_distinct_moments", strict.code)
    }

    @Test fun interlude_tries_a_lower_ranked_free_interval_and_keeps_unknown_faces_ineligible() {
        val original = pool(List(15) { 1_500L })
        val observations = (0L until original.sourceDurationMs * 1_000L step 250_000L).map { timeUs ->
            val occupied = timeUs in 4_000_000L..6_000_000L
            val free = timeUs in 35_000_000L..37_000_000L
            VisualEventMap.Observation(timeUs,
                face = if (occupied || free) null else VisualEventMap.Face(.9f, 0f),
                faceInferenceSucceeded = true,
                visualQuality = if (occupied) 1f else if (free) .8f else .6f,
                meanLuma = .5f,
                composition = VisualEventMap.Composition(.35f, .8f, .8f, .8f, .8f))
        }
        val visual = VisualEventMap(original.sourceDurationMs * 1_000L, emptyList(), observations)
        val chosen = HeartbeatDirector.selectDistinctSubjectlessInterlude(original, visual)!!
        assertTrue(chosen.sourceStartMs >= 34_000L)
        assertTrue(original.clips.none { overlap(chosen.sourceStartMs, chosen.sourceEndMs,
            it.sourceStartMs, it.sourceEndMs) > 0L })
        val graph = HeartbeatDirector.build(original, "audio", visualMap = visual)
        assertLiveAndDistinct(graph)
        assertTrue(graph.clips[4].id.endsWith("role-15"))
        assertTrue(graph.clips[15].id.endsWith("role-15"))
        assertTrue(graph.clips[25].id.endsWith("role-4"))
        val unknown = visual.copy(observations = observations.map {
            it.copy(face = null, faceInferenceSucceeded = false)
        })
        assertNull(HeartbeatDirector.selectDistinctSubjectlessInterlude(original, unknown))
    }

    @Test fun densely_coincident_source_roles_cannot_be_hidden_by_permutation() {
        val original = pool(List(15) { 1_500L })
        val clips = original.clips.mapIndexed { index, clip ->
            // Every role shares the same source time: no permutation or 250ms shift can provide
            // all the genuinely distinct ranges. This is an adversarial planner input, not video.
            clip.copy(sourceStartMs = 1_000L + index * 10L,
                sourceEndMs = 2_500L + index * 10L)
        }
        val rejection = assertThrows(MaterialRejectedException::class.java) {
            HeartbeatDirector.build(original.copy(clips = clips), "audio")
        }
        assertEquals("insufficient_distinct_moments", rejection.code)
    }

    @Test(timeout = 2_000L) fun observed_v21_300_24_23ms_conflicts_are_repaired_by_joint_live_reservation() {
        // Intervals reconstructed from the actual v21 inspector's first-phrase source/output
        // PTS. The original pool IDs were not saved, so this is an interval regression, not a
        // claim to reconstruct the original pool selection or to approve a new actual export.
        val intervals = listOf(5802L to 6452L, 13569L to 14686L, 27073L to 27989L,
            23854L to 24904L, 11627L to 12127L, 1144L to 2111L, 17620L to 18137L,
            4902L to 5352L, 25071L to 26187L, 8127L to 8627L, 17L to 534L,
            22429L to 22829L, 26163L to 27096L, 16121L to 17138L, 11827L to 12927L)
        val base = pool(intervals.map { it.second - it.first })
        val clips = base.clips.mapIndexed { index, clip -> clip.copy(
            sourceStartMs = intervals[index].first, sourceEndMs = intervals[index].second) }
        val old = base.copy(sourceDurationMs = 27_989L, clips = clips,
            metadata = NleProjectMetadata(generator = HeartbeatMontageProfile.ID))
        assertEquals(347f / 11750f, RenderedMp4Acceptance.repeatedSourceRatio(old), .00000001f)
        val attachments = FrameAttachmentTimeline((0L..28_000_000L step 250_000L).map { timeUs ->
            FrameAttachments(timeUs, subjectQuality = .8f,
                mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .96f),
                faceRegion = FrameAttachments.FaceRegion(.5f, .4f, .3f, .3f, .9f))
        })
        assertLiveAndDistinct(HeartbeatDirector.build(old.copy(frameAttachments = attachments), "audio"))
    }

    @Test fun touching_half_open_live_reservations_are_not_an_overlap() {
        val lengths = HeartbeatDirector.liveRoleLengths()
        val original = pool(lengths)
        var cursor = 0L
        val clips = original.clips.mapIndexed { index, clip ->
            clip.copy(sourceStartMs = cursor, sourceEndMs = cursor + lengths[index])
                .also { cursor += lengths[index] }
        }
        val graph = HeartbeatDirector.build(original.copy(clips = clips), "audio")
        assertLiveAndDistinct(graph)
        assertEquals(0L, graph.clips.first().sourceStartMs)
        assertEquals(lengths[0], graph.clips[1].sourceStartMs)
    }

    @Test(timeout = 2_000L) fun temporal_union_proves_dense_ordinary_domains_impossible_without_factorial_search() {
        val original = pool(List(15) { 1_500L })
        val clips = original.clips.mapIndexed { index, clip ->
            val start = when(index) { 0 -> 0L; 4 -> 3_000L; 9 -> 6_000L; else -> 10_000L }
            clip.copy(sourceStartMs = start, sourceEndMs = start + 1_500L)
        }
        val rejection = assertThrows(MaterialRejectedException::class.java) {
            HeartbeatDirector.build(original.copy(clips = clips), "audio")
        }
        assertEquals("insufficient_distinct_moments", rejection.code)
    }

    @Test fun triple_source_multiplicity_is_counted_by_the_unchanged_aggregate_audit() {
        val original = HeartbeatDirector.build(pool(List(15) { 1_500L }), "audio")
        val repeated = original.copy(clips = original.clips.mapIndexed { index, clip ->
            if (index in 1..2) clip.copy(sourceStartMs = original.clips[0].sourceStartMs,
                sourceEndMs = original.clips[0].sourceEndMs) else clip
        })
        val total = repeated.clips.take(15).sumOf { it.sourceEndMs - it.sourceStartMs }
        // Three pairs, not one set-union duplicate: retain the actual acceptance semantics.
        assertEquals(3f * 650f / total, RenderedMp4Acceptance.repeatedSourceRatio(repeated), .0000001f)
    }

    @Test fun pairwise_four_percent_allowance_does_not_prove_the_two_percent_aggregate_rule() {
        val phraseLengths = HeartbeatMontageProfile.scenes.take(15).map { scene ->
            (scene.endUs + 500L) / 1_000L - (scene.startUs + 500L) / 1_000L
        }
        val original = pool(phraseLengths)
        var cursor = 0L
        val clips = original.clips.mapIndexed { index, clip ->
            if (index > 0) cursor -= minOf(phraseLengths[index - 1], phraseLengths[index]) * 4L / 100L
            clip.copy(sourceStartMs = cursor, sourceEndMs = cursor + phraseLengths[index])
                .also { cursor = it.sourceEndMs }
        }
        clips.forEachIndexed { index, clip -> clips.drop(index + 1).forEach { other ->
            val overlap = overlap(clip.sourceStartMs, clip.sourceEndMs, other.sourceStartMs, other.sourceEndMs)
            assertTrue(overlap.toFloat() / (clip.sourceEndMs - clip.sourceStartMs) <= .04f)
            assertTrue(overlap.toFloat() / (other.sourceEndMs - other.sourceStartMs) <= .04f)
        } }
        val old = original.copy(clips = clips,
            metadata = NleProjectMetadata(generator = HeartbeatMontageProfile.ID))
        assertTrue(RenderedMp4Acceptance.repeatedSourceRatio(old) > .02f)
        val attachments = FrameAttachmentTimeline((0L..40_000_000L step 250_000L).map { timeUs ->
            FrameAttachments(timeUs, mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .96f),
                subjectQuality = .8f, faceRegion = FrameAttachments.FaceRegion(.5f, .4f, .3f, .3f, .9f))
        })
        try {
            assertLiveAndDistinct(HeartbeatDirector.build(old.copy(frameAttachments = attachments), "audio"))
        } catch (rejection: MaterialRejectedException) {
            assertEquals("insufficient_distinct_moments", rejection.code)
        }
    }

    @Test fun shifted_light_portrait_transform_uses_the_actual_reserved_source_midpoint() {
        val original = pool(List(15) { 1_500L })
        val clips = original.clips.mapIndexed { index, clip -> when(index) {
            6 -> clip.copy(sourceStartMs = 13_650L, sourceEndMs = 14_250L)
            9 -> clip.copy(sourceStartMs = 14_000L, sourceEndMs = 14_600L)
            else -> clip
        } }
        val timeline = FrameAttachmentTimeline((0L..40_000_000L step 50_000L).map { timeUs ->
            FrameAttachments(timeUs, mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .96f),
                subjectQuality = .8f,
                faceRegion = FrameAttachments.FaceRegion(.5f, .4f,
                    if (timeUs == 14_300_000L) .35f else .15f, .25f, .9f))
        })
        val graph = HeartbeatDirector.build(original.copy(clips = clips, frameAttachments = timeline), "audio")
        val light = graph.clips[9]
        assertEquals(HeartbeatDirector.lightPortraitTransform(light, timeline), light.transform)
        assertNotEquals(HeartbeatDirector.lightPortraitTransform(clips[9], timeline), light.transform)
        assertLiveAndDistinct(graph)
    }

    private fun pool(lengths: List<Long>): MontageGraph {
        var cursor = 0L
        val clips = lengths.mapIndexed { index, length ->
            val start = cursor
            cursor += length + 600L
            MontageGraph.Clip("reservation-fixture-$index", start, start + length,
                length, MontageGraph.ShotRole.CLOSE, MontageGraph.Transition.HARD_CUT,
                MontageGraph.Motion.HOLD, .8f, lengths.take(index).sum())
        }
        return MontageGraph(40_000L, lengths.sum(), clips = clips)
    }

    private fun boundaryPool(role: Int, startMs: Long, endMs: Long, readable: Boolean): MontageGraph {
        val original = pool(List(15) { 1_500L })
        return original.copy(clips = original.clips.mapIndexed { index, clip -> when(index) {
            0 -> clip.copy(sourceStartMs = 36_000L, sourceEndMs = 37_500L)
            role -> clip.copy(sourceStartMs = startMs, sourceEndMs = endMs)
            else -> clip
        } }, frameAttachments = if (readable) readableTimeline() else FrameAttachmentTimeline())
    }

    private fun readableTimeline() = FrameAttachmentTimeline((0L..40_000_000L step 250_000L).map { timeUs ->
        FrameAttachments(timeUs, mask = FrameAttachments.Plane(1, 1, floatArrayOf(1f), .96f),
            subjectQuality = .8f, faceRegion = FrameAttachments.FaceRegion(.5f, .4f, .3f, .3f, .9f))
    })

    private fun assertUnclampedEcho(graph: MontageGraph) {
        val durationUs = graph.sourceDurationMs * 1_000L
        val echoFrames = HighQualityFramePlan.build(graph, 60).frames.filter { it.layer.heartbeatEcho }
        assertTrue(echoFrames.isNotEmpty())
        echoFrames.forEach { frame ->
            val requested = frame.sourceTimeUs + frame.layer.secondarySourceOffsetMs * 1_000L
            assertTrue("${frame.clipIndex}: $requested", requested in 0L until durationUs)
            assertEquals(requested, MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(frame, durationUs))
        }
    }

    private fun assertLiveAndDistinct(graph: MontageGraph) {
        graph.clips.take(HeartbeatMontageProfile.scenes.size).forEach { clip ->
            assertEquals(clip.outputDurationMs, clip.sourceEndMs - clip.sourceStartMs)
            assertTrue(clip.sourceStartMs >= 0L && clip.sourceEndMs <= graph.sourceDurationMs)
        }
        assertEquals(0f, RenderedMp4Acceptance.repeatedSourceRatio(graph), 0f)
        val firstPhrase = graph.clips.take(15)
        firstPhrase.forEachIndexed { index, clip ->
            firstPhrase.drop(index + 1).forEach { other ->
                assertEquals(0L, overlap(clip.sourceStartMs, clip.sourceEndMs,
                    other.sourceStartMs, other.sourceEndMs))
            }
        }
    }

    private fun assertDisjoint(reservations: List<HeartbeatDirector.LiveRoleReservation>) {
        reservations.forEachIndexed { index, reservation ->
            reservations.drop(index + 1).forEach { other ->
                assertEquals(0L, overlap(reservation.startMs, reservation.endMs, other.startMs, other.endMs))
            }
        }
    }

    private fun overlap(firstStart: Long, firstEnd: Long, secondStart: Long, secondEnd: Long): Long =
        (minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)).coerceAtLeast(0L)
}
