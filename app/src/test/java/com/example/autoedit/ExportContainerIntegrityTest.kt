package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportContainerIntegrityTest {
    private val square = ExportProfile(ProjectAspect.SQUARE_1_1, ExportQuality.P1080,
        OutputSize(1080,1080), 60, 9_000_000, "test.avc")
    private val squareProbe = ExportContract.Probe(1080,1080,0,2000,
        "audio/mp4a-latm", "video/avc", 60)

    @Test fun portrait_only_legacy_gate_does_not_reject_square_new_export() {
        assertTrue(ExportContract.validateExact(squareProbe, square, 2000).accepted)
        assertTrue(!ExportContract.validateVmeFinal(squareProbe, 2000, true).accepted)
        assertEquals(emptyList<String>(), VeykadRenderInspector.containerIssues("video/avc",
            "audio/mp4a-latm",1080,1080,0,120,94,0,16000,0,1080,1080))
    }

    @Test fun exact_contract_rejects_wrong_geometry_rotation_codecs_and_fps() {
        listOf(squareProbe.copy(encodedWidth = 1088), squareProbe.copy(encodedHeight = 1920),
            squareProbe.copy(rotationDegrees = 90), squareProbe.copy(videoMime = "video/hevc"),
            squareProbe.copy(audioMime = null), squareProbe.copy(videoFps = 30),
            squareProbe.copy(videoFps = null)).forEach {
            assertTrue(!ExportContract.validateExact(it, square, 2000).accepted)
        }
    }

    @Test fun inspector_exports_actual_square_metadata_without_a_portrait_override() {
        val evidence = VeykadRenderInspector.ContainerEvidence("video/avc", "audio/mp4a-latm",
            1080,1080,0,120,94,0,0,1_983_333,1_984_000,667,emptyList(),60,2000)
        assertEquals(squareProbe, evidence.toExportProbe())
        assertTrue(ExportContract.validateExact(evidence.toExportProbe(), square,2000).accepted)
    }
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
