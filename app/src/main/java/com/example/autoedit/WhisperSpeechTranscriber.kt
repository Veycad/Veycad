package com.veycad.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal class WhisperSpeechTranscriber : SpeechTranscriber {
    override fun transcribe(context: Context, source: File, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue> {
        return transcribeOutcome(context,source,language,checkCancelled,onProgress).cuesOrThrow()
    }
    override fun transcribePcm(context: Context, pcm: PcmFile, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue> {
        return transcribePcmOutcome(context,pcm,language,checkCancelled,onProgress).cuesOrThrow()
    }
    override fun transcribeOutcome(context: Context, source: File, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): SpeechOutcome =
        SpeechOutcomes.captureOutcome {
            require(source.isFile && language in setOf("auto","ru","en"))
            val pcm=stage(SpeechFailureCode.AUDIO_DECODE) {
                TextPcmDecoder.decode(source,File(context.cacheDir,"text-work"),16000,1,checkCancelled)
            }
            try { transcribePcmOutcome(context,pcm,language,checkCancelled,onProgress) }
            finally { pcm.file.delete() }
        }
    override fun transcribePcmOutcome(context: Context, pcm: PcmFile, language: String, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): SpeechOutcome {
        var performed=false
        var verifiedModel:String?=null
        var handle=0L
        return SpeechOutcomes.captureOutcome(evidence={ SpeechEvidence(recognitionPerformed=performed,modelSha256=verifiedModel) }) {
          try {
            require(language in setOf("auto","ru","en"))
            require(pcm.sampleRate==16000 && pcm.channels==1 && pcm.frames>=0 && pcm.file.isFile && pcm.file.length()>=pcm.frames*2)
            checkCancelled()
            if(!pcm.hasAudio) return@captureOutcome SpeechOutcome.NoSpeech(SpeechEvidence(
                recognitionPerformed=false,reason=SpeechNoSpeechReason.NO_AUDIO_TRACK))
            var cues=emptyList<CaptionCue>()
            val windows=SpeechWindows.forFrames(pcm.frames)
            RandomAccessFile(pcm.file,"r").use { input ->
                windows.forEachIndexed { index,(offset,count) ->
                    checkCancelled(); input.seek(offset*2)
                    val bytes=ByteArray(count*2); input.readFully(bytes)
                    val samples=FloatArray(count) { i ->
                        ((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort()/32768f
                    }
                    // Skip only digital silence; quiet intelligible speech must reach the model.
                    if(samples.any { it!=0f }) {
                        if(handle==0L) {
                            val model=stage(SpeechFailureCode.MODEL_UNAVAILABLE) { unpackModel(context,checkCancelled) }
                            verifiedModel=MODEL_SHA
                            handle=stage(SpeechFailureCode.MODEL_UNAVAILABLE) { WhisperNative.open(model.path) }
                            if(handle==0L) throw SpeechFailureException(SpeechFailureCode.MODEL_UNAVAILABLE)
                            onProgress(5)
                        }
                        performed=true
                        val rows=stage(SpeechFailureCode.NATIVE) {
                            WhisperNative.recognize(handle,samples,language,NativeCancellation(checkCancelled))
                        }
                        checkCancelled()
                        val incoming=WhisperResultParser.cues(rows,offset,pcm.frames,count)
                        cues=CaptionSegmenter.merge(cues,incoming,pcm.frames*1_000_000/16000)
                    }
                    onProgress(5+(index+1)*95/windows.size)
                }
            }
            val evidence=SpeechEvidence(recognitionPerformed=performed,modelSha256=verifiedModel)
            if(cues.isNotEmpty()) SpeechOutcome.Success(cues,evidence)
            else SpeechOutcome.NoSpeech(evidence.copy(reason=if(performed) SpeechNoSpeechReason.RECOGNIZED_NO_SPEECH else SpeechNoSpeechReason.DIGITAL_SILENCE))
          } finally { if(handle!=0L) WhisperNative.close(handle) }
        }
    }
    private inline fun <T> stage(code:SpeechFailureCode,action:()->T):T = try { action() }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(failed:SpeechFailureException) { throw failed }
        catch(failed:Exception) { throw SpeechFailureException(code,failed) }
    private fun unpackModel(context: Context, checkCancelled: () -> Unit): File = synchronized(modelInstallLock) {
        val directory=File(context.filesDir,"speech-model").apply { mkdirs() }
        val model=File(directory,"ggml-base-$MODEL_SHA.bin")
        if(SpeechModelIntegrity.matches(model,MODEL_SHA,147951465L,checkCancelled)) return@synchronized model
        val partial=File(directory,"model.partial")
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            context.assets.open("speech/ggml-base.bin").use { input ->
                java.io.FileOutputStream(partial).use { output ->
                    val bytes=ByteArray(128*1024)
                    while(true) { checkCancelled(); val n=input.read(bytes); if(n<0) break; digest.update(bytes,0,n); output.write(bytes,0,n) }
                    output.fd.sync()
                }
            }
            if(digest.digest().joinToString("") { "%02x".format(it) }!=MODEL_SHA)
                throw SpeechFailureException(SpeechFailureCode.MODEL_INTEGRITY)
            check(partial.renameTo(model)) { "Не удалось сохранить модель речи" }
            model
        } finally { partial.delete() }
    }
    companion object {
        private val modelInstallLock=Any()
        const val MODEL_SHA="60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    }
}
