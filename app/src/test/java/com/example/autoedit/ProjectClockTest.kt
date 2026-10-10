package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ProjectClockTest {
    @Test fun absoluteFrameTimesDoNotAccumulateRounding() {
        assertEquals(1_000_000L, ProjectClock(60).timeUs(60))
        assertEquals(100_000L, ProjectClock(30).timeUs(3))
        assertEquals(33_333L, ProjectClock(30).timeUs(1))
        assertEquals(16_667L, ProjectClock(60).timeUs(1))
        assertEquals(35_791_394_116_667L, ProjectClock(60).timeUs(Int.MAX_VALUE))
    }
    @Test fun nearestFrameRoundsAbsoluteTimeAndRejectsOverflow() {
        assertEquals(0, ProjectClock(60).nearestFrame(8_333))
        assertEquals(1, ProjectClock(60).nearestFrame(8_334))
        assertEquals(3, ProjectClock(30).nearestFrame(100_000))
        assertEquals(Int.MAX_VALUE, ProjectClock(60).nearestFrame(35_791_394_116_667L))
        assertThrows(IllegalArgumentException::class.java) { ProjectClock(60).nearestFrame(Long.MAX_VALUE) }
    }
    @Test fun rejectsInvalidClockAndSpans() {
        assertThrows(IllegalArgumentException::class.java) { ProjectClock(24) }
        assertThrows(IllegalArgumentException::class.java) { ProjectClock(30).timeUs(-1) }
        assertThrows(IllegalArgumentException::class.java) { ProjectClock(30).nearestFrame(-1) }
        assertThrows(IllegalArgumentException::class.java) { FrameSpan(-1, 5) }
        assertThrows(IllegalArgumentException::class.java) { FrameSpan(5, 5) }
        assertEquals(5, FrameSpan(3, 8).length)
    }
}
