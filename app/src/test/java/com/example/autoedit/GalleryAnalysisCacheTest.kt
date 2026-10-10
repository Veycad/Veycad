package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class GalleryAnalysisCacheTest {
    private fun moments(id: String) = listOf(GalleryMoment(id, 0, 500_000,
        GalleryFeatures(250_000, .8f, .1f, .1f, 0f, .7f, null), 42))

    @Test fun identicalBytesRebindToEachSelectionWithoutCollapsingIdentity() {
        val cache = GalleryAnalysisCache()
        val first = mediaSource("first", 500_000)
        val second = mediaSource("second", 500_000)
        val original = moments(first.id).toMutableList()
        cache.store(first, original)
        original.clear()
        assertEquals("first", cache.load(first)!!.single().sourceId)
        assertEquals("second", cache.load(second)!!.single().sourceId)
        assertEquals(42L, cache.load(second)!!.single().keyHash)
        assertNull(cache.load(second.copy(fingerprint = "b".repeat(64))))
    }

    @Test fun versionIntervalsAndSourceBoundsInvalidateOldMeasurements() {
        val cache = GalleryAnalysisCache()
        val source = mediaSource(durationUs = 500_000)
        cache.store(source, moments(source.id))
        assertNull(cache.load(source.copy(durationUs = 499_999)))
        assertNull(cache.load(source, GalleryAnalysisProfile(analyzerVersion = 2)))
        assertNull(cache.load(source, GalleryAnalysisProfile(coarseIntervalUs = 500_000)))
        assertNull(cache.load(source, GalleryAnalysisProfile(refineIntervalUs = 125_000)))
    }

    @Test fun boundedLruEvictsOldEntryAndRejectsOutOfBoundsEvidence() {
        val cache = GalleryAnalysisCache(maxEntries = 2)
        val a = mediaSource("a", 500_000)
        val b = mediaSource("b", 500_000).copy(fingerprint = "b".repeat(64))
        val c = mediaSource("c", 500_000).copy(fingerprint = "c".repeat(64))
        cache.store(a, moments("a")); cache.store(b, moments("b"))
        assertNotNull(cache.load(a))
        cache.store(c, moments("c"))
        assertNull(cache.load(b))
        assertNotNull(cache.load(a))
        assertThrows(IllegalArgumentException::class.java) {
            cache.store(a, listOf(moments("a").single().copy(endUs = 500_001)))
        }
    }
}
