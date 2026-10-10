package com.veycad.app
import org.junit.Assert.*
import org.junit.Test
class SpeechWindowTest {
    @Test fun boundedOverlappingWindowsIncludeTail() {
        val windows=SpeechWindows.forFrames(65*16000L)
        assertEquals(listOf(0L,28*16000L,56*16000L),windows.map { it.first })
        assertTrue(windows.all { it.second<=30*16000 })
        assertEquals(9*16000,windows.last().second)
        assertTrue(SpeechWindows.forFrames(0).isEmpty())
    }
}
