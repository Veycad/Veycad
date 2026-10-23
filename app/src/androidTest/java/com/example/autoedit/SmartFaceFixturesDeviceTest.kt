package com.veycad.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual bundled ML Kit face detector; all image bytes derive from versioned app references. */
@RunWith(AndroidJUnit4::class)
class SmartFaceFixturesDeviceTest {
    @Test fun bundledReferenceDerivativesProveZeroOneTwoFacesAndFailClosedMapping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A supplied zero mask isolates the real face detector from independent segmentation quality.
        // Subject centroid/hold/occlusion rules have separate mask fixtures and native framing tests.
        val emptyMask = FrameAttachments.Plane(32, 32, FloatArray(1024), 1f)
        LocalSemanticFrameAnalyzer().use { analyzer ->
            val blank = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            try {
                val zero = analyzer.analyze(blank, emptyMask, runPose = false)
                assertTrue("Bundled face model must execute", zero.faceInferenceSucceeded)
                assertEquals(0, zero.detectedFaceCount)
                assertEquals(SmartFramingStatus.NO_PERSON, mapped(zero).status)
            } finally { blank.recycle() }

            var one: Bitmap? = null
            var region: FrameAttachments.FaceRegion? = null
            val references = listOf(R.raw.style_preview_heartbeat, R.raw.style_preview_fear, R.raw.style_preview_duality)
            referenceSearch@ for (resource in references) {
                context.resources.openRawResourceFd(resource).use { descriptor ->
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                        for (time in listOf(500_000L, 1_000_000L, 2_000_000L, 4_000_000L)) {
                            val decoded = reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                            val scale = minOf(1.0, 640.0 / maxOf(decoded.width, decoded.height))
                            val frame = if (scale == 1.0) decoded else Bitmap.createScaledBitmap(decoded,
                                (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
                                .also { decoded.recycle() }
                            val result = analyzer.analyze(frame, emptyMask, runPose = false)
                            if (result.faceInferenceSucceeded && result.detectedFaceCount == 1 && result.faceRegion != null) {
                                one = frame; region = result.faceRegion
                                Log.i("SmartFaceFixture", "Versioned reference=${context.resources.getResourceEntryName(resource)} ptsUs=$time count=1")
                                break
                            }
                            frame.recycle()
                        }
                    }
                }
                if (one != null) break@referenceSearch
            }
            val single = requireNotNull(one) { "Versioned reference fixture must contain one real detected face; no fake result or skip is accepted" }
            try {
                val singleResult = analyzer.analyze(single, emptyMask, runPose = false)
                assertTrue(singleResult.faceInferenceSucceeded)
                assertEquals(1, singleResult.detectedFaceCount)
                assertEquals(SmartFramingStatus.UNKNOWN, mapped(singleResult).status)
                val face = requireNotNull(region)
                val cropWidth = (face.width * single.width * 2.2f).roundToInt().coerceIn(1, single.width)
                val cropHeight = (face.height * single.height * 2.2f).roundToInt().coerceIn(1, single.height)
                val left = (face.centerX * single.width - cropWidth / 2f).roundToInt().coerceIn(0, single.width - cropWidth)
                val top = (face.centerY * single.height - cropHeight / 2f).roundToInt().coerceIn(0, single.height - cropHeight)
                val crop = Bitmap.createBitmap(single, left, top, cropWidth, cropHeight)
                val tile = Bitmap.createScaledBitmap(crop, 280, 280, true)
                val pair = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
                try {
                    pair.eraseColor(Color.GRAY)
                    Canvas(pair).apply { drawBitmap(tile, 10f, 20f, null); drawBitmap(tile, 350f, 20f, null) }
                    val two = analyzer.analyze(pair, emptyMask, runPose = false)
                    assertTrue(two.faceInferenceSucceeded)
                    assertEquals("Two separated copies of an actual detected reference face", 2, two.detectedFaceCount)
                    val centered = mapped(two)
                    assertEquals(SmartFramingStatus.AMBIGUOUS, centered.status)
                    assertEquals(.5f, centered.centerX, 0f); assertEquals(.5f, centered.centerY, 0f)
                } finally {
                    pair.recycle(); if (tile !== crop) tile.recycle(); if (crop !== single) crop.recycle()
                }
                // Actual failed inference is not fabricated by corrupt images: the deterministic
                // failure seam below proves the null evidence cannot inherit the previous face.
                val failed = singleResult.copy(faceInferenceSucceeded = false, detectedFaceCount = null)
                val fallback = mapped(failed)
                assertEquals(SmartFramingStatus.UNKNOWN, fallback.status)
                assertEquals(.5f, fallback.centerX, 0f); assertEquals(.5f, fallback.centerY, 0f)
            } finally { single.recycle() }
        }
    }

    private fun mapped(result: LocalSemanticFrameAnalyzer.Result): SmartFramingTrack.Point {
        val pixels = LumaMotionEstimator.Plane(12, 18, FloatArray(216) { .5f })
        val observation = MediaFrameVisualAnalyzer.observationForFrame(0,
            LumaMotionEstimator.estimate(null, pixels), result)
        val analysis = MediaFrameVisualAnalyzer.Result(SourceAnalysisProfile.EDITORIAL_SEMANTICS,
            0, 1_000_000, listOf(observation), FrameAttachmentTimeline(), 1, 0, 0)
        return SmartFramingTrack.build(analysis, listOf(VisualEventMap.UsableWindow(0, 1_000_000))).sample(0)
    }
}
