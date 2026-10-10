package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class RenderTargetPolicyTest {
    @Test fun recordable_and_timestamp_are_encoder_only() {
        assertFalse(RenderTargetPolicy.DISPLAY.recordable)
        assertNull(RenderTargetPolicy.DISPLAY.presentationTimeNs(123_456L))
        assertTrue(RenderTargetPolicy.ENCODER.recordable)
        assertEquals(123_456_000L, RenderTargetPolicy.ENCODER.presentationTimeNs(123_456L))
    }
    @Test fun output_viewport_fits_actual_surface_and_centers_bars() {
        assertEquals(OutputViewport(230, 0, 180, 320), OutputViewport.fit(OutputSize(720,1280), OutputSize(640,320)))
        assertEquals(OutputViewport(0, 230, 320, 180), OutputViewport.fit(OutputSize(1280,720), OutputSize(320,640)))
        assertEquals(OutputViewport(0,0,720,1280), OutputViewport.fit(OutputSize(720,1280), OutputSize(720,1280)))
        val odd=OutputViewport.fit(OutputSize(360,450),OutputSize(641,319))
        assertEquals(255,odd.width)
        assertEquals(193,odd.x)
        assertEquals(319,odd.height)
    }
    @Test fun invalid_timestamps_fail_before_presentation() {
        assertThrows(IllegalArgumentException::class.java) { RenderTargetPolicy.ENCODER.presentationTimeNs(-1L) }
        assertThrows(ArithmeticException::class.java) { RenderTargetPolicy.ENCODER.presentationTimeNs(Long.MAX_VALUE) }
    }
}
