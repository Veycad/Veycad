package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SourceAnalysisProfileTest {
    private val editorial = SourceAnalysisProfile.EDITORIAL_SEMANTICS
    private val full = SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE
    private val unknown = listOf(VisualEventMap.Observation(125_000L),
        VisualEventMap.Observation(375_000L), VisualEventMap.Observation(625_000L))

    private fun result(profile: SourceAnalysisProfile, assessments: Int,
        observations: List<VisualEventMap.Observation> = unknown) = MediaFrameVisualAnalyzer.Result(
        profile, assessments, 2_000_000L, observations, FrameAttachmentTimeline(), 0, 0, 0)

    @Test fun independent_product_requirements_request_only_the_capabilities_they_consume() {
        assertEquals(full, SourceAnalysisProfile.requiredFor(MontageStyleCatalog.Recipe.FEAR_STROBE))
        for (recipe in listOf(MontageStyleCatalog.Recipe.SIGMA, MontageStyleCatalog.Recipe.HEARTBEAT,
            MontageStyleCatalog.Recipe.DUALITY_LOOP)) {
            assertEquals(editorial, SourceAnalysisProfile.requiredFor(recipe))
        }
        assertTrue(full.supplies(editorial))
        assertFalse(editorial.supplies(full))
    }

    @Test fun editorial_profile_never_invokes_plane_preparation_or_measurement_work() {
        for (profile in SourceAnalysisProfile.values()) {
            var planes = 0
            var assessments = 0
            repeat(unknown.size) {
                val prepared = profile.correspondence { planes++; 1 }
                val measured = profile.correspondence { assessments++; requireNotNull(prepared) }
                if (!profile.requestsCorrespondence) assertNull(measured)
            }
            assertEquals(if (profile.requestsCorrespondence) unknown.size else 0, planes)
            assertEquals(if (profile.requestsCorrespondence) unknown.size else 0, assessments)
        }
    }

    @Test fun not_requested_is_distinct_from_all_assessed_but_unknown() {
        val skipped = result(editorial, 0)
        val attempted = result(full, unknown.size)
        assertEquals(SourceAnalysisProfile.CorrespondenceState.NOT_REQUESTED, skipped.profile.correspondenceState)
        assertEquals(SourceAnalysisProfile.CorrespondenceState.ASSESSED, attempted.profile.correspondenceState)
        attempted.requireCapabilities(full)
        assertThrows(IllegalArgumentException::class.java) { skipped.requireCapabilities(full) }
        assertTrue(unknown.all { it.motionMeasurement == null && it.cameraMeasurement == null })
        val report = FearOpeningEvidence.evaluate(attempted.observations)
        assertFalse(report.supported)
        assertEquals(0, report.measuredSamples)
        assertEquals(unknown.size, report.unknownSamples)
    }

    @Test fun partial_assessment_coverage_cannot_claim_full_capability() {
        for (count in listOf(0, 1, 2, 4)) {
            assertThrows(IllegalArgumentException::class.java) { result(full, count) }
        }
    }

    @Test fun editorial_result_cannot_carry_measured_fields_or_assessment_counts() {
        val measured = VisualEventMap.Observation(375_000L,
            cameraMeasurement = VisualEventMap.CameraMeasurement(.3f, 0f, 1f, 20, 4,
                125_000L, 375_000L, 375_000L, 250_000L))
        assertThrows(IllegalArgumentException::class.java) { result(editorial, 1) }
        assertThrows(IllegalArgumentException::class.java) { result(editorial, 0, listOf(measured)) }
    }

    @Test fun source_bytes_and_cadence_cannot_make_profiles_share_a_cache_key() {
        val source = Files.createTempFile("source-profile-key", ".fixture").toFile()
        try {
            source.writeBytes(byteArrayOf(12, 34, 56))
            val editorialKey = MediaFrameAnalysisCache.key(source, 250_000L, editorial)
            val fullKey = MediaFrameAnalysisCache.key(source, 250_000L, full)
            assertNotEquals(editorialKey.fileName, fullKey.fileName)
            assertEquals(editorial, editorialKey.profile)
            assertEquals(full, fullKey.profile)
            assertTrue(editorialKey.fileName.startsWith("v18-${editorial.cacheToken}-250000-"))
            assertTrue(fullKey.fileName.startsWith("v18-${full.cacheToken}-250000-"))
            assertNotEquals(fullKey.fileName, MediaFrameAnalysisCache.key(source, 500_000L, full).fileName)
        } finally { source.delete() }
    }

    @Test fun stable_tokens_round_trip_without_using_enum_ordinals() {
        assertEquals("editorial-semantics-v1", editorial.cacheToken)
        assertEquals("editorial-correspondence-v1", full.cacheToken)
        for (profile in SourceAnalysisProfile.values()) {
            assertEquals(profile, SourceAnalysisProfile.fromCacheToken(profile.cacheToken))
        }
        assertThrows(IllegalArgumentException::class.java) { SourceAnalysisProfile.fromCacheToken("0") }
    }

    @Test fun cache_header_mismatch_cannot_be_fixed_by_renaming_an_editorial_cache() {
        withCache(result(editorial, 0)) { file ->
            assertEquals(result(editorial, 0), MediaFrameAnalysisCache.read(file, editorial))
            assertThrows(IllegalArgumentException::class.java) { MediaFrameAnalysisCache.read(file, full) }
        }
    }

    @Test fun all_unknown_full_cache_keeps_explicit_capability_and_does_not_infer_success() {
        withCache(result(full, unknown.size)) { file ->
            val restored = MediaFrameAnalysisCache.read(file, full)
            assertEquals(result(full, unknown.size), restored)
            restored.requireCapabilities(full)
            assertFalse(FearOpeningEvidence.evaluate(restored.observations).supported)
            assertThrows(IllegalArgumentException::class.java) { MediaFrameAnalysisCache.read(file, editorial) }
        }
    }

    @Test fun corrupted_completed_count_cannot_turn_skipped_work_into_full_capability() {
        withCache(result(full, unknown.size)) { file ->
            val payload = GZIPInputStream(file.inputStream()).use { it.readBytes() }
            // Header: magic/version, Java modified-UTF ASCII token, assessment count.
            ByteBuffer.wrap(payload).putInt(8 + 2 + full.cacheToken.length, 0)
            GZIPOutputStream(file.outputStream()).use { it.write(payload) }
            assertThrows(IllegalArgumentException::class.java) { MediaFrameAnalysisCache.read(file, full) }
        }
    }

    @Test fun trailing_cache_payload_is_rejected() {
        withCache(result(editorial, 0)) { file ->
            val payload = GZIPInputStream(file.inputStream()).use { it.readBytes() }
            GZIPOutputStream(file.outputStream()).use { it.write(payload); it.write(1) }
            assertThrows(IllegalArgumentException::class.java) { MediaFrameAnalysisCache.read(file, editorial) }
        }
    }

    @Test fun removing_only_typed_measurements_keeps_editorial_events_and_all_non_fear_graphs() {
        val first = sourceObservations(false)
        val second = sourceObservations(true)
        fun map(values: List<VisualEventMap.Observation>) = VisualEventMapAnalyzer.analyze(30_000_000L, values)
        fun editorialOnly(values: List<VisualEventMap.Observation>) = values.map {
            it.copy(motionMeasurement = null, cameraMeasurement = null)
        }
        val fullFirst = map(first)
        val fullSecond = map(second)
        val editorialFirst = map(editorialOnly(first))
        val editorialSecond = map(editorialOnly(second))
        assertEquals(fullFirst.events, editorialFirst.events)
        assertEquals(fullSecond.events, editorialSecond.events)
        assertEquals(editorialFirst.observations, fullFirst.observations.map {
            it.copy(motionMeasurement = null, cameraMeasurement = null)
        })
        val mask = FrameAttachments.Plane(2, 2, floatArrayOf(0f, 1f, 0f, 1f), .95f)
        val attachments = FrameAttachmentTimeline(first.map {
            FrameAttachments(it.sourceTimeUs, mask = mask, depth = mask,
                flow = FrameAttachments.FlowPlane(1, 1, listOf(.25f, -.25f), .8f),
                faceRegion = FrameAttachments.FaceRegion(.5f, .3f, .2f, .2f, .9f))
        })
        val fullHeartbeatPool = HeartbeatSourcePool.select(fullFirst, attachments)
        val editorialHeartbeatPool = HeartbeatSourcePool.select(editorialFirst, attachments)
        assertEquals(fullHeartbeatPool, editorialHeartbeatPool)
        assertEquals(HeartbeatDirector.build(fullHeartbeatPool, "author-score", visualMap = fullFirst),
            HeartbeatDirector.build(editorialHeartbeatPool, "author-score", visualMap = editorialFirst))
        assertEquals(DualityLoopDirector.build(fullFirst, fullSecond, "author-score"),
            DualityLoopDirector.build(editorialFirst, editorialSecond, "author-score"))
        val rate = 48_000
        val beats = (0 until 36).map {
            AudioBeatMap.Beat(it * 24_000L, .9f, AudioBeatMap.FrequencyBand.LOW, it % 4 == 0)
        }
        val audio = AudioBeatMap(rate, 18L * rate, 120f, beats, beats.map {
            AudioBeatMap.Onset(it.sampleIndex, it.strength, it.dominantBand)
        })
        fun sigma(visual: VisualEventMap) = EventMatchingDirector.direct(EventMatchingDirector.Request(
            "source", "author-score", ReferenceMontageProfile.OUTPUT_DURATION_MS,
            audio.loopedTo(ReferenceMontageProfile.OUTPUT_DURATION_MS * 48L), visual,
            attachments, ReferenceMontageProfile.ID), EventMatchingDirector.Style.DYNAMIC)
        assertEquals(sigma(fullFirst), sigma(editorialFirst))
    }

    private fun sourceObservations(kinetic: Boolean): List<VisualEventMap.Observation> = (0 until 120).map { index ->
        val time = 125_000L + index * 250_000L
        val motion = (if (kinetic) .35f else .03f) + index % 9 * .035f
        VisualEventMap.Observation(time,
            cameraMotion = VisualEventMap.Vector(motion, 0f),
            subjectMotion = VisualEventMap.Vector(0f, motion * .5f),
            face = VisualEventMap.Face(.9f, (index % 11 - 5) * 5f),
            gestureConfidence = index % 7 * .12f, gestureEvidenceAvailable = true,
            personMaskConfidence = .95f, personMaskTemporalIou = .9f,
            visualQuality = .68f + index % 5 * .05f,
            meanLuma = .2f + index % 9 * .055f,
            composition = VisualEventMap.Composition(.2f + index % 8 * .09f, .8f, .8f, .8f, .8f),
            humanPresenceConfidence = .9f,
            motionMeasurement = if (index == 0 || index % 3 == 0) null else
                VisualEventMap.MotionMeasurement(.8f, .4f, 0f, 1f, 12, .8f, 40, 4, 250_000L),
            cameraMeasurement = if (index == 0) null else VisualEventMap.CameraMeasurement(
                .4f, 0f, 1f, 40, 4, time - 250_000L, time, time, 250_000L))
    }

    private fun withCache(value: MediaFrameVisualAnalyzer.Result, inspect: (File) -> Unit) {
        val file = Files.createTempFile("source-profile-cache", ".gz").toFile()
        try { MediaFrameAnalysisCache.write(value, file); inspect(file) }
        finally { file.delete() }
    }
}
