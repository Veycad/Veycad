package com.veycad.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class WhisperDeviceTest {
    @Test fun missingAudioAndInvalidInputAreExplicitOutcomes() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val source=SyntheticVideo.create(File(context.cacheDir,"no-audio-outcome.mp4"),1000)
        val result=WhisperSpeechTranscriber().transcribeOutcome(context,source,"auto",{}, {}) as SpeechOutcome.NoSpeech
        assertEquals(SpeechNoSpeechReason.NO_AUDIO_TRACK,result.evidence.reason)
        assertEquals(false,result.evidence.recognitionPerformed)
        val missing=WhisperSpeechTranscriber().transcribeOutcome(context,File(context.cacheDir,"does-not-exist.mp4"),"ru",{}, {}) as SpeechOutcome.Failure
        assertEquals(SpeechFailureCode.INPUT_INVALID,missing.code)
        assertFalse(missing.userMessage.contains(context.cacheDir.path))
    }
    @Test fun preparedPcmIsBorrowedAndNativeRecognitionCanBeCancelled() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val pcm=File(context.cacheDir,"cancel-speech.raw").apply { writeBytes(ByteArray(16000*2*2)) }
        val silence=WhisperSpeechTranscriber().transcribePcmOutcome(context,PcmFile(pcm,16000,1,32000,true),"ru",{}, {}) as SpeechOutcome.NoSpeech
        assertEquals(false,silence.evidence.recognitionPerformed)
        assertEquals(SpeechNoSpeechReason.DIGITAL_SILENCE,silence.evidence.reason)
        pcm.writeBytes(ByteArray(16000*2*2) { 40 })
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val modelReady=java.util.concurrent.atomic.AtomicBoolean()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            WhisperSpeechTranscriber().transcribePcm(context,PcmFile(pcm,16000,1,32000,true),"ru",
                { if(modelReady.get() && calls.incrementAndGet()>8) throw java.util.concurrent.CancellationException() },
                { if(it==5) modelReady.set(true) })
        }
        assertTrue(pcm.isFile)
        assertTrue(calls.get()>8)
        pcm.delete()
    }
    @Test fun automaticLanguageRecognizesRussianSpeech() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val testContext=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val wav=testContext.assets.open("speech/russian.wav").use { it.readBytes() }
        val buffer=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).apply { position(12) }
        var bytes=ByteArray(0)
        while(buffer.remaining()>=8) {
            val id=ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII); val size=buffer.int
            if(id=="data") { bytes=ByteArray(size).also { buffer.get(it) }; break }
            buffer.position(buffer.position()+size+(size and 1))
        }
        val pcm=File(context.cacheDir,"russian.raw").apply { writeBytes(bytes) }
        val video=SyntheticVideo.create(File(context.cacheDir,"russian-video.mp4"),8000)
        val source=File(context.cacheDir,"russian-speech.mp4")
        TextAudioMuxer.mux(video,PcmFile(pcm,16000,1,bytes.size/2L,true),source,{})
        val cues=WhisperSpeechTranscriber().transcribe(context,source,"auto",{}, {})
        assertTrue(cues.joinToString(" ") { it.text }.lowercase().contains("субтитр"))
        assertTrue(cues.all { it.endUs<=8_000_000 })
        val quiet=ByteArray(bytes.size)
        for(i in 0 until bytes.size/2) {
            val sample=(((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort().toInt()/32).toShort().toInt()
            quiet[2*i]=sample.toByte(); quiet[2*i+1]=(sample shr 8).toByte()
        }
        pcm.writeBytes(quiet)
        val quietResult=WhisperSpeechTranscriber().transcribePcmOutcome(context,PcmFile(pcm,16000,1,quiet.size/2L,true),"ru",{}, {}) as SpeechOutcome.Success
        assertEquals(true,quietResult.evidence.recognitionPerformed)
        assertEquals(WhisperSpeechTranscriber.MODEL_SHA,quietResult.evidence.modelSha256)
        assertTrue(quietResult.cues.joinToString(" ") { it.text }.lowercase().contains("субтитр"))
        pcm.delete()
    }
    @Test fun actualBundledMultilingualModelRecognizesEnglishAndSilenceStaysEmpty() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val testContext=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val wav=testContext.assets.open("speech/jfk.wav").use { it.readBytes() }
        val buffer=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(12)
        var samples=FloatArray(0)
        while(buffer.remaining()>=8) {
            val id=ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
            val size=buffer.int
            if(id=="data") { samples=FloatArray(size/2) { buffer.short/32768f }; break }
            buffer.position(buffer.position()+size+(size and 1))
        }
        assertTrue(samples.isNotEmpty())
        val pcm=File(context.cacheDir,"jfk.raw")
        pcm.outputStream().buffered().use { output -> samples.forEach { value ->
            val s=(value*32768).toInt(); output.write(s and 255); output.write((s shr 8) and 255)
        } }
        val video=SyntheticVideo.create(File(context.cacheDir,"jfk-video.mp4"),12000)
        val speech=File(context.cacheDir,"jfk-speech.mp4")
        TextAudioMuxer.mux(video,PcmFile(pcm,16000,1,samples.size.toLong(),true),speech,{})
        val cues=WhisperSpeechTranscriber().transcribe(context,speech,"en",{}, {})
        assertTrue(cues.joinToString(" ") { it.text }.lowercase().contains("country"))
        assertTrue(cues.all { it.startUs<it.endUs && it.endUs<=12_000_000 })
        assertTrue(cues.zipWithNext().all { (a,b) -> a.endUs<=b.startUs })
        pcm.delete()
        val silent=SyntheticVideo.create(File(context.cacheDir,"speech-silence.mp4"),2000)
        val silencePcm=File(context.cacheDir,"silence.raw").apply { writeBytes(ByteArray(16000*2*2)) }
        val silentAudio=File(context.cacheDir,"speech-silent-audio.mp4")
        TextAudioMuxer.mux(silent,PcmFile(silencePcm,16000,1,32000,true),silentAudio,{})
        assertTrue(WhisperSpeechTranscriber().transcribe(context,silentAudio,"auto",{},{}).isEmpty())
        silencePcm.delete()
    }
}
