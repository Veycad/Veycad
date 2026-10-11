package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SpeechTokenEvidenceTest {
    private val commit="48f628a84833905ee4a0658ee6d4a5c915ce1997"
    private fun hex(bytes:ByteArray)=bytes.joinToString("") { "%02x".format(it) }
    private fun window(text:String,tokens:List<ByteArray>,times:List<Pair<Int,Int>>,
        offset:Long=0,frames:Int=16000,requested:String="auto"):SpeechWindowEvidence {
        val rows=listOf("H\t1\t$commit\t${"a".repeat(64)}\t$frames\t1\t7275\t100\t200",
            "S\t0\t0\t${frames/160}\t0.1\t${hex(text.toByteArray())}")+tokens.mapIndexed { i,bytes ->
            "T\t0\t$i\t$i\tTEXT\t${times[i].first}\t${times[i].second}\t0.8\t${hex(bytes)}"
        }
        return SpeechTokenParser.window(rows.toTypedArray(),offset,offset+frames,frames,requested)
    }
    @Test fun splitUtf8TokensGroupWholeRussianWordsAndPreserveObservedTimes() {
        val bytes="Привет 😀 мир".toByteArray()
        val parts=listOf(bytes.copyOfRange(0,1),bytes.copyOfRange(1,12),bytes.copyOfRange(12,18),bytes.copyOfRange(18,bytes.size))
        val result=window("Привет 😀 мир",parts,listOf(10 to 20,20 to 40,40 to 50,60 to 80),16000)
        assertEquals(listOf("Привет","мир"),result.words.map { it.text })
        assertEquals(1_100_000L,result.words.first().startUs)
        assertEquals(1_400_000L,result.words.first().endUs)
        assertEquals(listOf(0,1),result.words.first().contributors.map { it.tokenIndex })
        assertEquals(2_000_000L,result.cues.single().endUs)
        assertEquals(SpeechLanguageOrigin.BACKEND_DETECTED,result.languageOrigin)
        assertEquals("ru",result.language)
        assertEquals(.8,result.words.first().tokenProbabilityMean!!,.000001)
        assertEquals("d0",result.tokens.first().bytesHex)
    }
    @Test fun unknownAndZeroLengthTokenTimesRemainUntimedWithoutInterpolation() {
        val result=window("да да да",listOf("да ".toByteArray(),"да ".toByteArray(),"да".toByteArray()),
            listOf(-1 to -1,20 to 20,30 to 50))
        assertNull(result.words[0].startUs); assertNull(result.words[1].endUs)
        assertEquals(300_000L,result.words[2].startUs)
        assertEquals(3,result.words.size)
        assertEquals(-1L,result.tokens.first().startTick)
        assertEquals(SpeechLanguageOrigin.REQUESTED,window("да",listOf("да".toByteArray()),listOf(0 to 10),requested="ru").languageOrigin)
    }
    @Test fun malformedWireUtf8ProbabilityAndOutOfWindowTimesAreFailures() {
        val valid=listOf("H\t1\t$commit\t${"a".repeat(64)}\t16000\t1\t7275\t100\t200",
            "S\t0\t0\t100\t0.1\td0b4d0b0","T\t0\t0\t1\tTEXT\t10\t20\t0.8\td0b4d0b0")
        val bad=listOf(emptyArray(),valid.drop(1).toTypedArray(),
            listOf(valid.first(),"T\t-1\t0\t1\tTEXT\t10\t20\t0.8\td0b4d0b0").toTypedArray(),
            valid.dropLast(1).plus("T\t0\t0\t1\tTEXT\t-2\t20\t0.8\td0b4d0b0").toTypedArray(),
            valid.dropLast(1).plus("T\t0\t0\t1\tTEXT\t10\t101\t0.8\td0b4d0b0").toTypedArray(),
            valid.dropLast(1).plus("T\t0\t0\t1\tTEXT\t30\t20\t0.8\td0b4d0b0").toTypedArray(),
            valid.dropLast(1).plus("T\t0\t0\t1\tTEXT\t10\t20\tNaN\td0b4d0b0").toTypedArray(),
            valid.dropLast(1).plus("T\t0\t0\t201\tTEXT\t10\t20\t0.8\td0b4d0b0").toTypedArray(),
            valid.dropLast(1).plus("T\t0\t0\t1\tTEXT\t10\t20\t0.8\td0").toTypedArray())
        for(rows in bad) {
            val failure=assertThrows(SpeechFailureException::class.java) { SpeechTokenParser.window(rows,0,16000,16000,"auto") }
            assertEquals(SpeechFailureCode.RESULT_INVALID,failure.code)
        }
    }
    @Test fun rawCollectionsAreDefensiveAndManualCueChangesCannotRewriteThem() {
        val observed=window("да",listOf("да".toByteArray()),listOf(10 to 20))
        val mutable=mutableListOf(observed)
        val raw=SpeechRawEvidence(mutable)
        mutable.clear()
        assertEquals(1,raw.windows.size)
        assertThrows(UnsupportedOperationException::class.java) { (raw.windows as MutableList).clear() }
        val corrected=observed.cues.single().copy(text="Исправлено",manuallyReviewed=true)
        assertEquals("да",raw.words.single().text); assertTrue(corrected.manuallyReviewed)
        assertThrows(UnsupportedOperationException::class.java) { (raw.words.single().contributors as MutableList).clear() }
    }
    @Test fun overlapSelectsOneObservedWordWithoutAveragingOrRemovingRepeatedSpeech() {
        val first=window("да да",listOf("да ".toByteArray(),"да".toByteArray()),listOf(80 to 90,91 to 99))
        val next=window("да да",listOf("да ".toByteArray(),"да".toByteArray()),listOf(0 to 10,11 to 19),12800)
        val raw=SpeechRawEvidence(listOf(first,next))
        assertEquals(2,raw.words.size)
        assertEquals(listOf(800_000L,910_000L),raw.words.map { it.startUs })
        assertEquals(2,raw.suppressedDuplicates.size)
        assertEquals(2,raw.windows.size)
    }
    @Test fun emptySuccessfulWindowStillContainsBackendAndLanguageEvidence() {
        val observed=SpeechTokenParser.window(arrayOf("H\t1\t$commit\t${"a".repeat(64)}\t16000\t1\t7275\t100\t200"),0,16000,16000,"auto")
        assertTrue(observed.cues.isEmpty()); assertTrue(observed.tokens.isEmpty())
        assertEquals(commit,observed.backendCommit); assertEquals("ru",observed.language)
    }
    @Test fun cancellationPreservesTheFirstExceptionEvenForOneShotCallback() {
        val failure=java.util.concurrent.CancellationException("one shot")
        var first=true
        val cancellation=NativeCancellation { if(first) { first=false; throw failure } }
        assertTrue(cancellation.isCancelled())
        assertTrue(cancellation.isCancelled())
        assertSame(failure,assertThrows(java.util.concurrent.CancellationException::class.java) { cancellation.rethrow() })
    }
}
