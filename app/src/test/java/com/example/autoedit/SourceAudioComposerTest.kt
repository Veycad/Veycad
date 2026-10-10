package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.File
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.sin

class SourceAudioComposerTest {
    private val stereo = PcmFormat(48_000, 2)
    private fun clip(id: String, start: Int, end: Int, from: Long, to: Long, audio: Boolean = true) =
        SourceAudioClip(id, FrameSpan(start, end), SourceTimeMap(listOf(
            SourceTimeMap.Point(0, from), SourceTimeMap.Point(end - start, to))), audio)

    private class Captured : PcmSink {
        val data = ArrayList<Short>()
        var format: PcmFormat? = null
        override fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int) {
            assertEquals(data.size.toLong() / format.channels, startFrame)
            assertTrue(frames <= format.sampleRate)
            this.format = format
            for (i in 0 until frames * format.channels) data += samples[i]
        }
        fun at(frame: Int, channel: Int = 0): Int {
            assertNotNull("Compositor emitted no PCM", format)
            return data[frame * format!!.channels + channel].toInt()
        }
        fun wav(name: String) {
            val file = File("build/t2a-waveform/$name.wav")
            file.parentFile!!.mkdirs()
            DataOutputStream(file.outputStream()).use { stream ->
                fun word(value: Int) { stream.writeByte(value); stream.writeByte(value ushr 8) }
                fun dword(value: Int) { word(value); word(value ushr 16) }
                stream.writeBytes("RIFF"); dword(36 + data.size * 2); stream.writeBytes("WAVEfmt ")
                dword(16); word(1); word(format!!.channels); dword(format!!.sampleRate)
                dword(format!!.sampleRate * format!!.channels * 2); word(format!!.channels * 2); word(16)
                stream.writeBytes("data"); dword(data.size * 2)
                for (sample in data) word(sample.toInt())
            }
        }
    }

    /** Generates bounded chunks on demand; no source-sized arrays or pretend native decoder. */
    private class Generated(
        private val frames: Int,
        private val origin: Long = 0,
        private val chunkFrames: Int = 480,
        private val value: (Int, Int) -> Short,
        private val failAtRead: Int? = null,
        override val format: PcmFormat = PcmFormat(48_000, 2)
    ) : PcmStream {
        var reads = 0
        var closes = 0
        private var next = 0
        override fun read(): PcmChunk? {
            reads++
            if (reads == failAtRead) throw IOException("fixture read failure")
            if (next == frames) return null
            val n = minOf(chunkFrames, frames - next)
            val start = next
            next += n
            return PcmChunk(format, origin + start * 1_000_000L / format.sampleRate,
                ShortArray(n * format.channels) { value(start + it / format.channels, it % format.channels) })
        }
        override fun close() { closes++ }
    }

    @Test fun rawOriginsGapsSilentMiddleAndRepeatKeepTheOutputClock() {
        // Mutating source identity, subtracting video origin again, or shifting a gap loses these labels.
        val opened = arrayListOf<Pair<String, Generated>>()
        val requests = arrayListOf<Pair<String, Long>>()
        val provider = PcmStreamProvider { id, requested ->
            requests += id to requested
            val stream = when (id) {
                "a" -> Generated(4_800, 1_020_000, value = { _, ch -> if (ch == 0) 12_000 else -6_000 })
                "c" -> Generated(2_400, 5_000_000, value = { _, ch -> if (ch == 0) 18_000 else 3_000 })
                else -> error("Silent source must not be opened")
            }
            opened += id to stream
            stream
        }
        val out = Captured()
        val mutable = mutableListOf(clip("a", 0, 3, 1_000_000, 1_100_000),
            clip("b", 3, 6, 8_000_000, 8_100_000, false),
            clip("c", 6, 9, 5_000_000, 5_100_000), clip("a", 9, 12, 1_000_000, 1_100_000))
        val composer = SourceAudioComposer(ProjectClock(30), mutable, provider)
        mutable.clear()
        composer.compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertEquals(38_400, out.data.size)
        assertEquals(0, out.at(480)) // first audio PTS is 20 ms after video's 1 s origin
        assertEquals(12_000, out.at(1_440))
        assertEquals(-6_000, out.at(1_440, 1))
        assertEquals(0, out.at(7_200)) // missing-track middle clip keeps its 100 ms
        assertEquals(18_000, out.at(10_800))
        assertEquals(0, out.at(13_440)) // c ends at source 5.05 s, not video end
        assertEquals(12_000, out.at(15_840)) // repeated a reopens at its original PTS
        assertEquals(listOf("a", "c", "a"), opened.map { it.first })
        assertEquals(listOf("a" to 1_000_000L, "c" to 5_000_000L, "a" to 1_000_000L), requests)
        assertTrue(opened.all { it.second.closes == 1 })
        out.wav("origins-gap-repeat")
    }

    @Test fun measuredInternalGapIsSilentInsteadOfStitchingChunksTogether() {
        val chunks = listOf(PcmChunk(stereo, 2_000_000, ShortArray(960) { 9_000 }),
            PcmChunk(stereo, 2_030_000, ShortArray(960) { -9_000 }))
        var i = 0
        var closes = 0
        val stream = object : PcmStream {
            override val format = stereo
            override fun read() = chunks.getOrNull(i++)
            override fun close() { closes++ }
        }
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("gap", 0, 3, 2_000_000, 2_100_000)),
            PcmStreamProvider { _, _ -> stream }).compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertEquals(9_000, out.at(240))
        assertEquals(0, out.at(960))
        assertEquals(-9_000, out.at(1_680))
        assertEquals(0, out.at(2_160))
        assertEquals(1, closes)
    }

    @Test fun canonicalRampPlacesIndependentImpulseMarkersOnVideoFrames() {
        // 30 fps, source anchors 0, 10, 60, 90 ms. Markers at source 10/60 ms
        // belong to output 33.333/66.667 ms -> sample 1600/3200 (subsample rounding).
        val map = SourceTimeMap(listOf(SourceTimeMap.Point(0, 7_000_000),
            SourceTimeMap.Point(1, 7_010_000), SourceTimeMap.Point(2, 7_060_000),
            SourceTimeMap.Point(3, 7_090_000)))
        val input = Generated(4_800, 7_000_000, value = { frame, ch ->
            if (frame == 480 || frame == 2_880) (if (ch == 0) 25_000 else -12_500).toShort() else 0 })
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(SourceAudioClip("ramp", FrameSpan(0, 3), map)),
            PcmStreamProvider { _, _ -> input }).compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertTrue(out.at(1_600) > 24_000)
        assertTrue(out.at(3_200) > 24_000)
        assertEquals(-.5, out.at(1_600, 1).toDouble() / out.at(1_600), .0001)
        assertEquals(0, out.at(2_400))
        out.wav("canonical-ramp")
    }

    @Test fun heldSourceTimeProducesSilenceThenResumesAtTheSavedAnchor() {
        val map = SourceTimeMap(listOf(SourceTimeMap.Point(0, 100_000),
            SourceTimeMap.Point(1, 100_000), SourceTimeMap.Point(3, 166_667)))
        val input = Generated(8_000, 100_000, value = { i, _ ->
            if (i == 800) 20_000 else if (i == 2_400) -20_000 else 0 })
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(SourceAudioClip("hold", FrameSpan(0, 3), map)),
            PcmStreamProvider { _, _ -> input }).compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertEquals(0, out.at(800))
        // 800 source samples =16.667 ms after saved 100 ms origin. HOLD ends at
        // output33.333 ms -> impulse resumes near50 ms (sample2400), not16.667 ms.
        assertTrue(out.at(2_400) > 19_000)
        assertEquals(0, out.at(1_600))
        assertEquals(0, out.at(3_200))
        assertEquals(1, input.closes)
        out.wav("hold-silence-resume")
    }

    @Test fun cutsFadeOnlyThePrimaryVoiceWithoutOverlappingUnrelatedSources() {
        val streams = arrayListOf<Generated>()
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("left", 0, 3, 0, 100_000),
            clip("right", 3, 6, 0, 100_000)), PcmStreamProvider { id, _ ->
            Generated(4_800, value = { _, ch ->
                if (id == "left" && ch == 0 || id == "right" && ch == 1) 20_000 else 0 })
                .also { streams += it }
        }).compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertEquals(10_000, out.at(4_560)) // 5 ms before cut
        assertEquals(0, out.at(4_560, 1))
        assertEquals(0, out.at(4_800))
        assertEquals(0, out.at(5_040))
        assertEquals(10_000, out.at(5_040, 1)) // 5 ms after cut
        assertTrue(streams.all { it.closes == 1 })
    }

    @Test fun absoluteThirtyAndSixtyFpsCutsDoNotAccumulateAudioRounding() {
        for ((fps, lengths) in listOf(30 to listOf(1_600, 1_600, 1_600),
            60 to listOf(800, 800, 800))) {
            val out = Captured()
            SourceAudioComposer(ProjectClock(fps), (0..2).map { clip("$it", it, it + 1, 0, 50_000) },
                PcmStreamProvider { _, _ -> Generated(2_400, value = { _, _ -> 1_000 }) })
                .compose(AudioMixSettings(AudioMixMode.SOURCE), out)
            assertEquals(lengths.sum() * 2, out.data.size)
            assertEquals(0, out.at(lengths[0]))
            assertEquals(0, out.at(lengths[0] + lengths[1]))
        }
    }

    @Test fun eofIsReadOnceAndSilenceDoesNotHideFailures() {
        val input = Generated(480, value = { _, _ -> 4_000 })
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("short", 0, 30, 0, 1_000_000)),
            PcmStreamProvider { _, _ -> input }).compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertEquals(0, out.at(24_000))
        assertEquals(2, input.reads)
        assertEquals(1, input.closes)
        val broken = Generated(4_800, value = { _, _ -> 4_000 }, failAtRead = 2)
        val failure = assertThrows(IOException::class.java) {
            SourceAudioComposer(ProjectClock(30), listOf(clip("broken", 0, 3, 0, 100_000)),
                PcmStreamProvider { _, _ -> broken }).compose(AudioMixSettings(AudioMixMode.SOURCE), Captured())
        }
        assertEquals("fixture read failure", failure.message)
        assertEquals(1, broken.closes)
    }

    @Test fun cancellationAndSinkFailureCloseSourceAndMusicExactlyOnce() {
        for (cancel in listOf(true, false)) {
            val source = Generated(48_000, value = { _, _ -> 1_000 })
            val music = Generated(48_000, value = { _, _ -> 2_000 })
            val sentinel = IOException("stop")
            var checks = 0
            val sink = object : PcmSink {
                override fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int) {
                    if (!cancel) throw sentinel
                }
            }
            val actual = assertThrows(IOException::class.java) {
                SourceAudioComposer(ProjectClock(30), listOf(clip("source", 0, 30, 0, 1_000_000)),
                    PcmStreamProvider { _, _ -> source }).compose(
                    AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC), sink,
                    music = PcmStreamProvider { _, _ -> music }, checkCancelled = {
                        if (cancel && ++checks == 4) throw sentinel
                    })
            }
            assertSame(sentinel, actual)
            assertEquals(1, source.closes)
            assertEquals(1, music.closes)
        }
    }

    @Test fun fullScaleSourceAndMusicUseConstantHeadroomWithoutHardClipping() {
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("source", 0, 3, 0, 100_000)),
            PcmStreamProvider { _, _ -> Generated(4_800, value = { frame, _ ->
                if (frame < 2_400) 32_767 else -32_768 }) }).compose(
            AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, 1f, 1f), out,
            music = PcmStreamProvider { _, _ -> Generated(4_800, value = { frame, _ ->
                if (frame < 2_400) 32_767 else -32_768 }) })
        assertEquals(29_203, out.at(1_440))
        assertEquals(-29_204, out.at(3_360))
        assertTrue(out.data.all { abs(it.toInt()) < 32_767 })
        out.wav("fullscale-mix")
    }

    @Test fun sttSeesPreMusicSpeechEvenWhenAppMusicHasVoiceAndSourceGainIsZero() {
        val mix = Captured()
        val stt = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("silent", 0, 3, 0, 100_000, false)),
            PcmStreamProvider { _, _ -> error("missing source audio") }).compose(
            AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, 0f, 1f), mix, stt,
            PcmStreamProvider { _, _ -> Generated(4_800, value = { _, _ -> 16_000 }) })
        assertEquals(16_000, mix.at(2_400))
        assertEquals(PcmFormat(16_000, 1), stt.format)
        assertEquals(1_600, stt.data.size)
        assertTrue(stt.data.all { it == 0.toShort() })
        mix.wav("music-with-silent-source")
        stt.wav("premusic-silent-stt")
        val voiced = Captured()
        val muted = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("quiet", 0, 3, 0, 100_000)),
            PcmStreamProvider { _, _ -> Generated(4_800, value = { _, ch -> if (ch == 0) 1_200 else 600 }) })
            .compose(AudioMixSettings(AudioMixMode.SOURCE, 0f), muted, voiced)
        assertEquals(900, voiced.at(800))
        assertTrue(muted.data.all { it == 0.toShort() })
        voiced.wav("premusic-quiet-stt")
    }

    @Test fun sttLowpassRejectsAliasingAndKeepsToneTimestampWithoutFilterDelay() {
        fun tone(hz: Double): Captured {
            val speech = Captured()
            SourceAudioComposer(ProjectClock(30), listOf(clip("tone", 0, 30, 0, 1_000_000)),
                PcmStreamProvider { _, _ -> Generated(48_000, value = { i, _ ->
                    (10_000 * sin(2 * Math.PI * hz * i / 48_000)).toInt().toShort() }) })
                .compose(AudioMixSettings(AudioMixMode.SOURCE), object : PcmSink {
                    override fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int) {}
                }, speech)
            return speech
        }
        val low = tone(1_000.0)
        assertEquals(16_000, low.data.size)
        assertTrue(low.at(4_004) > 9_800) // 250.25 ms, 1 kHz sine positive peak
        assertTrue(abs(low.at(4_000)) < 5) // 250 ms zero crossing, no group delay
        val high = tone(12_000.0)
        assertTrue(high.data.subList(100, 15_900).maxOf { abs(it.toInt()) } < 100)
    }

    @Test fun monoAndNon48kInputResampleByMeasuredTimeAndChunkBoundaries() {
        val input = Generated(4_410, chunkFrames = 147, format = PcmFormat(44_100, 1),
            value = { i, _ -> (i * 2).toShort() })
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("mono", 0, 3, 0, 100_000)),
            PcmStreamProvider { _, _ -> input }).compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertEquals(4_410, out.at(2_400)) // 50 ms -> source sample 2205
        assertEquals(out.at(2_400), out.at(2_400, 1))
        assertEquals(2_940, out.at(1_600)) // exact 1/30 s crosses a chunk boundary
    }

    @Test fun longInputRetainsBoundedChunksAndScratchRatherThanTimelineArrays() {
        val input = Generated(5_760_000, chunkFrames = 48_000, value = { _, _ -> 500 })
        var emittedFrames = 0L
        val stats = SourceAudioComposer(ProjectClock(30), listOf(clip("long", 0, 3_600, 0, 120_000_000)),
            PcmStreamProvider { _, _ -> input }).compose(AudioMixSettings(AudioMixMode.SOURCE), object : PcmSink {
                override fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int) {
                    assertEquals(emittedFrames, startFrame)
                    emittedFrames += frames
                }
            })
        assertEquals(5_760_000L, emittedFrames)
        assertTrue(stats.peakRetainedPcmSamples in 96_000..195_000)
        assertTrue(stats.peakRetainedPcmBytes in 388_000..400_000)
        assertEquals(1, stats.peakOpenStreams)
        assertEquals(1, input.closes)
        println("LONG_PCM frames=$emittedFrames peakRetainedPcmSamples=${stats.peakRetainedPcmSamples} peakRetainedPcmBytes=${stats.peakRetainedPcmBytes} peakOpenStreams=${stats.peakOpenStreams} closes=${input.closes}")
    }

    @Test fun inputPcmSnapshotCannotBeMutatedAndInvalidSettingsOrChunksAreRejected() {
        val samples = ShortArray(960) { 7_000 }
        val chunk = PcmChunk(stereo, 0, samples)
        samples.fill(0)
        var read = false
        val out = Captured()
        SourceAudioComposer(ProjectClock(30), listOf(clip("snapshot", 0, 1, 0, 33_333)),
            PcmStreamProvider { _, _ -> object : PcmStream {
                override val format = stereo
                override fun read(): PcmChunk? = if (read) null else chunk.also { read = true }
                override fun close() {}
            } }).compose(AudioMixSettings(AudioMixMode.SOURCE), out)
        assertEquals(7_000, out.at(240))
        for (gain in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { AudioMixSettings(AudioMixMode.SOURCE, gain) }
            assertThrows(IllegalArgumentException::class.java) { AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, musicGain = gain) }
        }
        assertThrows(IllegalArgumentException::class.java) { PcmChunk(stereo, 0, ShortArray(96_002)) }
        assertThrows(IllegalArgumentException::class.java) { PcmChunk(stereo, 0, shortArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { PcmChunk(stereo, -1, ShortArray(2)) }
        assertThrows(IllegalArgumentException::class.java) { SourceAudioComposer(ProjectClock(30),
            listOf(clip("bad", 1, 3, 0, 100_000)), PcmStreamProvider { _, _ -> null }) }
    }

    @Test fun musicModeIsExplicitlyRoutedToLegacyExportWithoutOpeningNewStreams() {
        val settings = AudioMixSettings(AudioMixMode.MUSIC, musicGain = 2f)
        assertThrows(IllegalArgumentException::class.java) {
            SourceAudioComposer(ProjectClock(30), listOf(clip("music", 0, 1, 0, 33_333)),
                PcmStreamProvider { _, _ -> error("legacy path only") }).compose(settings, Captured())
        }
    }

    @Test fun malformedStreamFailuresDoNotBecomeSilenceAndPreserveCloseSuppression() {
        for (wrongFormat in listOf(false, true)) {
            var read = 0
            var closes = 0
            val stream = object : PcmStream {
                override val format = stereo
                override fun read(): PcmChunk? = when (read++) {
                    0 -> PcmChunk(stereo, 0, ShortArray(960) { 1_000 })
                    1 -> PcmChunk(if (wrongFormat) PcmFormat(16_000, 1) else stereo,
                        if (wrongFormat) 10_000 else 5_000, ShortArray(960) { 1_000 })
                    else -> null
                }
                override fun close() { closes++ }
            }
            assertThrows(IllegalArgumentException::class.java) {
                SourceAudioComposer(ProjectClock(30), listOf(clip("invalid", 0, 3, 0, 100_000)),
                    PcmStreamProvider { _, _ -> stream }).compose(AudioMixSettings(AudioMixMode.SOURCE), Captured())
            }
            assertEquals(1, closes)
        }
        val readFailure = IOException("read")
        val closeFailure = IOException("close")
        var closes = 0
        val actual = assertThrows(IOException::class.java) {
            SourceAudioComposer(ProjectClock(30), listOf(clip("failure", 0, 3, 0, 100_000)),
                PcmStreamProvider { _, _ -> object : PcmStream {
                    override val format = stereo
                    override fun read(): PcmChunk? = throw readFailure
                    override fun close() { closes++; throw closeFailure }
                } }).compose(AudioMixSettings(AudioMixMode.SOURCE), Captured())
        }
        assertSame(readFailure, actual)
        assertArrayEquals(arrayOf(closeFailure), actual.suppressed)
        assertEquals(1, closes)
        assertThrows(IllegalArgumentException::class.java) { PcmChunk(stereo, Long.MAX_VALUE, ShortArray(2)) }
    }

    @Test fun streamingMixAndSpeechKeepAtMostTwoOwnedStreamsAndBoundedPcm() {
        val source = Generated(240_000, chunkFrames = 48_000, value = { _, _ -> 1_000 })
        val music = Generated(240_000, chunkFrames = 48_000, value = { _, _ -> 2_000 })
        val discard = object : PcmSink {
            override fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int) {}
        }
        val stats = SourceAudioComposer(ProjectClock(30), listOf(clip("s", 0, 150, 0, 5_000_000)),
            PcmStreamProvider { _, _ -> source }).compose(AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC),
            discard, discard, PcmStreamProvider { _, _ -> music })
        assertEquals(2, stats.peakOpenStreams)
        assertTrue(stats.peakRetainedPcmBytes in 380_000..800_000)
        assertEquals(1, source.closes)
        assertEquals(1, music.closes)
        println("MIX_PCM peakRetainedPcmSamples=${stats.peakRetainedPcmSamples} peakRetainedPcmBytes=${stats.peakRetainedPcmBytes} peakOpenStreams=${stats.peakOpenStreams} sourceCloses=${source.closes} musicCloses=${music.closes}")
    }
}
