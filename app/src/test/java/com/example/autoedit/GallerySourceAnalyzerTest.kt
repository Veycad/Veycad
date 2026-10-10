package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class GallerySourceAnalyzerTest {
    private val image = FloatArray(32 * 24) { if ((it % 32 / 3 + it / 32 / 4) % 2 == 0) .25f else .75f }
    private class Frames(val pts: LongArray, val image: FloatArray,
        val changeAt: Long? = null, val imageAt: ((Long) -> FloatArray)? = null) : GalleryFrameReader {
        val requests = mutableListOf<LongArray>()
        override fun bounds(source: MediaSource, checkCancelled: () -> Unit): GalleryFrameBounds {
            checkCancelled()
            return GalleryFrameBounds(pts.first(), pts.last())
        }
        override fun read(source: MediaSource, targetsUs: LongArray, checkCancelled: () -> Unit,
            onFrame: (Long, FloatArray, Int, Int) -> Unit) {
            requests += targetsUs
            targetsUs.forEach { target ->
                checkCancelled()
                val actual = pts.firstOrNull { it >= target } ?: error("request beyond actual EOF")
                onFrame(actual, imageAt?.invoke(actual) ?: if (changeAt != null && actual >= changeAt)
                    FloatArray(image.size) { .9f } else image, 32, 24)
            }
        }
    }

    @Test fun keepsShortClipWithinEof() {
        val reader = Frames(longArrayOf(0, 100_000, 250_000, 466_666), image)
        val moments = GallerySourceAnalyzer(frameReader = reader).analyze(mediaSource(durationUs = 500_000)) {}
        assertTrue(moments.isNotEmpty())
        assertTrue(moments.all { it.startUs >= 0 && it.endUs <= 500_000 && it.endUs - it.startUs >= 500_000 })
        assertTrue(moments.all { it.features.faceConfidence == null })
        assertTrue(reader.requests.flatMap { it.asIterable() }.all { it <= 466_666 })
    }

    @Test fun usesActualNonzeroVfrPtsAndRefinesLocally() {
        val pts = longArrayOf(120_000, 330_000, 610_000, 980_000, 1_280_000, 1_670_000, 2_230_000, 2_700_000)
        val reader = Frames(pts, image)
        val source = mediaSource(durationUs = 2_780_000).copy(firstVideoPtsUs = 120_000)
        val moments = GallerySourceAnalyzer(frameReader = reader).analyze(source) {}
        assertTrue(moments.isNotEmpty())
        assertTrue(moments.all { it.features.timeUs in pts && it.startUs >= 120_000 && it.endUs <= 2_900_000 })
        assertEquals(2, reader.requests.size)
        assertTrue(reader.requests.last().any { it !in reader.requests.first() })
    }

    @Test fun nonzeroOriginKeepsEligibleHalfSecondContentOnRawPtsAxis() {
        for (pts in listOf(longArrayOf(5_000, 250_000, 495_000), longArrayOf(120_000, 353_333, 586_666))) {
            val source = mediaSource(durationUs = 500_000).copy(firstVideoPtsUs = pts.first())
            val moments = GallerySourceAnalyzer(frameReader = Frames(pts, image)).analyze(source) {}
            assertTrue("Eligible shifted half-second content was lost", moments.isNotEmpty())
            assertTrue(moments.all { it.startUs == pts.first() && it.endUs == pts.first() + 500_000 &&
                it.features.timeUs in pts && it.endUs - it.startUs == 500_000L })
        }
    }

    @Test fun gradualRefinementCannotEraseCoarseSceneEvidence() {
        val pts = (0..8).map { it * 250_000L }.toLongArray()
        val reader = Frames(pts, image, imageAt = { time ->
            FloatArray(image.size) { when (time) {
                0L -> .1f
                250_000L -> .2f
                500_000L -> .3f
                750_000L -> .4f
                else -> .5f
            } }
        })
        val coarse = GalleryFrameMetrics.measure(1_000_000, FloatArray(image.size) { .5f }, 32, 24,
            FloatArray(image.size) { .1f })
        assertTrue(coarse.sceneChange > .6f)
        val moments = GallerySourceAnalyzer(frameReader = reader).analyze(mediaSource(durationUs = 2_250_000)) {}
        assertTrue(moments.isNotEmpty())
        assertTrue("Refinement erased the coarse transition", moments.none { it.startUs < 1_000_000 && it.endUs > 1_000_000 })
        assertTrue(moments.any { it.features.timeUs == 1_000_000L && it.features.sceneChange >= .8f })
    }

    @Test fun sceneNearEofCannotCreateWindowAcrossTheCut() {
        val pts = (0..11).map { it * 250_000L }.toLongArray()
        val moments = GallerySourceAnalyzer(frameReader = Frames(pts, image, 2_000_000))
            .analyze(mediaSource(durationUs = 3_000_000)) {}
        assertTrue(moments.isNotEmpty())
        assertTrue(moments.none { it.startUs < 2_000_000 && it.endUs > 2_000_000 })
        assertTrue(moments.all { it.endUs <= 3_000_000 })
    }

    @Test fun refinementCannotEraseACoarseCutAtItsFirstDecodedFrame() {
        // Later sharp scenes exhaust the bounded candidate budget. Refinement starts on
        // the bright cut itself, so retaining only refine targets loses its predecessor.
        val pts = (0..320).map { it * 250_000L }.toLongArray()
        val reader = Frames(pts, image, imageAt = { time ->
            when {
                time < 10_000_000 -> FloatArray(image.size) { .4f }
                time < 11_000_000 -> FloatArray(image.size) { .9f }
                else -> image
            }
        })
        val moments = GallerySourceAnalyzer(frameReader = reader)
            .analyze(mediaSource(durationUs = 80_250_000)) {}
        assertTrue(moments.any { it.startUs < 10_000_000 })
        assertTrue("Refinement erased a measured cut", moments.none { it.startUs < 10_000_000 && it.endUs > 10_000_000 })
    }

    @Test fun cancellationDuringRefineEscapesWithoutCachingPartialEvidence() {
        val reader = Frames((0..16).map { it * 250_000L }.toLongArray(), image)
        val cache = GalleryAnalysisCache()
        val source = mediaSource(durationUs = 4_250_000)
        assertThrows(CancellationException::class.java) {
            GallerySourceAnalyzer(frameReader = reader, cache = cache).analyze(source) {
                if (reader.requests.size == 2) throw CancellationException("refine cancelled")
            }
        }
        assertNull(cache.load(source))
    }

    @Test fun invalidDecodedEvidenceIsRejectedRatherThanClamped() {
        val reader = Frames(longArrayOf(0, 1_000_000), image)
        assertThrows(IllegalArgumentException::class.java) {
            GallerySourceAnalyzer(frameReader = reader).analyze(mediaSource(durationUs = 500_000)) {}
        }
    }

    @Test fun cancellingCachedResultStillCancels() {
        val source = mediaSource(durationUs = 500_000)
        val analyzer = GallerySourceAnalyzer(frameReader = Frames(longArrayOf(0, 466_666), image))
        analyzer.analyze(source) {}
        assertThrows(CancellationException::class.java) { analyzer.analyze(source) { throw CancellationException() } }
    }
}
