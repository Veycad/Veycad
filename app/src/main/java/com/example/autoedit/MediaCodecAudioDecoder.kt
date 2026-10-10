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
               checkCancelled: () -> Unit = {}): DecodedAudio {
        require(file.isFile)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            decodeTrack(extractor, maxDecodeUs, preserveFloatHeadroom, checkCancelled)
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
                            checkCancelled: () -> Unit): DecodedAudio {
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track")
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        if (preserveFloatHeadroom) {
            // Ask for float output so AAC overshoots are measured before integer saturation.
            // The returned encoding still has to be checked: this is a request, not evidence.
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        }
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio track has no MIME")
        val decoder = MediaCodec.createDecoderByType(mime)
        val maxUs = maxDecodeUs.coerceAtLeast(0L)

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
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
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
