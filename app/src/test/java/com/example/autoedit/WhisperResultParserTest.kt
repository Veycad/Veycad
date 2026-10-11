package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class WhisperResultParserTest {
    @Test fun localPhraseTimesAreClippedToActualWindowAndMappedToOutputClock() {
        val cue=WhisperResultParser.cues(arrayOf("400000\t1500000\tПривет"),16000,48000,16000).single()
        assertEquals(1_400_000L,cue.startUs)
        assertEquals(2_000_000L,cue.endUs)
        assertEquals("Привет",cue.text)
    }
    @Test fun malformedRowsNeverTurnIntoSuccessfulNoSpeech() {
        for(row in listOf("missing","x\t10\tText","0\t0\tText","-1\t10\tText","1000000\t1100000\tText")) {
            assertThrows(SpeechFailureException::class.java) { WhisperResultParser.cues(arrayOf(row),0,16000,16000) }
        }
    }
}
