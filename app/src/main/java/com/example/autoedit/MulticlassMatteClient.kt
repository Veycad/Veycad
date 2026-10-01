package com.example.autoedit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Loads quantized PTS masks produced in the isolated MediaPipe process. */
internal object MulticlassMatteClient {
    class Analysis internal constructor(
        val masks: Map<Long, FrameAttachments.Plane>,
        private val cache: File?,
        val decodedTimesUs: LongArray
    ) : AutoCloseable {
        fun decodedFrame(timeUs: Long): Bitmap? {
            val file = cache?.resolve("$timeUs.argb")?.takeIf(File::isFile) ?: return null
            FileInputStream(file).channel.use { channel ->
                require(channel.size() in 9..MAXIMUM_CACHED_FRAME_BYTES.toLong())
                val buffer = ByteBuffer.allocateDirect(channel.size().toInt()).order(ByteOrder.BIG_ENDIAN)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
                require(!buffer.hasRemaining()) { "Truncated decoded frame cache: ${file.name}" }
                buffer.flip()
                val width = buffer.int
                val height = buffer.int
                require(width > 0 && height > 0 && buffer.remaining() == width * height * 4)
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.copyPixelsFromBuffer(buffer)
                }
            }
        }

        override fun close() {
            cache?.deleteRecursively()
        }
    }

    fun decodedTargets(source: File, planned: LongArray, checkCancelled: () -> Unit = {},
        analysisClock: Boolean = false): LongArray {
        checkCancelled()
        if (planned.isEmpty()) return planned
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val pts = ArrayList<Long>()
            while (extractor.sampleTime >= 0L) {
                checkCancelled()
                require(pts.size < 1_000_000) { "Source frame index exceeds local budget" }
                pts.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            val sorted = pts.distinct().sorted().toLongArray()
            return if (analysisClock) SourceAnalysisTimeline.align(planned, sorted)
                else OpeningMatteRefinement.alignToDecodedFrames(planned, sorted)
        } finally { extractor.release() }
    }

    fun analyzeTargets(context: Context, source: File, targets: LongArray,
        jobLease: File? = null): Map<Long, FrameAttachments.Plane> {
        val result = requireNotNull(context.contentResolver.call(
            Uri.parse("content://${context.packageName}.matte"), MulticlassMatteProvider.METHOD_TARGETS,
            null, Bundle().apply {
                putString(MulticlassMatteProvider.EXTRA_SOURCE, source.absolutePath)
                putLongArray(MulticlassMatteProvider.EXTRA_TARGETS, targets)
                putString(MulticlassMatteProvider.EXTRA_JOB_LEASE, jobLease?.path)
            }))
        return read(File(requireNotNull(result.getString(MulticlassMatteProvider.EXTRA_RESULT))))
    }
    fun analyze(context: Context, source: File, intervalUs: Long, jobLease: File? = null): Analysis {
        val authority = "${context.packageName}.matte"
        val result = requireNotNull(context.contentResolver.call(
            Uri.parse("content://$authority"),
            MulticlassMatteProvider.METHOD_ANALYZE,
            null,
            Bundle().apply {
                putString(MulticlassMatteProvider.EXTRA_SOURCE, source.absolutePath)
                putLong(MulticlassMatteProvider.EXTRA_INTERVAL_US, intervalUs)
                putString(MulticlassMatteProvider.EXTRA_JOB_LEASE, jobLease?.path)
            }
        ))
        val cache = File(requireNotNull(result.getString(MulticlassMatteProvider.EXTRA_RESULT)))
        val frames = result.getString(MulticlassMatteProvider.EXTRA_FRAME_CACHE)?.let(::File)
        val times = requireNotNull(result.getLongArray(MulticlassMatteProvider.EXTRA_DECODED_TIMES))
        require(times.isNotEmpty() && times.first() >= 0L &&
            times.toList().zipWithNext().all { (a, b) -> a < b })
        return Analysis(read(cache, requireFrames = false), frames, times)
    }

    internal fun read(
        file: File,
        requireFrames: Boolean = true
    ): Map<Long, FrameAttachments.Plane> {
        val output = LinkedHashMap<Long, FrameAttachments.Plane>()
        DataInputStream(FileInputStream(file)).use { stream ->
            require(stream.readInt() == MulticlassMatteProvider.CACHE_MAGIC)
            require(stream.readInt() == MulticlassMatteProvider.CACHE_VERSION)
            // EOF is legal only between records. A partial timestamp is corruption,
            // just like truncated geometry/pixels, and must not become a successful mask.
            while (stream.available() > 0) {
                val timeUs = stream.readLong()
                val width = stream.readUnsignedShort()
                val height = stream.readUnsignedShort()
                val values = FloatArray(width * height) { stream.readUnsignedByte() / 255f }
                val confidence = SemanticMaskMetrics.stats(values).confidence
                output[timeUs] = FrameAttachments.Plane(width, height, values, confidence)
            }
        }
        require(!requireFrames || output.isNotEmpty()) { "Isolated matte cache was empty" }
        return output
    }

    private const val MAXIMUM_CACHED_FRAME_BYTES = 480 * 480 * 4 + 8
}
