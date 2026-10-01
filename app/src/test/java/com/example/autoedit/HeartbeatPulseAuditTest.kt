package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class HeartbeatPulseAuditTest {
    @Test fun current_profile_tail_measures_every_end_exclusive_frame_and_terminal_white_boundary() {
        val report = HeartbeatPulseAudit.evaluateTail(tailSamples())
        assertTrue(report.matched)
        assertEquals(82, report.expectedFrames)
        assertEquals(82, report.measuredFrames)
        assertEquals(82, report.blackFrames)
        assertEquals(2, report.boundaryMeasuredFrames)
        assertTrue(report.unexpectedTimesUs.isEmpty())
        assertEquals(19_800_000L, report.firstBlackUs)
        assertEquals(0L, report.startOffsetUs)
        assertEquals(21_150_000L, tailSamples().last().outputTimeUs)
        assertTrue(tailSamples().all { it.outputTimeUs < 21_166_000L })
    }

    @Test fun tail_start_has_one_frame_tolerance_not_one_frame_missing_coverage() {
        for (offset in listOf(-16_667L, 0L, 16_667L)) {
            assertTrue("offset=$offset", HeartbeatPulseAudit.evaluateTail(tailSamples(offset)).matched)
        }
        val floored = tailSamples().map { it.copy(outputTimeUs = it.outputTimeUs - 1) }
        assertTrue(HeartbeatPulseAudit.evaluateTail(floored).matched)
        assertEquals(-1L, HeartbeatPulseAudit.evaluateTail(floored).startOffsetUs)
        for (index in listOf(0, 1, 2, 15, tailSamples().lastIndex)) {
            val missing = tailSamples().filterIndexed { sampleIndex, _ -> sampleIndex != index }
            val report = HeartbeatPulseAudit.evaluateTail(missing)
            assertFalse("missing index=$index", report.matched)
            assertEquals(listOf(tailSamples()[index].outputTimeUs), report.missingTimesUs)
        }
    }

    @Test fun missing_bright_early_and_late_tail_never_pass_matching_pulse_windows() {
        val pulses = HeartbeatMontageProfile.pulses.map {
            sample(it.startUs - 1, if (it.kind == HeartbeatMontageProfile.PulseKind.WHITE) 1f else 0f)
        }
        val pulseOnly = HeartbeatPulseAudit.evaluate(pulses)
        assertEquals(26, pulseOnly.matched)
        assertFalse(pulseOnly.tail.matched)
        val bright = tailSamples().map { if (it.outputTimeUs >= 19_800_000L) it.copy(luma = .5f) else it }
        assertFalse(HeartbeatPulseAudit.evaluateTail(bright).matched)
        for (offset in listOf(-33_333L, 33_333L)) {
            assertFalse("offset=$offset", HeartbeatPulseAudit.evaluateTail(tailSamples(offset)).matched)
        }
        val late = HeartbeatPulseAudit.evaluateTail(tailSamples(33_333L))
        assertTrue(late.brightTimesUs.contains(19_816_667L))
        val brightHole = tailSamples().mapIndexed { index, frame ->
            if (index == 40) frame.copy(luma = .2f) else frame
        }
        assertFalse(HeartbeatPulseAudit.evaluateTail(brightHole).matched)
        val earlyWithHole = tailSamples(-16_667L).map {
            if (it.outputTimeUs == 19_800_000L) it.copy(luma = 1f) else it
        }
        assertFalse(HeartbeatPulseAudit.evaluateTail(earlyWithHole).matched)
        val missingAtFinal = tailSamples().dropLast(1) + sample(21_166_667L, 0f)
        assertFalse(HeartbeatPulseAudit.evaluateTail(missingAtFinal).matched)
    }

    @Test fun off_clock_and_duplicate_decoded_evidence_are_unknown_not_coverage() {
        val twoMicrosecondsLate = tailSamples().map { it.copy(outputTimeUs = it.outputTimeUs + 2) }
        val offClock = HeartbeatPulseAudit.evaluateTail(twoMicrosecondsLate)
        assertFalse(offClock.matched)
        assertEquals(twoMicrosecondsLate.map { it.outputTimeUs }, offClock.unexpectedTimesUs)
        val duplicate = tailSamples() + tailSamples()[10]
        val duplicated = HeartbeatPulseAudit.evaluateTail(duplicate)
        assertFalse(duplicated.matched)
        assertEquals(listOf(tailSamples()[10].outputTimeUs, tailSamples()[10].outputTimeUs),
            duplicated.unexpectedTimesUs)
    }

    @Test fun every_extra_tail_image_fails_even_when_nominal_82_plus_2_slots_are_complete() {
        for (offset in listOf(-2L, 2L)) for (luma in listOf(0f, 1f)) {
            val extraUs = 20_000_000L + offset
            val report = HeartbeatPulseAudit.evaluateTail(tailSamples() + sample(extraUs, luma))
            assertFalse("extra PTS=$extraUs luma=$luma", report.matched)
            assertEquals(82, report.measuredFrames)
            assertEquals(2, report.boundaryMeasuredFrames)
            assertTrue(report.missingTimesUs.isEmpty())
            assertEquals(listOf(extraUs), report.unexpectedTimesUs)
            assertEquals(if (luma > .05f) listOf(extraUs) else emptyList<Long>(), report.brightTimesUs)
        }
        for (offset in listOf(-1L, 1L)) for (luma in listOf(0f, 1f)) {
            val extraUs = 20_000_000L + offset
            val report = HeartbeatPulseAudit.evaluateTail(tailSamples() + sample(extraUs, luma))
            assertFalse("duplicate slot PTS=$extraUs luma=$luma", report.matched)
            assertEquals(listOf(20_000_000L), report.missingTimesUs)
            assertEquals(listOf(extraUs, 20_000_000L).sorted(), report.unexpectedTimesUs)
        }
        val afterFinalUs = 21_166_667L
        val afterFinal = HeartbeatPulseAudit.evaluateTail(tailSamples() + sample(afterFinalUs, 0f))
        assertFalse(afterFinal.matched)
        assertEquals(listOf(afterFinalUs), afterFinal.unexpectedTimesUs)
    }

    @Test fun sampler_keeps_every_actual_tail_image_after_advancing_or_exhausting_targets() {
        val boundaryStartUs = HeartbeatPulseAudit.tailSamplingTargetsUs().first() - 1L
        assertFalse(RenderedVisualSampler.shouldMeasureDecodedFrame(boundaryStartUs - 1L,
            boundaryStartUs + 1L, boundaryStartUs))
        assertTrue(RenderedVisualSampler.shouldMeasureDecodedFrame(boundaryStartUs,
            boundaryStartUs + 1L, boundaryStartUs))
        for (extraUs in listOf(20_000_001L, 20_000_002L)) {
            assertTrue(RenderedVisualSampler.shouldMeasureDecodedFrame(extraUs, 20_016_667L,
                boundaryStartUs))
            assertFalse(RenderedVisualSampler.shouldMeasureDecodedFrame(extraUs, 20_016_667L, null))
        }
        assertTrue(RenderedVisualSampler.shouldMeasureDecodedFrame(21_166_667L, null, boundaryStartUs))
        assertFalse(RenderedVisualSampler.shouldMeasureDecodedFrame(21_166_667L, null, null))
    }

    @Test fun sampler_requests_only_tail_and_adjacent_white_frames_at_exact60fps() {
        val graph = MontageGraph(22_000L, 21_166L, clips = listOf(
            MontageGraph.Clip("whole", 0, 21_166, 21_166, MontageGraph.ShotRole.FINALE,
                MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0)),
            metadata = NleProjectMetadata(generator = "${HeartbeatMontageProfile.ID}:test"))
        val targets = RenderedVisualSampler.samplingTargets(graph, 100_000L)
        assertEquals(tailTimesUs, HeartbeatPulseAudit.tailSamplingTargetsUs())
        assertTrue(targets.containsAll(tailTimesUs))
        assertFalse(targets.contains(21_166_667L))
        assertTrue(targets.size < 400) // Not a full1270-frame source-vision pass.
    }

    // Independent 60fps reference clock: two white boundary images followed by 82 black
    // images. Do not obtain the fixture from the production scheduler being audited.
    private val tailTimesUs = List(84) { index ->
        19_766_667L + index / 3 * 50_000L + listOf(0L, 16_666L, 33_333L)[index % 3]
    }

    private fun tailSamples(startOffsetUs: Long = 0L) = tailTimesUs.map {
        sample(it, if (it >= 19_800_000L + startOffsetUs) 0f else 1f)
    }

    @Test fun decoder_rounding_does_not_skip_a_single_frame_pulse() {
        val target = 1_766_667L
        assertTrue(RenderedVisualSampler.reachedSampleTarget(target-1,target))
        assertTrue(RenderedVisualSampler.reachedSampleTarget(target,target))
        assertFalse(RenderedVisualSampler.reachedSampleTarget(target-2,target))
        assertFalse(RenderedVisualSampler.reachedSampleTarget(target-16_667,target))
        assertTrue(RenderedVisualSampler.reachedSampleTarget(0,0))
        assertFalse(RenderedVisualSampler.reachedSampleTarget(0,Long.MAX_VALUE))
        val report = HeartbeatPulseAudit.evaluate(listOf(sample(target-1,1f)))
        assertEquals(1,report.matched)
    }
    private fun sample(t: Long,luma: Float) = RenderedMp4Acceptance.VisualSample(t,0f,luma,.5f,.5f,0f,false,0f)
    @Test fun missing_evidence_and_wrong_colour_never_pass() {
        assertEquals(26,HeartbeatPulseAudit.evaluate(emptyList()).missing.size)
        val wrong = HeartbeatPulseAudit.evaluate(listOf(sample(650_000,.1f)))
        assertEquals(listOf(1),wrong.wrongLuma)
        assertEquals(0,wrong.matched)
    }
    @Test fun all_measured_pulses_match_only_their_own_windows() {
        val samples = HeartbeatMontageProfile.pulses.map {
            sample(it.startUs-1,if(it.kind == HeartbeatMontageProfile.PulseKind.WHITE)1f else 0f)
        }
        assertEquals(26,HeartbeatPulseAudit.evaluate(samples).matched)
        val after = HeartbeatMontageProfile.pulses.map { sample(it.endUs, .5f) }
        assertEquals(0,HeartbeatPulseAudit.evaluate(after).matched)
    }
}
