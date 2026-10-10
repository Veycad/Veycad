package com.veycad.app

import android.opengl.EGL14
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class EncoderCapabilityProbeDeviceTest {
    private fun probe() = EncoderCapabilityProbe(cache = mutableMapOf())

    private fun supportedProfile(probe: EncoderCapabilityProbe): ExportProfile {
        val candidates = probe.query().mapNotNull {
            ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16, ExportQuality.P720, 30, it).profile
        }
        assertTrue("Device must declare at least one 720p AVC Surface encoder", candidates.isNotEmpty())
        return candidates.first()
    }

    @Test fun supported_and_explicitly_unsupported_combinations() {
        val probe = probe()
        val profile = supportedProfile(probe)
        assertTrue(probe.verifySurfaceStart(profile) {})
        val caps = probe.query().first { it.codecName == profile.encoderName }
        assertNull(ExportProfileResolver.resolve(ProjectAspect.PORTRAIT_9_16,
            ExportQuality.K4, 100_000, caps).profile)
        assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
    }

    @Test fun cancellation_after_egl_attach_releases_resources_and_does_not_cache_failure() {
        val probe = probe()
        val profile = supportedProfile(probe)
        assertThrows(CancellationException::class.java) {
            probe.verifySurfaceStart(profile) {
                if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) throw CancellationException()
            }
        }
        assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
        // Same key must reach the real codec again, rather than use a cached false.
        assertTrue(probe.verifySurfaceStart(profile) {})
        repeat(3) { assertTrue(probe().verifySurfaceStart(profile) {}) }
        assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
    }

    @Test fun failure_after_codec_configuration_releases_codec_before_retry() {
        val probe = probe()
        val profile = supportedProfile(probe)
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            probe.verifySurfaceStart(profile) {
                if (++checks == 3) throw IllegalStateException("Abort after configure")
            }
        }
        assertTrue(probe.verifySurfaceStart(profile) {})
        assertFalse(probe.verifySurfaceStart(profile.copy(encoderName = "absent.codec")) {})
        assertTrue(probe().verifySurfaceStart(profile) {})
        assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
    }
}
