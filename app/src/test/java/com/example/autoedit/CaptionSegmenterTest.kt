package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class CaptionSegmenterTest {
    @Test fun containedSuffixDoesNotRepeatOrShortenExistingPhrase() {
        for (end in listOf(29_000_000L,30_000_000L)) {
            val previous=CaptionCue("a","hello world",27_000_000,30_000_000)
            assertEquals(listOf(previous),CaptionSegmenter.merge(listOf(previous),
                listOf(CaptionCue("b","world",28_000_000,end)),31_000_000))
        }
    }
    @Test fun overlapFromDifferentlySplitWindowsDoesNotStackCaptionBlocks() {
        val result=CaptionSegmenter.merge(listOf(CaptionCue("a","Начало фразы",0,2_000_000)),
            listOf(CaptionCue("b","фразы и конец",1_500_000,3_000_000)),3_000_000)
        assertEquals(2,result.size)
        assertEquals("и конец",result[1].text)
        assertTrue(result[0].endUs<=result[1].startUs)
    }
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
