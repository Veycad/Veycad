package com.veycad.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.Mp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Prepared AAC needs negative priming PTS, rejected by older platform muxers. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PreparedMp4Muxer(file: File) {
    private val platform = if (Build.VERSION.SDK_INT >= 35)
        MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) else null
    private val legacy = if (platform == null) NativeResources().use { resources ->
        val output = resources.own(FileOutputStream(file), FileOutputStream::close)
        val muxer = Mp4Muxer.Builder(output).setSampleBatchingEnabled(false)
            .setSampleCopyingEnabled(false).setAttemptStreamableOutputEnabled(false).build()
        resources.transfer()
        muxer
    } else null
    private var closed = false

    fun addTrack(format: MediaFormat): Int = platform?.addTrack(format)
        ?: requireNotNull(legacy).addTrack(MediaFormatUtil.createFormatFromMediaFormat(format))

    fun setOrientationHint(degrees: Int) {
        if (platform != null) platform.setOrientationHint(degrees)
        else requireNotNull(legacy).addMetadataEntry(Mp4OrientationData(degrees))
    }

    fun start() { platform?.start() }

    fun writeSampleData(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (platform != null) platform.writeSampleData(track, data, info)
        else {
            val sample = data.duplicate().apply {
                position(info.offset); limit(info.offset + info.size)
            }.slice()
            requireNotNull(legacy).writeSampleData(track, sample,
                androidx.media3.muxer.BufferInfo(info.presentationTimeUs, info.size, info.flags))
        }
    }

    fun stop() { if (platform != null) platform.stop() else closeLegacy() }
    fun release() { if (platform != null) platform.release() else closeLegacy() }

    private fun closeLegacy() {
        if (closed) return
        closed = true
        requireNotNull(legacy).close()
    }
}
