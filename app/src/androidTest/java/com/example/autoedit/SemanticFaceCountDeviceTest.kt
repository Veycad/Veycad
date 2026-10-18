package com.veycad.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic result mapping only. Real ML 0/1/2-face fixtures are a separate pending gate. */
@RunWith(AndroidJUnit4::class)
class SemanticFaceCountDeviceTest {
    @Test fun current_inference_counts_and_failure_map_without_inventing_a_person() {
        val pixels = LumaMotionEstimator.Plane(12, 18, FloatArray(216) { .5f })
        val estimate = LumaMotionEstimator.estimate(null, pixels)
        for (count in listOf(0, 1, 2, null)) {
            val semantic = LocalSemanticFrameAnalyzer.Result(null, null, 0f, 0f, null, null,
                null, 0f, 0f, 0, 0f, faceInferenceSucceeded = count != null,
                detectedFaceCount = count)
            val observation = MediaFrameVisualAnalyzer.observationForFrame(0, estimate, semantic)
            assertEquals(count, observation.detectedFaceCount)
            assertEquals(count != null, observation.faceInferenceSucceeded)
            val analysis = MediaFrameVisualAnalyzer.Result(SourceAnalysisProfile.EDITORIAL_SEMANTICS,
                0, 1_000_000, listOf(observation), FrameAttachmentTimeline(), 1, 0, 0)
            val point = SmartFramingTrack.build(analysis, listOf(VisualEventMap.UsableWindow(0, 1_000_000))).sample(0)
            assertEquals(.5f, point.centerX, 0f)
            assertEquals(when (count) {
                0 -> SmartFramingStatus.NO_PERSON
                2 -> SmartFramingStatus.AMBIGUOUS
                else -> SmartFramingStatus.UNKNOWN
            }, point.status)
        }
    }
}
