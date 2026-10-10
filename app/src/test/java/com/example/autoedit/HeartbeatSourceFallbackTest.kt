package com.veycad.app

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Pure measured-domain/selection tests, not decoded-source or human acceptance evidence. */
class HeartbeatSourceFallbackTest {
    @Test fun preferred_success_returns_exact_pool_and_graph_without_generating_alternates() {
        val visual = visual()
        val preferred = HeartbeatSourcePool.select(visual, FrameAttachmentTimeline())
        val expected = preferred.copy(metadata = NleProjectMetadata(generator = "callback-result"))
        var calls = 0
        val result = HeartbeatSourcePool.direct(visual, FrameAttachmentTimeline(), "audio", director = {
            calls++
            assertEquals(preferred, it)
            expected
        })
        assertEquals(preferred, result.pool)
        assertSame(expected, result.graph)
        assertEquals(1, calls)
        assertEquals("preferred-pool", result.trace.method)
        assertEquals(0, result.trace.generatedCandidates)
        assertEquals(0L, result.trace.candidateVisits)
        assertEquals(1, result.trace.directorValidations)
        assertFalse(result.trace.finiteDomainExhausted)
    }

    @Test fun actual_233_267_ms_cadence_has_two_measured_samples_in_short_pair_windows() {
        val times = listOf(133_333L, 400_000L, 633_333L, 900_000L, 1_133_333L)
        val visual = VisualEventMap(1_700_000L, emptyList(), times.map { observation(it) })
        for (length in listOf(400L, 450L)) {
            val windows = HeartbeatSourcePool.measuredFallbackCandidates(visual, length)
            assertTrue(windows.any { it.sourceStartMs > 0L && it.sourceEndMs < 1_700L })
            windows.forEach { window ->
                assertEquals(length, window.sourceEndMs - window.sourceStartMs)
                assertTrue(times.count { it / 1_000L in window.sourceStartMs until window.sourceEndMs } >= 2)
            }
            val anchor = times[2] / 1_000L - length / 2L
            assertEquals(1, times.count { it / 1_000L in anchor until anchor + length })
        }
        val pair = HeartbeatSourcePool.measuredFallbackCandidates(visual, 450L)
            .single { it.sourceStartMs == 291L }
        assertEquals(741L, pair.sourceEndMs)
    }

    @Test fun nominal_pair_anchor_does_not_invent_coverage_across_a_large_real_gap() {
        val visual = VisualEventMap(2_000_000L, emptyList(),
            listOf(133_333L, 900_000L, 1_633_333L).map { observation(it) })
        assertTrue(HeartbeatSourcePool.measuredFallbackCandidates(visual, 450L).isEmpty())
    }

    @Test fun clamped_windows_keep_half_open_actual_sample_membership() {
        val visual = VisualEventMap(1_000_000L, emptyList(),
            listOf(0L, 450_000L, 999_999L).map { observation(it) })
        val windows = HeartbeatSourcePool.measuredFallbackCandidates(visual, 450L)
        assertTrue(windows.none { it.sourceStartMs == 0L }) // sample at450ms is excluded
        windows.forEach { window ->
            assertTrue(visual.observations.count {
                it.sourceTimeUs / 1_000L in window.sourceStartMs until window.sourceEndMs
            } >= 2)
        }
    }

    @Test fun all_fifteen_use_superset_keeps_non_echo_boundary_without_blanket_margin() {
        val short = clip(0L, 450L)
        val noEvidence = FrameAttachmentTimeline()
        assertFalse(HeartbeatDirector.hasAnyLiveUse(30_000L, noEvidence, short))
        assertTrue(HeartbeatDirector.hasAnyLiveUse(30_000L, noEvidence, short, echoOffsetMs = 0L))
        // Longer boundary material can legitimately fill a no-echo role; do not premultiply a
        // nominal source role's temporal offset into every candidate's viability filter.
        assertTrue(HeartbeatDirector.hasAnyLiveUse(30_000L, noEvidence, clip(0L, 650L)))
    }

    @Test fun viability_preserves_unknown_and_occluded_extension_refusal_and_cancellation() {
        val unknown = timeline(face = false, occlusion = 0f)
        val occluded = timeline(face = true, occlusion = .64f)
        assertFalse(HeartbeatDirector.hasAnyLiveUse(30_000L, unknown, clip(0L, 450L)))
        assertFalse(HeartbeatDirector.hasAnyLiveUse(30_000L, occluded, clip(0L, 450L)))
        assertTrue(HeartbeatDirector.hasAnyLiveUse(30_000L, timeline(), clip(0L, 450L)))
        assertFalse(HeartbeatDirector.hasAnyLiveUse(30_000L, unknown, clip(0L, 450L),
            allowShiftedHandles = false, allowProfileGap = false))
        assertThrows(CancellationException::class.java) {
            HeartbeatDirector.hasAnyLiveUse(30_000L, timeline(), clip(0L, 450L),
                checkCancelled = { throw CancellationException("stop") })
        }
    }

    @Test fun full_search_can_replace_multiple_selected_windows_and_is_deterministic() {
        val visual = visual()
        val original = HeartbeatSourcePool.select(visual, FrameAttachmentTimeline())
        fun changed(pool: MontageGraph) = pool.clips.indices.count { index ->
            pool.clips[index].sourceStartMs != original.clips[index].sourceStartMs ||
                pool.clips[index].sourceEndMs != original.clips[index].sourceEndMs
        }
        fun run() = HeartbeatSourcePool.direct(visual, FrameAttachmentTimeline(), "audio", director = { pool ->
            if (changed(pool) < 2) throw MaterialRejectedException("insufficient_distinct_moments", "fixture requires two substitutions")
            pool
        })
        val first = run()
        val second = run()
        assertTrue(changed(first.pool) >= 2)
        assertSame(first.pool, first.graph)
        assertEquals(first.pool, second.pool)
        assertEquals(first.trace, second.trace)
        assertEquals("measured-pair-joint-v1", first.trace.method)
        assertTrue(first.trace.generatedCandidates > 0)
        assertDistinct(first.pool)
    }

    @Test fun callback_other_rejections_and_programming_errors_are_not_retried() {
        val visual = visual()
        var calls = 0
        val other = MaterialRejectedException("insufficient_motion", "other")
        val received = assertThrows(MaterialRejectedException::class.java) {
            HeartbeatSourcePool.direct(visual, FrameAttachmentTimeline(), "audio", director = {
                calls++; throw other
            })
        }
        assertSame(other, received)
        assertEquals(1, calls)
        val bug = IllegalStateException("callback bug")
        assertSame(bug, assertThrows(IllegalStateException::class.java) {
            HeartbeatSourcePool.direct(visual, FrameAttachmentTimeline(), "audio", director = { throw bug })
        })
    }

    @Test fun exhausted_first_rank_prefix_is_not_misreported_as_finite_domain_failure() {
        val visual = visual()
        val original = HeartbeatSourcePool.select(visual, FrameAttachmentTimeline())
        var calls = 0
        val result = HeartbeatSourcePool.direct(visual, FrameAttachmentTimeline(), "audio",
            limits = HeartbeatSourcePool.SearchLimits(maxDirectorValidations = 2), director = { pool ->
                calls++
                if (calls == 1) throw MaterialRejectedException("insufficient_distinct_moments", "preferred fixture")
                assertNotEquals(original.clips.map { it.sourceStartMs to it.sourceEndMs },
                    pool.clips.map { it.sourceStartMs to it.sourceEndMs })
                pool
            })
        assertEquals(2, calls)
        assertEquals(2, result.trace.directorValidations)
        assertFalse(result.trace.finiteDomainExhausted)
        assertDistinct(result.pool)
    }

    @Test fun fallback_full_director_retains_authored_clocks_and_unclamped_echo_for_both_signs() {
        val visual = visual().let { map -> map.copy(observations = map.observations.mapIndexed { index, observation ->
            observation.copy(visualQuality = .68f + (index % 5) * .05f,
                meanLuma = .20f + (index % 9) * .055f,
                composition = VisualEventMap.Composition(.20f + (index % 8) * .09f, .8f, .8f, .8f, .8f))
        }) }
        val evidence = timeline(endUs = 30_000_000L)
        for (offset in listOf(-67L, 67L)) {
            var calls = 0
            val result = HeartbeatSourcePool.direct(visual, evidence, "audio", echoOffsetMs = offset,
                director = { pool ->
                    if (++calls == 1) throw MaterialRejectedException("insufficient_distinct_moments", "exercise fallback only")
                    HeartbeatDirector.build(pool, "audio", offset, visualMap = visual)
                })
            val graph = result.graph
            assertEquals(27, graph.clips.size)
            assertEquals(21_166L, graph.outputDurationMs)
            assertEquals(0f, RenderedMp4Acceptance.repeatedSourceRatio(graph), 0f)
            graph.clips.take(26).forEach {
                assertEquals(it.outputDurationMs, it.sourceEndMs - it.sourceStartMs)
            }
            val frames = HighQualityFramePlan.build(graph, 60).frames
            assertEquals(1_270, frames.size)
            frames.filter { it.layer.heartbeatEcho }.forEach { frame ->
                val requested = frame.sourceTimeUs + frame.layer.secondarySourceOffsetMs * 1_000L
                assertTrue(requested >= 0L && requested < graph.sourceDurationMs * 1_000L)
                assertEquals(requested, MediaCodecSpeedRampRenderer.temporalLayerSourceTimeUs(frame,
                    graph.sourceDurationMs * 1_000L))
            }
            assertTrue(calls >= 2)
            assertEquals("measured-pair-joint-v1", result.trace.method)
        }
    }

    @Test fun preferred_generation_is_cooperatively_cancellable() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            HeartbeatSourcePool.direct(visual(), FrameAttachmentTimeline(), "audio", checkCancelled = {
                if (++checks == 20) throw CancellationException("during generation")
            }, director = { throw AssertionError("must not validate after cancellation") })
        }
        assertEquals(20, checks)
    }

    @Test fun callback_budget_is_non_material_and_reports_configured_limits() {
        val error = assertThrows(HeartbeatCandidateSearchIncompleteException::class.java) {
            HeartbeatSourcePool.direct(visual(), FrameAttachmentTimeline(), "audio",
                limits = HeartbeatSourcePool.SearchLimits(maxDirectorValidations = 1), director = {
                    throw MaterialRejectedException("insufficient_distinct_moments", "fixture")
                })
        }
        assertEquals(1, error.trace.directorValidations)
        assertEquals(1, error.trace.maxDirectorValidations)
        assertFalse(error.trace.finiteDomainExhausted)
        assertTrue(error.message!!.contains("1/1"))
        assertFalse((error as Throwable) is MaterialRejectedException)
    }

    @Test(timeout = 20_000) fun four_thousand_observations_use_bounded_indexed_fallback_and_visits() {
        val visual = visual(4_000, 250_000L)
        val error = assertThrows(HeartbeatCandidateSearchIncompleteException::class.java) {
            HeartbeatSourcePool.direct(visual, FrameAttachmentTimeline(), "audio",
                limits = HeartbeatSourcePool.SearchLimits(maxCandidateVisits = 1_000L), director = {
                    throw MaterialRejectedException("insufficient_distinct_moments", "stress fixture")
                })
        }
        assertEquals(1_000L, error.trace.candidateVisits)
        assertEquals(1_000L, error.trace.maxCandidateVisits)
        assertTrue(error.trace.generatedCandidates <= 15 * (2 * 4_000 + 1) + 15)
        assertTrue(error.trace.candidateCount <= 15 * (2 * 4_000 + 1) + 15)
        assertEquals(1, error.trace.directorValidations)
        assertFalse(error.trace.finiteDomainExhausted)
    }

    @Test fun genuine_finite_empty_domain_retains_typed_scoped_exhaustion() {
        val visual = VisualEventMap(30_000_000L, emptyList(),
            listOf(observation(0L), observation(29_900_000L)))
        val error = assertThrows(HeartbeatCandidateDomainExhaustedException::class.java) {
            HeartbeatSourcePool.direct(visual, FrameAttachmentTimeline(), "audio")
        }
        assertEquals("insufficient_distinct_moments", error.code)
        assertTrue(error.trace.finiteDomainExhausted)
        assertEquals(0, error.trace.candidateCount)
    }

    private fun visual(count: Int = 300, stepUs: Long = 100_000L) =
        VisualEventMap(count * stepUs, emptyList(), (0 until count).map { observation(it * stepUs) })

    private fun observation(timeUs: Long) = VisualEventMap.Observation(timeUs,
        face = VisualEventMap.Face(.9f, 0f), visualQuality = .9f, meanLuma = .5f,
        composition = VisualEventMap.Composition(.5f, .9f, .9f, .9f, .9f),
        humanPresenceConfidence = .9f)

    private fun clip(start: Long, end: Long) = MontageGraph.Clip("window", start, end, end - start,
        MontageGraph.ShotRole.DETAIL, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0L)

    private fun timeline(face: Boolean = true, occlusion: Float = 0f, endUs: Long = 1_500_000L) = FrameAttachmentTimeline(
        (0L..endUs step 100_000L).map { time -> FrameAttachments(time,
            mask = FrameAttachments.Plane(1, 1, listOf(1f), .95f), subjectQuality = .9f,
            subjectOcclusion = occlusion, maskTemporalIou = .9f,
            faceRegion = if (face) FrameAttachments.FaceRegion(.5f, .4f, .2f, .2f, .9f) else null) })

    private fun assertDistinct(pool: MontageGraph) {
        pool.clips.forEachIndexed { index, clip ->
            assertEquals(clip.outputDurationMs, clip.sourceEndMs - clip.sourceStartMs)
            pool.clips.drop(index + 1).forEach { other ->
                assertTrue(clip.sourceEndMs <= other.sourceStartMs || other.sourceEndMs <= clip.sourceStartMs)
            }
        }
    }
}
