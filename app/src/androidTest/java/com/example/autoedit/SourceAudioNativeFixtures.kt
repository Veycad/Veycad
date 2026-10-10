package com.veycad.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Independently authored fixture clocks. No SourceAudioComposer/SourceTimeMap/decoder helper
 * computes the oracles. Files stay in app-private test evidence for remote inspection/listening. */
internal object SourceAudioNativeFixtures {
    fun pcmWave(file: File, bytes: ByteArray, rate: Int, channels: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+bytes.size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate*channels*2)
        header.putShort((channels*2).toShort()).putShort(16).put("data".toByteArray()).putInt(bytes.size)
        file.outputStream().use { it.write(header.array()); it.write(bytes) }
    }

    fun wav(file: File, seconds: Int = 1, sample: (Int) -> Short): File {
        val count = seconds * 48_000
        file.outputStream().buffered().use { output ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
            header.putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(count * 2)
            output.write(header.array())
            val bytes = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0
            while (frame < count) {
                bytes.clear()
                val size = minOf(1024, count - frame)
                repeat(size) { bytes.putShort(sample(frame++)) }
                output.write(bytes.array(), 0, size * 2)
            }
        }
        return file
    }

    fun tone(frame: Int, hz: Double = 440.0, level: Double = 10_000.0) =
        (sin(2 * PI * hz * frame / 48_000) * level).toInt().toShort()

    /** AAC packets use literal authored offset/gap, with positive first raw PTS, no video-origin subtraction. */
    fun aac(file: File, originUs: Long = 0, gapUs: Long = 0, hz: Double = 440.0): File {
        val encoder = AacEncoderMuxer.createEncoder(AacEncoderMuxer.Config(48_000, 2))
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var queued = false
            var ended = false
            var track = -1
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!ended) {
                check(System.nanoTime() < deadline)
                if (!queued) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        val size = minOf(1024, 48_000 - frame, buffer.capacity() / 4)
                        repeat(size) { val sample = tone(frame + it, hz); buffer.putShort(sample); buffer.putShort(sample) }
                        encoder.queueInputBuffer(index, 0, size * 4, frame * 1_000_000L / 48_000,
                            if (size == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frame += size; queued = size == 0
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.presentationTimeUs in 0 until 1_000_000) {
                                val rawPts = info.presentationTimeUs + originUs + if (info.presentationTimeUs >= 512_000) gapUs else 0
                                val authored = MediaCodec.BufferInfo().apply { set(info.offset, info.size, rawPts, 0) }
                                muxer.writeSampleData(track, encoder.getOutputBuffer(index)!!, authored)
                            }
                            ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            muxer.writeSampleData(track, ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
                set(0, 0, originUs + 1_000_000 + gapUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            })
            muxer.stop(); started = false
        } finally {
            try { try { encoder.stop() } finally { encoder.release() } }
            finally { try { if (started) runCatching { muxer.stop() } } finally { muxer.release() } }
        }
        return file
    }

    /** Remux fixture tracks without decoding; first audio input determines first-track selection. */
    fun remux(file: File, inputs: List<File>, shiftVideoUs: Long = 0, rotation: Int = 0): File {
        val extractors = mutableListOf<MediaExtractor>()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            muxer.setOrientationHint(rotation)
            val outputs = inputs.map { source ->
                val extractor = MediaExtractor(); extractors += extractor
                extractor.setDataSource(source.path); extractor.selectTrack(0)
                muxer.addTrack(extractor.getTrackFormat(0))
            }
            muxer.start(); started = true
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            extractors.forEachIndexed { index, extractor ->
                val video = extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                while (extractor.sampleTime >= 0) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    val info = MediaCodec.BufferInfo().apply { set(0, size, extractor.sampleTime + if (video) shiftVideoUs else 0,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
                    muxer.writeSampleData(outputs[index], buffer, info)
                    extractor.advance()
                }
                if (video) muxer.writeSampleData(outputs[index], ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
                    set(0, 0, shiftVideoUs + 1_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                })
            }
            muxer.stop(); started = false
        } finally { extractors.forEach { it.release() }; try { if (started) runCatching { muxer.stop() } } finally { muxer.release() } }
        return file
    }
}
