package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

/** Actual AVC/Surface fixtures. Compilation alone does not constitute device evidence. */
@RunWith(AndroidJUnit4::class)
class GalleryAnalysisIntegrationTest {
    @Test fun landscapeAndMovingTextureNeedNoPeople() = withClip("landscape", regularPts(45)) { file ->
        val source = MediaInputInspector().inspect("landscape", file, file.name)
        val moments = GallerySourceAnalyzer().analyze(source) {}
        assertTrue(moments.isNotEmpty())
        assertTrue(moments.all { it.features.faceConfidence == null && it.endUs <= source.videoEndPtsUs })
        val actual = videoPts(file)
        assertTrue(moments.all { it.features.timeUs in actual })
    }

    @Test fun nonzeroVfrPtsAndIncompleteGopRemainActualAtEof() {
        val inputPts = LongArray(23) { i -> 120_000L + i * 42_000L + (i / 3) * 31_000L }
        withClip("nonzero-vfr", inputPts) { file ->
            val source = MediaInputInspector().inspect("vfr", file, file.name)
            val pts = videoPts(file)
            assertTrue(pts.first() > 0)
            assertEquals(pts.first(), source.firstVideoPtsUs)
            assertEquals(pts.first() + source.durationUs, source.videoEndPtsUs)
            assertTrue(pts.zipWithNext().map { it.second - it.first }.distinct().size > 1)
            val observed = mutableListOf<Long>()
            val requested = longArrayOf(pts.first() + 1, pts.last())
            SequentialBitmapDecoder.decodeActual(file, requested, 32, 32) { target, decoded, bitmap ->
                assertTrue(decoded in pts)
                assertTrue(decoded >= target)
                assertFalse(bitmap.isRecycled)
                observed += decoded
            }
            assertTrue(observed.first() > requested.first())
            assertEquals(pts.last(), observed.last())
            val moments = GallerySourceAnalyzer().analyze(source) {}
            assertTrue(moments.isNotEmpty())
            assertTrue(moments.all { it.features.timeUs in pts && it.startUs >= pts.first() && it.endUs <= source.videoEndPtsUs })
            // Neither boundary invents a frame for a request after the last real sample.
            assertThrows(IllegalStateException::class.java) {
                SequentialBitmapDecoder.decodeActual(file, longArrayOf(pts.last() + 1), 32, 32) { _, _, _ ->
                    fail("There is no frame after actual EOF")
                }
            }
            assertThrows(IllegalStateException::class.java) {
                SequentialBitmapDecoder.decode(file, longArrayOf(pts.first() + 1), 32, 32,
                    requireExactPts = true) { _, _ -> fail("Non-indexed exact request must fail") }
            }
        }
    }

    @Test fun halfSecondClipHasBoundedWindow() = withClip("short", regularPts(15)) { file ->
        val source = MediaInputInspector().inspect("short", file, file.name)
        assertTrue(source.durationUs in 500_000L..550_000L)
        val moments = GallerySourceAnalyzer().analyze(source) {}
        assertTrue(moments.isNotEmpty())
        assertTrue(moments.all { it.endUs <= source.videoEndPtsUs && it.endUs - it.startUs >= 500_000 })
    }

    @Test fun decodeAndRefineCancellationReleaseWorker() = withClip("cancel", regularPts(90)) { file ->
        val source = MediaInputInspector().inspect("cancel", file, file.name)
        var checks = 0
        val start = SystemClock.elapsedRealtime()
        assertThrows(CancellationException::class.java) {
            SequentialBitmapDecoder.decodeActual(file, longArrayOf(0, 2_000_000), 32, 32,
                checkCancelled = { if (++checks == 10) throw CancellationException("codec cancelled") }) { _, _, _ -> }
        }
        assertTrue(SystemClock.elapsedRealtime() - start < 5_000)
        var passes = 0
        val cache = GalleryAnalysisCache()
        val reader = object : GalleryFrameReader {
            override fun bounds(source: MediaSource, checkCancelled: () -> Unit) = GalleryBitmapFrameReader.bounds(source, checkCancelled)
            override fun read(source: MediaSource, targetsUs: LongArray, checkCancelled: () -> Unit,
                onFrame: (Long, FloatArray, Int, Int) -> Unit) {
                passes++
                GalleryBitmapFrameReader.read(source, targetsUs, {
                    checkCancelled()
                    if (passes == 2) throw CancellationException("refine cancelled")
                }, onFrame)
            }
        }
        assertThrows(CancellationException::class.java) {
            GallerySourceAnalyzer(frameReader = reader, cache = cache).analyze(source) {}
        }
        assertEquals(2, passes)
        assertNull(cache.load(source))
        // A fresh decode proves the previous codec/EGL resources were not kept alive.
        assertTrue(GallerySourceAnalyzer().analyze(source) {}.isNotEmpty())
    }

    private fun regularPts(count: Int) = LongArray(count) { (it * 1_000_000L + 29) / 30 }

    private fun withClip(name: String, pts: LongArray, run: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "gallery-$name-${System.nanoTime()}.mp4")
        try { createTextureVideo(file, pts); run(file) } finally { file.delete() }
    }

    private fun videoPts(file: File): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val pts = mutableListOf<Long>()
            while (extractor.sampleTrackIndex >= 0) {
                pts += extractor.sampleTime
                if (!extractor.advance()) break
            }
            return pts.distinct().sorted()
        } finally { extractor.release() }
    }

    /** Small real texture encoder; last sample intentionally does not complete a GOP. */
    @Suppress("DEPRECATION") // Intentional fallback for AVC encoders without flexible YUV420.
    private fun createTextureVideo(file: File, pts: LongArray) {
        val width = 96
        val height = 64
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val supported = codec.codecInfo.getCapabilitiesForType("video/avc").colorFormats
            val color = if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in supported)
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            else supported.first { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar }
            codec.configure(MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frame = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Texture encoder timed out" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == pts.size) {
                            codec.queueInputBuffer(index, 0, 0, pts.last() + 33_334,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val pixels = ByteArray(width * height * 3 / 2) { offset ->
                                if (offset >= width * height) 128.toByte() else {
                                    val x = offset % width
                                    val y = offset / width
                                    val movingPatch = x in (frame % 30 + 30)..(frame % 30 + 40) && y in 20..35
                                    (if (movingPatch) 128 else if ((x / 6 + y / 6) % 2 == 0) 64 else 192).toByte()
                                }
                            }
                            codec.getInputBuffer(index)!!.apply { clear(); put(pixels) }
                            codec.queueInputBuffer(index, 0, pixels.size, pts[frame], 0)
                            frame++
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
    }
}
