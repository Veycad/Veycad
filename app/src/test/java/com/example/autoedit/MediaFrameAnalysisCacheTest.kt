package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class MediaFrameAnalysisCacheTest {
    @Test fun cache_round_trip_retains_count_and_inference_failure() {
        val observations = listOf(0, 1, 2, null).mapIndexed { i, count ->
            VisualEventMap.Observation(i * 250_000L, faceInferenceSucceeded = count != null,
                detectedFaceCount = count)
        }
        val frames = observations.map { FrameAttachments(it.sourceTimeUs,
            FrameAttachments.Plane(1, 1, floatArrayOf(.7f), .9f),
            detectedFaceCount = it.detectedFaceCount, faceInferenceSucceeded = it.faceInferenceSucceeded) }
        val result = MediaFrameVisualAnalyzer.Result(SourceAnalysisProfile.EDITORIAL_SEMANTICS,
            0, 2_000_000, observations, FrameAttachmentTimeline(frames), 4, 4, 0)
        val file = Files.createTempFile("analysis-face-count", ".gz").toFile()
        try {
            MediaFrameAnalysisCache.write(result, file)
            val restored = MediaFrameAnalysisCache.read(file, result.profile)
            assertEquals(listOf(0, 1, 2, null), restored.observations.map { it.detectedFaceCount })
            assertEquals(listOf(true, true, true, false), restored.observations.map { it.faceInferenceSucceeded })
            assertEquals(result, restored)
        } finally { file.delete() }
    }
    @Test fun incompatible_versions_cannot_supply_current_evidence_even_with_a_complete_payload() {
        val current = MediaFrameVisualAnalyzer.Result(
            SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE, 1, 2_000_000L,
            listOf(VisualEventMap.Observation(125_000L)), FrameAttachmentTimeline(), 0, 0, 0)
        val file = Files.createTempFile("analysis-version-cache", ".gz").toFile()
        try {
            MediaFrameAnalysisCache.write(current, file)
            assertEquals(current, MediaFrameAnalysisCache.read(file, current.profile))
            val original = java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes() }
            // Old versions 4..18 represent successive incompatible evidence contracts.
            // Full payloads ensure accepting a bad header cannot merely fail later on EOF.
            for (version in (4..18).toList() + listOf(0, -1, 20, Int.MAX_VALUE)) {
                val payload = original.copyOf()
                java.nio.ByteBuffer.wrap(payload).putInt(4, version)
                java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(payload) }
                org.junit.Assert.assertThrows("Incompatible cache version $version",
                    IllegalArgumentException::class.java) { MediaFrameAnalysisCache.read(file, current.profile) }
            }
            val wrongMagic = original.copyOf()
            java.nio.ByteBuffer.wrap(wrongMagic).putInt(0, 0)
            java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(wrongMagic) }
            org.junit.Assert.assertThrows("Cache magic must be checked before reading evidence",
                IllegalArgumentException::class.java) { MediaFrameAnalysisCache.read(file, current.profile) }
        } finally { file.delete() }
    }

    @Test
    fun exactRoundTripKeepsDirectorAndRendererEvidence() {
        val mask = FrameAttachments.Plane(2, 2, floatArrayOf(.1f, .2f, .8f, .9f), .75f)
        val result = MediaFrameVisualAnalyzer.Result(
            profile = SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE,
            correspondenceAssessmentsCompleted = 3,
            durationUs = 2_000_000L,
            observations = listOf(VisualEventMap.Observation(
                sourceTimeUs = 125_000L,
                cameraMotion = VisualEventMap.Vector(.1f, -.2f, .3f),
                subjectMotion = VisualEventMap.Vector(-.4f, .5f, -.1f),
                face = VisualEventMap.Face(.9f, 12f, -3f, 2f, .2f, -.1f),
                gestureConfidence = .7f,
                personMaskConfidence = .8f,
                personMaskTemporalIou = .77f,
                visualQuality = .83f,
                meanLuma = .42f,
                composition = VisualEventMap.Composition(.4f, .8f, .7f, .6f, .9f),
                sceneChangeConfidence = .72f,
                humanPresenceConfidence = .84f,
                motionMeasurement = VisualEventMap.MotionMeasurement(.6f, .2f, -.1f,
                    .8f, 17, .62f, 30, 3, 280_000L)
            ), VisualEventMap.Observation(375_000L, faceInferenceSucceeded = true,
                gestureEvidenceAvailable = true, cameraMeasurement = VisualEventMap.CameraMeasurement(
                    .4f, -.2f, .8f, 30, 3, 125_000L, 375_000L, 375_000L, 250_000L)),
                VisualEventMap.Observation(625_000L, faceInferenceSucceeded = false)),
            attachments = FrameAttachmentTimeline(listOf(FrameAttachments(
                sourceTimeUs = 125_000L,
                mask = mask,
                depth = mask.copy(confidence = .62f),
                flow = FrameAttachments.FlowPlane(1, 1, listOf(.25f, -.5f), .55f),
                subjectQuality = .72f,
                subjectOcclusion = .13f,
                maskTemporalIou = .77f,
                faceRegion = FrameAttachments.FaceRegion(.5f, .3f, .2f, .25f, .9f)
            ))),
            semanticFrames = 1,
            maskFrames = 1,
            semanticModelSuccesses = 3
        )
        val file = Files.createTempFile("analysis-cache", ".gz").toFile()
        try {
            MediaFrameAnalysisCache.write(result, file)
            assertEquals(result, MediaFrameAnalysisCache.read(file, result.profile))
        } finally {
            file.delete()
        }
    }
}
