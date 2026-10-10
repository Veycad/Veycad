package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GalleryMomentSelectorTest {
    @Test fun diversifiesWithoutRepeatingSourceWindows() {
        for (count in listOf(10, 20)) {
            val sources = gallerySources(count)
            val moments = sources.items.flatMapIndexed { index, source ->
                (0..4).map { window -> galleryMoment(source.id, window * 4_000_000L,
                    quality = if (index == 0) 1f else .8f, hash = (index * 7L + window) * 0x123456789L) }
            }
            val selected = GalleryMomentSelector.select(moments, sources, 30_000_000)
            assertEquals(selected, GalleryMomentSelector.select(moments.reversed(), sources, 30_000_000))
            assertTrue(selected.map { it.sourceId }.distinct().size >= 7)
            assertTrue(selected.sumOf { it.endUs - it.startUs } <= 30_000_000)
            for (windows in selected.groupBy { it.sourceId }.values) {
                val ordered = windows.sortedBy { it.startUs }
                assertTrue(ordered.zipWithNext().all { (a, b) -> a.endUs <= b.startUs })
            }
        }
    }

    @Test fun repeatedKeysAndAdjacentSourceLoseToFreshGoodScenes() {
        val sources = gallerySources(4)
        val pool = listOf(galleryMoment("s0", 0, hash = 0),
            galleryMoment("s1", 0, hash = 0),
            galleryMoment("s0", 4_000_000, hash = -1),
            galleryMoment("s2", 0, hash = 0x5555555555555555),
            galleryMoment("s3", 0, hash = 0x3333333333333333))
        assertEquals(listOf("s0", "s2", "s3"),
            GalleryMomentSelector.select(pool, sources, 12_000_000).map { it.sourceId })
    }

    @Test fun equalScoresUseSelectedSourceOrderThenWindowBounds() {
        val sources = MediaSourceSet(gallerySources(3).items.reversed())
        val pool = listOf(galleryMoment("s0", 0, hash = -1),
            galleryMoment("s2", 4_000_000, hash = 0), galleryMoment("s2", 0, hash = 0))
        assertEquals(listOf("s2", "s0"), GalleryMomentSelector.select(pool, sources, 8_000_000).map { it.sourceId })
        assertEquals(0L, GalleryMomentSelector.select(pool, sources, 4_000_000).single().startUs)
    }

    @Test fun ratingBalancesQualityMotionCompositionAndInstabilityWithoutFaceGate() {
        val sources = gallerySources(5)
        val pool = listOf(galleryMoment("s0", 0, quality = .9f, motion = 0f, composition = 0f),
            galleryMoment("s1", 0, quality = .8f, motion = 1f, composition = 1f),
            galleryMoment("s2", 0, quality = 1f, motion = 1f, composition = 1f, instability = .5f),
            galleryMoment("s3", 0, quality = .1f),
            galleryMoment("s4", 0, quality = 1f, instability = 1f))
        assertEquals("s1", GalleryMomentSelector.select(pool, sources, 4_000_000).single().sourceId)
        assertTrue(GalleryMomentSelector.select(pool, sources, 60_000_000).none { it.sourceId in listOf("s3", "s4") })
    }

    @Test fun identicalContentCannotSupplyOverlappingWindowsUnderDifferentIds() {
        val original = gallerySources(2)
        val sources = MediaSourceSet(listOf(original.items[0], original.items[1].copy(
            file = original.items[0].file, fingerprint = original.items[0].fingerprint)))
        val result = GalleryMomentSelector.select(listOf(galleryMoment("s0", 0),
            galleryMoment("s1", 0), galleryMoment("s1", 4_000_000, hash = -1)), sources, 30_000_000)
        assertEquals(listOf("s0", "s1"), result.map { it.sourceId })
        assertEquals(listOf(0L, 4_000_000L), result.map { it.startUs })
    }

    @Test fun rejectsUnknownInvalidRawBoundsAndNonNormalizedFeatures() {
        val sources = gallerySources(1, originUs = 5_000_123)
        val good = galleryMoment("s0", 5_000_123)
        for (bad in listOf(good.copy(sourceId = "missing"), good.copy(startUs = 0),
            good.copy(endUs = 25_000_124), good.copy(endUs = good.startUs),
            good.copy(features = good.features.copy(quality = Float.NaN)),
            good.copy(features = good.features.copy(localMotion = 1.1f)))) {
            assertThrows(IllegalArgumentException::class.java) { GalleryMomentSelector.select(listOf(bad), sources, 4_000_000) }
        }
        assertEquals(good, GalleryMomentSelector.select(listOf(good), sources, 4_000_000).single())
    }

    @Test fun budgetTrimsOnceAndNeverIncludesSubHalfSecondWindows() {
        val sources = gallerySources(1)
        assertEquals(600_000L, GalleryMomentSelector.select(listOf(galleryMoment("s0", 0)), sources, 600_000).single().endUs)
        assertTrue(GalleryMomentSelector.select(listOf(galleryMoment("s0", 0)), sources, 499_999).isEmpty())
        assertTrue(GalleryMomentSelector.select(listOf(galleryMoment("s0", 0, lengthUs = 499_999)), sources, 1_000_000).isEmpty())
    }

    @Test fun contradictoryDuplicateEvidenceStillHasDeterministicTieBreak() {
        val sources = gallerySources(1)
        val absent = galleryMoment("s0", 0)
        val present = absent.copy(features = absent.features.copy(faceConfidence = 1f))
        assertEquals(GalleryMomentSelector.select(listOf(absent, present), sources, 4_000_000),
            GalleryMomentSelector.select(listOf(present, absent), sources, 4_000_000))
    }
}

internal fun gallerySources(count: Int, durationUs: Long = 20_000_000, originUs: Long = 0) =
    MediaSourceSet((0 until count).map { index -> MediaSource("s$index", File("selected-$index.mp4"),
        "selected-$index", durationUs, 1, 0, 720, 1280, "video/avc", 3, false,
        index.toString(16).padStart(64, '0'), originUs,
        VideoPresentationBounds(originUs, originUs + durationUs)) })

internal fun galleryMoment(id: String, startUs: Long, lengthUs: Long = 4_000_000,
    quality: Float = .8f, motion: Float = .5f, composition: Float = .5f,
    instability: Float = 0f, hash: Long = 0) = GalleryMoment(id, startUs, startUs + lengthUs,
    GalleryFeatures(startUs, quality, motion, instability, 0f, composition, null), hash)
