package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportContainerIntegrityTest {
    private fun issues(width: Int = 720, height: Int = 1280, rotation: Int = 0,
                       audioStart: Long = 0, videoSamples: Int = 549,
                       audioSamples: Int = 860) = VeykadRenderInspector.containerIssues(
        "video/avc", "audio/mp4a-latm", width, height, rotation,
        videoSamples, audioSamples, 0, 16_000, audioStart, 720, 1280)

    @Test fun exact_export_geometry_and_clock_pass() {
        assertEquals(emptyList<String>(), issues())
    }

    @Test fun swapped_dimensions_and_double_rotation_fail() {
        assertTrue(issues(width = 1280, height = 720).contains("encoded_dimensions_do_not_match_export"))
        assertTrue(issues(rotation = 90).contains("encoded_rotation_not_baked"))
    }

    @Test fun shifted_audio_and_non_monotonic_tracks_fail() {
        assertTrue(issues(audioStart = 21_333).contains("audio_track_does_not_start_at_pts_zero"))
        assertTrue(issues(videoSamples = -549).contains("non_monotonic_sample_pts"))
        assertTrue(issues(audioSamples = -860).contains("non_monotonic_sample_pts"))
    }

    @Test fun absent_tracks_are_not_zero_drift_success() {
        assertTrue(issues(videoSamples = 0).contains("encoded_video_track_missing"))
        assertTrue(issues(audioSamples = 0).contains("encoded_aac_track_missing"))
    }
}
