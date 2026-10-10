package com.veycad.app

import java.util.concurrent.CancellationException

internal enum class SpeechTimingGranularity { PHRASE }
internal enum class SpeechNoSpeechReason { NO_AUDIO_TRACK, DIGITAL_SILENCE, RECOGNIZED_NO_SPEECH, LEGACY_EMPTY }
internal enum class SpeechFailureCode(val userMessage:String) {
    INPUT_INVALID("Не удалось открыть звуковые данные видео."),
    AUDIO_DECODE("Не удалось декодировать звук этого видео."),
    MODEL_UNAVAILABLE("Не удалось загрузить модель речи."),
    MODEL_INTEGRITY("Модель речи повреждена. Попробуйте повторить обработку."),
    IO("Не удалось прочитать или сохранить звуковые данные."),
    NATIVE("Ошибка локального распознавания речи. Попробуйте повторить."),
    RESULT_INVALID("Распознавание вернуло некорректный результат. Попробуйте повторить."),
    UNKNOWN("Не удалось распознать речь. Попробуйте повторить.")
}
internal class SpeechFailureException(val code:SpeechFailureCode,cause:Exception?=null):Exception(code.userMessage,cause)
internal data class SpeechWordTiming(val text:String,val startUs:Long,val endUs:Long)
/** No word accuracy or confidence is inferred from phrase timestamps or a successful call. */
internal data class SpeechEvidence(
    val timing:SpeechTimingGranularity=SpeechTimingGranularity.PHRASE,
    val wordTimings:List<SpeechWordTiming>?=null,
    val confidence:Double?=null,
    val accuracyMeasured:Boolean=false,
    val recognitionPerformed:Boolean?=null,
    val reason:SpeechNoSpeechReason?=null,
    val modelSha256:String?=null,
    val confidenceCalibrated:Boolean=false
) {
    init {
        require(confidence==null || (confidence.isFinite() && confidence in 0.0..1.0))
        require(!confidenceCalibrated || confidence!=null)
    }
    val needsReview:Boolean get()=!confidenceCalibrated || confidence==null || confidence<.75
}
internal sealed interface SpeechOutcome {
    data class Success(val cues:List<CaptionCue>,val evidence:SpeechEvidence):SpeechOutcome
    data class NoSpeech(val evidence:SpeechEvidence):SpeechOutcome
    data class Failure(val cause:Exception,val evidence:SpeechEvidence,val code:SpeechFailureCode):SpeechOutcome {
        val userMessage:String get()=code.userMessage
    }
}
internal fun SpeechOutcome.cuesOrThrow():List<CaptionCue> = when(this) {
    is SpeechOutcome.Success -> cues
    is SpeechOutcome.NoSpeech -> emptyList()
    is SpeechOutcome.Failure -> throw cause
}
internal object SpeechOutcomes {
    fun capture(action:()->List<CaptionCue>):SpeechOutcome = captureOutcome {
            val cues=action().toList()
            if(cues.isEmpty()) SpeechOutcome.NoSpeech(SpeechEvidence(reason=SpeechNoSpeechReason.LEGACY_EMPTY))
            else SpeechOutcome.Success(cues,SpeechEvidence())
    }
    fun captureOutcome(evidence:()->SpeechEvidence={SpeechEvidence()},action:()->SpeechOutcome):SpeechOutcome =
        try { action() } catch(cancelled:CancellationException) { throw cancelled }
        catch(failed:Exception) {
            val code=when(failed) {
                is SpeechFailureException -> failed.code
                is java.io.IOException -> SpeechFailureCode.IO
                is IllegalArgumentException -> SpeechFailureCode.INPUT_INVALID
                else -> SpeechFailureCode.UNKNOWN
            }
            SpeechOutcome.Failure(failed,evidence(),code)
        }
}
