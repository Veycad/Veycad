package com.veycad.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Focused orchestration evidence only. These tests do not certify native STT or RU quality. */
@RunWith(AndroidJUnit4::class)
class ComposedSpeechTranscriberDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val folder = TemporaryFolder()
    private val audible = AudioMixSettings(AudioMixMode.SOURCE)
    // Hand-authored s16le mono: +1, -1, 0, +2, -2, 0. Deliberately very quiet.
    private val quiet = byteArrayOf(1, 0, -1, -1, 0, 0, 2, 0, -2, -1, 0, 0)

    private fun prepared(bytes: ByteArray = quiet, frames: Long = 18L): SourceAacPreparation =
        SourceAacPreparation(folder.newFile().apply { writeBytes(byteArrayOf(7, 8)) },
            folder.newFile().apply { writeBytes(bytes) }, frames, PcmCompositionStats(0, 0), 0)

    private class RecordingOwner(var result: SpeechOutcome) : SpeechTranscriber {
        var calls = 0
        var input: PcmFile? = null
        var language: String? = null
        var seenContext: Context? = null
        var cancellation: (() -> Unit)? = null
        var progress: ((Int) -> Unit)? = null
        var duringCall: () -> Unit = {}
        override fun transcribe(context: Context, source: File, language: String,
            checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue> =
            error("Composed speech must not decode MP4")
        override fun transcribePcm(context: Context, pcm: PcmFile, language: String,
            checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue> =
            error("Bridge must preserve typed owner outcome")
        override fun transcribeOutcome(context: Context, source: File, language: String,
            checkCancelled: () -> Unit, onProgress: (Int) -> Unit): SpeechOutcome =
            error("Composed speech must not decode MP4")
        override fun transcribePcmOutcome(context: Context, pcm: PcmFile, language: String,
            checkCancelled: () -> Unit, onProgress: (Int) -> Unit): SpeechOutcome {
            calls++
            input = pcm
            this.language = language
            seenContext = context
            cancellation = checkCancelled
            progress = onProgress
            duringCall()
            return result
        }
    }

    private fun evidence(): SpeechEvidence {
        val cue = CaptionCue("observed", "Привет", 0, 300)
        val raw = SpeechRawEvidence(listOf(SpeechWindowEvidence(0, 6, "owner-backend",
            "owner-bridge", "ru", SpeechLanguageOrigin.REQUESTED, listOf(cue),
            listOf(SpeechRawSegment(0, 0, 1, .01, "Привет")), emptyList(), emptyList())))
        return SpeechEvidence(recognitionPerformed = true, requestedLanguage = "ru", inputFrames = 6,
            alignmentKind = SpeechAlignmentKind.TOKEN_HEURISTIC, rawEvidence = raw)
    }

    private fun assertBorrowed(prepared: SourceAacPreparation, bytes: ByteArray) {
        prepared.requireOpen()
        assertArrayEquals(byteArrayOf(7, 8), prepared.aacFile.readBytes())
        assertArrayEquals(bytes, prepared.speechPcmFile.readBytes())
    }

    @Test fun rawPcmWithoutVideoReachesOnlyTypedPcmRouteOnceAndRetainsQuietBytes() {
        val prepared = prepared()
        val evidence = evidence()
        val success = SpeechOutcome.Success(listOf(CaptionCue("observed", "Привет", 0, 300)), evidence)
        val owner = RecordingOwner(success)
        val bridge = ComposedSpeechTranscriber(owner)
        try {
            val result = bridge.transcribeOutcome(context, prepared, audible, true, "ru", {}, {})
            assertSame(success, result)
            assertSame(evidence, (result as SpeechOutcome.Success).evidence)
            assertSame(evidence.rawEvidence, result.evidence.rawEvidence)
            assertEquals(1, owner.calls)
            val pcm = requireNotNull(owner.input)
            assertSame(prepared.speechPcmFile, pcm.file)
            assertEquals(16000, pcm.sampleRate)
            assertEquals(1, pcm.channels)
            assertEquals(6L, pcm.frames)
            assertTrue(pcm.hasAudio)
            assertSame(context, owner.seenContext)
            assertEquals("ru", owner.language)
            assertBorrowed(prepared, quiet)
        } finally { prepared.close() }
        assertFalse(prepared.aacFile.exists())
        assertFalse(prepared.speechPcmFile.exists())
    }

    @Test fun readyMp4StillUsesOwnerVideoDecodeRoute() {
        // This real decoder route must remain usable; it must not require SourceAacPreparation.
        val source = SyntheticVideo.create(folder.newFile("ready.mp4"), 1000)
        val result = WhisperSpeechTranscriber().transcribeOutcome(context, source, "auto", {}, {})
        if (result is SpeechOutcome.Failure) throw result.cause
        assertEquals(SpeechNoSpeechReason.NO_AUDIO_TRACK, (result as SpeechOutcome.NoSpeech).evidence.reason)
        assertTrue(source.isFile)
    }

    @Test fun partialFinalSpeechFrameUsesOwnerTruncationRatherThanAacPacketPadding() {
        // Twenty 48 kHz presentation frames contain six complete 16 kHz speech frames.
        val prepared = prepared(frames = 20L)
        val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
        try {
            ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, true, "ru", {}, {})
            assertEquals(6L, owner.input!!.frames)
            assertEquals(1, owner.calls)
            assertBorrowed(prepared, quiet)
        } finally { prepared.close() }
    }

    @Test fun requestedLanguagesAndCallbacksReachTheSameOwnerCall() {
        for (language in listOf("ru", "en", "auto")) {
            val prepared = prepared()
            val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
            val progress = mutableListOf<Int>()
            var checked = 0
            val cancel: () -> Unit = { checked++ }
            val report: (Int) -> Unit = { progress.add(it) }
            owner.duringCall = { owner.cancellation!!.invoke(); owner.progress!!.invoke(37); owner.progress!!.invoke(100) }
            try {
                ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, true, language, cancel, report)
                assertEquals(language, owner.language)
                assertSame(cancel, owner.cancellation)
                assertSame(report, owner.progress)
                assertEquals(listOf(37, 100), progress)
                assertTrue(checked >= 2)
                assertEquals(1, owner.calls)
                assertBorrowed(prepared, quiet)
            } finally { prepared.close() }
        }
    }

    @Test fun noSpeechAndFailureKeepOutcomeEvidenceCauseAndCallerOwnership() {
        val cause = IOException("owner decoder detail")
        val evidence = evidence()
        val outcomes = listOf(SpeechOutcome.NoSpeech(evidence.copy(reason = SpeechNoSpeechReason.RECOGNIZED_NO_SPEECH)),
            SpeechOutcome.Failure(cause, evidence, SpeechFailureCode.NATIVE))
        for (outcome in outcomes) {
            val prepared = prepared()
            val owner = RecordingOwner(outcome)
            try {
                val result = ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, true, "ru", {}, {})
                assertSame(outcome, result)
                if (result is SpeechOutcome.Failure) {
                    assertSame(cause, result.cause)
                    assertSame(evidence, result.evidence)
                    assertSame(evidence.rawEvidence, result.evidence.rawEvidence)
                    assertEquals(SpeechFailureCode.NATIVE, result.code)
                }
                assertEquals(1, owner.calls)
                assertBorrowed(prepared, quiet)
            } finally { prepared.close() }
        }
    }

    @Test fun cancellationDuringOwnerCallPreservesExceptionCauseAndBorrowedFiles() {
        val prepared = prepared()
        val cause = IOException("cancel detail")
        val cancellation = CancellationException("cancelled").apply { initCause(cause) }
        var cancelled = false
        val check: () -> Unit = { if (cancelled) throw cancellation }
        val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
        owner.duringCall = { cancelled = true; owner.cancellation!!.invoke() }
        try {
            val thrown = assertThrows(CancellationException::class.java) {
                ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, true, "en", check, {})
            }
            assertSame(cancellation, thrown)
            assertSame(cause, thrown.cause)
            assertEquals(1, owner.calls)
            assertBorrowed(prepared, quiet)
        } finally { prepared.close() }
    }

    @Test fun thrownOwnerFailureIsVisibleAndKeepsItsCodeAndOriginalCause() {
        val prepared = prepared()
        val cause = IOException("model read failed")
        val failure = SpeechFailureException(SpeechFailureCode.MODEL_INTEGRITY, cause)
        val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
        owner.duringCall = { throw failure }
        try {
            val result = ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, true, "auto", {}, {})
                as SpeechOutcome.Failure
            assertEquals(SpeechFailureCode.MODEL_INTEGRITY, result.code)
            assertSame(failure, result.cause)
            assertSame(cause, result.cause.cause)
            assertEquals(1, owner.calls)
            assertBorrowed(prepared, quiet)
        } finally { prepared.close() }
    }

    @Test fun cancellationBeforeCallDoesNotInvokeOwnerOrCloseFiles() {
        val prepared = prepared()
        val cancellation = CancellationException("already cancelled")
        val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
        try {
            assertSame(cancellation, assertThrows(CancellationException::class.java) {
                ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, true, "ru", { throw cancellation }, {})
            })
            assertEquals(0, owner.calls)
            assertBorrowed(prepared, quiet)
        } finally { prepared.close() }
    }

    @Test fun musicAndMutedSourceAreRejectedBeforeRecognition() {
        val excluded = listOf(AudioMixSettings(AudioMixMode.MUSIC),
            AudioMixSettings(AudioMixMode.SOURCE, sourceGain = 0f),
            AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, sourceGain = 0f))
        for (settings in excluded) {
            val prepared = prepared()
            val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
            try {
                val result = ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, settings, true, "ru", {}, {})
                assertEquals(SpeechFailureCode.INPUT_INVALID, (result as SpeechOutcome.Failure).code)
                assertEquals(0, owner.calls)
                assertBorrowed(prepared, quiet)
            } finally { prepared.close() }
        }
    }

    @Test fun arbitrarilyQuietAudibleSourceMixReachesOwnerBeforeGainOrMusic() {
        val prepared = prepared()
        val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
        try {
            ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared,
                AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, sourceGain = Float.MIN_VALUE), true, "ru", {}, {})
            assertEquals(1, owner.calls)
            assertArrayEquals(quiet, owner.input!!.file.readBytes())
            assertBorrowed(prepared, quiet)
        } finally { prepared.close() }
    }

    @Test fun inspectedTrackPresenceIsForwardedEvenWhenPreparedBytesAreIdenticalSilence() {
        for (hasSourceAudio in listOf(false, true)) {
            val bytes = ByteArray(12)
            val prepared = prepared(bytes)
            val expected = SpeechOutcome.NoSpeech(SpeechEvidence(reason = if (hasSourceAudio)
                SpeechNoSpeechReason.DIGITAL_SILENCE else SpeechNoSpeechReason.NO_AUDIO_TRACK))
            val owner = RecordingOwner(expected)
            try {
                val result = ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, hasSourceAudio, "auto", {}, {})
                assertEquals(hasSourceAudio, owner.input!!.hasAudio)
                assertSame(expected, result)
                assertEquals(1, owner.calls)
                assertBorrowed(prepared, bytes)
            } finally { prepared.close() }
        }
    }

    @Test fun closedMissingTruncatedExtraAndOverflowedInputNeverInvokesOwner() {
        val cases = listOf<(SourceAacPreparation) -> Unit>(
            { it.close() }, { assertTrue(it.speechPcmFile.delete()) }, { assertTrue(it.aacFile.delete()) },
            { it.speechPcmFile.writeBytes(byteArrayOf(1, 0)) },
            { it.speechPcmFile.appendBytes(byteArrayOf(0, 0)) })
        for (invalidate in cases) {
            val prepared = prepared()
            invalidate(prepared)
            val aacBefore = prepared.aacFile.takeIf { it.isFile }?.readBytes()
            val speechBefore = prepared.speechPcmFile.takeIf { it.isFile }?.readBytes()
            assertInvalidWithoutInvocation(prepared)
            assertEquals(aacBefore != null, prepared.aacFile.isFile)
            assertEquals(speechBefore != null, prepared.speechPcmFile.isFile)
            if (aacBefore != null) assertArrayEquals(aacBefore, prepared.aacFile.readBytes())
            if (speechBefore != null) assertArrayEquals(speechBefore, prepared.speechPcmFile.readBytes())
            prepared.close()
        }
        for (frames in listOf(-1L, Long.MAX_VALUE)) {
            val prepared = prepared(frames = frames)
            try {
                assertInvalidWithoutInvocation(prepared)
                assertBorrowed(prepared, quiet)
            } finally { prepared.close() }
        }
    }

    @Test fun invalidLanguageDoesNotInvokeOwnerOrReleasePreparation() {
        val prepared = prepared()
        try { assertInvalidWithoutInvocation(prepared, "fr"); assertBorrowed(prepared, quiet) }
        finally { prepared.close() }
    }

    private fun assertInvalidWithoutInvocation(prepared: SourceAacPreparation, language: String = "ru") {
        val owner = RecordingOwner(SpeechOutcome.NoSpeech(SpeechEvidence()))
        val result = ComposedSpeechTranscriber(owner).transcribeOutcome(context, prepared, audible, true, language, {}, {})
        assertEquals(SpeechFailureCode.INPUT_INVALID, (result as SpeechOutcome.Failure).code)
        assertNotNull(result.cause)
        assertEquals(0, owner.calls)
    }
}
