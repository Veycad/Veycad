package com.veycad.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.view.Surface
import java.io.File

/**
 * One forward-decoding source range on a caller-owned Surface. Owns only the extractor and
 * decoder, and neither allocates GL resources nor encodes output. Use on the Surface's GL worker.
 * [beginRange] starts a new direction from the previous sync sample; [advanceTo] selects the
 * first available source PTS >= target, retaining the last valid texture at physical EOS.
 *
 * The callback must await and update the caller's OES image before returning (and poll the same
 * cancellation callback while awaiting it). Decode/callback/cancellation exceptions propagate:
 * the session or pool must close this cursor in its own finally. Partial construction cleans
 * itself up. [close] never releases the borrowed Surface or the compositor that created it.
 */
internal class VideoDecoderCursor(
    file: File,
    outputSurface: Surface,
    private val checkCancelled: () -> Unit = {}
) : AutoCloseable {
    private val extractor = MediaExtractor()
    private lateinit var decoder: MediaCodec
    private var released = false
    val rotationDegrees: Int
    private var inputEnded = false
    private var outputEnded = false
    private var seeked = false
    private var hasDecodedTexture = false

    /** Source PTS of the image accepted by the callback; null before a range has a valid image. */
    var currentTexturePtsUs: Long? = null
        private set

    init {
        try {
            checkCancelled()
            extractor.setDataSource(file.absolutePath)
            checkCancelled()
            val track = (0 until extractor.trackCount).firstOrNull {
                val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                checkCancelled()
                mime?.startsWith("video/") == true
            } ?: error("No video track")
            rotationDegrees = MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(file.absolutePath)
                    checkCancelled()
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    checkCancelled()
                    rotation
                } finally { retriever.release() }
            }
            extractor.selectTrack(track)
            checkCancelled()
            val format = extractor.getTrackFormat(track)
            checkCancelled()
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: error("No video mime"))
            checkCancelled()
            decoder.configure(format, outputSurface, null, 0)
            checkCancelled()
            decoder.start()
            checkCancelled()
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    /** Starts one independently directed source range without recreating MediaCodec. */
    fun beginRange(targetUs: Long) {
        checkOpen()
        checkCancelled()
        // A newly started decoder has nothing to flush. Flushing before its first queued
        // sample stalls some Codec2 implementations when a Surface is rebound to a new file.
        if (seeked) {
            decoder.flush()
            checkCancelled()
        }
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        checkCancelled()
        inputEnded = false
        outputEnded = false
        seeked = true
        hasDecodedTexture = false
        currentTexturePtsUs = null
    }

    fun advanceTo(targetUs: Long, onTextureFrame: () -> Unit): Boolean {
        checkOpen()
        checkCancelled()
        // Container duration can exceed the final decodable PTS. Keep its last valid texture.
        if (outputEnded) return hasDecodedTexture
        if (hasDecodedTexture && targetUs <= requireNotNull(currentTexturePtsUs)) return true
        if (!seeked) {
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            checkCancelled()
            seeked = true
        }
        val info = MediaCodec.BufferInfo()
        var lastProgressNs = System.nanoTime()
        while (!outputEnded) {
            checkCancelled()
            check(System.nanoTime() - lastProgressNs < CODEC_STALL_TIMEOUT_NS) {
                "Transition decoder stalled before reaching overlap frame"
            }
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                checkCancelled()
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex) ?: error("Missing decoder input")
                    checkCancelled()
                    val size = extractor.readSampleData(buffer, 0)
                    checkCancelled()
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                        checkCancelled()
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        checkCancelled()
                        extractor.advance()
                        checkCancelled()
                    }
                    lastProgressNs = System.nanoTime()
                }
            }
            val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            checkCancelled()
            when (outputIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    lastProgressNs = System.nanoTime()
                    val render = info.size > 0
                    decoder.releaseOutputBuffer(outputIndex, render)
                    checkCancelled()
                    if (render) {
                        onTextureFrame()
                        hasDecodedTexture = true
                        currentTexturePtsUs = info.presentationTimeUs
                        checkCancelled()
                        if (info.presentationTimeUs >= targetUs) return true
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        }
        return hasDecodedTexture
    }

    private fun checkOpen() { check(!released) { "Video decoder cursor is closed" } }

    override fun close() = release()

    fun release() {
        if (released) return
        released = true
        if (::decoder.isInitialized) {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        runCatching { extractor.release() }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val CODEC_STALL_TIMEOUT_NS = 20_000_000_000L
    }
}
