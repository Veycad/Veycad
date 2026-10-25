package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import java.io.File

/** A real, decodable AVC clip generated on-device, independent of user footage. */
internal object SyntheticVideo {
    fun create(target: File, durationMs: Long = 3_000, width: Int = 160, height: Int = 240,
        lumaBase: Int = 40, fps: Int = 30, textureSeed: Int? = null, iFrameIntervalSeconds: Int = 1, lumaAt: ((x: Int, y: Int, frame: Int) -> Int)? = null, lumaPhase: Int = 0): File {
        require(durationMs in 1_000..180_000)
        require(lumaPhase in 0..99)
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && lumaBase in 0..155)
        require(fps in 1..60 && iFrameIntervalSeconds in 1..2)
        if (lumaPhase == 0 && textureSeed == null && lumaAt == null && iFrameIntervalSeconds == 1 && fps == 30 && lumaBase == 40 && target.isFile && target.length() > 0) {
            val valid = runCatching {
                MediaMetadataRetriever().let { metadata ->
                    try {
                        metadata.setDataSource(target.path)
                        kotlin.math.abs(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() - durationMs) <= 100 &&
                            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() == width &&
                            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() == height
                    } finally { metadata.release() }
                }
            }.getOrDefault(false)
            if (valid) return target
        }
        target.parentFile!!.mkdirs()
        val codec = MediaCodec.createEncoderByType("video/avc")
        val temporary = File(target.path + ".partial")
        val muxer = MediaMuxer(temporary.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val supported = codec.codecInfo.getCapabilitiesForType("video/avc").colorFormats
            val color = if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in supported)
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            else supported.first { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar }
            codec.configure(MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, 200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frame = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Synthetic AVC encoding timed out" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == (durationMs * fps / 1_000).toInt()) {
                            codec.queueInputBuffer(index, 0, 0, frame * 1_000_000L / fps,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val pixels = ByteArray(width * height * 3 / 2) { offset ->
                                if (offset >= width * height) 128.toByte()
                                else if (lumaAt != null) lumaAt(offset % width, offset / width, frame).also { require(it in 0..255) }.toByte()
                                else if (textureSeed == null) (lumaBase + (frame + lumaPhase) % 100).toByte()
                                else {
                                    val x = offset % width; val y = offset / width
                                    val bright = ((x + frame + textureSeed * 7) / 8 + y / 8) % 2 == 0
                                    (if (bright) 200 - textureSeed * 8 else 40 + textureSeed * 12).toByte()
                                }
                            }
                            codec.getInputBuffer(index)!!.apply { clear(); put(pixels) }
                            codec.queueInputBuffer(index, 0, pixels.size, frame * 1_000_000L / fps, 0)
                            frame++
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
        check(temporary.length() > 0 && temporary.renameTo(target))
        return target
    }
}
