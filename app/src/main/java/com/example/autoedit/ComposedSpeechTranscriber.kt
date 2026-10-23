package com.veycad.app

import android.content.Context
import java.io.RandomAccessFile

/** Borrows the source-only speech bus from the audio owner, before gain/headroom/app music.
 * The caller keeps preparation open until all STT/render/mux borrowers finish, including on
 * cancellation. Ready-MP4 captions continue to use [SpeechTranscriber.transcribeOutcome].
 * No recognition cache, project mutation, decoder, mixer or model ownership lives here. */
internal class ComposedSpeechTranscriber(private val transcriber: SpeechTranscriber) {
    /** Common-editor STT needs PCM only; it must not depend on the device's AAC container support. */
    fun transcribeOutcome(context: Context, prepared: ComposedProjectAudio, settings: AudioMixSettings,
        language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): SpeechOutcome = SpeechOutcomes.captureOutcome {
        checkCancelled()
        require(settings.mode != AudioMixMode.MUSIC && settings.sourceGain > 0f) { "Для субтитров включите исходную речь" }
        require(language in setOf("auto", "ru", "en"))
        prepared.requireOpen()
        val frames = prepared.sampleFrames / 3
        require(frames <= Long.MAX_VALUE / 1000000)
        RandomAccessFile(prepared.speechPcmFile, "r").use { input ->
            require(input.length() == Math.multiplyExact(frames, 2L)) { "Prepared speech byte count does not match sample frames" }
        }
        transcriber.transcribePcmOutcome(context,
            PcmFile(prepared.speechPcmFile, 16000, 1, frames, prepared.hasSourceAudio), language, checkCancelled, onProgress)
    }

    /** [hasSourceAudio] must come from the same inspected/compiled source snapshot that produced
     * [prepared], never from the mixed AAC file or the amplitude of the speech PCM. A present
     * silent track is still audio; the existing recognizer determines the no-speech reason. */
    fun transcribeOutcome(
        context: Context,
        prepared: SourceAacPreparation,
        settings: AudioMixSettings,
        hasSourceAudio: Boolean,
        language: String,
        checkCancelled: () -> Unit,
        onProgress: (Int) -> Unit
    ): SpeechOutcome = SpeechOutcomes.captureOutcome {
        checkCancelled()
        require(settings.mode == AudioMixMode.SOURCE || settings.mode == AudioMixMode.SPEECH_AND_MUSIC) {
            "Source captions require an audible source mode"
        }
        require(settings.sourceGain.isFinite() && settings.sourceGain > 0f) {
            "Source captions require positive source gain"
        }
        require(language in setOf("ru", "en", "auto"))
        try { prepared.requireOpen() }
        catch (closed: IllegalStateException) { throw SpeechFailureException(SpeechFailureCode.INPUT_INVALID, closed) }
        require(prepared.sampleFrames >= 0L)
        // Owner preparation already checks speechFrames == composed 48 kHz sampleFrames / 3.
        // Integer division is its exact contract, including a fractional final 16 kHz frame.
        val frames = prepared.sampleFrames / 3L
        require(frames <= Long.MAX_VALUE / 1_000_000L) { "Speech timestamp range overflow" }
        val bytes = Math.multiplyExact(frames, 2L)
        // Opening read-only proves this is a readable borrowed file; no PCM copies or changes.
        RandomAccessFile(prepared.speechPcmFile, "r").use { input ->
            require(input.length() == bytes) { "Prepared speech byte count does not match sample frames" }
        }
        checkCancelled()
        transcriber.transcribePcmOutcome(context,
            PcmFile(prepared.speechPcmFile, 16000, 1, frames, hasSourceAudio),
            language, checkCancelled, onProgress)
    }
}
