package com.veycad.app

import java.util.Collections
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Nonpersistent compiler adapter input. This is not a second project/graph or time map. */
data class SourceAudioClip(val sourceId: String, val span: FrameSpan, val sourceMap: SourceTimeMap,
    val hasAudio: Boolean = true) {
    init {
        require(sourceId.isNotBlank())
        require(sourceMap.points.last().localFrame == span.length)
    }
}

/** Actual high-water marks of compositor-owned PCM sample slots and open provider streams.
 * Includes decoded chunks, output scratch, channel scratch and FIR history; excludes native
 * decoder allocation and sink-owned storage, which need their own T2B measurements. */
data class PcmCompositionStats(val peakRetainedPcmSamples: Int, val peakOpenStreams: Int,
    val peakRetainedPcmBytes: Int = 0)

/** Bounded source-following resampling (speed changes also change pitch).
 * Every frame uses the saved canonical map, then anchors no farther than 1 ms apart.
 * HOLD emits silence, never advances or loops speech. Only one primary voice is sampled.
 * MUSIC remains the legacy export path; this class does not change or call native export. */
class SourceAudioComposer(private val clock: ProjectClock, clips: List<SourceAudioClip>,
    private val sources: PcmStreamProvider) {
    private val clips = Collections.unmodifiableList(ArrayList(clips))
    init {
        require(this.clips.isNotEmpty() && this.clips.first().span.start == 0)
        require(this.clips.zipWithNext().all { (a, b) -> a.span.endExclusive == b.span.start })
    }

    /** Optional speech is faded primary-source stereo downmixed/filtered to 16 kHz mono,
     * BEFORE user source gain, mix headroom and app music. Borrowed sinks remain caller-owned.
     * Exceptions (including cancellation) preserve their identity; use suppresses close failures. */
    fun compose(settings: AudioMixSettings, output: PcmSink, speech: PcmSink? = null,
        music: PcmStreamProvider? = null, checkCancelled: () -> Unit = {}): PcmCompositionStats {
        require(settings.mode != AudioMixMode.MUSIC) { "MUSIC uses legacy export" }
        require(settings.mode != AudioMixMode.SPEECH_AND_MUSIC || music != null)
        checkCancelled()
        val meter = BufferMeter()
        val rendered = OutputBuffer(PcmFormat(48_000, 2), output, meter)
        val totalFrames = sampleFrame(clips.last().span.endExclusive)
        val speechFilter = speech?.let { SpeechFilter(totalFrames / 3, it, meter) }
        val primary = DoubleArray(2)
        val score = DoubleArray(2)
        meter.retain(4, 8)
        // One constant bound for the entire mix avoids sample clipping and limiter pumping.
        val sum = settings.sourceGain.toDouble() + settings.musicGain
        val headroom = if (settings.mode == AudioMixMode.SPEECH_AND_MUSIC && sum > 1.0)
            .89125094 / sum else 1.0
        val musicCursor = if (settings.mode == AudioMixMode.SPEECH_AND_MUSIC)
            music!!.open("app-music", 0)?.let { ChunkCursor(it, meter, checkCancelled) } else null
        musicCursor.use { musicReader ->
            for (clip in clips) {
                checkCancelled()
                val cursor = if (clip.hasAudio) sources.open(clip.sourceId, clip.sourceMap.sample(0))
                    ?.let { ChunkCursor(it, meter, checkCancelled) } else null
                cursor.use { reader ->
                    val mapping = FrameAnchors(clip)
                    val startUs = clock.timeUs(clip.span.start)
                    val endUs = clock.timeUs(clip.span.endExclusive)
                    for (frame in sampleFrame(clip.span.start) until sampleFrame(clip.span.endExclusive)) {
                        if (frame % 1_024 == 0L) checkCancelled()
                        val outputUs = frame * 1_000_000.0 / 48_000
                        mapping.moveTo(outputUs)
                        primary.fill(0.0)
                        if (!mapping.hold) reader?.sample(mapping.sourceUs, mapping.sourceFraction, primary)
                        var fade = 1.0
                        if (clip.span.start > 0) fade = minOf(fade, (outputUs - startUs) / 10_000)
                        if (clip.span.endExclusive < clips.last().span.endExclusive)
                            fade = minOf(fade, (endUs - outputUs) / 10_000)
                        fade = fade.coerceIn(0.0, 1.0)
                        primary[0] *= fade
                        primary[1] *= fade
                        speechFilter?.push((primary[0] + primary[1]) / 2)
                        score.fill(0.0)
                        if (musicReader != null) {
                            val whole = floor(outputUs).toLong()
                            musicReader.sample(whole, outputUs - whole, score)
                        }
                        rendered.push((primary[0] * settings.sourceGain + score[0] * settings.musicGain) * headroom,
                            (primary[1] * settings.sourceGain + score[1] * settings.musicGain) * headroom)
                    }
                }
            }
            checkCancelled()
            rendered.flush()
            speechFilter?.finish()
        }
        return PcmCompositionStats(meter.peakSamples, meter.peakStreams, meter.peakBytes)
    }

    private fun sampleFrame(frame: Int): Long = (clock.timeUs(frame) * 48_000 + 500_000) / 1_000_000

    /** Rolling frame/sub-ms anchors: memory does not grow with timeline length. */
    private inner class FrameAnchors(private val clip: SourceAudioClip) {
        private var local = -1
        private var frameStart = 0L
        private var frameEnd = 0L
        private var sourceStart = 0L
        private var sourceDelta = 0L
        private var anchorStart = 0L
        private var anchorEnd = 0L
        private var sourceAtAnchor = 0.0
        private var sourceAnchorDelta = 0.0
        var hold = false; private set
        var sourceUs = 0L; private set
        var sourceFraction = 0.0; private set

        fun moveTo(timeUs: Double) {
            if (local < 0 || timeUs >= frameEnd) {
                do { local++ } while (local + 1 < clip.span.length &&
                    timeUs >= clock.timeUs(clip.span.start + local + 1))
                frameStart = clock.timeUs(clip.span.start + local)
                frameEnd = clock.timeUs(clip.span.start + local + 1)
                sourceStart = clip.sourceMap.sample(local)
                sourceDelta = clip.sourceMap.sample(local + 1) - sourceStart
                hold = sourceDelta == 0L
                anchorEnd = frameStart
            }
            if (timeUs >= anchorEnd) {
                anchorStart = frameStart + ((timeUs - frameStart).toLong() / 1_000) * 1_000
                anchorEnd = minOf(anchorStart + 1_000, frameEnd)
                sourceAtAnchor = sourceDelta.toDouble() * (anchorStart - frameStart) / (frameEnd - frameStart)
                sourceAnchorDelta = sourceDelta.toDouble() * (anchorEnd - anchorStart) / (frameEnd - frameStart)
            }
            val delta = sourceAtAnchor + sourceAnchorDelta * (timeUs - anchorStart) / (anchorEnd - anchorStart)
            val whole = floor(delta).toLong()
            sourceUs = sourceStart + whole
            sourceFraction = delta - whole
        }
    }

    private class BufferMeter {
        private var samples = 0
        private var bytes = 0
        private var streams = 0
        var peakSamples = 0; private set
        var peakBytes = 0; private set
        var peakStreams = 0; private set
        fun retain(count: Int, bytesPerSample: Int = 2) {
            samples += count; bytes += count * bytesPerSample
            peakSamples = maxOf(samples, peakSamples); peakBytes = maxOf(bytes, peakBytes)
        }
        fun release(count: Int) { samples -= count; bytes -= count * 2 }
        fun opened() { streams++; peakStreams = maxOf(streams, peakStreams) }
        fun closed() { streams-- }
    }

    /** A current chunk and (only at its last sample) one lookahead chunk. EOF is sticky.
     * Interpolation bridges continuous chunk boundaries, never measured gaps or missing EOF. */
    private class ChunkCursor(private val stream: PcmStream, private val meter: BufferMeter,
        private val checkCancelled: () -> Unit) : AutoCloseable {
        private var current: PcmChunk? = null
        private var next: PcmChunk? = null
        private var eof = false
        private var closed = false
        private var lastEndFloorUs: Long? = null
        init { meter.opened() }

        private fun read(): PcmChunk? {
            if (eof) return null
            checkCancelled()
            val chunk = stream.read() ?: run { eof = true; return null }
            require(chunk.format == stream.format) { "PCM format changed" }
            require(lastEndFloorUs == null || chunk.startPtsUs >= lastEndFloorUs!! - 1) { "Unordered PCM chunks" }
            lastEndFloorUs = chunk.startPtsUs + chunk.frames * 1_000_000L / chunk.format.sampleRate
            meter.retain(chunk.retainedSamples)
            return chunk
        }

        fun sample(timeUs: Long, fractionUs: Double, channels: DoubleArray) {
            if (current == null) current = next?.also { next = null } ?: read()
            while (true) {
                val chunk = current ?: return
                val offsetUs = (timeUs - chunk.startPtsUs).toDouble() + fractionUs
                if (offsetUs < 0) return
                val position = offsetUs * chunk.format.sampleRate / 1_000_000
                if (position >= chunk.frames) {
                    meter.release(chunk.retainedSamples)
                    current = null
                    current = next?.also { next = null } ?: read()
                    continue
                }
                val index = floor(position).toInt()
                val fraction = position - index
                val following = if (index + 1 == chunk.frames && fraction > 0) {
                    if (next == null && !eof) next = read()
                    next
                } else null
                val continuous = following != null && kotlin.math.abs(
                    (following.startPtsUs - chunk.startPtsUs).toDouble() -
                        chunk.frames * 1_000_000.0 / chunk.format.sampleRate) <= 1.0
                for (channel in 0..1) {
                    val inputChannel = minOf(channel, chunk.format.channels - 1)
                    val a = chunk.sample(index, inputChannel)
                    val b = when {
                        index + 1 < chunk.frames -> chunk.sample(index + 1, inputChannel)
                        continuous -> following!!.sample(0, inputChannel)
                        else -> a // last sample's own interval only, never beyond chunk endpoint
                    }
                    channels[channel] = a + (b - a) * fraction
                }
                return
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            current?.let { meter.release(it.retainedSamples) }
            next?.let { meter.release(it.retainedSamples) }
            current = null; next = null
            try { stream.close() } finally { meter.closed() }
        }
    }

    private class OutputBuffer(private val format: PcmFormat, private val sink: PcmSink, meter: BufferMeter) {
        private val samples = ShortArray(1_024 * format.channels)
        private var size = 0
        var frames = 0L; private set
        init { meter.retain(samples.size) }
        fun push(left: Double, right: Double = 0.0) {
            samples[size++] = signed16(left)
            if (format.channels == 2) samples[size++] = signed16(right)
            if (size == samples.size) flush()
        }
        fun flush() {
            if (size == 0) return
            val count = size / format.channels
            sink.write(format, frames, samples, count)
            frames += count
            size = 0
        }
    }

    /** 63-tap normalized windowed-sinc anti-alias FIR, 7.2 kHz cutoff at 48 kHz.
     * 31-frame lookahead compensates group delay; edges use silence, never extra STT duration. */
    private class SpeechFilter(private val outputFrames: Long, sink: PcmSink, meter: BufferMeter) {
        private val history = DoubleArray(63)
        private val coefficients = DoubleArray(63) { i ->
            val x = i - 31
            val ideal = if (x == 0) .3 else sin(.3 * PI * x) / (PI * x)
            ideal * (.54 - .46 * cos(2 * PI * i / 62))
        }.also { taps -> val sum = taps.sum(); for (i in taps.indices) taps[i] /= sum }
        private val output = OutputBuffer(PcmFormat(16_000, 1), sink, meter)
        private var inputFrames = 0L
        private var emitted = 0L
        init { meter.retain(history.size, 8) }
        fun push(sample: Double) {
            history[(inputFrames % history.size).toInt()] = sample
            val center = inputFrames - 31
            if (center >= 0 && center % 3 == 0L && emitted < outputFrames) {
                var value = 0.0
                for (tap in coefficients.indices) {
                    val past = inputFrames - tap
                    if (past >= 0) value += coefficients[tap] * history[(past % history.size).toInt()]
                }
                output.push(value)
                emitted++
            }
            inputFrames++
        }
        fun finish() {
            repeat(31) { push(0.0) }
            output.flush()
        }
    }

    companion object {
        private fun signed16(value: Double): Short = (value * 32_768).toInt().coerceIn(-32_768, 32_767).toShort()
    }
}
