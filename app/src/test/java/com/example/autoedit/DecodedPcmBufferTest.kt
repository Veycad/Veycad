package com.veycad.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DecodedPcmBufferTest {
    @Test fun actual_float_format_downmixes_without_rebasing_pts() {
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        listOf(.5f, -.25f, .125f, 1f, 1f, 1f).forEach { bytes.putFloat(it) }
        bytes.flip()
        val chunk = DecodedPcmBuffer.convert(bytes, 48_000, 3, 4, 720_000)!!
        assertEquals(PcmFormat(48_000, 1), chunk.format)
        assertEquals(720_000, chunk.startPtsUs)
        assertEquals(.125, chunk.sample(0, 0), .00004)
        assertEquals(32767 / 32768.0, chunk.sample(1, 0), .000001)
    }

    @Test fun pcm16_preserves_negative_fullscale_and_buffer_slice() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2) { bytes.putShort(99) }
        bytes.putShort(-32768); bytes.putShort(32767); bytes.putShort(1000); bytes.putShort(-1000)
        bytes.position(4)
        val chunk = DecodedPcmBuffer.convert(bytes, 48_000, 2, 2, 90_000)!!
        assertEquals(2, chunk.frames)
        assertEquals(-1.0, chunk.sample(0, 0), 0.0)
        assertEquals(90_000, chunk.startPtsUs)
    }

    @Test fun negative_priming_crops_whole_frames_but_positive_origin_remains() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { bytes.putShort((1000 + it).toShort()) }; bytes.flip()
        val chunk = DecodedPcmBuffer.convert(bytes, 48_000, 1, 2, -21)!!
        assertEquals(2, chunk.frames)
        assertEquals(21, chunk.startPtsUs)
        assertEquals(1002 / 32768.0, chunk.sample(0, 0), 0.0)
    }

    @Test fun malformed_nonfinite_oversize_and_unsupported_buffers_fail() {
        fun fails(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} }
        fails { DecodedPcmBuffer.convert(ByteBuffer.allocate(3), 48_000, 2, 2, 0) }
        fails { DecodedPcmBuffer.convert(ByteBuffer.allocate(192_004), 48_000, 2, 2, 0) }
        fails { DecodedPcmBuffer.convert(ByteBuffer.allocate(4), 48_000, 1, 123, 0) }
        fails { DecodedPcmBuffer.convert(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).apply { flip() }, 48_000, 1, 4, 0) }
    }
}
