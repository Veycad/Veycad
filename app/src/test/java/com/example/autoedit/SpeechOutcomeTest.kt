package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class SpeechOutcomeTest {
    @Test fun successfulPhrasesKeepTheirTimesWithoutClaimingWordAccuracy() {
        val cues=listOf(CaptionCue("a","Привет мир",100_000,800_000))
        val result=SpeechOutcomes.capture { cues } as SpeechOutcome.Success
        assertEquals(cues,result.cues)
        assertEquals(SpeechTimingGranularity.PHRASE,result.evidence.timing)
        assertNull(result.evidence.wordTimings)
        assertNull(result.evidence.confidence)
        assertFalse(result.evidence.accuracyMeasured)
    }
    @Test fun noSpeechAndBackendFailureAreDistinct() {
        val empty=SpeechOutcomes.capture { emptyList() } as SpeechOutcome.NoSpeech
        assertNull(empty.evidence.recognitionPerformed)
        assertEquals(SpeechNoSpeechReason.LEGACY_EMPTY,empty.evidence.reason)
        val error=IOException("Decoder failed")
        assertSame(error,(SpeechOutcomes.capture { throw error } as SpeechOutcome.Failure).cause)
    }
    @Test fun invalidBackendResultHasItsOwnFailureCodeAndSafeMessage() {
        val failure=SpeechOutcomes.capture {
            WhisperResultParser.cues(arrayOf("bad\trow"),0,16000,16000)
        } as SpeechOutcome.Failure
        assertEquals(SpeechFailureCode.RESULT_INVALID,failure.code)
        assertFalse(failure.userMessage.contains("bad"))
    }
    @Test fun cancellationRemainsCancellation() {
        assertThrows(CancellationException::class.java) { SpeechOutcomes.capture { throw CancellationException() } }
    }
}
