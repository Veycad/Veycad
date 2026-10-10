package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class LoopingMusicPcmStreamTest {
    @Test fun selected_tail_loops_on_output_zero_clock_and_keeps_measured_gaps() {
        var opened = 0
        var closed = 0
        val format = PcmFormat(8_000, 1)
        val provider = PcmStreamProvider { id, start ->
            assertEquals("app-music", id); assertEquals(1000, start); opened++
            object : PcmStream {
                override val format = format
                var count = 0
                override fun read() = when (count++) {
                    0 -> PcmChunk(format, 0, ShortArray(16) { 111 })
                    1 -> PcmChunk(format, 3000, ShortArray(8) { 222 })
                    else -> null
                }
                override fun close() { closed++ }
            }
        }
        LoopingMusicPcmStream(provider, 1000).use { stream ->
            val chunks = List(6) { stream.read()!! }
            assertEquals(listOf(0L,1000L,2000L,3000L,4000L,5000L), chunks.map { it.startPtsUs })
            assertEquals(listOf(111,0,222,111,0,222), chunks.map { (it.sample(0,0)*32768).toInt() })
            assertTrue(chunks.all { it.frames == 8 })
        }
        assertEquals(2, opened); assertEquals(opened, closed)
    }

    @Test fun empty_selected_tail_fails_instead_of_spinning_or_successful_silence() {
        var closed = 0
        val provider = PcmStreamProvider { _, _ -> object : PcmStream {
            override val format = PcmFormat(48_000, 2)
            override fun read(): PcmChunk? = null
            override fun close() { closed++ }
        } }
        try { LoopingMusicPcmStream(provider, 9000).use { it.read() }; fail("empty tail") }
        catch (_: IllegalStateException) {}
        assertEquals(1, closed)
    }

    @Test fun reviewed_legacy_window_keeps_complete_frames() {
        assertEquals(480 until 1000, AudioPcmWindow.frames(1_940_000,1000,48_000,1_950_000,20_000))
        assertEquals(0 until 480, AudioPcmWindow.frames(1_960_000,1000,48_000,1_950_000,20_000))
        assertEquals(1 until 4, AudioPcmWindow.frames(0,4,44_100,1,100))
    }
}
