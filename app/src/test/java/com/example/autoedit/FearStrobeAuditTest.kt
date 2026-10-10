package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FearStrobeAuditTest {
    @Test fun codec_one_microsecond_floor_is_not_an_unexpected_black_frame() {
        val pulse = FearStrobeProfile.shutterPulses[1]
        val report = FearStrobeAudit.evaluate(listOf(sample(pulse.startUs - 1L, 0f)))
        assertTrue(report.unexpectedBlackFramesUs.isEmpty())
        assertEquals(1, report.shutterFramesMeasured)
        assertEquals(1, report.shutterFramesMatched)
        assertFalse(report.missingPulseIndices.contains(1))
    }

    @Test fun black_outside_every_authored_interval_is_reported() {
        val report = FearStrobeAudit.evaluate(listOf(sample(9_000_000L, 0f)))
        assertEquals(listOf(9_000_000L), report.unexpectedBlackFramesUs)
    }

    @Test fun complete_shutter_and_finale_evidence_fails_when_one_required_interval_is_missing_or_wrong() {
        // Frame indices come from the authored WW/B/WW reference, independently of the
        // production pulse list. Positive samples alone must never prove absent intervals.
        val shutterTimes = ((300..328 step 2).toList() + (476..504 step 2).toList())
            .map { (it * 1_000_000L + 15L) / 30L }
        val samples = shutterTimes.map { sample(it, 0f) } + listOf(
            sample(16_833_333L, 1f), sample(16_900_000L, 0f),
            sample(16_933_333L, 1f), sample(17_000_000L, 0f))
        val good = FearStrobeAudit.evaluate(samples)
        assertEquals(30, good.shutterFramesMeasured)
        assertEquals(30, good.shutterFramesMatched)
        assertTrue(good.finaleMatched)
        assertTrue(good.missingPulseIndices.isEmpty())
        assertTrue(good.wrongLumaIndices.isEmpty())
        assertTrue(good.unexpectedBlackFramesUs.isEmpty())

        val graph = MontageGraph(18_300L, 18_300L, clips = listOf(
            MontageGraph.Clip("fear-test", 0L, 18_300L, 18_300L,
                MontageGraph.ShotRole.ACTION, MontageGraph.Transition.OPEN,
                MontageGraph.Motion.HOLD, 1f, 0L)
        ), metadata = NleProjectMetadata(generator = FearStrobeProfile.ID))
        val audio = AudioBeatMap(48_000, 48_000L * 19L, null, emptyList(), emptyList())
        val container = RenderedMp4Acceptance.ContainerSample(
            18_300_000L, 18_300_000L, "video/avc", "audio/mp4a-latm")
        val reference = RenderedMp4Acceptance.ReferenceMontageCard(maximumColourJump = 1f)
        fun nativeReport(evidence: List<RenderedMp4Acceptance.VisualSample>) =
            RenderedMp4Acceptance.evaluate(graph, audio, container, evidence, reference)
        assertTrue(nativeReport(samples).issues.toString(), nativeReport(samples).accepted)

        for (index in samples.indices) {
            val missing = FearStrobeAudit.evaluate(samples.filterIndexed { i, _ -> i != index })
            assertEquals("missing pulse $index", listOf(index), missing.missingPulseIndices)
            val wrong = FearStrobeAudit.evaluate(samples.mapIndexed { i, frame ->
                if (i == index) frame.copy(luma = .5f) else frame
            })
            assertEquals("wrong pulse $index", listOf(index), wrong.wrongLumaIndices)
            val missingNative = nativeReport(samples.filterIndexed { i, _ -> i != index })
            assertFalse("missing pulse $index", missingNative.accepted)
            assertTrue(missingNative.issues.toString(), missingNative.issues.any {
                it.startsWith("fear-pulse-mismatch:missing=$index:")
            })
            val wrongNative = nativeReport(samples.mapIndexed { i, frame ->
                if (i == index) frame.copy(luma = .5f) else frame
            })
            assertFalse("wrong pulse $index", wrongNative.accepted)
            assertTrue(wrongNative.issues.toString(), wrongNative.issues.any {
                it.endsWith(":wrong=$index") && it.startsWith("fear-pulse-mismatch:")
            })
            if (index in 30..32) {
                assertFalse(missing.finaleMatched)
                assertFalse(wrong.finaleMatched)
            }
        }
    }

    private fun sample(timeUs: Long, luma: Float) = RenderedMp4Acceptance.VisualSample(
        outputTimeUs = timeUs,
        transitionStrength = 0f,
        luma = luma,
        chromaU = .5f,
        chromaV = .5f,
        artifactScore = 0f,
        faceExpected = false,
        faceConfidence = 0f
    )
}
