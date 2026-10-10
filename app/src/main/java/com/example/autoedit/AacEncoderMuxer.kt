package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.Context
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Owned AAC encoder primitives. The export backend feeds PCM from the selected local music
 * asset in graph order; timestamps are sample-clock based rather than decoder callback time.
 * Keeping this separate from Media3 makes the mux contract testable and prevents looping gaps.
 */
internal object AacEncoderMuxer {
    const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024

    data class Config(val sampleRate: Int, val channelCount: Int, val bitrate: Int = 192_000) {
        init { require(sampleRate > 0 && channelCount in 1..2) }
    }

    fun createEncoder(config: Config): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_SAMPLES_PER_ACCESS_UNIT * config.channelCount * 2)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /** Queue PCM with a monotonic sample clock. Callers loop/cut PCM using [AudioExportPlan]. */
    fun queuePcm(encoder: MediaCodec, pcm: ByteBuffer, samplesBeforeBuffer: Long, config: Config): Boolean {
        val index = encoder.dequeueInputBuffer(10_000)
        if (index < 0) return false
        val target = encoder.getInputBuffer(index) ?: error("Missing AAC encoder input")
        target.clear(); target.put(pcm)
        encoder.queueInputBuffer(index, 0, target.position(), AudioExportPlan.presentationTimeUs(samplesBeforeBuffer, config.sampleRate), 0)
        return true
    }

    /**
     * Decodes the bundled music once, loops/cuts PCM on the sample clock, encodes AAC-LC and
     * muxes it with the VME H.264 stream. No Media3 audio clock participates in this path.
     */
    fun muxMusic(context: Context, resourceId: Int, videoFile: File, outputFile: File, durationUs: Long) {
        val decoded = MediaCodecAudioDecoder.decodeResource(context, resourceId, durationUs,
            preserveFloatHeadroom = true)
        val pcm = prepareMusicPcm(decoded)
        muxPcm(pcm, videoFile, outputFile, durationUs)
    }

    /** Same owned AAC path for user-selected files; the source never has to be packaged in APK. */
    fun muxMusicFile(audioFile: File, videoFile: File, outputFile: File, durationUs: Long,
        checkCancelled: () -> Unit = {}, startUs: Long = 0L) {
        checkCancelled()
        val decoded = MediaCodecAudioDecoder.decode(audioFile, durationUs,
            preserveFloatHeadroom = true, checkCancelled = checkCancelled, startUs = startUs)
        muxPcm(prepareMusicPcm(decoded, checkCancelled), videoFile, outputFile, durationUs, checkCancelled)
    }

    private fun prepareMusicPcm(decoded: MediaCodecAudioDecoder.DecodedAudio,
                                checkCancelled: () -> Unit = {}): Pcm {
        val channels = if (decoded.channelCount <= 2) decoded.channelCount else 1
        val samples = if (channels == decoded.channelCount) decoded.interleavedSamples else decoded.mono()
        val prepared = AudioExportHeadroom.prepare(samples, checkCancelled)
        return Pcm(PcmSampleConverter.encode16(prepared.samples), decoded.sampleRate, channels)
    }

    private fun muxPcm(pcm: Pcm, videoFile: File, outputFile: File, durationUs: Long, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        val config = Config(pcm.sampleRate, pcm.channels)
        val targetSamples = durationUs * config.sampleRate / 1_000_000L
        val looped = loopPcm(pcm.bytes, pcm.bytesPerFrame, targetSamples)
        val encoded = encode(looped, targetSamples, config, checkCancelled)
        val video = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val videoTrack = (0 until video.trackCount).first { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoFormat = video.getTrackFormat(videoTrack)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(encoded.format)
            muxer.start()
            video.selectTrack(videoTrack)
            // KEY_MAX_INPUT_SIZE is normally populated for an MP4 track. On API 26–27 we
            // cannot read the current sample size, so retain a conservative fallback for a
            // large H.264 keyframe rather than truncating the video during the final mux.
            val declaredMaxSampleSize = runCatching {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(0)
            var buffer = ByteBuffer.allocateDirect(max(declaredMaxSampleSize, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                checkCancelled()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val sampleSize = video.sampleSize
                    if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
                }
                buffer.clear(); val size = video.readSampleData(buffer, 0)
                if (size < 0) break
                val muxerFlags = if (video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, video.sampleTime, muxerFlags)
                muxer.writeSampleData(outVideo, buffer, info); video.advance()
            }
            encoded.samples.forEach { sample -> muxer.writeSampleData(outAudio, sample.data.duplicate(), sample.info) }
        } finally {
            video.release(); runCatching { muxer.stop() }; muxer.release()
        }
    }

    private data class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int) {
        val bytesPerFrame get() = channels * 2
    }
    private data class Encoded(val format: MediaFormat, val samples: List<Sample>)
    private data class Sample(val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private fun loopPcm(source: ByteArray, bytesPerFrame: Int, targetSamples: Long): ByteArray {
        require(source.isNotEmpty() && source.size % bytesPerFrame == 0)
        val wanted = (targetSamples * bytesPerFrame).toInt(); val out = ByteArray(wanted); var offset = 0
        while (offset < wanted) { val count = minOf(source.size, wanted - offset); System.arraycopy(source, 0, out, offset, count); offset += count }
        return out
    }

    private fun encode(pcm: ByteArray, targetSamples: Long, config: Config, checkCancelled: () -> Unit): Encoded {
        val encoder = createEncoder(config); val samples = mutableListOf<Sample>(); var format: MediaFormat? = null; var byteOffset = 0; var sampleOffset = 0L; var eosQueued = false; val info = MediaCodec.BufferInfo()
        encoder.start()
        try { while (true) {
            checkCancelled()
            if (!eosQueued) { val index = encoder.dequeueInputBuffer(10_000); if (index >= 0) { val input = encoder.getInputBuffer(index)!!; val bytes = minOf(input.capacity(), pcm.size - byteOffset); if (bytes > 0) { input.put(pcm, byteOffset, bytes); encoder.queueInputBuffer(index, 0, bytes, AudioExportPlan.presentationTimeUs(sampleOffset, config.sampleRate), 0); byteOffset += bytes; sampleOffset += bytes / (config.channelCount * 2) } else { encoder.queueInputBuffer(index, 0, 0, AudioExportPlan.presentationTimeUs(targetSamples, config.sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosQueued = true } } }
            when (val index = encoder.dequeueOutputBuffer(info, 10_000)) { MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = encoder.outputFormat; MediaCodec.INFO_TRY_AGAIN_LATER -> Unit; else -> if (index >= 0) { if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.presentationTimeUs <= AudioExportPlan.presentationTimeUs(targetSamples, config.sampleRate)) { val source = encoder.getOutputBuffer(index)!!; source.position(info.offset); source.limit(info.offset + info.size); val copy = ByteBuffer.allocateDirect(info.size); copy.put(source); copy.flip(); samples += Sample(copy, MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }) }; val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; encoder.releaseOutputBuffer(index, false); if (end) break } }
        } } finally { runCatching { encoder.stop() }; encoder.release() }
        return Encoded(requireNotNull(format), samples)
    }
}
