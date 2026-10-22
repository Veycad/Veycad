package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInMusicCatalogTest {
    @Test fun every_catalog_track_is_reachable_from_an_authored_style() {
        val tracks = BuiltInMusicCatalog.tracks
        val routed = listOf(MontageStyleCatalog.sigma, MontageStyleCatalog.heartbeat,
            MontageStyleCatalog.fearStrobe, MontageStyleCatalog.dualityLoop)
            .map { BuiltInMusicCatalog.trackForStyle(it.id) }
        assertEquals(routed.toSet(), tracks.toSet())
        assertTrue(tracks.all { it.rawResourceId > 0 && it.bpm > 0 })
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
