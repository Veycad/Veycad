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
    private fun recordInspectedArtifact(file: File) {
        val root=InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val path=file.relativeTo(root).invariantSeparatorsPath
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        File(file.parentFile,"${file.name}.device-sha256.json").writeText("""{"path":"$path","sha256":"$hash"}""")
    }
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

    @Test fun native_evidence_survives_later_ui_resets_but_never_another_run() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.getDir("ui-fixtures",0),"evidence-lifecycle-${System.nanoTime()}").apply { mkdirs() }
        try {
            val evidence=File(root,"source-audio-native-evidence/case/marker.mp4").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
            val state=File(root,"product-state").apply { writeText("old state") }
            UiNativeEvidence.resetCase(root)
            assertArrayEquals(byteArrayOf(1,2,3,4),evidence.readBytes()); assertFalse(state.exists())
            state.writeText("new state")
            UiNativeEvidence.resetRun(root)
            assertFalse(evidence.exists()); assertEquals("new state",state.readText())
        } finally { root.deleteRecursively() }
    }

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
        val first = SourceAudioNativeFixtures.aac(File(dir,"first.m4a"),0,100_000,440.0)
        val second = SourceAudioNativeFixtures.aac(File(dir,"second.m4a"),0,0,900.0)
        val video = SyntheticVideo.create(File(dir,"video.mp4"),1000)
        // Second audio is a real zero anchor. Apply origins only when writing this final
        // multitrack container; a positive origin in a single-track file is normalized away.
        val multi = SourceAudioNativeFixtures.remux(File(dir,"multi.mp4"),listOf(video,first,second),120_000,90,
            audioOriginsUs=mapOf(1 to 200_000L))
        val raw = android.media.MediaExtractor()
        try {
            raw.setDataSource(multi.path)
            val origins = (0 until raw.trackCount).map { track ->
                raw.selectTrack(track); val pts=raw.sampleTime; raw.unselectTrack(track); pts
            }
            File(dir,"actual-extractor-origins.txt").writeText(origins.toString())
            assertEquals(listOf(120_000L,200_000L,0L),origins)
        } finally { raw.release() }
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
        val loopMetrics=MediaCodecAudioDecoder.StreamMetrics()
        val loop=MediaCodecAudioDecoder.loopingMusicProvider(multi,metrics=loopMetrics).open("app-music",0)!!
        val cancellation=CancellationException("during real initial audio gap")
        try {
            loop.use {
                repeat(2) {
                    val gap=loop.read()!!
                    assertEquals(0.0,gap.sample(0,0),0.0)
                    assertEquals(1,loopMetrics.retainedChunks)
                    assertEquals(1,loopMetrics.peakRetainedChunks)
                }
                throw cancellation
            }
        } catch (failure: CancellationException) { assertSame(cancellation,failure) }
        assertEquals(0,loopMetrics.openDecoders); assertEquals(0,loopMetrics.retainedChunks)
    }

    @Test fun wholly_after_audio_eof_window_is_valid_sticky_silence_and_subsequent_export_succeeds() {
        val dir=directory("after-eof")
        val audio=SourceAudioNativeFixtures.aac(File(dir,"short.m4a"))
        val video=SyntheticVideo.create(File(dir,"long-video.mp4"),4000)
        val input=SourceAudioNativeFixtures.remux(File(dir,"short-audio-long-video.mp4"),listOf(video,audio),videoEndUs=4_000_000)
        val metrics=MediaCodecAudioDecoder.StreamMetrics()
        val provider=source(mapOf("a" to input),metrics)
        // Requested window 2s..3s: even its 500ms preroll is wholly after 1s audio EOF.
        val opened=provider.open("a",2_000_000)
        assertNotNull("valid exhausted track must not become absent",opened)
        opened!!.use { stream ->
            // An extractor can return the last preceding packet on seek-past-end. Consume only
            // that bounded predecessor; no chunk may reach the requested window.
            while (true) { val chunk=stream.read() ?: break; assertTrue(chunk.startPtsUs+chunk.frames*1_000_000L/48000 < 1_500_000) }
            repeat(3) { assertNull(stream.read()) }
        }
        assertEquals(0,metrics.openDecoders); assertEquals(0,metrics.retainedChunks)
        val silent=SourceAudioComposer(ProjectClock(30),listOf(clip("a",0,30,2_000_000,3_000_000)),provider)
        val result=pcm(silent)
        assertEquals(96_000,result.size); assertTrue(result.all { it==0.toShort() })
        assertEquals(0,metrics.openDecoders)
        AacEncoderMuxer.prepareSourceAudio(silent,AudioMixSettings(AudioMixMode.SOURCE),dir).use { assertEquals(48_000,it.sampleFrames) }
        val retry=SourceAudioComposer(ProjectClock(30),listOf(clip("a",0,30)),provider)
        AacEncoderMuxer.prepareSourceAudio(retry,AudioMixSettings(AudioMixMode.SOURCE),dir).use { assertEquals(48_000,it.sampleFrames) }
        assertEquals(0,metrics.openDecoders); assertEquals(0,metrics.retainedChunks)
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
            val decoded = MediaCodecAudioDecoder.decode(output,preserveFloatHeadroom=true)
            assertTrue(decoded.pcmEncoding in listOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
            assertEquals(48_000,decoded.frameCount)
            File(dir,"actual-duration.txt").writeText("aac=$audioDuration decodedFrames=${decoded.frameCount} pcmEncoding=${decoded.pcmEncoding}")
            // Borrowed file is still owned/live after mux; an owner STT adapter is a later gate.
            assertTrue(prepared.speechPcmFile.isFile)
        }
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("source-audio-") || it.name.startsWith("source-speech-") })
    }

    @Test fun actual_aac_markers_match_authored_video_frames_and_duration_at_30_and_60() {
        val dir = directory("av-frames")
        val wave = SourceAudioNativeFixtures.wav(File(dir,"marker.wav")) { frame ->
            val distance=listOf(1600,24000,46400).minOf { abs(frame-it) }
            (30_000 * kotlin.math.exp(-(distance/25.0)*(distance/25.0))).toInt().toShort()
        }
        for (fps in listOf(30,60)) {
            val composer = SourceAudioComposer(ProjectClock(fps),listOf(clip("a",0,fps)),source(mapOf("a" to wave)))
            AacEncoderMuxer.prepareSourceAudio(composer,AudioMixSettings(AudioMixMode.SOURCE),dir).use { prepared ->
                val video = SyntheticVideo.create(File(dir,"video-$fps.mp4"),1000,fps=fps)
                val output = File(dir,"av-$fps.mp4")
                val retained=File(dir,"prepared-$fps.m4a"); prepared.aacFile.copyTo(retained)
                AacEncoderMuxer.muxPreparedAudio(prepared,video,output,1_000_000)
                val videoPts = videoPackets(output).map { it.substringBefore(':').toLong() }
                assertEquals(fps,videoPts.size)
                assertEquals(500_000,videoPts[fps/2])
                for (encoded in listOf(retained,output)) {
                assertTrue("priming packets preserved ${encoded.name}",recordAudioPackets(encoded)<0)
                val peaks=DoubleArray(3); val peakUs=LongArray(3) { -1 }; var audioEndUs = 0L; var decodedFrames=0L
                File(dir,"${encoded.name}-decoded-chunks.txt").bufferedWriter().use { ledger ->
                source(mapOf("out" to encoded)).open("out",0)!!.use { stream ->
                    while (true) {
                        val chunk = stream.read() ?: break
                        ledger.appendLine("start=${chunk.startPtsUs} frames=${chunk.frames} format=${stream.format}")
                        repeat(chunk.frames) { frame ->
                            val value = abs(chunk.sample(frame,0))
                            val pts=chunk.startPtsUs+frame*1_000_000L/48_000
                            val region=when(pts) { in 0..150_000 -> 0; in 350_000..650_000 -> 1; in 850_000..1_050_000 -> 2; else -> -1 }
                            if (region>=0 && value>peaks[region]) { peaks[region]=value; peakUs[region]=pts }
                        }
                        audioEndUs=chunk.startPtsUs+chunk.frames*1_000_000L/48_000
                        decodedFrames+=chunk.frames
                    }
                }
                ledger.appendLine("decodedFrames=$decodedFrames endUs=$audioEndUs EOF=true")
                }
                File(dir,"${encoded.name}-evidence.txt").writeText("format=${trackFormat(encoded,"audio/")} videoMarkers=${listOf(videoPts[fps/30],videoPts[fps/2],videoPts[fps-fps/30])}, audioPeaks=${peakUs.toList()}, audioEnd=$audioEndUs, frames=$decodedFrames peaks=${peaks.toList()}")
                for ((index, expected) in listOf(33_333L,500_000L,966_667L).withIndex()) {
                    assertTrue("${encoded.name} marker $index missing",peaks[index]>.3)
                    assertTrue("fps=$fps ${encoded.name} marker=$index pts=${peakUs[index]}",abs(peakUs[index]-expected)<=22)
                }
                assertEquals("sample-exact decoded count ${encoded.name}",48_000,decodedFrames)
                assertTrue("fps=$fps decoded audio end=$audioEndUs",abs(audioEndUs-1_000_000)<=1_000_000/fps)
                recordInspectedArtifact(encoded)
                }
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
        val probeCancellation=assertThrows(CancellationException::class.java) {
            AacEncoderMuxer.prepareSourceAudio(composer,AudioMixSettings(AudioMixMode.SOURCE),dir,checkCancelled={
                if(dir.listFiles()!!.any { it.name.startsWith("aac-probe-") && it.length()>2048 }) throw cancelled
            })
        }
        assertSame(cancelled,probeCancellation)
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("aac-probe-") })
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

    @Test fun actual_nonintegral_movie_tick_lengths_trim_to_exact_pcm_samples_at_30_and_60() {
        val dir=directory("partial-frame-trim")
        val wav=SourceAudioNativeFixtures.wav(File(dir,"source.wav"),2) { SourceAudioNativeFixtures.tone(it) }
        for (fps in listOf(30,60)) for (full in listOf(false,true)) {
            val frames=if(full) 51_200L else if(fps==30) 49_600L else 48_800L
            val endUs=if(full) 1_066_666L else if(fps==30) 1_033_333L else 1_016_666L
            val videoFrames=if(full) fps*16/15 else fps+1
            val kind=if(full) "full" else "partial"
            val composer=SourceAudioComposer(ProjectClock(fps),listOf(clip("a",0,videoFrames,0,endUs)),source(mapOf("a" to wav)))
            AacEncoderMuxer.prepareSourceAudio(composer,AudioMixSettings(AudioMixMode.SOURCE),dir).use { prepared ->
                assertEquals(frames,prepared.sampleFrames)
                val retained=File(dir,"prepared-$fps-$kind.m4a"); prepared.aacFile.copyTo(retained)
                val video=SyntheticVideo.create(File(dir,"video-$fps-$kind.mp4"),if(full) 1067 else if(fps==30) 1034 else 1017,fps=fps)
                val output=File(dir,"final-$fps-$kind.mp4")
                AacEncoderMuxer.muxPreparedAudio(prepared,video,output,endUs)
                for(file in listOf(retained,output)) {
                    assertTrue(recordAudioPackets(file)<0)
                    var count=0L
                    File(dir,"${file.name}-samples.txt").bufferedWriter().use { ledger ->
                        source(mapOf("a" to file)).open("a",0)!!.use { stream -> while(true) {
                            val chunk=stream.read() ?: break
                            ledger.appendLine("start=${chunk.startPtsUs} frames=${chunk.frames} format=${stream.format}")
                            count+=chunk.frames
                        } }
                        ledger.appendLine("expected=$frames actual=$count EOF=true format=${trackFormat(file,"audio/")}")
                    }
                    assertEquals("sample-exact ${file.name}",frames,count)
                }
            }
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
            val retained=File(dir,"mixed-for-listening.m4a")
            prepared.aacFile.copyTo(retained)
            prepared.speechPcmFile.copyTo(File(dir,"premusic-16k.pcm"))
            val decoded = MediaCodecAudioDecoder.decode(retained,preserveFloatHeadroom=true)
            // C2's AAC decoder can return PCM16 despite the float request. Never relabel that
            // saturated signal as float proof. The mandatory remote host oracle decodes this
            // exact retained AAC with a real float AAC decoder and gates peak/flat-run quality.
            assertTrue(decoded.pcmEncoding in listOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
            assertEquals(48_000,decoded.frameCount)
            recordInspectedArtifact(retained)
            File(dir,"float-oracle.json").writeText("""{"aac":"mixed-for-listening.m4a","sampleFrames":48000,"sampleRate":48000,"channels":2,"androidPcmEncoding":${decoded.pcmEncoding},"pcmPeak":$pcmPeak}""")
            File(dir,"peak-evidence.txt").writeText("pcmPeak=$pcmPeak actualAndroidEncoding=${decoded.pcmEncoding}; unclamped AAC quality requires host oracle; human listening NOT ASSESSED")
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

    private fun recordAudioPackets(file: File): Long {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            extractor.selectTrack(track)
            val first=extractor.sampleTime
            File(file.parentFile,"${file.name}-encoded-pts.txt").bufferedWriter().use { ledger ->
                ledger.appendLine("format=${extractor.getTrackFormat(track)}")
                var packets=0L
                while (extractor.sampleTrackIndex>=0) {
                    val size=if(android.os.Build.VERSION.SDK_INT>=28) extractor.sampleSize else -1L
                    ledger.appendLine("packet=$packets pts=${extractor.sampleTime} bytes=$size flags=${extractor.sampleFlags}")
                    packets++; extractor.advance()
                }
                ledger.appendLine("packets=$packets EOF=true")
            }
            return first
        } finally { extractor.release() }
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
