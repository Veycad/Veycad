package com.veycad.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Same-UID, non-exported inference endpoint isolated from the ML Kit native runtime. */
class MulticlassMatteProvider : ContentProvider() {
    override fun onCreate() = true

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == METHOD_ANALYZE || method == METHOD_TARGETS)
        val source = File(requireNotNull(extras?.getString(EXTRA_SOURCE)))
        val intervalUs = extras.getLong(EXTRA_INTERVAL_US)
        // Same-UID worker lease disappears on cancellation or recovery after process death.
        val lease = extras.getString(EXTRA_JOB_LEASE)?.let(::File)
        val checkCancelled = { check(lease == null || lease.isFile) { "Монтаж отменён" } }
        checkCancelled()
        val targets = if (method == METHOD_TARGETS) requireNotNull(extras.getLongArray(EXTRA_TARGETS)) else null
        require(source.isFile && (targets != null || intervalUs in 100_000L..1_000_000L))
        targets?.let {
            require(it.size in 1..OpeningMatteRefinement.MAX_FRAMES && it.all { time -> time >= 0 })
            require(it.toList().zipWithNext().all { (a, b) -> a / 1_000L < b / 1_000L })
        }
        val output = File(requireNotNull(context).cacheDir,
            if (targets == null) "multiclass-matte.bin" else "opening-matte.bin")
        val temporary = File(output.parentFile, "${output.name}.partial")
        val frames = if (targets == null) File(output.parentFile, FRAME_CACHE_DIRECTORY) else null
        val temporaryFrames = frames?.let { File(output.parentFile, "${it.name}.partial") }
        temporary.delete()
        temporaryFrames?.deleteRecursively()
        temporaryFrames?.mkdirs()
        val decodedTimes = try {
            analyze(source, intervalUs, temporary, targets, temporaryFrames, checkCancelled).also {
                checkCancelled()
            }
        } catch (error: Throwable) {
            temporary.delete()
            temporaryFrames?.deleteRecursively()
            throw error
        }
        output.delete()
        require(temporary.renameTo(output)) { "Unable to publish matte cache" }
        if (frames != null && temporaryFrames != null) {
            frames.deleteRecursively()
            require(temporaryFrames.renameTo(frames)) { "Unable to publish decoded frame cache" }
        }
        return Bundle().apply {
            putString(EXTRA_RESULT, output.absolutePath)
            putLongArray(EXTRA_DECODED_TIMES, decodedTimes)
            frames?.let { putString(EXTRA_FRAME_CACHE, it.absolutePath) }
        }
    }

    private fun analyze(source: File, intervalUs: Long, output: File, targets: LongArray?,
        frameCache: File?, checkCancelled: () -> Unit): LongArray {
        checkCancelled()
        val startedNs = System.nanoTime()
        val segmenter = targets?.let {
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_PATH).build())
                .setRunningMode(RunningMode.VIDEO)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
            ImageSegmenter.createFromOptions(requireNotNull(context), options)
        }
        segmenter.use {
            val metadata = SequentialBitmapDecoder.metadata(source)
            val durationUs = metadata.durationUs
            val baseDimensions = analysisDimensions(metadata)
            val dimensions = if (targets == null) baseDimensions else
                OpeningMatteRefinement.dimensions(baseDimensions.first, baseDimensions.second, targets.size)
            require(targets == null || targets.last() < durationUs)
            val times = targets ?: MulticlassMatteClient.decodedTargets(source,
                SequentialBitmapDecoder.intervalTargets(durationUs, intervalUs), checkCancelled,
                analysisClock = true)
            val frameBuffer = frameCache?.let {
                ByteBuffer.allocateDirect(8 + dimensions.first * dimensions.second * 4)
                    .order(ByteOrder.BIG_ENDIAN)
            }
            DataOutputStream(FileOutputStream(output)).use { stream ->
                stream.writeInt(CACHE_MAGIC)
                stream.writeInt(CACHE_VERSION)
                val stats = SequentialBitmapDecoder.decode(
                    source, times, dimensions.first, dimensions.second, requireExactPts = targets == null
                ) { timeUs, frame ->
                        checkCancelled()
                        // MPImage.close owns/recycles its bitmap. The decoder lends this frame
                        // to every target reached by one output PTS, so inference needs its own copy.
                        val image = BitmapImageBuilder(
                            frame.copy(Bitmap.Config.ARGB_8888, false)
                        ).build()
                        try {
                            if (frameCache != null && frameBuffer != null) {
                                writeFrameCache(frame, timeUs, frameCache, frameBuffer)
                            }
                            if (segmenter != null) {
                                val masks = segmenter.segmentForVideo(image, timeUs / 1_000L)
                                    .confidenceMasks().orElseThrow()
                                require(masks.size >= CLASS_COUNT)
                                val width = masks.first().width
                                val height = masks.first().height
                                val channels = masks.take(CLASS_COUNT).map { mask ->
                                    val buffer = ByteBufferExtractor.extract(mask)
                                        .order(ByteOrder.nativeOrder()).apply { rewind() }
                                    FloatArray(width * height) { buffer.float.coerceIn(0f, 1f) }
                                }
                                stream.writeLong(timeUs)
                                stream.writeShort(width)
                                stream.writeShort(height)
                                val personMask = PersonMaskUnion.combine(channels, width, height)
                                repeat(width * height) { pixel ->
                                    stream.writeByte((personMask[pixel] * 255f + .5f).toInt())
                                }
                                masks.forEach { it.close() }
                            }
                            checkCancelled()
                        } finally {
                            image.close()
                        }
                }
                Log.i(TAG, "${if (segmenter == null) "Frame decode" else "Exact matte"} completed in " +
                    "${(System.nanoTime() - startedNs) / 1_000_000L} ms; " +
                    "requested=${stats.requestedFrames}, decoded=${stats.decodedFrames}")
            }
            return times
        }
    }

    /** Exact ARGB pixels already decoded for segmentation, consumed once by the main analyzer. */
    private fun writeFrameCache(bitmap: Bitmap, timeUs: Long, directory: File,
        buffer: ByteBuffer) {
        require(buffer.capacity() >= 8 + bitmap.byteCount)
        buffer.clear()
        buffer.putInt(bitmap.width)
        buffer.putInt(bitmap.height)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.flip()
        FileOutputStream(File(directory, "$timeUs.argb")).channel.use { channel ->
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private fun analysisDimensions(metadata: SequentialBitmapDecoder.Metadata): Pair<Int, Int> {
        val width = metadata.displayWidth
        val height = metadata.displayHeight
        val scale = MAX_INPUT_SIDE.toFloat() / maxOf(width, height)
        return (width * scale).toInt().coerceAtLeast(64) to
            (height * scale).toInt().coerceAtLeast(64)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?) = 0

    companion object {
        const val METHOD_ANALYZE = "analyze"
        const val METHOD_TARGETS = "analyze_targets"
        const val EXTRA_TARGETS = "targets_us"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_JOB_LEASE = "job_lease"
        const val EXTRA_INTERVAL_US = "interval_us"
        const val EXTRA_RESULT = "result"
        const val EXTRA_FRAME_CACHE = "frame_cache"
        const val EXTRA_DECODED_TIMES = "decoded_times_us"
        const val CACHE_MAGIC = 0x564D4154
        const val CACHE_VERSION = 1
        private const val MODEL_ASSET_PATH = "models/selfie_multiclass_256x256.tflite"
        private const val CLASS_COUNT = 6
        private const val MAX_INPUT_SIDE = 480
        private const val FRAME_CACHE_DIRECTORY = "multiclass-decoded-frames"
        private const val TAG = "VeycadMatte"
    }
}
