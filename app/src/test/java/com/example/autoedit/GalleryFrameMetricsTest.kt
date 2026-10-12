package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class GalleryFrameMetricsTest {
    private val width = 32
    private val height = 24
    private fun landscape(shift: Int = 0) = FloatArray(width * height) { i ->
        val x = (i % width + shift) % width
        val y = i / width
        if ((x / 3 + y / 4) % 2 == 0) .25f else .75f
    }

    @Test fun prefersSharpStableLandscape() {
        val image = landscape()
        val stableLandscape = GalleryFrameMetrics.measure(0, image, width, height, image)
        val blurredCameraShake = GalleryFrameMetrics.measure(250_000, FloatArray(image.size) { .5f },
            width, height, image)
        assertTrue(stableLandscape.quality > blurredCameraShake.quality)
        assertNull(stableLandscape.faceConfidence)
        assertEquals(0f, stableLandscape.localMotion, .001f)
    }

    @Test fun globalTranslationDoesNotBecomeLocalAction() {
        val translated = GalleryFrameMetrics.measure(250_000, landscape(2), width, height, landscape())
        assertTrue(translated.cameraInstability > .1f)
        assertTrue(translated.localMotion < .05f)
        val changed = landscape().also { image ->
            for (y in 8..15) for (x in 12..19) image[y * width + x] = .5f
        }
        val action = GalleryFrameMetrics.measure(250_000, changed, width, height, landscape())
        assertTrue(action.localMotion > translated.localMotion)
    }

    @Test fun strongGlobalTranslationCannotSubstituteForLocalAction() {
        val random = java.util.Random(47)
        val original = FloatArray(width * height) { .25f + random.nextFloat() * .5f }
        val panned = FloatArray(original.size) { i -> original[i / width * width + (i % width + 6) % width] }
        val features = GalleryFrameMetrics.measure(250_000, panned, width, height, original)
        assertTrue("A six-pixel camera pan became local action", features.localMotion < .01f)
        assertTrue(features.cameraInstability > .5f)
    }

    @Test fun exposureCutAndMalformedSamplesAreExplicit() {
        val cut = GalleryFrameMetrics.measure(1, FloatArray(width * height) { .9f }, width, height,
            FloatArray(width * height) { .1f })
        assertTrue(cut.sceneChange > .6f)
        assertThrows(IllegalArgumentException::class.java) {
            GalleryFrameMetrics.measure(0, floatArrayOf(Float.NaN), 1, 1, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GalleryFrameMetrics.measure(0, floatArrayOf(.5f), 2, 2, null)
        }
    }
}
