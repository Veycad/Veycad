package com.veycad.app

import android.media.AudioFormat
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any Android-supported local audio track into normalized PCM for analysis and AAC mux. */
internal object MediaCodecAudioDecoder {
    private const val STALL_TIMEOUT_NS = 20_000_000_000L

    /** Per-operation accounting of primed decoder and looping-provider pending chunks.
     * Transfer between these owners releases then acquires, never double counts. Returned chunks
     * belong to the compositor's separate accounting. Excludes codec internals. Not thread safe. */
    class StreamMetrics {
        var openDecoders = 0; private set
        var peakOpenDecoders = 0; private set
        var decodedBuffers = 0L; private set
        var peakDecoderBufferBytes = 0; private set
        var peakRetainedChunks = 0; private set
        var retainedChunks = 0; private set
        internal fun opened() { openDecoders++; peakOpenDecoders = maxOf(peakOpenDecoders, openDecoders) }
        internal fun closed() { openDecoders-- }
        internal fun buffer(bytes: Int) { decodedBuffers++; peakDecoderBufferBytes = maxOf(peakDecoderBufferBytes, bytes) }
        internal fun retained() { retainedChunks++; peakRetainedChunks = maxOf(peakRetainedChunks, retainedChunks) }
        internal fun released() { retainedChunks-- }
    }

    fun loopingMusicProvider(file: File, startUs: Long = 0, metrics: StreamMetrics = StreamMetrics(),
        checkCancelled: () -> Unit = {}): PcmStreamProvider {
        require(startUs >= 0)
        val raw = sourceProvider(mapOf("app-music" to file), metrics, checkCancelled)
        return PcmStreamProvider { id, outputUs ->
            require(id == "app-music" && outputUs == 0L)
            LoopingMusicPcmStream(raw, startUs, metrics)
        }
    }

    /** Immutable identity/path lookup. Files remain borrowed and must not be replaced during use.
     * Only an absent audio track returns null; invalid files, codecs and cancellation throw. */
    fun sourceProvider(files: Map<String, File>, metrics: StreamMetrics = StreamMetrics(),
        checkCancelled: () -> Unit = {}): PcmStreamProvider {
        require(files.keys.all { it.isNotBlank() })
        val inputs = files.mapValues { File(it.value.absolutePath) }.toMap()
        return PcmStreamProvider { id, startUs ->
            require(startUs >= 0)
            checkCancelled()
            val file = requireNotNull(inputs[id]) { "Unknown source audio ID: $id" }
            require(file.isFile) { "Missing source audio file: $id" }
            val extractor = MediaExtractor()
            var stream: DecoderStream? = null
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                check(extractor.trackCount > 0) { "Source contains no readable media tracks" }
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (track == null) { extractor.release(); null } else {
                    extractor.selectTrack(track)
                    val firstPacketUs = extractor.sampleTime
                    // Preserve a preceding interpolation sample and MP3 reservoir preroll.
                    // Do not crop to requested PTS: the compositor consumes bounded preroll.
                    if (startUs > 0) extractor.seekTo((startUs - 500_000).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val format = extractor.getTrackFormat(track)
                    if (extractor.sampleTime > firstPacketUs && format.containsKey(MediaFormat.KEY_ENCODER_DELAY))
                        format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
                    // The independent preflight closes before opening the decoder and never moves this cursor.
                    val gaplessClock = continuousGaplessAacClock(file, track, format, firstPacketUs,
                        extractor.sampleTime, checkCancelled)
                    codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                    codec.configure(format, null, null, 0)
                    codec.start()
                    stream = DecoderStream(extractor, codec, metrics, checkCancelled, gaplessClock)
                    stream.prime()
                    stream
                }
            } catch (failure: Throwable) {
                if (stream != null) runCatching { stream.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                else {
                    codec?.let { runCatching { it.stop() }; runCatching { it.release() }.exceptionOrNull()?.let(failure::addSuppressed) }
                    runCatching { extractor.release() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }

    private fun continuousGaplessAacClock(file: File, track: Int, format: MediaFormat,
        firstPacketUs: Long, decoderStartUs: Long, checkCancelled: () -> Unit): GaplessAacClock? {
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return null
        val hasDelay = format.containsKey(MediaFormat.KEY_ENCODER_DELAY)
        val hasPadding = format.containsKey(MediaFormat.KEY_ENCODER_PADDING)
        if (hasDelay != hasPadding || hasDelay && decoderStartUs != firstPacketUs) return null
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (channels !in 1..2) return null
        val asc = format.getByteBuffer("csd-0")?.duplicate() ?: return null
        if (asc.remaining() != 2 && (hasDelay || asc.remaining() != 5)) return null
        val bits = ((asc.get().toInt() and 255) shl 8) or (asc.get().toInt() and 255)
        // AAC-LC sync extension that explicitly disables SBR, with zero alignment bits.
        if (asc.hasRemaining() && ((asc.get().toInt() and 255) != 0x56 ||
                (asc.get().toInt() and 255) != 0xe5 || asc.get().toInt() != 0)) return null
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val rateIndex = (bits ushr 7) and 15
        // AAC-LC, explicit channel configuration, 1024-frame GA syntax.
        if ((bits ushr 11) != 2 || rateIndex !in rates.indices || rates[rateIndex] != rate ||
            ((bits ushr 3) and 15) != channels || (bits and 7) != 0) return null
        val edit = if (!hasDelay) {
            // Old extractors can omit gapless keys and expose unedited packet timestamps.
            if (Build.VERSION.SDK_INT >= 30 || firstPacketUs != 0L || decoderStartUs < 0 ||
                !format.containsKey(MediaFormat.KEY_TRACK_ID)) return null
            Mp4PresentationBounds.aacEdit(file, format.getInteger(MediaFormat.KEY_TRACK_ID), rate, checkCancelled)
                ?: return null
        } else null
        val delay = edit?.delayFrames ?: format.getInteger(MediaFormat.KEY_ENCODER_DELAY)
        if (delay < 0 || delay % 1024 != 0) return null
        val proof = MediaExtractor()
        try {
            proof.setDataSource(file.absolutePath)
            proof.selectTrack(track)
            var packets = 0L
            while (proof.sampleTime != -1L) {
                checkCancelled()
                if (proof.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) return null
                val expectedUs = Math.addExact(firstPacketUs,
                    AudioExportPlan.presentationTimeUs(Math.multiplyExact(packets, 1024L), rate))
                if (proof.sampleTime < expectedUs - 2 || proof.sampleTime > expectedUs + 2) return null
                packets = Math.addExact(packets, 1L)
                if (!proof.advance()) break
            }
            val codedFrames = Math.multiplyExact(packets, 1024L)
            if (codedFrames <= delay) return null
            val padding = if (edit != null) codedFrames - delay - edit.presentationFrames
                else format.getInteger(MediaFormat.KEY_ENCODER_PADDING).toLong()
            if (padding !in 0L..1023L) return null
            val frames = codedFrames - delay - padding
            if (frames <= 0 || !format.containsKey(MediaFormat.KEY_DURATION)) return null
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            // Extractors report either the presentation edit or the full coded duration.
            // Both must agree exactly with counted packets and real gapless metadata.
            fun matchesDuration(sampleFrames: Long): Boolean {
                val numerator = Math.multiplyExact(sampleFrames, 1_000_000L)
                val floorUs = numerator / rate
                val ceilUs = Math.addExact(floorUs, if (numerator % rate == 0L) 0L else 1L)
                return durationUs in floorUs..ceilUs
            }
            if (!matchesDuration(frames) && !matchesDuration(codedFrames) &&
                !(edit != null && matchesDuration(codedFrames - padding))) return null
            if (edit != null) {
                // The independently checked extractor packet grid also identifies interior seeks.
                val skippedFrames = Math.addExact(Math.multiplyExact(decoderStartUs, rate.toLong()), 500_000L) / 1_000_000L
                if (skippedFrames % 1024 != 0L || skippedFrames >= codedFrames ||
                    kotlin.math.abs(AudioExportPlan.presentationTimeUs(skippedFrames, rate) - decoderStartUs) > 2L) return null
                val remainingDelay = (delay - skippedFrames).coerceAtLeast(0L)
                val emittedBeforeSeek = (skippedFrames - delay).coerceAtLeast(0L)
                if (emittedBeforeSeek >= frames) return null
                format.setInteger(MediaFormat.KEY_ENCODER_DELAY, remainingDelay.toInt())
                format.setInteger(MediaFormat.KEY_ENCODER_PADDING, padding.toInt())
                return GaplessAacClock(rate, AudioExportPlan.presentationTimeUs(emittedBeforeSeek, rate), padding.toInt(),
                    frames - emittedBeforeSeek, Math.addExact(decoderStartUs, AudioExportPlan.presentationTimeUs(remainingDelay, rate)))
            }
            val codecOriginUs = Math.addExact(firstPacketUs, AudioExportPlan.presentationTimeUs(delay.toLong(), rate))
            val originUs = if (Build.VERSION.SDK_INT < 30) firstPacketUs else codecOriginUs
            if (originUs < 0 || codecOriginUs < 0) return null
            return GaplessAacClock(rate, originUs, padding.toInt(), frames, codecOriginUs)
        } finally { proof.release() }
    }

    private class DecoderStream(val extractor: MediaExtractor, val decoder: MediaCodec,
        val metrics: StreamMetrics, val checkCancelled: () -> Unit,
        private val gaplessClock: GaplessAacClock?) : PcmStream {
        private var signature: Triple<Int, Int, Int>? = null
        private var first: PcmChunk? = null
        private var inputEnded = false
        private var outputEnded = false
        private var closed = false
        private var lastProgressNs = System.nanoTime()
        private val info = MediaCodec.BufferInfo()
        private var clockDiagnostics = 0
        private var pendingOutput: ByteBuffer? = null
        private var pendingIndex = -1
        private var pendingPtsUs = 0L
        override val format: PcmFormat get() = requireNotNull(signature).let { PcmFormat(it.first, if (it.second <= 2) it.second else 1) }
        init { metrics.opened() }

        fun prime() {
            first = next()
            if (first != null) metrics.retained()
            check(signature != null) { "Decoder reached EOF without actual PCM format evidence" }
        }

        override fun read(): PcmChunk? {
            check(!closed)
            checkCancelled()
            first?.let { first = null; metrics.released(); return it }
            return next()
        }

        private fun acceptFormat(actual: MediaFormat) {
            val rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT // Android's documented default PCM encoding.
            require(rate in 8_000..192_000 && channels in 1..8 && encoding in setOf(2, 3, 4)) { "Unsupported decoded PCM format: $actual" }
            val value = Triple(rate, channels, encoding)
            check(signature == null || signature == value) { "Incompatible midstream PCM format change: $signature to $value" }
            signature = value
        }

        private fun next(): PcmChunk? {
            while (!outputEnded || pendingOutput != null) {
                checkCancelled()
                pendingOutput?.let { bytes ->
                    val actual = requireNotNull(signature)
                    val width = when (actual.third) { 2 -> 2; 3 -> 1; else -> 4 }
                    val size = minOf(bytes.remaining(), actual.first * actual.second * width)
                    val part = bytes.duplicate().apply { limit(position() + size) }
                    val chunk = try {
                        DecodedPcmBuffer.convert(part, actual.first, actual.second, actual.third, pendingPtsUs)
                    } catch (failure: Throwable) {
                        runCatching { releasePendingOutput() }.exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    }
                    bytes.position(bytes.position() + size)
                    if (bytes.hasRemaining()) pendingPtsUs = Math.addExact(pendingPtsUs, 1_000_000L)
                    else releasePendingOutput()
                    if (chunk != null) return chunk
                }
                if (pendingOutput != null || outputEnded) continue
                check(System.nanoTime() - lastProgressNs < STALL_TIMEOUT_NS) { "Source audio decoder stalled" }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted source audio is unsupported" }
                            check(count <= buffer.capacity()) { "Encoded audio sample exceeds codec capacity" }
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0)
                                MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, flags)
                            extractor.advance()
                        }
                        lastProgressNs = System.nanoTime()
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { acceptFormat(decoder.outputFormat); lastProgressNs = System.nanoTime() }
                    else -> if (index >= 0) {
                        try {
                            acceptFormat(decoder.outputFormat)
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val bytes = requireNotNull(decoder.getOutputBuffer(index)).duplicate()
                                bytes.position(info.offset); bytes.limit(info.offset + info.size)
                                val actual = requireNotNull(signature)
                                val width = when (actual.third) { 2 -> 2; 3 -> 1; else -> 4 }
                                check(info.size % (actual.second * width) == 0) { "Partial decoded PCM frame" }
                                val ptsUs = if (gaplessClock != null) {
                                    check(actual.first == gaplessClock.sampleRate) { "Gapless AAC output sample rate changed" }
                                    gaplessClock.accept(info.presentationTimeUs, info.size / (actual.second * width)).also {
                                        if (clockDiagnostics++ < 3) android.util.Log.i("SourceAacTiming",
                                            "gaplessPCM codecPts=${info.presentationTimeUs} pcmPts=$it frames=${info.size / (actual.second * width)}")
                                    }
                                } else info.presentationTimeUs
                                metrics.buffer(info.size)
                                if (outputEnded) gaplessClock?.finish()
                                // Borrow the codec buffer until its bounded PCM portions are consumed.
                                pendingOutput = bytes
                                pendingIndex = index
                                pendingPtsUs = ptsUs
                            }
                        } finally {
                            if (pendingIndex != index) decoder.releaseOutputBuffer(index, false)
                            lastProgressNs = System.nanoTime()
                        }
                    }
                }
            }
            gaplessClock?.finish()
            return null
        }

        private fun releasePendingOutput() {
            val index = pendingIndex
            pendingOutput = null
            pendingIndex = -1
            if (index >= 0) decoder.releaseOutputBuffer(index, false)
            lastProgressNs = System.nanoTime()
        }

        override fun close() {
            if (closed) return
            closed = true
            if (first != null) { first = null; metrics.released() }
            try { try { releasePendingOutput() } finally { try { decoder.stop() } finally { decoder.release() } } }
            finally { try { extractor.release() } finally { metrics.closed() } }
        }
    }

    data class DecodedAudio(
        val sampleRate: Int,
        val channelCount: Int,
        val interleavedSamples: FloatArray,
        val pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
    ) {
        init {
            require(sampleRate > 0 && channelCount in 1..8)
            require(interleavedSamples.size % channelCount == 0)
        }

        val frameCount: Long get() = interleavedSamples.size.toLong() / channelCount

        fun mono(): FloatArray = FloatArray(frameCount.toInt()) { frame ->
            var sum = 0f
            repeat(channelCount) { channel -> sum += interleavedSamples[frame * channelCount + channel] }
            sum / channelCount
        }
    }

    fun decode(file: File, maxDecodeUs: Long = Long.MAX_VALUE,
               preserveFloatHeadroom: Boolean = false,
               checkCancelled: () -> Unit = {}, startUs: Long = 0L): DecodedAudio {
        require(file.isFile && startUs >= 0 && maxDecodeUs > 0)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            decodeTrack(extractor, maxDecodeUs, preserveFloatHeadroom, checkCancelled, startUs)
        } finally {
            extractor.release()
        }
    }

    fun decodeResource(context: Context, resourceId: Int, maxDecodeUs: Long = Long.MAX_VALUE,
                       preserveFloatHeadroom: Boolean = false,
                       checkCancelled: () -> Unit = {}): DecodedAudio {
        val descriptor = requireNotNull(context.resources.openRawResourceFd(resourceId)) {
            "Music resource is not seekable"
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            decodeTrack(extractor, maxDecodeUs, preserveFloatHeadroom, checkCancelled)
        } finally {
            extractor.release()
            descriptor.close()
        }
    }

    private fun decodeTrack(extractor: MediaExtractor, maxDecodeUs: Long,
                            preserveFloatHeadroom: Boolean,
                            checkCancelled: () -> Unit, startUs: Long = 0L): DecodedAudio {
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track")
        extractor.selectTrack(track)
        val firstPacketUs = extractor.sampleTime
        val initialFormat = extractor.getTrackFormat(track)
        val maxUs = if (maxDecodeUs > Long.MAX_VALUE - startUs) Long.MAX_VALUE else startUs + maxDecodeUs
        // MP3 seek tables can report the requested time for a different packet. Count the PCM
        // from the original decoder origin so delay, padding and the selected window share a clock.
        val mp3Rate = if (firstPacketUs == 0L && initialFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_MPEG)
            initialFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null
        fun ceilingSample(timeUs: Long, rate: Int): Long =
            timeUs / 1_000_000L * rate + (timeUs % 1_000_000L * rate + 999_999L) / 1_000_000L
        val mp3Start = mp3Rate?.let { ceilingSample(startUs, it) } ?: 0L
        val mp3End = mp3Rate?.let { ceilingSample(maxUs, it) } ?: Long.MAX_VALUE
        var mp3Frames = 0L
        if (mp3Rate == null && startUs > 0L) extractor.seekTo((startUs - 500_000L).coerceAtLeast(0L),
            MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val inputFormat = if (mp3Rate == null) extractor.getTrackFormat(track) else initialFormat
        if (mp3Rate == null) {
            if (extractor.sampleTime > firstPacketUs && inputFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY))
                inputFormat.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION) &&
                maxUs < inputFormat.getLong(MediaFormat.KEY_DURATION) &&
                inputFormat.containsKey(MediaFormat.KEY_ENCODER_PADDING))
                inputFormat.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
        }
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio track has no MIME")
        // The Android 8 Stagefright MP3 converter can crash when asked for float PCM.
        val canRequestFloat = mime != MediaFormat.MIMETYPE_AUDIO_RAW &&
            (mime != MediaFormat.MIMETYPE_AUDIO_MPEG || Build.VERSION.SDK_INT >= 28)
        if (preserveFloatHeadroom && canRequestFloat) {
            // The returned encoding still has to be checked: this is a request, not evidence.
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        }
        val decoder = MediaCodec.createDecoderByType(mime)

        val bytes = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var outputFormat: MediaFormat? = null
        var lastProgressNs = System.nanoTime()
        try {
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            while (!outputEnded) {
                checkCancelled()
                check(System.nanoTime() - lastProgressNs < STALL_TIMEOUT_NS) { "Audio decoder stalled" }
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: error("Missing decoder input")
                        input.clear()
                        val sampleTime = extractor.sampleTime
                        if (mp3Rate == null && sampleTime >= 0 && sampleTime >= maxUs) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                sampleTime,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                        lastProgressNs = System.nanoTime()
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                        lastProgressNs = System.nanoTime()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (outputFormat == null) outputFormat = decoder.outputFormat
                        if (info.size > 0) {
                            val output = decoder.getOutputBuffer(outputIndex) ?: error("Missing decoder output")
                            val format = requireNotNull(outputFormat)
                            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val sampleBytes = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> error("Unsupported decoder PCM encoding $encoding")
                            }
                            val frameBytes = sampleBytes * channels
                            val frameCount = info.size / frameBytes
                            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val frames = if (mp3Rate == null) {
                                AudioPcmWindow.frames(info.presentationTimeUs, frameCount, rate, startUs, maxDecodeUs)
                            } else {
                                check(rate == mp3Rate) { "MP3 output sample rate changed" }
                                val first = (mp3Start - mp3Frames).coerceIn(0L, frameCount.toLong()).toInt()
                                val end = (mp3End - mp3Frames).coerceIn(0L, frameCount.toLong()).toInt()
                                mp3Frames += frameCount
                                first until end
                            }
                            if (!frames.isEmpty()) {
                                output.position(info.offset + frames.first * frameBytes)
                                output.limit(info.offset + (frames.last + 1) * frameBytes)
                                val chunk = ByteArray(frames.count() * frameBytes)
                                check(bytes.size().toLong() + chunk.size <= 64L * 1024 * 1024) {
                                    "Аудиофрагмент слишком большой для обработки на устройстве"
                                }
                                output.get(chunk)
                                bytes.write(chunk)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || mp3Frames >= mp3End
                        decoder.releaseOutputBuffer(outputIndex, false)
                        lastProgressNs = System.nanoTime()
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }

        val decodedFormat = requireNotNull(outputFormat) { "No decoded audio format evidence" }
        val sampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT
        return DecodedAudio(sampleRate, channels,
            PcmSampleConverter.decode(bytes.toByteArray(), encoding, clampFloatSamples = !preserveFloatHeadroom),
            encoding)
    }
}

/** Pure byte/sample conversion kept outside MediaCodec so edge cases are covered on the JVM. */
internal object PcmSampleConverter {
    fun decode(bytes: ByteArray, encoding: Int, clampFloatSamples: Boolean = true): FloatArray = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> FloatArray(bytes.size) { index ->
            ((bytes[index].toInt() and 0xff) - 128) / 128f
        }
        AudioFormat.ENCODING_PCM_FLOAT -> {
            require(bytes.size % 4 == 0)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(buffer.remaining()).also { buffer.get(it) }.also { samples ->
                if (clampFloatSamples) samples.indices.forEach { index ->
                    samples[index] = samples[index].coerceIn(-1f, 1f)
                }
            }
        }
        AudioFormat.ENCODING_PCM_16BIT -> {
            require(bytes.size % 2 == 0)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            FloatArray(buffer.remaining()) { buffer.get() / 32768f }
        }
        else -> error("Unsupported decoder PCM encoding $encoding")
    }

    fun encode16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            buffer.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return bytes
    }
}
