package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ExportProfileResolverTest {
    private fun capabilities(widthAlignment: Int = 2, heightAlignment: Int = 2,
                             min: Int = 1, max: Int = 100_000_000,
                             supports: (OutputSize, Int) -> Boolean = { _, _ -> true }) =
        EncoderCapabilityProbe.Capabilities("test.avc", "video/avc", widthAlignment,
            heightAlignment, min, max, supports)

    @Test fun resolves_all_twelve_exact_sizes() {
        val expected = listOf(
            listOf(OutputSize(720,1280), OutputSize(1080,1920), OutputSize(2160,3840)),
            listOf(OutputSize(1280,720), OutputSize(1920,1080), OutputSize(3840,2160)),
            listOf(OutputSize(720,720), OutputSize(1080,1080), OutputSize(2160,2160)),
            listOf(OutputSize(720,900), OutputSize(1080,1350), OutputSize(2160,2700)))
        ProjectAspect.entries.forEachIndexed { i, aspect ->
            ExportQuality.entries.forEachIndexed { j, quality ->
                val result = ExportProfileResolver.resolve(aspect, quality, 30, capabilities())
                assertNull(result.reason)
                assertEquals(expected[i][j], result.profile!!.size)
                assertEquals("test.avc", result.profile.encoderName)
            }
        }
    }

    @Test fun fear_explicit_portrait_stays_portrait() {
        val selected = ProjectFormats.forRecipe(MontageStyleCatalog.Recipe.FEAR_STROBE,
            ProjectFormatSelection(ProjectAspect.PORTRAIT_9_16, true))
        assertEquals(OutputSize(1080,1920), ExportProfileResolver.resolve(selected.aspect,
            ExportQuality.P1080, 30, capabilities()).profile!!.size)
    }

    @Test fun heartbeat_keeps_sixty_fps() {
        val result = ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16,
            ExportQuality.P720, 60, capabilities { size, fps -> size == OutputSize(720,1280) && fps == 60 })
        assertEquals(60, result.profile!!.fps)
        assertEquals(10_000_000, result.profile.bitrate)
    }

    @Test fun alignment_failure_disables_option_without_rounding() {
        val result = ExportProfileResolver.resolve(ProjectAspect.FEED_4_5, ExportQuality.P1080,
            30, capabilities(heightAlignment = 16))
        assertNull(result.profile)
        assertNotNull(result.reason)
    }

    @Test fun unsupported_rate_and_non_avc_disable_option() {
        assertNull(ExportProfileResolver.resolve(ProjectAspect.SQUARE_1_1, ExportQuality.P720,
            60, capabilities { _, fps -> fps == 30 }).profile)
        assertNull(ExportProfileResolver.resolve(ProjectAspect.SQUARE_1_1, ExportQuality.P720,
            30, capabilities().copy(mime = "video/hevc")).profile)
        assertNull(ExportProfileResolver.resolve(ProjectAspect.SQUARE_1_1, ExportQuality.P720,
            0, capabilities()).profile)
    }

    @Test fun codec_range_with_zero_lower_bound_keeps_positive_profile_bitrate() {
        val result = ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16,
            ExportQuality.P720, 30, capabilities(min = 0))
        assertNotNull(result.profile)
        assertEquals(5_000_000, result.profile!!.bitrate)
    }

    @Test fun bitrate_scales_with_pixel_area_and_fps_and_is_clamped() {
        val examples = listOf(
            Triple(ProjectAspect.PORTRAIT_9_16, ExportQuality.P720, 5_000_000),
            Triple(ProjectAspect.LANDSCAPE_16_9, ExportQuality.P1080, 8_000_000),
            Triple(ProjectAspect.SQUARE_1_1, ExportQuality.P720, 2_812_500),
            Triple(ProjectAspect.FEED_4_5, ExportQuality.P1080, 5_625_000),
            Triple(ProjectAspect.PORTRAIT_9_16, ExportQuality.K4, 32_000_000),
            Triple(ProjectAspect.SQUARE_1_1, ExportQuality.K4, 18_000_000))
        examples.forEach { (aspect, quality, bitrate) ->
            assertEquals(bitrate, ExportProfileResolver.resolve(aspect, quality, 30, capabilities()).profile!!.bitrate)
            assertEquals(bitrate * 2, ExportProfileResolver.resolve(aspect, quality, 60, capabilities()).profile!!.bitrate)
        }
        assertEquals(3_000_000, ExportProfileResolver.resolve(ProjectAspect.SQUARE_1_1,
            ExportQuality.P720, 30, capabilities(min = 3_000_000)).profile!!.bitrate)
        assertEquals(20_000_000, ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16,
            ExportQuality.K4, 60, capabilities(max = 20_000_000)).profile!!.bitrate)
    }

    @Test fun advertised_4k_start_failure_disables_only_that_pair() {
        var starts = 0
        val backend = object : EncoderCapabilityProbe.Backend {
            override fun query() = listOf(capabilities())
            override fun verifySurfaceStart(profile: ExportProfile, checkCancelled: () -> Unit): Boolean {
                starts++
                return profile.size != OutputSize(2160,3840) || profile.fps != 60
            }
        }
        val probe = EncoderCapabilityProbe(backend, { "build-a" }, mutableMapOf())
        val caps = probe.query().single()
        val failed = ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.K4, 60, caps).profile!!
        assertTrue(ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.K4, 60, caps).requiresSurfaceVerification)
        assertFalse(probe.verifySurfaceStart(failed) {})
        assertFalse(probe.verifySurfaceStart(failed) {})
        assertEquals(1, starts)
        assertNull(ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.K4, 60, caps).profile)
        assertNotNull(ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.K4, 30, caps).profile)
        assertNotNull(ExportProfileResolver.resolve(ProjectAspect.LANDSCAPE_16_9, ExportQuality.K4, 60, caps).profile)
        assertNotNull(ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.P1080, 60, caps).profile)
        val successful = ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.K4, 30, caps).profile!!
        assertTrue(probe.verifySurfaceStart(successful) {})
        assertFalse(ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.K4, 30, caps).requiresSurfaceVerification)
        assertTrue(ExportProfileResolver.resolve(ProjectAspect.LANDSCAPE_16_9, ExportQuality.K4, 60, caps).requiresSurfaceVerification)
        assertFalse(ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.P1080, 60, caps).requiresSurfaceVerification)
    }

    @Test fun cancelled_probe_is_not_cached_and_cache_distinguishes_bitrate_codec_and_build() {
        var starts = 0
        val backend = object : EncoderCapabilityProbe.Backend {
            override fun query() = listOf(capabilities())
            override fun verifySurfaceStart(profile: ExportProfile, checkCancelled: () -> Unit): Boolean {
                starts++
                checkCancelled()
                return true
            }
        }
        var build = "build-a"
        val probe = EncoderCapabilityProbe(backend, { build }, mutableMapOf())
        val profile = ExportProfileResolver.resolve(ProjectAspect.SQUARE_1_1, ExportQuality.K4,
            30, probe.query().single()).profile!!
        // Cancellation after the preflight check must also avoid writing the cache.
        var checks = 0
        assertThrows(CancellationException::class.java) {
            probe.verifySurfaceStart(profile) { if (++checks == 2) throw CancellationException() }
        }
        assertTrue(probe.verifySurfaceStart(profile) {})
        assertTrue(probe.verifySurfaceStart(profile) {})
        assertTrue(probe.verifySurfaceStart(profile.copy(bitrate = 19_000_000)) {})
        assertTrue(probe.verifySurfaceStart(profile.copy(encoderName = "other.avc")) {})
        build = "build-b"
        assertTrue(probe.verifySurfaceStart(profile) {})
        assertEquals(5, starts)
    }
}
