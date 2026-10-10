package com.veycad.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodec
import android.media.MediaMuxer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class TextExportDeviceTest {
    @Test fun nonzeroVideoPtsAndShortDelayedAudioKeepRelativeTimeWithoutLooping() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder=File(context.cacheDir,"text-offset-test").apply { mkdirs() }
        val source=File(folder,"source.mp4")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("media/offset-short-audio.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project=TextVideoProbe.project(source)
        assertEquals(2_000_000L,project.durationUs)
        val output=File(folder,"result.mp4")
        TextVideoExporter.export(context,project,output,{}, {})
        val pcm=TextPcmDecoder.decode(output,folder,16000,1,{})
        try {
            assertEquals(32000L,pcm.frames)
            val bytes=pcm.file.readBytes()
            fun energy(from:Int,to:Int):Long=(from until to).sumOf { i ->
                kotlin.math.abs(((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort().toInt()).toLong()
            }
            assertTrue("Delayed audio retains initial silence",energy(1600,6400)*5<energy(10000,14000))
            assertTrue("Short audio ends in silence instead of looping",energy(24000,30000)*5<energy(10000,14000))
        } finally { pcm.file.delete() }
    }
    @Test fun passthroughDoesNotSwitchToASecondCompatibleAudioTrack() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder=File(context.cacheDir,"text-multiple-audio-test").apply { mkdirs() }
        val source=File(folder,"two-audio.mp4")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("media/two-audio.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val result=File(folder,"result.mp4")
        TextVideoExporter.export(context,TextVideoProbe.project(source),result,{}, {})
        val decoded=TextPcmDecoder.decode(result,folder,16000,1,{})
        try {
            val bytes=decoded.file.readBytes()
            fun power(frequency:Int):Double {
                var real=0.0; var imaginary=0.0
                for(i in 8000 until 24000) {
                    val sample=((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort().toDouble()
                    val phase=2*Math.PI*frequency*i/16000
                    real+=sample*kotlin.math.cos(phase); imaginary+=sample*kotlin.math.sin(phase)
                }
                return real*real+imaginary*imaginary
            }
            assertTrue("Keep first MP3 audio (330 Hz), not second AAC (880 Hz)",power(330)>power(880)*20)
        } finally { decoded.file.delete() }
    }
    private fun audioPackets(file:File):List<Pair<Long,Long>> {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack((0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            })
            val buffer=java.nio.ByteBuffer.allocate(65536)
            val result=mutableListOf<Pair<Long,Long>>()
            while(true) {
                buffer.clear(); val size=extractor.readSampleData(buffer,0); if(size<0) break
                val bytes=ByteArray(size); buffer.position(0); buffer.get(bytes)
                result.add(extractor.sampleTime to java.util.zip.CRC32().apply { update(bytes) }.value)
                extractor.advance()
            }
            return result
        } finally { extractor.release() }
    }
    @Test fun ninetyDegreeSourceExportsAtItsOrientedGeometry() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder=File(context.cacheDir,"text-rotation-test").apply { mkdirs() }
        val original=SyntheticVideo.create(File(folder,"raw.mp4"),1000)
        val rotated=File(folder,"rotated.mp4")
        val extractor=MediaExtractor(); val muxer=MediaMuxer(rotated.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(original.path); extractor.selectTrack(0)
            val track=muxer.addTrack(extractor.getTrackFormat(0)); muxer.setOrientationHint(90); muxer.start()
            val buffer=java.nio.ByteBuffer.allocate(65536); val info=MediaCodec.BufferInfo()
            while(true) {
                buffer.clear(); val size=extractor.readSampleData(buffer,0); if(size<0) break
                info.set(0,size,extractor.sampleTime,if(extractor.sampleFlags and 1!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track,buffer,info); extractor.advance()
            }
            muxer.stop()
        } finally { extractor.release(); muxer.release() }
        val project=TextVideoProbe.project(rotated)
        assertEquals(240,project.width); assertEquals(160,project.height)
        val output=File(folder,"result.mp4")
        TextVideoExporter.export(context,project.copy(layers=listOf(project.hook("Поворот"))),output,{}, {})
        MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(output.path)
            assertEquals("240",metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("160",metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            assertEquals("0",metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            assertNotNull(metadata.getFrameAtTime(500_000))
        }
    }
    @Test fun burnsCyrillicInCorrectBandAndKeepsOriginalAudioClock() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder=File(context.cacheDir,"text-export-test").apply { mkdirs() }
        val raw=File(folder,"tone.raw")
        raw.outputStream().buffered().use { out ->
            repeat(48000*2) { frame ->
                val sample=if(frame<24000) 0 else (sin(2*Math.PI*440*frame/48000)*12000).toInt()
                repeat(2) { out.write(sample and 255); out.write((sample shr 8) and 255) }
            }
        }
        val silent=SyntheticVideo.create(File(folder,"silent.mp4"),2000)
        val source=File(folder,"source.mp4")
        TextAudioMuxer.mux(silent,PcmFile(raw,48000,2,96000,true),source,{})
        val project=TextEditProject(source.path,2_000_000,160,240,layers=listOf(
            TextLayer("hook","Привет",0,1_000_000,TextStyle(darkPlate=false))))
        val result=File(folder,"result.mp4")
        TextVideoExporter.export(context,project,result,{}, {})
        assertEquals("Compatible AAC packets are copied without another lossy encode",audioPackets(source),audioPackets(result))
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(result.path)
            assertTrue(kotlin.math.abs(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()-2000)<100)
            assertEquals("yes",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            val active= retriever.getFrameAtTime(500_000,MediaMetadataRetriever.OPTION_CLOSEST)!!
            val after= retriever.getFrameAtTime(1_500_000,MediaMetadataRetriever.OPTION_CLOSEST)!!
            fun bright(bitmap:android.graphics.Bitmap,top:Int,bottom:Int):Int =
                (top until bottom).sumOf { y -> (0 until bitmap.width).count { x -> Color.red(bitmap.getPixel(x,y))>210 } }
            assertTrue("Hook is visible in upper band",bright(active,20,80)>10)
            assertEquals("Hook ends before second frame",0,bright(after,20,80))
            assertEquals("Text is not vertically flipped",0,bright(active,160,220))
            File(context.filesDir,"text-test-active.png").outputStream().use { active.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            File(context.filesDir,"text-test-after.png").outputStream().use { after.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            active.recycle(); after.recycle()
        }
        val decoded=TextPcmDecoder.decode(result,folder,16000,1,{})
        val samples=decoded.file.readBytes()
        fun energy(start:Int,end:Int):Long = (start until end).sumOf { i ->
            val s=((samples[i*2].toInt() and 255) or (samples[i*2+1].toInt() shl 8)).toShort().toInt()
            kotlin.math.abs(s).toLong()
        }
        assertTrue("Original silence precedes tone",energy(1000,6000)*5<energy(10000,15000))
        decoded.file.delete()
    }
}
