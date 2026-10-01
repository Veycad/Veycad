package com.example.autoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRevealContinuityTest {
    private fun reveal(progress: Float) = GpuTransitionModel.sample(
        MontageGraph.Transition.FOREGROUND_REENTRY, progress
    ).originalBackgroundReveal

    @Test fun originalPlateIsCompleteWhenTransitionEnds() {
        assertEquals(0f, reveal(.88f), 0f)
        assertEquals(1f, reveal(1f), 0f)
        assertEquals(1f, reveal(1.01f), 0f)
        // Last 30 fps frame must not leave the former 26% jump into the full plate.
        assertTrue(1f - reveal(1f - 1f / 75f) < .04f)
    }

    @Test fun revealIsMonotonicAndDoesNotMoveTheSettledSubject() {
        val frames = (66..75).map {
            GpuTransitionModel.sample(MontageGraph.Transition.FOREGROUND_REENTRY, it / 75f)
        }
        assertTrue(frames.zipWithNext().all { (a, b) ->
            b.originalBackgroundReveal >= a.originalBackgroundReveal
        })
        frames.forEach { assertEquals(1f, it.foregroundReentry, .00001f) }
    }
}
