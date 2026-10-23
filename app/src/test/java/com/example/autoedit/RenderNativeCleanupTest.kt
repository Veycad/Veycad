package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class RenderNativeCleanupTest {
    @Test fun failedGlCleanupRetainsEglSurfaceAndEncoderUntilOwnerRetry() {
        val calls = mutableListOf<String>()
        var fail = true
        val cleanup = RenderNativeCleanup(
            stopEncoder = { calls += "stop" }, releaseDecoders = { calls += "decoders" },
            releaseGl = { calls += "gl"; if (fail) error("context unavailable") },
            releaseTarget = { calls += "egl" }, releaseInputSurface = { calls += "surface" },
            releaseEncoder = { calls += "encoder" }, releaseMuxer = { calls += "muxer" })
        assertThrows(IllegalStateException::class.java) { cleanup.close() }
        assertEquals(listOf("stop", "decoders", "gl", "muxer"), calls)
        fail = false
        cleanup.close(); cleanup.close()
        assertEquals(listOf("stop", "decoders", "gl", "muxer", "gl", "egl", "surface", "encoder"), calls)
    }

    @Test fun independentlyRetiredEncoderAndMuxerAreNotReplayedAfterStopFailure() {
        val calls = mutableListOf<String>()
        val cleanup = RenderNativeCleanup(stopEncoder = { calls += "stop"; error("stop failed") },
            releaseDecoders = { calls += "decoders" }, releaseGl = { calls += "gl" },
            releaseTarget = { calls += "egl" }, releaseInputSurface = { calls += "surface" },
            releaseEncoder = { calls += "encoder" }, releaseMuxer = { calls += "muxer" })
        assertThrows(IllegalStateException::class.java) { cleanup.close() }
        cleanup.close()
        assertEquals(1, calls.count { it == "stop" })
        assertEquals(1, calls.count { it == "encoder" })
        assertEquals(1, calls.count { it == "muxer" })
    }
}
