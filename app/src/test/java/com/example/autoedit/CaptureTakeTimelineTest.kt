package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class CaptureTakeTimelineTest {
    private val script = CaptureScript.fear
    @Test fun earlyStopDoesNotInventLaterTakes() {
        val windows = CaptureTakeTimeline.windows(script, 3, 18_000)
        assertEquals(1, windows.size)
        assertEquals(18_000L, windows.single().durationMs)
        assertFalse(windows.single().complete)
        assertFalse(CaptureTakeTimeline.usableForMontage(windows.single()))
    }
    @Test fun encodedFrameToleranceDoesNotAdmitInterruptedBestTake() {
        val nearEnd = CaptureTakeTimeline.windows(script, 3,
            script.durationMs - CaptureTakeTimeline.COMPLETION_TOLERANCE_MS).single()
        val interrupted = CaptureTakeTimeline.windows(script, 3,
            script.durationMs - CaptureTakeTimeline.COMPLETION_TOLERANCE_MS - 1).single()
        assertTrue(CaptureTakeTimeline.usableForMontage(nearEnd))
        assertFalse(CaptureTakeTimeline.usableForMontage(interrupted))
    }
    @Test fun breakIsNotPartOfEitherTake() {
        val firstEnd = script.durationMs
        assertTrue(CaptureTakeTimeline.position(script, 3, firstEnd).recovering)
        val nextStart = firstEnd + CaptureScript.RECOVERY_MS
        val position = CaptureTakeTimeline.position(script, 3, nextStart)
        assertEquals(2, position.ordinal)
        assertFalse(position.recovering)
        assertEquals(0, position.offsetMs)
        val windows = CaptureTakeTimeline.windows(script, 3, nextStart + 1_000)
        assertEquals(firstEnd, windows[0].endMs)
        assertEquals(nextStart, windows[1].startMs)
    }
    @Test fun endingDuringBreakPreservesOnlyFinishedTake() {
        val windows = CaptureTakeTimeline.windows(script, 3, script.durationMs + 1_000)
        assertEquals(1, windows.size)
        assertTrue(windows.single().complete)
    }
    @Test fun completeSeriesHasNoFinalBreak() {
        val end = CaptureTakeTimeline.totalDurationMs(script, 3)
        assertEquals(script.durationMs * 3 + CaptureScript.RECOVERY_MS * 2, end)
        assertTrue(CaptureTakeTimeline.position(script, 3, end).finished)
        assertEquals(3, CaptureTakeTimeline.windows(script, 3, end).count { it.complete })
    }
    @Test fun oneTakeDoesNotAskForAnother() {
        assertEquals(script.durationMs, CaptureTakeTimeline.totalDurationMs(script, 1))
        assertTrue(CaptureTakeTimeline.position(script, 1, script.durationMs).finished)
    }
    @Test(expected = IllegalArgumentException::class) fun refusesUnboundedRecording() {
        CaptureTakeTimeline.totalDurationMs(script, 50)
    }
    @Test fun onlyEligibleFiniteScoresCanWin() {
        val invalid = CaptureTakePreparation.Assessment(1, false, 100f, "Нет движения")
        val unknown = CaptureTakePreparation.Assessment(2, true, Float.NaN, "Не измерено")
        val good = CaptureTakePreparation.Assessment(3, true, .5f, "Подходит")
        assertEquals(good, CaptureTakePreparation.choose(listOf(invalid, unknown, good)))
        assertNull(CaptureTakePreparation.choose(listOf(invalid, unknown)))
    }
    @Test fun equalScoresKeepStableOriginalOrder() {
        val a = CaptureTakePreparation.Assessment(1, true, .5f, "Подходит")
        val b = a.copy(ordinal = 2)
        assertEquals(a, CaptureTakePreparation.choose(listOf(b, a)))
    }
    @Test fun manualChoiceAcceptsEligibleTakeWithUnknownRecommendationScore() {
        val unknown = CaptureTakePreparation.Assessment(2, true, Float.NaN, "Подходит для монтажа")
        assertNull(CaptureTakePreparation.select(listOf(unknown), null))
        assertEquals(unknown, CaptureTakePreparation.select(listOf(unknown), 2))
        assertNull(CaptureTakePreparation.select(listOf(unknown.copy(eligible = false)), 2))
    }
}
