package com.veycad.app

import android.content.Context
import java.io.File

internal interface SpeechTranscriber {
    fun transcribe(context: Context, source: File, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue>
    /** Borrowed signed s16le, 16 kHz mono, padded/aligned to the final video's zero-based clock. */
    fun transcribePcm(context: Context, pcm: PcmFile, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue>
    fun transcribeOutcome(context: Context, source: File, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): SpeechOutcome =
        SpeechOutcomes.capture { transcribe(context,source,language,checkCancelled,onProgress) }
    fun transcribePcmOutcome(context: Context, pcm: PcmFile, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): SpeechOutcome =
        SpeechOutcomes.capture { transcribePcm(context,pcm,language,checkCancelled,onProgress) }
}

internal object SpeechWindows {
    fun forFrames(frames: Long): List<Pair<Long,Int>> {
        require(frames>=0)
        return generateSequence(0L) { it+28*16000 }.takeWhile { it<frames }
            .map { it to minOf(30*16000L,frames-it).toInt() }.toList()
    }
}
