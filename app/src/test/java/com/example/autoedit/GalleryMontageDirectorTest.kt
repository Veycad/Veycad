package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class GalleryMontageDirectorTest {
    private val silence = AudioBeatMap(1_000, 60_000, null, emptyList(), emptyList())

    @Test fun shortfallDoesNotPadWithRepeats() {
        val sources = gallerySources(2, durationUs = 600_000)
        val moments = sources.items.map { galleryMoment(it.id, 0, lengthUs = 600_000) }
        val result = GalleryMontageDirector.direct(sources, moments, silence, 30_000)
        assertEquals(1_200L, result.outputDurationMs)
        assertEquals(listOf(0, 1), result.clips.map { it.sourceIndex })
        assertEquals(result.clips, GalleryMontageDirector.direct(sources, moments.reversed(), silence, 30_000).clips)
        assertTrue(result.clips.all { it.sourceEndMs <= 600 && it.outputDurationMs == it.sourceEndMs - it.sourceStartMs })
        assertTrue(result.clips.all { it.speedRamp.keyframes.all { key -> key.speed == 1f } })
        val frames = HighQualityFramePlan.build(result).frames
        assertTrue(frames.all { it.sourceTimeUs in 0 until 600_000 })
        assertTrue(frames.groupBy { it.clipIndex }.values.all { group -> group.zipWithNext().all { (a, b) -> b.sourceTimeUs > a.sourceTimeUs } })
    }

    @Test fun shorterThanOneSecondAndEmptyPoolsFailExplicitly() {
        val sources = gallerySources(1, durationUs = 999_000)
        for (moments in listOf(emptyList(), listOf(galleryMoment("s0", 0, lengthUs = 999_000)))) {
            val error = assertThrows(MaterialRejectedException::class.java) {
                GalleryMontageDirector.direct(sources, moments, silence, 15_000)
            }
            assertEquals("gallery_insufficient_material", error.code)
        }
        val exact = gallerySources(2, durationUs = 500_000)
        assertEquals(1_000L, GalleryMontageDirector.direct(exact, exact.items.map {
            galleryMoment(it.id, 0, lengthUs = 500_000) }, silence, 15_000).outputDurationMs)
    }

    @Test fun rawPtsRoundsInwardAndResolvesImmutableSelectionTable() {
        val original = gallerySources(20, durationUs = 1_500_456, originUs = 5_000_123)
        val sources = MediaSourceSet(original.items.reversed())
        val graph = GalleryMontageDirector.direct(sources,
            listOf(galleryMoment("s0", 5_000_123, lengthUs = 1_500_456)), silence, 15_000)
        val clip = graph.clips.single()
        assertEquals(19, clip.sourceIndex)
        assertEquals("selected-0.mp4", sources.items[clip.sourceIndex].file.name)
        assertEquals(5_001L, clip.sourceStartMs)
        assertEquals(6_500L, clip.sourceEndMs)
        assertEquals(1_499L, graph.outputDurationMs)
        assertTrue(HighQualityFramePlan.build(graph).frames.all { it.sourceIndex == 19 && it.sourceTimeUs in 5_000_123 until 6_500_579 })
    }

    @Test fun beatsShortenRealWindowsWithoutExtendingOrChangingSpeed() {
        val sources = gallerySources(2)
        val beats = listOf(0L, 900L, 1_800L, 2_700L, 3_600L, 4_500L, 5_400L, 6_300L, 7_200L).map {
            AudioBeatMap.Beat(it, 1f, AudioBeatMap.FrequencyBand.LOW, false) }
        val graph = GalleryMontageDirector.direct(sources,
            listOf(galleryMoment("s0", 0), galleryMoment("s1", 0, hash = -1)), silence.copy(beats = beats), 15_000)
        assertEquals(listOf(3_600L, 3_600L), graph.clips.map { it.outputDurationMs })
        assertEquals(listOf(0L, 3_600L), graph.clips.map { it.beatAnchorMs })
        assertEquals(7_200L, graph.outputDurationMs)
        assertTrue(graph.clips.all { it.sourceEndMs == 3_600L })
        assertEquals("neon_drift", graph.audioTrack?.sourceId)
    }

    @Test fun requestedDurationsBoundGraphAndOtherValuesAreRejected() {
        val sources = gallerySources(20)
        val moments = sources.items.mapIndexed { index, source -> galleryMoment(source.id, 0, hash = index * 0x123456789L) }
        for (duration in listOf(15_000L, 30_000L, 60_000L)) {
            val graph = GalleryMontageDirector.direct(sources, moments, silence, duration)
            assertEquals(duration, graph.outputDurationMs)
            assertTrue(graph.clips.all { it.outputDurationMs == it.sourceEndMs - it.sourceStartMs })
        }
        assertThrows(IllegalArgumentException::class.java) { GalleryMontageDirector.direct(sources, moments, silence, 29_000) }
    }

    @Test fun enoughUnusedMaterialFillsBeatTrimmedBudgetWithoutRepeats() {
        val sources = gallerySources(20)
        val beats = (0 until 60_000 step 900).map {
            AudioBeatMap.Beat(it.toLong(), 1f, AudioBeatMap.FrequencyBand.LOW, false) }
        val graph = GalleryMontageDirector.direct(sources, sources.items.mapIndexed { index, source ->
            galleryMoment(source.id, 0, hash = index * 0x123456789L)
        }, silence.copy(beats = beats), 30_000)
        assertEquals(30_000L, graph.outputDurationMs)
        assertEquals(graph.clips.size, graph.clips.map { it.sourceIndex }.distinct().size)
    }

    @Test fun largeRawOriginDoesNotOverflowGraphOrFramePlan() {
        val origin = Long.MAX_VALUE - 2_000_000L
        val sources = gallerySources(1, 1_500_000, origin)
        val graph = GalleryMontageDirector.direct(sources,
            listOf(galleryMoment("s0", origin, 1_500_000)), silence, 15_000)
        assertTrue(HighQualityFramePlan.build(graph).frames.all {
            it.sourceTimeUs >= origin && it.sourceTimeUs < origin + 1_500_000
        })
    }

    @Test fun internalGalleryRouteAcceptsNoFacesAndBypassesLegacySourceProfile() {
        val sources = gallerySources(10)
        VeycadAutomaticEditor.Request.validateRecipeSources(sources, MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
        val graph = VeycadAutomaticEditor.directGallery(sources,
            sources.items.map { galleryMoment(it.id, 0) }, silence, 30_000)
        assertTrue(graph.clips.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            SourceAnalysisProfile.requiredFor(MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
        }
    }
}
