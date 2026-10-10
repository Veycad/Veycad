package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class CaptionSegmenterTest {
    @Test fun overlappingWindowDoesNotDuplicateSpeechButKeepsActualRepetition() {
        val old = listOf(CaptionCue("a", "Привет", 10_000_000, 11_000_000))
        val next = listOf(CaptionCue("b", " привет ", 10_500_000, 11_100_000),
            CaptionCue("c", "Привет", 12_000_000, 13_000_000),
            CaptionCue("d", "Конец", 14_000_000, 16_000_000))
        val result = CaptionSegmenter.merge(old, next, 15_000_000)
        assertEquals(listOf("a", "c", "d"), result.map { it.id })
        assertEquals(15_000_000L, result.last().endUs)
    }
}
