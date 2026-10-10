package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInMusicCatalogTest {
    @Test fun catalog_contains_ten_distinct_phonk_arrangements() {
        val tracks = BuiltInMusicCatalog.tracks

        assertEquals(10, tracks.size)
        assertEquals(10, tracks.map { it.id }.distinct().size)
        assertEquals(10, tracks.map { it.title }.distinct().size)
        assertTrue(tracks.all { it.bpm in 120..170 && it.pattern.size == 4 })
        assertEquals(1, tracks.count { it.rawResourceId != null })
        assertEquals(121, tracks.single { it.id == "leonid_reentry" }.bpm)
    }

    @Test fun procedural_track_is_deterministic_audible_and_not_clipped() {
        val track = BuiltInMusicCatalog.tracks.first { it.rawResourceId == null }

        val first = BuiltInMusicCatalog.synthesize(track, sampleRate = 8_000, durationSeconds = 2)
        val second = BuiltInMusicCatalog.synthesize(track, sampleRate = 8_000, durationSeconds = 2)

        assertTrue(first.contentEquals(second))
        assertTrue(first.any { kotlin.math.abs(it.toInt()) > 2_000 })
        assertTrue(first.count { kotlin.math.abs(it.toInt()) >= Short.MAX_VALUE } < first.size / 100)
    }

    @Test fun authored_styles_have_distinct_music_routes() {
        val sigma = BuiltInMusicCatalog.trackForStyle(MontageStyleCatalog.sigma.id)
        val heartbeat = BuiltInMusicCatalog.trackForStyle(MontageStyleCatalog.heartbeat.id)
        val fear = BuiltInMusicCatalog.trackForStyle(MontageStyleCatalog.fearStrobe.id)
        val duality = BuiltInMusicCatalog.trackForStyle(MontageStyleCatalog.dualityLoop.id)

        assertEquals(ReferenceMontageProfile.AUTHOR_TRACK_ID, sigma.id)
        assertEquals(HeartbeatMontageProfile.AUTHOR_TRACK_ID, heartbeat.id)
        assertEquals(FearStrobeProfile.AUTHOR_TRACK_ID, fear.id)
        assertEquals(DualityLoopProfile.AUTHOR_TRACK_ID, duality.id)
        assertEquals(4, listOf(sigma, heartbeat, fear, duality).map { it.rawResourceId }.distinct().size)
    }
}
