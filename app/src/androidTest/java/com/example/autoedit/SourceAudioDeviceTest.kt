package com.veycad.app

import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native runtime gates, discovered by tools/run_ui_tests.ps1. Compilation alone is not PASS. */
@RunWith(AndroidJUnit4::class)
class SourceAudioDeviceTest {
    private fun directory(name: String) = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "source-audio-native-evidence/$name-${System.nanoTime()}").apply { mkdirs() }
    private fun clip(id: String, start: Int, end: Int, rawStart: Long = 0, rawEnd: Long = 1_000_000) =
        SourceAudioClip(id, FrameSpan(start, end), SourceTimeMap(listOf(SourceTimeMap.Point(0, rawStart), SourceTimeMap.Point(end-start, rawEnd))))
    private fun pcm(composer: SourceAudioComposer): ShortArray {
        val bytes = ByteArrayOutputStream()
        composer.compose(AudioMixSettings(AudioMixMode.SOURCE), PcmSink { _, _, samples, frames ->
            repeat(frames * 2) { bytes.write(samples[it].toInt() and 255); bytes.write(samples[it].toInt() shr 8 and 255) }
        })
        return ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { buf -> ShortArray(buf.remaining()).also { buf.get(it) } }
    }
    private fun source(files: Map<String, File>, metrics: MediaCodecAudioDecoder.StreamMetrics = MediaCodecAudioDecoder.StreamMetrics(), cancel: () -> Unit = {}) =
        MediaCodecAudioDecoder.sourceProvider(files, metrics, cancel)

    @Test fun three_sources_silent_middle_early_eof_repeat_match_literal_markers_at_30_and_60() {
        val dir = directory("markers")
        val files = mapOf(
            "a" to SourceAudioNativeFixtures.wav(File(dir,"a.wav")) { 10_000 },
            "b" to SourceAudioNativeFixtures.wav(File(dir,"b.wav")) { -8_000 },
            "c" to SourceAudioNativeFixtures.wav(File(dir,"c.wav")) { 15_000 },
            "silent" to SyntheticVideo.create(File(dir,"silent.mp4"), 1000))
        for (fps in listOf(30,60)) {
            val n = fps / 5
            val clips = listOf(clip("a",0,n,100_000,300_000), clip("silent",n,n*2,0,200_000),
                clip("b",n*2,n*3,0,200_000), clip("c",n*3,n*4,900_000,1_100_000), clip("a",n*4,n*5,100_000,300_000))
            val metrics = MediaCodecAudioDecoder.StreamMetrics()
            val result = pcm(SourceAudioComposer(ProjectClock(fps),clips,source(files,metrics)))
            assertEquals(96_000,result.size)
            // Literal output positions, independent of map/helper arithmetic.
            for ((frame,value) in listOf(2400 to 10000,12000 to 0,21600 to -8000,31200 to 15000,36000 to 0,40800 to 10000))
                assertEquals("fps=$fps frame=$frame",value,result[frame*2].toInt())
            assertEquals(0,metrics.openDecoders); assertEquals(1,metrics.peakOpenDecoders)
            File(dir,"$fps-metrics.txt").writeText("buffers=${metrics.decodedBuffers}, maxBufferBytes=${metrics.peakDecoderBufferBytes}, open=${metrics.openDecoders}, peak=${metrics.peakOpenDecoders}")
        }
    }

    @Test fun actual_nonzero_audio_pts_gap_and_first_track_remain_independent_of_video_origin() {
        val dir = directory("origins-gap")
        val first = SourceAudioNativeFixtures.aac(File(dir,"first.m4a"),200_000,100_000,440.0)
        val second = SourceAudioNativeFixtures.aac(File(dir,"second.m4a"),0,0,900.0)
        val video = SyntheticVideo.create(File(dir,"video.mp4"),1000)
        val multi = SourceAudioNativeFixtures.remux(File(dir,"multi.mp4"),listOf(video,first,second),120_000,90)
        val metrics = MediaCodecAudioDecoder.StreamMetrics()
        val chunks = mutableListOf<Pair<Long,Int>>()
        var crossings = 0; var previous = 0.0
        source(mapOf("multi" to multi),metrics).open("multi",0)!!.use { stream ->
            while (true) {
                val chunk = stream.read() ?: break
                chunks += chunk.startPtsUs to chunk.frames
                repeat(chunk.frames) { index -> val now = chunk.sample(index,0); if (now >= 0 && previous < 0) crossings++; previous = now }
            }
            assertNull(stream.read())
        }
        File(dir,"actual-decoder-pts.txt").writeText(chunks.joinToString("\n") { "${it.first},${it.second}" })
        assertTrue("first raw audio=${chunks.first()}",chunks.first().first in 200_000..225_000)
        assertTrue("must retain authored 100ms gap",chunks.zipWithNext().any { (a,b) -> b.first - (a.first+a.second*1_000_000L/48000) > 75_000 })
        assertTrue("selected first 440Hz track: $crossings",crossings in 410..470)
        val result = pcm(SourceAudioComposer(ProjectClock(30),listOf(clip("multi",0,42,0,1_400_000)),source(mapOf("multi" to multi))))
        assertTrue((0 until 8_000).all { result[it*2] == 0.toShort() })
        assertTrue((35_600 until 38_000).all { result[it*2] == 0.toShort() })
        assertEquals(0,metrics.openDecoders)
    }

    @Test fun canonical_hold_and_ramp_use_independently_authored_native_waveform_markers() {
        val dir = directory("hold-ramp")
        val file = SourceAudioNativeFixtures.wav(File(dir,"markers.wav")) { frame -> when(frame) {
            in 5_570..5_630 -> 22_000 // raw116.667ms
            in 7_170..7_230 -> -22_000 // raw150ms
            else -> 0
        } }
        val map = SourceTimeMap(listOf(SourceTimeMap.Point(0,100_000),SourceTimeMap.Point(1,100_000),
            SourceTimeMap.Point(2,133_333),SourceTimeMap.Point(3,166_667)))
        val result = pcm(SourceAudioComposer(ProjectClock(30),listOf(SourceAudioClip("a",FrameSpan(0,3),map)),source(mapOf("a" to file))))
        assertEquals(0,result[800*2].toInt()); assertEquals(0,result[1600*2].toInt())
        assertTrue(result[2400*2] > 19_000); assertTrue(result[4000*2] < -19_000)
        // Variable saved frame durations, not a regenerated legacy speed integral.
        val ramp = SourceTimeMap(listOf(SourceTimeMap.Point(0,100_000),SourceTimeMap.Point(1,116_667),SourceTimeMap.Point(2,150_000),SourceTimeMap.Point(3,200_000)))
        val rampPcm = pcm(SourceAudioComposer(ProjectClock(30),listOf(SourceAudioClip("a",FrameSpan(0,3),ramp)),source(mapOf("a" to file))))
        assertTrue(rampPcm[1600*2] > 19_000); assertTrue(rampPcm[3200*2] < -19_000)
    }

    @Test fun prepare_before_video_borrows_premusic_pcm_and_mux_copies_real_video_pts_flags_rotation() {
        val dir = directory("prepare-mux")
        val wav = SourceAudioNativeFixtures.wav(File(dir,"source.wav")) { SourceAudioNativeFixtures.tone(it) }
        val composer = SourceAudioComposer(ProjectClock(30),listOf(clip("a",0,30)),source(mapOf("a" to wav)))
        AacEncoderMuxer.prepareSourceAudio(composer,AudioMixSettings(AudioMixMode.SOURCE),dir).use { prepared ->
            assertEquals(32_000,prepared.speechPcmFile.length())
            assertEquals(48_000,prepared.sampleFrames)
            assertTrue(prepared.peakAacPacketBytes in 1..65536)
            val seed = SyntheticVideo.create(File(dir,"seed.mp4"),1000)
            val video = SourceAudioNativeFixtures.remux(File(dir,"shifted.mp4"),listOf(seed),120_000,90)
            val output = File(dir,"output.mp4")
            AacEncoderMuxer.muxPreparedAudio(prepared,video,output,1_120_000)
            assertEquals(videoPackets(video),videoPackets(output))
            val videoFormat = trackFormat(output,"video/")
            assertEquals(90,videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            assertEquals(1_120_000,videoFormat.getLong(MediaFormat.KEY_DURATION))
            val audioDuration = trackFormat(output,"audio/").getLong(MediaFormat.KEY_DURATION)
            assertTrue("actual audio duration=$audioDuration",abs(audioDuration-1_000_000) <= 33_334)
            val decoded = MediaCodecAudioDecoder.decode(output,preserveFloatHeadroom=true)
            assertEquals(AudioFormat.ENCODING_PCM_FLOAT,decoded.pcmEncoding)
            assertTrue(abs(decoded.frameCount-48_000) <= 1600)
            File(dir,"actual-duration.txt").writeText("aac=$audioDuration decodedFrames=${decoded.frameCount} pcmEncoding=${decoded.pcmEncoding}")
            // Borrowed file is still owned/live after mux; an owner STT adapter is a later gate.
            assertTrue(prepared.speechPcmFile.isFile)
        }
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("source-audio-") || it.name.startsWith("source-speech-") })
    }

    @Test fun actual_aac_markers_match_authored_video_frames_and_duration_at_30_and_60() {
        val dir = directory("av-frames")
        val wave = SourceAudioNativeFixtures.wav(File(dir,"marker.wav")) { frame ->
            (30_000 * kotlin.math.exp(-((frame-24_000)/25.0)*((frame-24_000)/25.0))).toInt().toShort()
        }
        for (fps in listOf(30,60)) {
            val composer = SourceAudioComposer(ProjectClock(fps),listOf(clip("a",0,fps)),source(mapOf("a" to wave)))
            AacEncoderMuxer.prepareSourceAudio(composer,AudioMixSettings(AudioMixMode.SOURCE),dir).use { prepared ->
                val video = SyntheticVideo.create(File(dir,"video-$fps.mp4"),1000,fps=fps)
                val output = File(dir,"av-$fps.mp4")
                AacEncoderMuxer.muxPreparedAudio(prepared,video,output,1_000_000)
                val videoPts = videoPackets(output).map { it.substringBefore(':').toLong() }
                assertEquals(fps,videoPts.size)
                assertEquals(500_000,videoPts[fps/2])
                var peak = 0.0; var peakUs = 0L; var audioEndUs = 0L
                source(mapOf("out" to output)).open("out",0)!!.use { stream ->
                    while (true) {
                        val chunk = stream.read() ?: break
                        repeat(chunk.frames) { frame ->
                            val value = abs(chunk.sample(frame,0))
                            if (value > peak) { peak=value; peakUs=chunk.startPtsUs+frame*1_000_000L/48_000 }
                        }
                        audioEndUs=chunk.startPtsUs+chunk.frames*1_000_000L/48_000
                    }
                }
                File(dir,"av-$fps-evidence.txt").writeText("videoMarker=${videoPts[fps/2]}, audioPeak=$peakUs, audioEnd=$audioEndUs, peak=$peak")
                assertTrue("fps=$fps audio marker=$peakUs",abs(peakUs-500_000)<=1_000_000/fps)
                assertTrue("fps=$fps decoded audio end=$audioEndUs",abs(audioEndUs-1_000_000)<=1_000_000/fps)
            }
        }
    }

    @Test fun cancellation_after_decode_and_aac_begin_cleans_owned_files_then_reexports() {
        val dir = directory("cancel")
        val wav = SourceAudioNativeFixtures.wav(File(dir,"source.wav"),5) { SourceAudioNativeFixtures.tone(it) }
        val metrics = MediaCodecAudioDecoder.StreamMetrics()
        val cancelled = CancellationException("native fixture cancel")
        val provider = source(mapOf("a" to wav),metrics) { if(metrics.decodedBuffers >= 3) throw cancelled }
        val first = assertThrows(CancellationException::class.java) {
            pcm(SourceAudioComposer(ProjectClock(30),listOf(clip("a",0,150,0,5_000_000)),provider))
        }
        assertSame(cancelled,first); assertEquals(0,metrics.openDecoders)
        val composer = SourceAudioComposer(ProjectClock(30),listOf(clip("a",0,150,0,5_000_000)),source(mapOf("a" to wav)))
        assertThrows(CancellationException::class.java) {
            AacEncoderMuxer.prepareSourceAudio(composer,AudioMixSettings(AudioMixMode.SOURCE),dir,checkCancelled={
                if (dir.listFiles()!!.any { it.name.startsWith("source-audio-") && it.length() > 2048 }) throw cancelled
            })
        }
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("source-audio-") || it.name.startsWith("source-speech-") })
        val shortComposer = SourceAudioComposer(ProjectClock(30),listOf(clip("a",0,30)),source(mapOf("a" to wav)))
        AacEncoderMuxer.prepareSourceAudio(shortComposer,AudioMixSettings(AudioMixMode.SOURCE),dir).use { prepared ->
            val video = SyntheticVideo.create(File(dir,"video.mp4"),1000)
            val output = File(dir,"previous.mp4"); output.writeText("previous complete output")
            var checks = 0
            assertThrows(CancellationException::class.java) { AacEncoderMuxer.muxPreparedAudio(prepared,video,output,1_000_000) { if (++checks > 5) throw cancelled } }
            assertEquals("previous complete output",output.readText())
            assertTrue(dir.listFiles()!!.none { it.name.startsWith("source-mux-") })
            AacEncoderMuxer.muxPreparedAudio(prepared,video,output,1_000_000)
            assertEquals(30,videoPackets(output).size)
        }
    }

    @Test fun legacy_wave_mp3_windows_preserve_reviewed_delay_preroll_and_sample_boundaries() {
        val dir = directory("legacy-music")
        val wave = SourceAudioNativeFixtures.wav(File(dir,"intro.wav"),5) { if (it<96_000) 0 else SourceAudioNativeFixtures.tone(it) }
        val original = MediaCodecAudioDecoder.decode(wave,5_000_000)
        val tail = MediaCodecAudioDecoder.decode(wave,1_000_000,startUs=2_137_511)
        assertEquals(48_000,tail.frameCount)
        assertArrayEquals(original.mono().copyOfRange(102_601,150_601),tail.mono(),.0001f)
        val mp3 = File(dir,"fixture.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-120-bpm.mp3").use { input -> mp3.outputStream().use { input.copyTo(it) } }
        val analysis = MediaCodecAudioDecoder.decode(mp3,6_000_000,startUs=2_137_511)
        val export = MediaCodecAudioDecoder.decode(mp3,6_000_000,preserveFloatHeadroom=true,startUs=2_137_511)
        assertEquals(96_000,analysis.frameCount)
        assertEquals(analysis.frameCount,export.frameCount)
        assertArrayEquals(analysis.mono(),export.mono(),.002f)
        // The provider must retain an interpolation predecessor, unlike the cropped legacy call.
        source(mapOf("mp3" to mp3)).open("mp3",2_137_511)!!.use { stream ->
            val first=stream.read()!!
            assertTrue("preroll PTS=${first.startPtsUs}",first.startPtsUs<2_137_511)
            assertTrue(first.frames<=stream.format.sampleRate)
        }
    }

    @Test fun absent_track_and_corrupt_or_unknown_input_have_distinct_outcomes() {
        val dir = directory("failures")
        val silent = SyntheticVideo.create(File(dir,"video.mp4"),1000)
        val corrupt = File(dir,"corrupt.mp4").apply { writeBytes(byteArrayOf(1,3,4,9)) }
        val metrics = MediaCodecAudioDecoder.StreamMetrics()
        val provider = source(mapOf("silent" to silent,"corrupt" to corrupt),metrics)
        assertNull(provider.open("silent",0))
        assertThrows(Exception::class.java) { provider.open("corrupt",0) }
        assertThrows(IllegalArgumentException::class.java) { provider.open("missing",0) }
        assertEquals(0,metrics.openDecoders)
    }

    @Test fun long_repeated_sources_and_looped_selected_music_keep_bounded_native_working_set() {
        val dir = directory("bounded")
        val wav = SourceAudioNativeFixtures.wav(File(dir,"long.wav"),120) { SourceAudioNativeFixtures.tone(it) }
        val music = SourceAudioNativeFixtures.wav(File(dir,"music.wav")) { if(it<24_000) 0 else SourceAudioNativeFixtures.tone(it,880.0) }
        val metrics = MediaCodecAudioDecoder.StreamMetrics()
        val files = mapOf("a" to wav)
        val clips = listOf(clip("a",0,1800,0,60_000_000),clip("a",1800,3600,60_000_000,120_000_000))
        var frames = 0L
        val stats = SourceAudioComposer(ProjectClock(30),clips,source(files,metrics)).compose(
            AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC,.5f,.5f),PcmSink { _, start, _, count -> assertEquals(frames,start); frames+=count },
            music=MediaCodecAudioDecoder.loopingMusicProvider(music,500_000,metrics))
        assertEquals(5_760_000,frames); assertEquals(0,metrics.openDecoders)
        assertEquals(2,metrics.peakOpenDecoders); assertTrue(metrics.peakRetainedChunks in 1..2)
        assertEquals(0,metrics.retainedChunks)
        assertTrue(metrics.peakDecoderBufferBytes <= 48_000*8*4)
        assertTrue(stats.peakRetainedPcmBytes < 1_000_000)
        File(dir,"actual-buffer-accounting.txt").writeText("frames=$frames decoderBuffers=${metrics.decodedBuffers} maxDecoderBufferBytes=${metrics.peakDecoderBufferBytes} peakOpen=${metrics.peakOpenDecoders} peakRetained=${metrics.peakRetainedChunks} compositor=$stats")
    }

    @Test fun overlapping_fullscale_source_music_retains_float_aac_evidence_and_premusic_speech() {
        val dir = directory("peak")
        val wave = SourceAudioNativeFixtures.wav(File(dir,"fullscale.wav")) { SourceAudioNativeFixtures.tone(it,1000.0,32767.0) }
        val composer = SourceAudioComposer(ProjectClock(30),listOf(clip("a",0,30)),source(mapOf("a" to wave)))
        val mix = AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC,1f,1f)
        val music = MediaCodecAudioDecoder.loopingMusicProvider(wave)
        var pcmPeak = 0.0
        val mixedPcm = ByteArrayOutputStream()
        composer.compose(mix,PcmSink { _,_,samples,count -> repeat(count*2) {
            pcmPeak=maxOf(pcmPeak,abs(samples[it]/32768.0))
            mixedPcm.write(samples[it].toInt() and 255); mixedPcm.write(samples[it].toInt() shr 8 and 255)
        } },music=music)
        SourceAudioNativeFixtures.pcmWave(File(dir,"mixed-for-listening.wav"),mixedPcm.toByteArray(),48_000,2)
        assertTrue(pcmPeak <= .892)
        AacEncoderMuxer.prepareSourceAudio(composer,mix,dir,music).use { prepared ->
            prepared.aacFile.copyTo(File(dir,"mixed-for-listening.m4a"))
            prepared.speechPcmFile.copyTo(File(dir,"premusic-16k.pcm"))
            val decoded = MediaCodecAudioDecoder.decode(prepared.aacFile,preserveFloatHeadroom=true)
            assertEquals("Unclamped float evidence required",AudioFormat.ENCODING_PCM_FLOAT,decoded.pcmEncoding)
            val floats = ByteBuffer.allocate(decoded.interleavedSamples.size*4).order(ByteOrder.LITTLE_ENDIAN)
            decoded.interleavedSamples.forEach { floats.putFloat(it) }
            File(dir,"aac-decoded-float32.pcm").writeBytes(floats.array())
            val peak = decoded.interleavedSamples.maxOf { abs(it) }
            val fullScaleRuns = decoded.interleavedSamples.asSequence().windowed(16).count { block -> block.all { abs(it)>=.999f } }
            File(dir,"peak-evidence.txt").writeText("pcmPeak=$pcmPeak aacFloatPeak=$peak fullScaleRuns=$fullScaleRuns encoding=${decoded.pcmEncoding}; human listening NOT ASSESSED")
            assertTrue("AAC peak=$peak",peak <= 1f)
            assertEquals(0,fullScaleRuns)
        }
    }

    @Test fun premusic_speech_excludes_app_music_and_remains_borrowed_when_source_gain_is_zero() {
        val dir=directory("premusic")
        val silent=SourceAudioNativeFixtures.wav(File(dir,"silent.wav")) { 0 }
        val voiced=SourceAudioNativeFixtures.wav(File(dir,"voice.wav")) { SourceAudioNativeFixtures.tone(it,400.0,1200.0) }
        val score=SourceAudioNativeFixtures.wav(File(dir,"score.wav")) { SourceAudioNativeFixtures.tone(it,1000.0,16000.0) }
        for ((name,file) in listOf("silent" to silent,"voice" to voiced)) {
            val composer=SourceAudioComposer(ProjectClock(30),listOf(clip(name,0,30)),source(mapOf(name to file)))
            AacEncoderMuxer.prepareSourceAudio(composer,AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC,0f,1f),dir,
                MediaCodecAudioDecoder.loopingMusicProvider(score)).use { prepared ->
                val speech=prepared.speechPcmFile.readBytes()
                assertEquals(32000,speech.size)
                assertEquals(name=="silent",speech.all { it==0.toByte() })
                val audio=MediaCodecAudioDecoder.decode(prepared.aacFile)
                assertTrue(audio.interleavedSamples.any { abs(it)>.1f })
                prepared.speechPcmFile.copyTo(File(dir,"$name-premusic.pcm"))
            }
        }
        // Audible-source eligibility for captions is intentionally owned by T4/T8, not this PCM seam.
    }

    private fun trackFormat(file: File, prefix: String): MediaFormat {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(file.path); return (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) } }
        finally { extractor.release() }
    }

    private fun videoPackets(file: File): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack((0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") })
            val result = mutableListOf<String>(); val bytes = ByteBuffer.allocate(2*1024*1024)
            while (extractor.sampleTime>=0) {
                bytes.clear(); val size=extractor.readSampleData(bytes,0)
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes.array().copyOf(size)).joinToString("") { "%02x".format(it) }
                result += "${extractor.sampleTime}:${extractor.sampleFlags}:$hash"; extractor.advance()
            }
            return result
        } finally { extractor.release() }
    }
}
