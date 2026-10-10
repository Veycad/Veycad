package com.veycad.app

import android.media.AudioFormat
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any Android-supported local audio track into normalized PCM for analysis and AAC mux. */
internal object MediaCodecAudioDecoder {
    private const val STALL_TIMEOUT_NS = 20_000_000_000L

    /** Per-operation accounting, excluding codec-internal/native allocations. Not thread safe. */
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
            LoopingMusicPcmStream(raw, startUs)
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
                    codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                    codec.configure(format, null, null, 0)
                    codec.start()
                    stream = DecoderStream(extractor, codec, metrics, checkCancelled)
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

    private class DecoderStream(val extractor: MediaExtractor, val decoder: MediaCodec,
        val metrics: StreamMetrics, val checkCancelled: () -> Unit) : PcmStream {
        private var signature: Triple<Int, Int, Int>? = null
        private var first: PcmChunk? = null
        private var inputEnded = false
        private var outputEnded = false
        private var closed = false
        private var lastProgressNs = System.nanoTime()
        private val info = MediaCodec.BufferInfo()
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
            while (!outputEnded) {
                checkCancelled()
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
                                val chunk = DecodedPcmBuffer.convert(bytes, actual.first, actual.second, actual.third, info.presentationTimeUs)
                                metrics.buffer(info.size)
                                if (chunk != null) return chunk
                            }
                        } finally { decoder.releaseOutputBuffer(index, false); lastProgressNs = System.nanoTime() }
                    }
                }
            }
            return null
        }

        override fun close() {
            if (closed) return
            closed = true
            if (first != null) { first = null; metrics.released() }
            try { try { decoder.stop() } finally { decoder.release() } }
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
        // Audio extractors may seek to the closest packet even with PREVIOUS_SYNC. Leave
        // preroll for MP3's bit reservoir and discard it by the decoded PCM clock below.
        if (startUs > 0L) extractor.seekTo((startUs - 500_000L).coerceAtLeast(0L),
            MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val inputFormat = extractor.getTrackFormat(track)
        val maxUs = if (maxDecodeUs > Long.MAX_VALUE - startUs) Long.MAX_VALUE else startUs + maxDecodeUs
        // Encoder delay/padding describe the original file boundaries, not an interior seek
        // or the artificial EOS used for a bounded analysis/export window.
        if (startUs > 0L && extractor.sampleTime > firstPacketUs &&
            inputFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY))
            inputFormat.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
        if (inputFormat.containsKey(MediaFormat.KEY_DURATION) &&
            maxUs < inputFormat.getLong(MediaFormat.KEY_DURATION) &&
            inputFormat.containsKey(MediaFormat.KEY_ENCODER_PADDING))
            inputFormat.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
        if (preserveFloatHeadroom && inputFormat.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_RAW) {
            // Ask for float output so AAC overshoots are measured before integer saturation.
            // The returned encoding still has to be checked: this is a request, not evidence.
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        }
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio track has no MIME")
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
                        if (sampleTime >= 0 && sampleTime >= maxUs) {
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
                            val frames = AudioPcmWindow.frames(info.presentationTimeUs, info.size / frameBytes,
                                format.getInteger(MediaFormat.KEY_SAMPLE_RATE), startUs, maxDecodeUs)
                            output.position(info.offset + frames.first * frameBytes)
                            output.limit(info.offset + (frames.last + 1) * frameBytes)
                            val chunk = ByteArray(frames.count() * frameBytes)
                            check(bytes.size().toLong() + chunk.size <= 64L * 1024 * 1024) {
                                "Аудиофрагмент слишком большой для обработки на устройстве"
                            }
                            output.get(chunk)
                            bytes.write(chunk)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
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
